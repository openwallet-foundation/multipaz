package org.multipaz.nfc

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * A driver for USB CCID (Chip Card Interface Device) smart card readers.
 * This class handles the communication with a CCID-compliant smart card reader,
 * allowing for sending APDUs (Application Protocol Data Units) to a smart card
 * and receiving responses. It also provides notifications for card insertion and removal.
 *
 * The driver communicates with the CCID reader over bulk and interrupt USB endpoints.
 * It uses a listener interface to notify the application of card events.
 *
 * @property usbManager The UsbManager system service, used for accessing USB devices.
 * @property device The UsbDevice representing the CCID reader.
 */
internal class CcidDriver(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val interfaceIndex: Int,
    private val pollingFrameData: ByteArray? = null,
) {
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    private var interruptEndpoint: UsbEndpoint? = null
    private val sequence = AtomicInteger(0)
    private val usbLock = Any()
    @Volatile
    private var isConnected = false
    @Volatile
    private var isCardPoweredOn = false
    @Volatile
    private var isHardwarePollingConfigured = false
    @Volatile
    private var usesTwoBytePollingTypeCommand = false
    private var cachedSupportsCcidChaining: Boolean? = null
    private var cachedMaxCcidDataLength: Int? = null
    private var cachedMaxCommandLength: Int? = null
    private var listener: CcidDriverListener? = null

    /**
     * Connects to the CCID reader. This method must be called before any other
     * operations can be performed. It requests permission to access the USB device,
     * opens a connection, and starts listening for card events.
     *
     * @throws IOException if the connection to the device fails or interface cannot be claimed.
     * @throws SecurityException if permission to access the device is denied.
     */
    fun connect() {
        if (!usbManager.hasPermission(device)) {
            throw SecurityException("Permission denied for device ${device.deviceName}")
        }
        val conn = usbManager.openDevice(device)
            ?: throw IOException("Could not open device connection")
        connection = conn

        val iface = findCcidInterface(device, conn)
            ?: run {
                conn.close()
                connection = null
                throw IOException("CCID interface not found")
            }
        usbInterface = iface

        findEndpoints(iface)
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            connection = null
            usbInterface = null
            throw IOException("Could not claim CCID interface id=${iface.id}")
        }
        isConnected = true
        val chaining = computeSupportsCcidChaining()
        val maxCcidLen = computeMaxCcidDataLength()
        val maxCmdLen = if (chaining) {
            Logger.i(TAG, "Reader supports CCID block chaining (dwFeatures exchange level extended APDU) -> maxCommandLength=65535")
            65535
        } else {
            val len = if (maxCcidLen > 20) maxCcidLen - 10 else 255
            Logger.i(TAG, "Reader does not report CCID chaining -> maxCcidDataLength=$maxCcidLen, maxCommandLength=$len")
            len
        }
        cachedSupportsCcidChaining = chaining
        cachedMaxCcidDataLength = maxCcidLen
        cachedMaxCommandLength = maxCmdLen
        Logger.i(TAG, "Connected to CCID device ${device.deviceName} (interface id=${iface.id}), maxCommandLength=$maxCommandLength")
        configureContactlessPollingForIsoDep()
        startInterruptListener()
    }

    /**
     * Configures known contactless USB CCID readers to poll only ISO/IEC 14443 Type A (`NFC-A`)
     * and Type B (`NFC-B`), disabling FeliCa (`NFC-F`) and other non-ISO-DEP technologies, and
     * configures hardware ECP/VAS polling frames if supported by the reader firmware.
     *
     * Without disabling `NFC-F`, when an Android phone with NFC Observe Mode enabled enters the field,
     * Observe Mode suppresses initial `NFC-A`/`NFC-B` responses while the embedded Secure Element
     * (`eSE1`) immediately responds to `NFC-F` (`SENSF_REQ`), causing the reader to lock onto the
     * phone as a FeliCa card (`ATR: 3B 8F 80 01 80 4F 0C A0 00 00 03 06 11 00 3B ...`) and reject
     * ISO 7816-4 `SELECT` APDUs with `6A 81` (`SW_FUNC_NOT_SUPPORTED`).
     */
    private fun configureContactlessPollingForIsoDep() {
        var hardwarePollingConfigured = false
        var twoBytePollingType = false
        if (device.vendorId == VENDOR_ID_ACS) {
            val frame = pollingFrameData
            var ecpPayloadAccepted = false
            if (frame != null && frame.size >= 2 && frame[0] == 0x6A.toByte()) {
                try {
                    // ACS WalletMate II ECP payload configuration (E0 00 00 3B <len> <payload after 6A>).
                    // Note: The E0 00 00 xx escape command framing and 0x20 PICC polling parameter format
                    // come from publicly available ACS ACR1252U/ACR1552U reference manuals. The 0x3B
                    // register and Bit 7 (0x80) of E0 00 01 20 were identified via black-box USB probing
                    // of a retail WalletMate II (reading E0 00 00 3B 00 returned 01 00 00 02 90 00,
                    // matching the publicly documented 6A 01 00 00 02 polling frame without the leading
                    // 0x6A byte).
                    val payload = frame.copyOfRange(1, frame.size)
                    val setEcpCmd = ByteArray(5 + payload.size)
                    setEcpCmd[0] = 0xE0.toByte()
                    setEcpCmd[1] = 0x00
                    setEcpCmd[2] = 0x00
                    setEcpCmd[3] = 0x3B
                    setEcpCmd[4] = payload.size.toByte()
                    payload.copyInto(setEcpCmd, destinationOffset = 5)
                    val ecpResp = sendEscapeCommand(setEcpCmd)
                    if (ecpResp.size >= 2 &&
                        ecpResp[ecpResp.size - 2] == 0x90.toByte() &&
                        ecpResp[ecpResp.size - 1] == 0x00.toByte()
                    ) {
                        Logger.d(TAG, "ACS 0x3B ECP payload response: ${ecpResp.toHex()}")
                        ecpPayloadAccepted = true
                    }
                } catch (e: Exception) {
                    Logger.d(TAG, "ACS hardware ECP payload configuration not supported: ${e.message}")
                }
            }

            try {
                // WalletMate II / ACR1552U 2-byte Set PICC Polling Type:
                // E0 00 01 20 02 <byte1> 00
                // Byte 1: Bit 0 = ISO14443A, Bit 1 = ISO14443B, Bit 7 = ECP/VAS polling enabled.
                // Byte 0: 0x00 (disable FeliCa 212/424, Topaz, ISO15693, etc.)
                // Response is E1 00 00 00 02 <actualByte1> <actualByte0>; readers without ECP/VAS
                // support (such as standard ACR1552U) mask off Bit 7 and return 0x03 in resp[5].
                val pollTypeByte1 = if (ecpPayloadAccepted) 0x83.toByte() else 0x03.toByte()
                val resp = sendEscapeCommand(
                    byteArrayOf(0xE0.toByte(), 0x00, 0x01, 0x20, 0x02, pollTypeByte1, 0x00)
                )
                twoBytePollingType = true
                val ecpBitActive = resp.size >= 6 && (resp[5].toInt() and 0x80) != 0
                if (frame != null) {
                    if (ecpPayloadAccepted && ecpBitActive) {
                        hardwarePollingConfigured = true
                        Logger.i(TAG, "Reader enabled hardware polling frame ${frame.toHex()} (PICC polling type: ${resp.toHex()})")
                    } else {
                        Logger.i(TAG, "Reader does not support hardware polling frame ${frame.toHex()} (PICC polling type: ${resp.toHex()})")
                    }
                } else {
                    Logger.i(TAG, "Configured ACS 2-byte PICC Polling Type: ${resp.toHex()}")
                }
            } catch (e: Exception) {
                Logger.d(TAG, "2-byte ACS PICC Polling Type not supported, trying 1-byte command: ${e.message}")
                if (frame != null) {
                    Logger.i(TAG, "Reader does not support hardware polling frame ${frame.toHex()}")
                }
                try {
                    // Older ACS readers (e.g. ACR1252U) 1-byte Set PICC Operating Parameter / Polling Type:
                    // E0 00 00 20 01 03 -> Bit 0 = ISO/IEC 14443 Type A, Bit 1 = ISO/IEC 14443 Type B.
                    val resp = sendEscapeCommand(
                        byteArrayOf(0xE0.toByte(), 0x00, 0x00, 0x20, 0x01, 0x03)
                    )
                    Logger.i(TAG, "Configured ACS 1-byte PICC Polling Type: ${resp.toHex()}")
                } catch (e2: Exception) {
                    Logger.d(TAG, "Failed to configure ACS PICC Polling Type: ${e2.message}")
                }
            }
        }
        isHardwarePollingConfigured = hardwarePollingConfigured
        usesTwoBytePollingTypeCommand = twoBytePollingType
    }

    /**
     * Sends a vendor-specific `PC_to_RDR_Escape` (`0x6B`) command to the CCID reader and returns
     * the payload from the `RDR_to_PC_Escape` (`0x83`) response.
     *
     * @param payload the vendor-specific command bytes to send.
     * @return the response payload from the `RDR_to_PC_Escape` response.
     * @throws IOException if the driver is not connected or a USB communication error occurs.
     * @throws CcidException if the reader returns a CCID command error status.
     */
    @Throws(IOException::class, CcidException::class)
    fun sendEscapeCommand(payload: ByteArray): ByteArray {
        if (!isConnected) throw IOException("Driver is not connected.")
        return sendEscapeCommandInternal(payload)
    }

    @Throws(IOException::class, CcidException::class)
    private fun sendEscapeCommandInternal(payload: ByteArray): ByteArray {
        val cmd = createEscapeCommand(payload)
        val responseBuffer = sendAndReceive(cmd)
        val messageType = responseBuffer.get(0).toInt() and 0xFF
        if (messageType != (RDR_TO_PC_ESCAPE.toInt() and 0xFF)) {
            throw IOException(
                "Unexpected response message type for Escape command, expected 0x83 but got 0x${messageType.toString(16)}"
            )
        }
        val statusByte = responseBuffer.get(7).toInt() and 0xFF
        val commandStatus = statusByte and 0xC0
        if (commandStatus == 0x40) {
            val errorCode = responseBuffer.get(8).toInt() and 0xFF
            throw CcidException(
                "CCID Escape command failed with status 0x${statusByte.toString(16)}, error code 0x${errorCode.toString(16)}"
            )
        }
        val length = responseBuffer.getInt(1)
        if (length < 0 || length > responseBuffer.limit() - 10) {
            throw IOException("Incomplete or invalid Escape response data length: $length")
        }
        val data = ByteArray(length)
        responseBuffer.position(10)
        responseBuffer.get(data)
        return data
    }

    /**
     * Briefly power-cycles the smart card slot (`PC_to_RDR_IccPowerOff` `0x63` followed after
     * [fieldOffDelay] by `PC_to_RDR_IccPowerOn` `0x62`) so that a contactless reader performs
     * a fresh ISO-DEP (`RATS`) activation.
     *
     * A [fieldOffDelay] of 80 milliseconds is long enough to drop the active ISO-DEP session while
     * staying well below Android's 2000 ms `FIELD_OFF_IDLE_DELAY_DURATION` Observe Mode re-enable timer.
     *
     * @param fieldOffDelay the duration to keep the slot powered off before re-powering.
     * @return `true` if the card slot was successfully re-powered with a valid ATR, `false` otherwise.
     */
    suspend fun powerCycleCard(fieldOffDelay: Duration = 80.milliseconds): Boolean {
        if (!isConnected) return false
        return try {
            val powerOffCommand = powerOff()
            sendAndReceive(powerOffCommand)
            isCardPoweredOn = false
            delay(fieldOffDelay)
            for (attempt in 1..6) {
                if (!isConnected) return false
                try {
                    val powerOnCommand = powerOn()
                    val powerOnResponseBuffer = sendAndReceive(powerOnCommand)
                    val atr = parseDataBlockResponse(powerOnResponseBuffer)
                    if (atr.size > 2) {
                        isCardPoweredOn = true
                        Logger.i(TAG, "Re-powered card on attempt $attempt, ATR=${atr.toHex()}")
                        return true
                    }
                } catch (e: IOException) {
                    Logger.d(TAG, "IccPowerOn attempt $attempt after power-cycle failed: ${e.message}")
                }
                delay(45.milliseconds)
            }
            false
        } catch (e: IOException) {
            Logger.w(TAG, "Failed to power-cycle card slot", e)
            false
        }
    }

    /**
     * Disconnects from the CCID reader. This method should be called when the
     * application is finished with the device. It releases all resources and closes
     * the connection.
     */
    fun disconnect() {
        val wasConnected = isConnected
        val wasHardwarePolling = isHardwarePollingConfigured
        val wasTwoBytePollingType = usesTwoBytePollingTypeCommand
        isConnected = false
        isCardPoweredOn = false
        isHardwarePollingConfigured = false
        usesTwoBytePollingTypeCommand = false
        synchronized(usbLock) {
            val iface = usbInterface
            val conn = connection
            if (wasConnected && device.vendorId == VENDOR_ID_ACS && conn != null) {
                if (wasHardwarePolling) {
                    try {
                        sendEscapeCommandInternal(CMD_ACS_DEFAULT_ECP_PAYLOAD)
                    } catch (e: Exception) {
                        Logger.d(TAG, "Ignored error resetting hardware ECP payload on disconnect: ${e.message}")
                    }
                }
                if (wasTwoBytePollingType) {
                    try {
                        val resp = sendEscapeCommandInternal(CMD_ACS_POLLING_TYPE_NONE)
                        Logger.i(TAG, "Disabled ACS PICC polling on disconnect: ${resp.toHex()}")
                    } catch (e: Exception) {
                        Logger.d(TAG, "Ignored error disabling ACS PICC polling on disconnect: ${e.message}")
                    }
                }
            }
            usbInterface = null
            connection = null
            if (conn != null && iface != null) {
                try {
                    conn.releaseInterface(iface)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Error releasing USB interface", e)
                }
            }
            if (conn != null) {
                try {
                    conn.close()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Error closing USB connection", e)
                }
            }
        }
    }

    /**
     * Sets or removes the listener for card events.
     *
     * @param listener The listener to be notified of card events, or null to remove the current listener.
     */
    fun setListener(listener: CcidDriverListener?) {
        this.listener = listener
    }

    /**
     * Gets the current status of the card in the reader.
     *
     * @return A [CardStatus] enum indicating if a card is present and its state.
     * @throws IOException if there is a communication error.
     */
    fun getCardStatus(): CardStatus {
        if (!isConnected) throw IOException("Driver is not connected.")

        val command = createGetSlotStatusCommand()
        val response = sendAndReceive(command)

        val messageType = response.get(0)
        if (messageType != RDR_TO_PC_SLOTSTATUS.toByte()) {
            throw IOException("Unexpected response for GetSlotStatus: $messageType")
        }

        val statusByte = response.get(7)
        val iccStatus = statusByte.toInt() and 0x03 // bits 0 and 1

        return when (iccStatus) {
            0 -> CardStatus.PRESENT_ACTIVE
            1 -> CardStatus.PRESENT_INACTIVE
            2 -> CardStatus.ABSENT
            else -> CardStatus.UNKNOWN
        }
    }

    /**
     * Sends a command APDU to the smart card and returns the response APDU.
     * This is a synchronous operation and will block until the response is received.
     *
     * @param commandApdu The command APDU to send, as a ByteArray.
     * @return The response APDU received from the card, as a ByteArray.
     * @throws IOException if there is an error during the transfer.
     * @throws CcidException if the card returns an error.
     */
    @Throws(IOException::class)
    fun transceive(commandApdu: ByteArray): ByteArray {
        if (!isConnected) throw IOException("Driver is not connected.")

        if (!isCardPoweredOn) {
            val powerOnCommand = powerOn()
            val powerOnResponseBuffer = sendAndReceive(powerOnCommand)
            val powerOnResponse = parseDataBlockResponse(powerOnResponseBuffer)
            // The ATR is returned on power on. A valid ATR is typically longer than 2 bytes.
            if (powerOnResponse.size <= 2) {
                throw CcidException("Failed to power on card. Invalid ATR received.")
            }
            isCardPoweredOn = true
        }

        val maxCcidLen = maxCcidDataLength
        val response: ByteArray = if (commandApdu.size <= maxCcidLen) {
            val xfrBlock = createXfrBlock(commandApdu, wLevelParameter = 0)
            val responseBuffer = sendAndReceive(xfrBlock)
            parseDataBlockResponse(responseBuffer)
        } else {
            // Send APDU using CCID block chaining
            Logger.i(TAG, "Sending APDU of ${commandApdu.size} bytes using CCID block chaining in chunks of $maxCcidLen")
            val offsets = 0 until commandApdu.size step maxCcidLen
            var lastResponse = ByteArray(0)
            for (offset in offsets) {
                val isFirst = (offset == 0)
                val isLast = (offset + maxCcidLen >= commandApdu.size)
                val chunkSize = min(maxCcidLen, commandApdu.size - offset)
                val chunkBytes = commandApdu.copyOfRange(offset, offset + chunkSize)
                val wLevel: Short = when {
                    isFirst -> 1 // 0x0001: First block of command APDU
                    !isLast -> 3 // 0x0003: Intermediate block (another block is to follow)
                    else -> 2    // 0x0002: Last block (continues command APDU and ends command)
                }
                val xfrBlock = createXfrBlock(chunkBytes, wLevelParameter = wLevel)
                val responseBuffer = sendAndReceive(xfrBlock)
                lastResponse = parseDataBlockResponse(responseBuffer)
            }
            lastResponse
        }

        if (response.size < 2) {
            // If response is invalid, the card might have been removed.
            isCardPoweredOn = false
            throw CcidException("Invalid response from card")
        }

        return response
    }

    private fun createXfrBlock(data: ByteArray, wLevelParameter: Short = 0): ByteArray {
        val buffer = ByteBuffer.allocate(10 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PC_TO_RDR_XFRBLOCK)
        buffer.putInt(data.size)
        buffer.put(0x00) // bSlot
        buffer.put(sequence.getAndIncrement().toByte())
        buffer.put(0x00) // bBWI
        buffer.putShort(wLevelParameter)
        buffer.put(data)
        return buffer.array()
    }
    private fun powerOn(): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PC_TO_RDR_ICCPOWERON)
        buffer.putInt(0)
        buffer.put(0x00) //bSlot
        buffer.put(sequence.getAndIncrement().toByte())
        buffer.put(0x00) // bPowerSelect
        buffer.put(ByteArray(2))
        return buffer.array()
    }

    private fun powerOff(): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PC_TO_RDR_ICCPOWEROFF)
        buffer.putInt(0)
        buffer.put(0x00) // bSlot
        buffer.put(sequence.getAndIncrement().toByte())
        buffer.put(ByteArray(3)) // abRFU
        return buffer.array()
    }

    private fun createEscapeCommand(data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(10 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PC_TO_RDR_ESCAPE)
        buffer.putInt(data.size)
        buffer.put(0x00) // bSlot
        buffer.put(sequence.getAndIncrement().toByte())
        buffer.put(ByteArray(3)) // abRFU
        buffer.put(data)
        return buffer.array()
    }

    private fun createGetSlotStatusCommand(): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PC_TO_RDR_GETSLOTSTATUS)
        buffer.putInt(0) // length
        buffer.put(0x00) // bSlot
        buffer.put(sequence.getAndIncrement().toByte())
        buffer.put(ByteArray(3)) // RFU
        return buffer.array()
    }

    private fun sendAndReceive(command: ByteArray): ByteBuffer {
        synchronized(usbLock) {
            val bytesWritten = connection?.bulkTransfer(bulkOutEndpoint, command, command.size, TIMEOUT)
                ?: throw IOException("Failed to send command over bulk-out endpoint.")
            if (bytesWritten < 0) {
                throw IOException("Failed to send command over bulk-out endpoint: error $bytesWritten")
            }
            return readNextResponseBlock()
        }
    }

    private fun readNextResponseBlock(): ByteBuffer {
        while (true) {
            val responseBytes = ByteArray(MAX_RESPONSE_LENGTH)
            val bytesRead = connection?.bulkTransfer(bulkInEndpoint, responseBytes, responseBytes.size, TIMEOUT)
                ?: throw IOException("Failed to read response from bulk-in endpoint.")

            if (bytesRead < 0) throw IOException("Failed to read response from bulk-in endpoint: error $bytesRead")
            if (bytesRead < 10) throw IOException("Invalid response length: $bytesRead")

            val responseBuffer = ByteBuffer.wrap(responseBytes, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
            val statusByte = responseBuffer.get(7).toInt() and 0xFF
            val commandStatus = statusByte and 0xC0
            if (commandStatus == 0x80) {
                // Time Extension requested by reader (bmCommandStatus = 10b), wait for next response
                continue
            }
            return responseBuffer
        }
    }

    val supportsCcidChaining: Boolean
        get() = cachedSupportsCcidChaining ?: computeSupportsCcidChaining()

    private fun computeSupportsCcidChaining(): Boolean {
        val raw = try {
            connection?.rawDescriptors
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val ifaceId = usbInterface?.id ?: 0
        if (raw != null) {
            val ccidDesc = getCcidDescriptorForInterface(raw, ifaceId)
            if (ccidDesc != null && ccidDesc.size >= 44) {
                val dwFeatures = (ccidDesc[40].toInt() and 0xFF) or
                        ((ccidDesc[41].toInt() and 0xFF) shl 8) or
                        ((ccidDesc[42].toInt() and 0xFF) shl 16) or
                        ((ccidDesc[43].toInt() and 0xFF) shl 24)
                val exchangeLevel = dwFeatures and 0x000F0000
                return (exchangeLevel == 0x00040000) || (exchangeLevel == 0x00080000)
            }
        }
        return false
    }

    val maxCcidDataLength: Int
        get() = cachedMaxCcidDataLength ?: computeMaxCcidDataLength()

    private fun computeMaxCcidDataLength(): Int {
        val raw = try {
            connection?.rawDescriptors
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val ifaceId = usbInterface?.id ?: 0
        if (raw != null) {
            val ccidDesc = getCcidDescriptorForInterface(raw, ifaceId)
            if (ccidDesc != null && ccidDesc.size >= 48) {
                val maxMsgLen = (ccidDesc[44].toInt() and 0xFF) or
                        ((ccidDesc[45].toInt() and 0xFF) shl 8) or
                        ((ccidDesc[46].toInt() and 0xFF) shl 16) or
                        ((ccidDesc[47].toInt() and 0xFF) shl 24)
                if (maxMsgLen > 10) {
                    return maxMsgLen - 10
                }
            }
        }
        return 255
    }

    val maxCommandLength: Int
        get() {
            cachedMaxCommandLength?.let { return it }
            if (supportsCcidChaining) {
                return 65535
            }
            val maxCcidLen = maxCcidDataLength
            return if (maxCcidLen > 20) maxCcidLen - 10 else 255
        }

    private fun parseDataBlockResponse(responseBuffer: ByteBuffer): ByteArray {
        val messageType = responseBuffer.get(0).toInt() and 0xFF
        if (messageType == RDR_TO_PC_SLOTSTATUS.toInt() and 0xFF) {
            val statusByte = responseBuffer.get(7).toInt() and 0xFF
            val errorCode = responseBuffer.get(8).toInt() and 0xFF
            throw CcidException(
                "CCID reader returned SlotStatus (0x81) instead of DataBlock: status 0x${statusByte.toString(16)}, error 0x${errorCode.toString(16)}"
            )
        }
        if (messageType != RDR_TO_PC_DATABLOCK.toInt() and 0xFF) {
            throw IOException(
                "Unexpected response message type, expected Data Block (0x80) but got 0x${messageType.toString(16)}"
            )
        }
        val statusByte = responseBuffer.get(7).toInt() and 0xFF
        val commandStatus = statusByte and 0xC0
        if (commandStatus == 0x40) {
            val errorCode = responseBuffer.get(8).toInt() and 0xFF
            throw CcidException("CCID command failed with status 0x${statusByte.toString(16)}, error code 0x${errorCode.toString(16)}")
        }
        val length = responseBuffer.getInt(1)
        if (length < 0 || length > responseBuffer.limit() - 10) {
            throw IOException("Incomplete or invalid response data length: $length")
        }
        val data = ByteArray(length)
        responseBuffer.position(10)
        responseBuffer.get(data)

        // Handle CCID response block chaining per CCID Spec Rev 1.1 Section 6.2.1:
        // bChainParameter values: 0x01 (First block), 0x03 (Intermediate block).
        // Host requests next block using PC_to_RDR_XfrBlock with wLevelParameter = 0x0010.
        val bChainParameter = responseBuffer.get(9).toInt() and 0xFF
        val isChainedResponse = supportsCcidChaining && (bChainParameter == 0x01 || bChainParameter == 0x03)
        if (isChainedResponse) {
            Logger.i(TAG, "CCID response chaining: length=$length, bChainParameter=0x${bChainParameter.toString(16)}, requesting next chunk with wLevelParameter=0x0010")
            val getNextChunkBlock = createXfrBlock(data = ByteArray(0), wLevelParameter = 0x0010)
            val nextBuffer = sendAndReceive(getNextChunkBlock)
            val nextData = parseDataBlockResponse(nextBuffer)
            return data + nextData
        }

        return data
    }

    private fun hasApduStatusTrailer(data: ByteArray): Boolean {
        if (data.size < 2) return false
        val sw1 = data[data.size - 2].toInt() and 0xFF
        return sw1 == 0x90 || (sw1 in 0x61..0x6F)
    }

    private fun startInterruptListener() {
        val endpoint = interruptEndpoint ?: run {
            Logger.i(TAG, "No interrupt endpoint found for interface ${usbInterface?.id}")
            return
        }
        Thread {
            val buffer = ByteArray(endpoint.maxPacketSize)
            while (isConnected) {
                val conn = connection ?: break
                val bytesRead = try {
                    conn.bulkTransfer(endpoint, buffer, buffer.size, 500)
                } catch (e: Exception) {
                    -1
                }
                if (bytesRead > 0) {
                    Logger.i(TAG, "USB interrupt received ($bytesRead bytes): ${buffer.copyOf(bytesRead).toHex()}")
                    handleInterrupt(buffer)
                } else if (bytesRead < 0) {
                    if (!isConnected) break
                    try { Thread.sleep(50) } catch (_: InterruptedException) {}
                }
            }
        }.start()
    }

    private fun handleInterrupt(data: ByteArray) {
        if (data[0] == RDR_TO_PC_NOTIFYSLOTCHANGE.toByte()) {
            val slotState = data[1].toInt() and 0x03
            when (slotState) {
                0x03 -> { // Change, ICC Present -> Card was inserted
                    isCardPoweredOn = false
                    listener?.onCardInserted()
                }
                0x02 -> { // Change, No ICC -> Card was removed
                    isCardPoweredOn = false
                    listener?.onCardRemoved()
                }
            }
        }
    }

    private fun findCcidInterface(device: UsbDevice, connection: UsbDeviceConnection?): UsbInterface? {
        val iface = try {
            device.getInterface(interfaceIndex)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        if (iface != null) {
            Logger.i(TAG, "Using specified USB interface id=${iface.id} for device ${device.deviceName}")
            return iface
        }
        for (i in 0 until device.interfaceCount) {
            val candidate = device.getInterface(i)
            if (candidate.interfaceClass == UsbConstants.USB_CLASS_CSCID) {
                Logger.i(TAG, "Defaulting to first CCID interface id=${candidate.id} for device ${device.deviceName}")
                return candidate
            }
        }
        return null
    }

    private fun getCcidDescriptorForInterface(raw: ByteArray, targetIfaceNum: Int): ByteArray? {
        var idx = 0
        var currentIfaceNum = -1
        while (idx < raw.size - 2) {
            val len = raw[idx].toInt() and 0xFF
            val type = raw[idx + 1].toInt() and 0xFF
            if (len <= 0) break
            if (type == 0x04 && len >= 9 && idx + 9 <= raw.size) {
                currentIfaceNum = raw[idx + 2].toInt() and 0xFF
            } else if (type == 0x21 && currentIfaceNum == targetIfaceNum && len >= 0x36 && idx + len <= raw.size) {
                return raw.copyOfRange(idx, idx + len)
            }
            idx += len
        }
        return null
    }

    private fun findEndpoints(iface: UsbInterface) {
        for (i in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    bulkInEndpoint = endpoint
                } else {
                    bulkOutEndpoint = endpoint
                }
            } else if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                interruptEndpoint = endpoint
            }
        }
    }

    companion object {
        private const val TAG = "CcidDriver"
        private const val TIMEOUT = 5000
        private const val MAX_RESPONSE_LENGTH = 65546

        // USB Vendor IDs
        private const val VENDOR_ID_ACS = 0x072F // Advanced Card Systems Ltd. (1839)

        // CCID Message Types
        private const val PC_TO_RDR_ICCPOWERON: Byte = 0x62
        private const val PC_TO_RDR_ICCPOWEROFF: Byte = 0x63
        private const val PC_TO_RDR_GETSLOTSTATUS: Byte = 0x65
        private const val PC_TO_RDR_ESCAPE: Byte = 0x6B
        private const val PC_TO_RDR_XFRBLOCK: Byte = 0x6F
        private const val RDR_TO_PC_DATABLOCK: Byte = 0x80.toByte()
        private const val RDR_TO_PC_SLOTSTATUS: Byte = 0x81.toByte()
        private const val RDR_TO_PC_ESCAPE: Byte = 0x83.toByte()
        private const val RDR_TO_PC_NOTIFYSLOTCHANGE: Byte = 0x50

        // ACS Escape Commands
        private val CMD_ACS_DEFAULT_ECP_PAYLOAD = byteArrayOf(
            0xE0.toByte(), 0x00, 0x00, 0x3B, 0x04, 0x01, 0x00, 0x00, 0x02
        )
        private val CMD_ACS_POLLING_TYPE_NONE = byteArrayOf(
            0xE0.toByte(), 0x00, 0x01, 0x20, 0x02, 0x00, 0x00
        )
    }
}
