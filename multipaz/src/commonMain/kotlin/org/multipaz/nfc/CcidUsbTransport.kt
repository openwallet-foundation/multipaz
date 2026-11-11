package org.multipaz.nfc

import kotlinx.io.IOException
import kotlin.time.Duration

/**
 * USB interface metadata used for CCID reader discovery and interface filtering.
 *
 * @property id the USB `bInterfaceNumber` for the interface.
 * @property interfaceClass the USB `bInterfaceClass` (`0x0B` for CCID).
 * @property name the optional USB interface string descriptor.
 */
internal data class UsbInterfaceInfo(
    val id: Int,
    val interfaceClass: Int,
    val name: String? = null,
)

/**
 * Resolved USB endpoints for a claimed CCID interface.
 *
 * @property interfaceId the `bInterfaceNumber` of the CCID interface.
 * @property bulkInAddress the `bEndpointAddress` of the Bulk-IN endpoint.
 * @property bulkOutAddress the `bEndpointAddress` of the Bulk-OUT endpoint.
 * @property interruptInAddress the `bEndpointAddress` of the Interrupt-IN endpoint, or `null` if none.
 * @property interruptMaxPacketSize the `wMaxPacketSize` of the Interrupt-IN endpoint, or `0` if none.
 */
internal data class CcidInterfaceEndpoints(
    val interfaceId: Int,
    val bulkInAddress: Byte,
    val bulkOutAddress: Byte,
    val interruptInAddress: Byte?,
    val interruptMaxPacketSize: Int,
)

/**
 * Platform-neutral description of an attached USB device used by [ExternalNfcReaderStore]
 * when discovering and filtering CCID interfaces.
 *
 * @property vendorId the USB vendor ID (`idVendor`).
 * @property productId the USB product ID (`idProduct`).
 * @property manufacturerName the USB manufacturer string descriptor, if available.
 * @property productName the USB product string descriptor, if available.
 * @property interfaces the USB interfaces reported by the active configuration.
 * @property rawDescriptors the raw USB configuration descriptors (including class-specific `0x21`
 *   CCID descriptors), if available.
 */
