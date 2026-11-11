package org.multipaz.nfc

import kotlinx.io.IOException

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
     * @param timeoutMs transfer timeout in milliseconds.
     * @return the number of bytes written, or a negative value on error.
     * @throws IOException if a transport error occurs.
     */
    @Throws(IOException::class)
    fun bulkTransferOut(data: ByteArray, timeoutMs: Int): Int

    /**
     * Performs a synchronous bulk-IN transfer from the CCID reader.
     *
     * @param buffer destination buffer to receive bytes.
     * @param timeoutMs transfer timeout in milliseconds.
     * @return the number of bytes read, or a negative value on error.
     * @throws IOException if a transport error occurs.
     */
    @Throws(IOException::class)
    fun bulkTransferIn(buffer: ByteArray, timeoutMs: Int): Int

    /**
     * Polls the interrupt-IN endpoint for a CCID slot-change notification.
     *
     * @param buffer destination buffer to receive bytes.
     * @param timeoutMs poll timeout in milliseconds.
     * @return the number of bytes read, `0` or a negative value on timeout/no data.
     */
    fun interruptTransferIn(buffer: ByteArray, timeoutMs: Int): Int

    companion object {
        /** USB interface class for Chip/Smart Card Interface Devices (CCID). */
        const val USB_CLASS_CSCID = 0x0B

        private const val USB_DT_INTERFACE = 0x04
        private const val USB_DT_ENDPOINT = 0x05
        private const val USB_ENDPOINT_DIR_MASK = 0x80
        private const val USB_ENDPOINT_DIR_IN = 0x80
        private const val USB_ENDPOINT_XFERTYPE_MASK = 0x03
        private const val USB_ENDPOINT_XFER_BULK = 0x02
        private const val USB_ENDPOINT_XFER_INT = 0x03

        /**
         * Parses all primary (`bAlternateSetting == 0`) USB interfaces from a raw USB descriptor
         * stream (`bDescriptorType == 0x04`).
         */
        internal fun parseUsbInterfaces(
            rawDescriptors: ByteArray,
            interfaceNameResolver: (interfaceId: Int, iInterface: Int) -> String? = { _, _ -> null },
        ): List<UsbInterfaceInfo> {
            val result = mutableListOf<UsbInterfaceInfo>()
            var idx = 0
            while (idx < rawDescriptors.size - 2) {
                val len = rawDescriptors[idx].toInt() and 0xFF
                val type = rawDescriptors[idx + 1].toInt() and 0xFF
                if (len <= 0 || idx + len > rawDescriptors.size) break
                if (type == USB_DT_INTERFACE && len >= 9) {
                    val bInterfaceNumber = rawDescriptors[idx + 2].toInt() and 0xFF
                    val bAlternateSetting = rawDescriptors[idx + 3].toInt() and 0xFF
                    val bInterfaceClass = rawDescriptors[idx + 5].toInt() and 0xFF
                    val iInterface = rawDescriptors[idx + 8].toInt() and 0xFF
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
         * a raw USB descriptor stream. Prefers the interface whose `bInterfaceNumber` equals
         * [requestedInterfaceIndex], falling back to the first interface with
         * `bInterfaceClass == USB_CLASS_CSCID`.
         */
        internal fun findCcidInterfaceEndpoints(
            rawDescriptors: ByteArray,
            requestedInterfaceIndex: Int,
        ): CcidInterfaceEndpoints? {
            data class CandidateInterface(
                val id: Int,
                val interfaceClass: Int,
                var bulkIn: Byte? = null,
                var bulkOut: Byte? = null,
                var interruptIn: Byte? = null,
                var interruptMaxPacketSize: Int = 0,
            )

            val candidates = mutableListOf<CandidateInterface>()
            var currentCandidate: CandidateInterface? = null
            var idx = 0
            while (idx < rawDescriptors.size - 2) {
                val len = rawDescriptors[idx].toInt() and 0xFF
                val type = rawDescriptors[idx + 1].toInt() and 0xFF
                if (len <= 0 || idx + len > rawDescriptors.size) break
                if (type == USB_DT_INTERFACE && len >= 9) {
                    val bInterfaceNumber = rawDescriptors[idx + 2].toInt() and 0xFF
                    val bAlternateSetting = rawDescriptors[idx + 3].toInt() and 0xFF
                    val bInterfaceClass = rawDescriptors[idx + 5].toInt() and 0xFF
                    if (bAlternateSetting == 0) {
                        val cand = CandidateInterface(id = bInterfaceNumber, interfaceClass = bInterfaceClass)
                        candidates.add(cand)
                        currentCandidate = cand
                    } else {
                        currentCandidate = null
                    }
                } else if (type == USB_DT_ENDPOINT && len >= 7 && currentCandidate != null) {
                    val addr = rawDescriptors[idx + 2]
                    val attr = rawDescriptors[idx + 3].toInt() and 0xFF
                    val maxPkt = ((rawDescriptors[idx + 4].toInt() and 0xFF) or
                        ((rawDescriptors[idx + 5].toInt() and 0xFF) shl 8)) and 0x07FF
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

            val chosen = candidates.firstOrNull { it.id == requestedInterfaceIndex }
                ?: candidates.firstOrNull { it.interfaceClass == USB_CLASS_CSCID }
                ?: return null
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
