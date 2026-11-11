package org.multipaz.nfc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import kotlin.time.Clock

private const val TAG = "ExternalNfcReaderStore"

/**
 * A persistent store for externally connected NFC readers.
 *
 * Use [org.multipaz.nfc.ExternalNfcReaderStore.create] to create an instance.
 */
class ExternalNfcReaderStore(
    private val storage: Storage,
    private val partitionId: String
) {
    private lateinit var storageTable: StorageTable

    val _readers = MutableStateFlow<List<ExternalNfcReader>>(emptyList())

    /**
     * A [StateFlow] with the currently configured [ExternalNfcReader].
     */
    val readers: StateFlow<List<ExternalNfcReader>> = _readers.asStateFlow()

    /**
     * Adds a new external NFC reader connected via USB.
     *
     * On Android, the [ExternalNfcReaderStore.handleUsbDeviceAttached] extension method
     * can be used to automatically configuring supported devices when they are plugged
     * in.
     *
     * @param displayName a human-readable name for the device.
     * @param vendorId the vendor ID of the USB device.
     * @param productId the product ID of the USB device.
     * @param interfaceIndex the USB interface index for the CCID interface on the device.
     * @return a [ExternalNfcReaderUsb] for the added device.
     */
    suspend fun addUsbReader(
        displayName: String,
        vendorId: Int,
        productId: Int,
        interfaceIndex: Int
    ): ExternalNfcReaderUsb {
        val id = storageTable.insert(
            key = null,
            data = ByteString(),
            partitionId = partitionId
        )
        val reader = ExternalNfcReaderUsb(
            id = id,
            addedAt = Clock.System.now(),
            displayName = displayName,
            vendorId = vendorId,
            productId = productId,
            interfaceIndex = interfaceIndex
        )
        reader.store = this
        storageTable.update(
            key = id,
            data = ByteString(Cbor.encode(reader.toDataItem())),
            partitionId = partitionId
        )
        _readers.update { current ->
            current.toMutableList()
                .apply {
                    add(reader)
                }
                .sortedBy { it.addedAt }
        }
        return reader
    }

    /**
     * Sets the user display name for a reader.
     *
     * @param reader the reader to update.
     * @param userDisplayName the new user display name, or null to clear it.
     */
    suspend fun setUserDisplayName(reader: ExternalNfcReader, userDisplayName: String?) {
        val updatedReader = when (reader) {
            is ExternalNfcReaderUsb -> reader.copy(userDisplayName = userDisplayName)
        }.apply { store = this@ExternalNfcReaderStore }

        storageTable.update(
            key = reader.id,
            data = ByteString(Cbor.encode(updatedReader.toDataItem())),
            partitionId = partitionId
        )
        _readers.update { current ->
            current.toMutableList()
                .apply {
                    val idx = indexOfFirst { it.id == reader.id }
                    if (idx != -1) {
                        set(idx, updatedReader)
                    }
                }
                .sortedBy { it.addedAt }
        }
    }

    /**
     * Removes a previously configured reader.
     *
     * @param reader a [ExternalNfcReader].
     */
    suspend fun removeReader(reader: ExternalNfcReader) {
        storageTable.delete(
            key = reader.id,
            partitionId = partitionId
        )
        _readers.update { current ->
            current.toMutableList()
                .apply {
                    removeAll { it.id == reader.id }
                }
                .sortedBy { it.addedAt }
        }
    }

    private suspend fun initialize() {
        storageTable = storage.getTable(tableSpec)
        val readers = mutableListOf<ExternalNfcReader>()
        for ((_, encodedData) in storageTable.enumerateWithData(partitionId = partitionId)) {
            val reader = ExternalNfcReader.fromDataItem(Cbor.decode(encodedData.toByteArray()))
            reader.store = this
            readers.add(reader)
        }
        _readers.update { current ->
            current.toMutableList()
                .apply { readers.forEach { add(it) } }
                .sortedBy { it.addedAt }
        }
    }

    companion object {
        private val tableSpec = StorageTableSpec(
            name = "ExternalNfcReaderStore1",
            supportPartitions = true,
            supportExpiration = false,
        )

        /**
         * Creates a new [org.multipaz.nfc.ExternalNfcReaderStore] instance
         *
         * @param storage the [Storage] to use for storing already configured readers.
         * @param partitionId the partition id.
         * @returns a [org.multipaz.nfc.ExternalNfcReaderStore] instance.
         */
        suspend fun create(
            storage: Storage,
            partitionId: String = "default"
        ): ExternalNfcReaderStore {
            val instance = ExternalNfcReaderStore(storage, partitionId)
            instance.initialize()
            return instance
        }
    }
}

/**
 * Adds a USB device described by [deviceInfo] to the [ExternalNfcReaderStore] if it doesn't
 * exist already, filtering out non-NFC CCID interfaces (such as SAM/SIM slots or mechanical
 * contact card slots).
 */