internal data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String? = null,
    val productName: String? = null,
    val interfaces: List<UsbInterfaceInfo> = emptyList(),
    val rawDescriptors: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UsbDeviceInfo) return false
        return vendorId == other.vendorId &&
            productId == other.productId &&
            manufacturerName == other.manufacturerName &&
            productName == other.productName &&
            interfaces == other.interfaces &&
            rawDescriptors.contentEquals(other.rawDescriptors)
    }

    override fun hashCode(): Int {
        var result = vendorId
        result = 31 * result + productId
        result = 31 * result + (manufacturerName?.hashCode() ?: 0)
        result = 31 * result + (productName?.hashCode() ?: 0)
        result = 31 * result + interfaces.hashCode()
        result = 31 * result + (rawDescriptors?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Low-level USB transport abstraction for a USB CCID (Chip Card Interface Device) smart card reader.
 */
internal interface CcidUsbTransport {
    /** Human-readable or system identifier for the USB device (e.g. `/dev/bus/usb/001/004`). */
    val deviceName: String

    /** USB Vendor ID (`idVendor`). */
    val vendorId: Int

    /** USB Product ID (`idProduct`). */
    val productId: Int

    /** Resolved USB interface number (`bInterfaceNumber`) after [open] has been called. */
    val interfaceId: Int

    /** Raw USB descriptors for parsing the CCID functional descriptor (`0x21`), if available. */
    val rawDescriptors: ByteArray?

    /** Whether the claimed CCID interface exposes an interrupt-IN endpoint. */
    val hasInterruptEndpoint: Boolean

    /** Maximum packet size for the interrupt-IN endpoint, if present. */
    val interruptMaxPacketSize: Int

    /**
     * Opens the USB device, locates and claims the target CCID interface, and resolves its
     * bulk-IN, bulk-OUT, and optional interrupt-IN endpoints.
     *
     * @throws IOException if the device cannot be opened or the CCID interface cannot be claimed.
     */
    @Throws(IOException::class)
    fun open()

    /**
     * Releases the claimed USB interface and closes the underlying USB device connection.
     */
    fun close()

    /**
     * Performs a synchronous bulk-OUT transfer to the CCID reader.
     *
     * @param data the bytes to write.
     * @param timeout transfer timeout.
     * @return the number of bytes written, or a negative value on error.
     * @throws IOException if a transport error occurs.
     */
    @Throws(IOException::class)
    fun bulkTransferOut(data: ByteArray, timeout: Duration): Int

    /**
     * Performs a synchronous bulk-IN transfer from the CCID reader.
     *
     * @param buffer destination buffer to receive bytes.
     * @param timeout transfer timeout.
     * @return the number of bytes read, or a negative value on error.
     * @throws IOException if a transport error occurs.
     */
    @Throws(IOException::class)
    fun bulkTransferIn(buffer: ByteArray, timeout: Duration): Int

    /**
     * Polls the interrupt-IN endpoint for a CCID slot-change notification.
     *
     * @param buffer destination buffer to receive bytes.
     * @param timeout poll timeout.
     * @return the number of bytes read, `0` or a negative value on timeout/no data.
     */
    fun interruptTransferIn(buffer: ByteArray, timeout: Duration): Int

    companion object {
        /** USB interface class for Chip/Smart Card Interface Devices (CCID). */
        const val USB_CLASS_CSCID = 0x0B

        private const val USB_DT_CONFIG = 0x02
        private const val USB_DT_INTERFACE = 0x04
        private const val USB_DT_ENDPOINT = 0x05
        private const val USB_ENDPOINT_DIR_MASK = 0x80
        private const val USB_ENDPOINT_DIR_IN = 0x80
        private const val USB_ENDPOINT_XFERTYPE_MASK = 0x03
        private const val USB_ENDPOINT_XFER_BULK = 0x02
        private const val USB_ENDPOINT_XFER_INT = 0x03

        /**
         * Extracts the descriptor slice belonging to the active USB configuration (`USB_DT_CONFIG`)
         * matching [activeConfigurationValue] (or the first configuration if `null`). If
         * [rawDescriptors] does not contain any `USB_DT_CONFIG` header, returns [rawDescriptors]
         * unchanged.
         */
        internal fun extractActiveConfigSlice(
            rawDescriptors: ByteArray,
            activeConfigurationValue: Int? = null,
        ): ByteArray {
            var idx = 0
            var firstConfigSlice: ByteArray? = null
            while (idx < rawDescriptors.size - 2) {
                val len = rawDescriptors[idx].toInt() and 0xFF
                val type = rawDescriptors[idx + 1].toInt() and 0xFF
                if (len <= 0 || idx + len > rawDescriptors.size) break
                if (type == USB_DT_CONFIG && len >= 9) {
                    val wTotalLength = (rawDescriptors[idx + 2].toInt() and 0xFF) or
                        ((rawDescriptors[idx + 3].toInt() and 0xFF) shl 8)
                    val bConfigVal = rawDescriptors[idx + 5].toInt() and 0xFF
                    val configEnd = (idx + maxOf(len, wTotalLength)).coerceAtMost(rawDescriptors.size)
                    val slice = rawDescriptors.copyOfRange(idx, configEnd)
                    if (activeConfigurationValue == null || bConfigVal == activeConfigurationValue) {
                        return slice
                    }
                    if (firstConfigSlice == null) {
                        firstConfigSlice = slice
                    }
                    idx = configEnd
                } else {
                    idx += len
                }
            }
            return firstConfigSlice ?: rawDescriptors
        }

        /**
         * Parses all primary (`bAlternateSetting == 0`) USB interfaces from a raw USB descriptor
         * stream (`bDescriptorType == 0x04`), restricted to the active configuration if present.
         */
        internal fun parseUsbInterfaces(
            rawDescriptors: ByteArray,
            activeConfigurationValue: Int? = null,
            interfaceNameResolver: (interfaceId: Int, iInterface: Int) -> String? = { _, _ -> null },
        ): List<UsbInterfaceInfo> {
            val configBytes = extractActiveConfigSlice(rawDescriptors, activeConfigurationValue)
            val result = mutableListOf<UsbInterfaceInfo>()
            var idx = 0
            while (idx < configBytes.size - 2) {
                val len = configBytes[idx].toInt() and 0xFF
                val type = configBytes[idx + 1].toInt() and 0xFF
                if (len <= 0 || idx + len > configBytes.size) break
                if (type == USB_DT_INTERFACE && len >= 9) {
                    val bInterfaceNumber = configBytes[idx + 2].toInt() and 0xFF
                    val bAlternateSetting = configBytes[idx + 3].toInt() and 0xFF
                    val bInterfaceClass = configBytes[idx + 5].toInt() and 0xFF
                    val iInterface = configBytes[idx + 8].toInt() and 0xFF
                    if (bAlternateSetting == 0 && result.none { it.id == bInterfaceNumber }) {
                        result.add(
                            UsbInterfaceInfo(
                                id = bInterfaceNumber,
                                interfaceClass = bInterfaceClass,
                                name = interfaceNameResolver(bInterfaceNumber, iInterface)
                            )
                        )
                    }
                }
                idx += len
            }
            return result
        }

        /**
         * Resolves the CCID interface and its Bulk-IN, Bulk-OUT, and Interrupt-IN endpoints from
         * a raw USB descriptor stream.
         *
         * When [requestedInterfaceIndex] is non-null, prefers the interface whose
         * `bInterfaceNumber` equals [requestedInterfaceIndex], falling back to the first CCID
         * interface (`bInterfaceClass == USB_CLASS_CSCID`). When [requestedInterfaceIndex] is
         * `null`, automatically selects the first contactless NFC CCID interface (skipping
         * SAM/SIM and mechanical contact card slots).
         */
        internal fun findCcidInterfaceEndpoints(
            rawDescriptors: ByteArray,
            requestedInterfaceIndex: Int?,
            activeConfigurationValue: Int? = null,
            interfaceNameResolver: (interfaceId: Int, iInterface: Int) -> String? = { _, _ -> null },
        ): CcidInterfaceEndpoints? {
            data class CandidateInterface(
                val id: Int,
                val interfaceClass: Int,
                val iInterface: Int,
                var bulkIn: Byte? = null,
                var bulkOut: Byte? = null,
                var interruptIn: Byte? = null,
                var interruptMaxPacketSize: Int = 0,
            )

            val configBytes = extractActiveConfigSlice(rawDescriptors, activeConfigurationValue)
            val candidates = mutableListOf<CandidateInterface>()
            var currentCandidate: CandidateInterface? = null
            var idx = 0
            while (idx < configBytes.size - 2) {
                val len = configBytes[idx].toInt() and 0xFF
                val type = configBytes[idx + 1].toInt() and 0xFF
                if (len <= 0 || idx + len > configBytes.size) break
                if (type == USB_DT_INTERFACE && len >= 9) {
                    val bInterfaceNumber = configBytes[idx + 2].toInt() and 0xFF
                    val bAlternateSetting = configBytes[idx + 3].toInt() and 0xFF
                    val bInterfaceClass = configBytes[idx + 5].toInt() and 0xFF
                    val iInterface = configBytes[idx + 8].toInt() and 0xFF
                    if (bAlternateSetting == 0) {
                        val cand = CandidateInterface(
                            id = bInterfaceNumber,
                            interfaceClass = bInterfaceClass,
                            iInterface = iInterface
                        )
                        candidates.add(cand)
                        currentCandidate = cand
                    } else {
                        currentCandidate = null
                    }
                } else if (type == USB_DT_ENDPOINT && len >= 7 && currentCandidate != null) {
                    val addr = configBytes[idx + 2]
                    val attr = configBytes[idx + 3].toInt() and 0xFF
                    val maxPkt = ((configBytes[idx + 4].toInt() and 0xFF) or
                        ((configBytes[idx + 5].toInt() and 0xFF) shl 8)) and 0x07FF
                    val isIn = (addr.toInt() and USB_ENDPOINT_DIR_MASK) == USB_ENDPOINT_DIR_IN
                    when (attr and USB_ENDPOINT_XFERTYPE_MASK) {
                        USB_ENDPOINT_XFER_BULK -> {
                            if (isIn) {
                                currentCandidate.bulkIn = addr
                            } else {
                                currentCandidate.bulkOut = addr
                            }
                        }
                        USB_ENDPOINT_XFER_INT -> {
                            if (isIn) {
                                currentCandidate.interruptIn = addr
                                currentCandidate.interruptMaxPacketSize = maxPkt
                            }
                        }
                    }
                }
                idx += len
            }

            val chosen = if (requestedInterfaceIndex != null) {
                candidates.firstOrNull { it.id == requestedInterfaceIndex }
                    ?: candidates.firstOrNull { it.interfaceClass == USB_CLASS_CSCID }
            } else {
                val ccidCandidates = candidates.filter {
                    it.interfaceClass == USB_CLASS_CSCID && it.bulkIn != null && it.bulkOut != null
                }
                val nfcCandidates = ccidCandidates.filter { cand ->
                    val info = UsbInterfaceInfo(
                        id = cand.id,
                        interfaceClass = cand.interfaceClass,
                        name = interfaceNameResolver(cand.id, cand.iInterface)
                    )
                    !isDefinitelyNotNfc(info, configBytes)
                }
                nfcCandidates.firstOrNull() ?: ccidCandidates.firstOrNull()
            } ?: return null

            val bulkIn = chosen.bulkIn ?: return null
            val bulkOut = chosen.bulkOut ?: return null
            return CcidInterfaceEndpoints(
                interfaceId = chosen.id,
                bulkInAddress = bulkIn,
                bulkOutAddress = bulkOut,
                interruptInAddress = chosen.interruptIn,
                interruptMaxPacketSize = chosen.interruptMaxPacketSize
            )
        }
    }
}
