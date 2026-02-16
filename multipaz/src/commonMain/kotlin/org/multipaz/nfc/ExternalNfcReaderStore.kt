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
import kotlin.time.Clock

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
     * @return a [ExternalNfcReaderUsb] for the added device.
     */
    suspend fun addUsbReader(
        displayName: String,
        vendorId: Int,
        productId: Int
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
            productId = productId
        )
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
        for ((key, encodedData) in storageTable.enumerateWithData(partitionId = partitionId)) {
            val reader = ExternalNfcReader.fromDataItem(Cbor.decode(encodedData.toByteArray()))
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
            name = "ExternalNfcReaderStore",
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