internal suspend fun ExternalNfcReaderStore.handleUsbDeviceAttached(
    deviceInfo: UsbDeviceInfo
): ExternalNfcReaderUsb {
    val deviceDisplayName = deviceInfo.getNiceDisplayName()
    Logger.d(TAG, "USB Device attached: $deviceDisplayName")

    // If at least one interface for this USB device (VID/PID) already exists in the store,
    // skip examining/adding any new interfaces for this device. When a multi-interface CCID
    // reader is plugged in for the first time, all candidate NFC CCID interfaces are added to the store
    // so the user can manually delete any non-NFC interfaces (e.g. SAM or contact card slots)
    // from the UI. Preserving existing entries allows the user's manual deletions to persist
    // when the device is re-plugged.
    val existingReaderForDevice = this.readers.value.find { externalNfcReader ->
        (externalNfcReader as? ExternalNfcReaderUsb)?.let {
            it.vendorId == deviceInfo.vendorId && it.productId == deviceInfo.productId
        } ?: false
    }
    if (existingReaderForDevice != null) {
        Logger.i(
            TAG,
            "Device vid=${deviceInfo.vendorId} pid=${deviceInfo.productId} already has interface(s) configured in store, skipping auto-adding interfaces."
        )
        return existingReaderForDevice as ExternalNfcReaderUsb
    }

    val ccidInterfaces = deviceInfo.interfaces.filter {
        it.interfaceClass == CcidUsbTransport.USB_CLASS_CSCID
    }
    val candidateNfcInterfaces = ccidInterfaces.filter {
        !isDefinitelyNotNfc(it, deviceInfo.rawDescriptors)
    }
    val targetInterfaces = when {
        candidateNfcInterfaces.isNotEmpty() -> candidateNfcInterfaces
        ccidInterfaces.isNotEmpty() -> ccidInterfaces
        deviceInfo.interfaces.isNotEmpty() -> listOf(deviceInfo.interfaces.first())
        else -> listOf(UsbInterfaceInfo(id = 0, interfaceClass = CcidUsbTransport.USB_CLASS_CSCID))
    }

    var lastAddedReader: ExternalNfcReaderUsb? = null
    val format = HexFormat {
        number.prefix = "0x"
        number.minLength = 2
    }

    for (iface in targetInterfaces) {
        val ifaceIndex = iface.id
        val nameSuffix = if (targetInterfaces.size > 1) {
            val ifaceName = iface.name?.trim() ?: ""
            if (ifaceName.isNotEmpty()) " ($ifaceName)" else " (Interface ${iface.id})"
        } else {
            ""
        }
        val displayName = "$deviceDisplayName$nameSuffix"

        Logger.i(
            TAG,
            "Adding USB-connected external NFC reader '$displayName' " +
                "vid ${deviceInfo.vendorId.toHexString(format)} pid ${deviceInfo.productId.toHexString(format)} " +
                "interfaceIndex $ifaceIndex to persistent ExternalNfcReaderStore"
        )

        val reader = this.addUsbReader(
            displayName = displayName,
            vendorId = deviceInfo.vendorId,
            productId = deviceInfo.productId,
            interfaceIndex = ifaceIndex
        )
        lastAddedReader = reader
    }

    return lastAddedReader!!
}

internal fun isDefinitelyNotNfc(iface: UsbInterfaceInfo, rawDescriptors: ByteArray?): Boolean {
    val ifaceName = iface.name?.lowercase()?.trim() ?: ""
    if (ifaceName.contains("sam") || ifaceName.contains("sim")) {
        return true
    }
    if (ifaceName.contains("contact") && !ifaceName.contains("contactless")) {
        return true
    }
    if (rawDescriptors != null) {
        val ccidDesc = CcidDriver.getCcidDescriptorForInterface(rawDescriptors, iface.id)
        if (ccidDesc != null && ccidDesc.size >= 40) {
            val dwMechanical = CcidDriver.readInt32Le(ccidDesc, 36)
            if (dwMechanical != 0) {
                return true
            }
        }
    }
    return false
}

internal fun UsbDeviceInfo.getNiceDisplayName(): String {
    val manufacturer = this.manufacturerName?.trim()
    val product = this.productName?.trim()
    return when {
        !manufacturer.isNullOrBlank() && !product.isNullOrBlank() -> {
            if (product.startsWith(manufacturer, ignoreCase = true)) {
                product
            } else {
                "$manufacturer $product"
            }
        }
        !product.isNullOrBlank() -> product
        !manufacturer.isNullOrBlank() -> manufacturer
        else -> {
            val hexFormat = HexFormat {
                upperCase = true
                number.minLength = 4
                number.removeLeadingZeros = true
            }
            val vidHex = (this.vendorId and 0xFFFF).toHexString(hexFormat)
            val pidHex = (this.productId and 0xFFFF).toHexString(hexFormat)
            "Unknown USB Device (VID: $vidHex, PID: $pidHex)"
        }
    }
}
