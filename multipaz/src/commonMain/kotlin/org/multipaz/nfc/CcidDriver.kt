package org.multipaz.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import org.multipaz.util.Logger
import org.multipaz.util.SynchronizedLock
import org.multipaz.util.toHex
import org.multipaz.util.withLock
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal interface CcidDriverOps {
    val maxCommandLength: Int
    fun connect()
    fun disconnect()
    fun setListener(listener: CcidDriverListener?)
    fun getCardStatus(): CardStatus
    suspend fun powerCycleCard(fieldOffDelay: Duration = 80.milliseconds): Boolean
    fun transceive(commandApdu: ByteArray): ByteArray
}

/**
 * A driver for USB CCID (Chip Card Interface Device) smart card readers.
 * This class handles the communication with a CCID-compliant smart card reader,
 * allowing for sending APDUs (Application Protocol Data Units) to a smart card
 * and receiving responses. It also provides notifications for card insertion and removal.
 *
 * The driver communicates with the CCID reader over bulk and interrupt USB endpoints
 * provided by a platform [CcidUsbTransport].
 *
 * @property transport The underlying USB CCID transport.
 * @property pollingFrameData Optional custom polling frame bytes (e.g. ECP frame starting with `0x6A`).
 * @property backgroundScope Coroutine scope used for polling the USB interrupt-IN endpoint.
 */
internal class CcidDriver(
    private val transport: CcidUsbTransport,
    private val pollingFrameData: ByteArray? = null,
    private val backgroundScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : CcidDriverOps {
    private var nextSequenceNumber: Int = 0
    private val sequenceLock = SynchronizedLock()
    private val usbLock = SynchronizedLock()
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
    @Volatile
    private var listener: CcidDriverListener? = null
    private var interruptJob: Job? = null

    private fun nextSequence(): Byte = sequenceLock.withLock {
        val seq = nextSequenceNumber
        nextSequenceNumber = (seq + 1) and 0xFF
        seq.toByte()
    }

    /**
     * Connects to the CCID reader. This method must be called before any other
     * operations can be performed. It opens the underlying [CcidUsbTransport],
     * claims the CCID interface, configures contactless polling, and starts listening
     * for card insertion/removal events.
     *
     * @throws IOException if the connection to the device fails or interface cannot be claimed.
     */
    override fun connect() {
        transport.open()
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
        Logger.i(TAG, "Connected to CCID device ${transport.deviceName} (interface id=${transport.interfaceId}), maxCommandLength=$maxCommandLength")
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
        if (transport.vendorId == VENDOR_ID_ACS) {
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
                    if (e is CancellationException) throw e
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
                if (e is CancellationException) throw e
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
                    if (e2 is CancellationException) throw e2
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
        val responseBytes = sendAndReceive(cmd)
        val messageType = responseBytes[0].toInt() and 0xFF
        if (messageType != (RDR_TO_PC_ESCAPE.toInt() and 0xFF)) {
            throw IOException(
                "Unexpected response message type for Escape command, expected 0x83 but got 0x${messageType.toString(16)}"
            )
        }
        val statusByte = responseBytes[7].toInt() and 0xFF
        val commandStatus = statusByte and 0xC0
        if (commandStatus == 0x40) {
            val errorCode = responseBytes[8].toInt() and 0xFF
            throw CcidException(
                "CCID Escape command failed with status 0x${statusByte.toString(16)}, error code 0x${errorCode.toString(16)}"
            )
        }
        val length = readInt32Le(responseBytes, 1)
        if (length < 0 || length > responseBytes.size - 10) {
            throw IOException("Incomplete or invalid Escape response data length: $length")
        }
        return responseBytes.copyOfRange(10, 10 + length)
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
    override suspend fun powerCycleCard(fieldOffDelay: Duration): Boolean {
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
                    val powerOnResponse = sendAndReceive(powerOnCommand)
                    val atr = parseDataBlockResponse(powerOnResponse)
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
    override fun disconnect() {
        val wasConnected = isConnected
        val wasHardwarePolling = isHardwarePollingConfigured
        val wasTwoBytePollingType = usesTwoBytePollingTypeCommand
        isConnected = false
        isCardPoweredOn = false
        isHardwarePollingConfigured = false
        usesTwoBytePollingTypeCommand = false
        interruptJob?.cancel()
        interruptJob = null
        usbLock.withLock {
            if (wasConnected && transport.vendorId == VENDOR_ID_ACS) {
                if (wasHardwarePolling) {
                    try {
                        sendEscapeCommandInternal(CMD_ACS_DEFAULT_ECP_PAYLOAD)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.d(TAG, "Ignored error resetting hardware ECP payload on disconnect: ${e.message}")
                    }
                }
                if (wasTwoBytePollingType) {
                    try {
                        val resp = sendEscapeCommandInternal(CMD_ACS_POLLING_TYPE_NONE)
                        Logger.i(TAG, "Disabled ACS PICC polling on disconnect: ${resp.toHex()}")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.d(TAG, "Ignored error disabling ACS PICC polling on disconnect: ${e.message}")
                    }
                }
            }
            transport.close()
        }
    }

    /**
     * Sets or removes the listener for card events.
     *
     * @param listener The listener to be notified of card events, or null to remove the current listener.
     */
    override fun setListener(listener: CcidDriverListener?) {
        this.listener = listener
    }

    /**
     * Gets the current status of the card in the reader.
     *
     * @return A [CardStatus] enum indicating if a card is present and its state.
     * @throws IOException if there is a communication error.
     */
    override fun getCardStatus(): CardStatus {
        if (!isConnected) throw IOException("Driver is not connected.")

        val command = createGetSlotStatusCommand()
        val response = sendAndReceive(command)

        val messageType = response[0]
        if (messageType != RDR_TO_PC_SLOTSTATUS) {
            throw IOException("Unexpected response for GetSlotStatus: $messageType")
        }

        val statusByte = response[7]
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
     *
     * @param commandApdu The command APDU to send, as a ByteArray.
     * @return The response APDU received from the card, as a ByteArray.
     * @throws IOException if there is an error during the transfer.
     * @throws CcidException if the card returns an error.
     */
    @Throws(IOException::class)
    override fun transceive(commandApdu: ByteArray): ByteArray {
        if (!isConnected) throw IOException("Driver is not connected.")

        if (!isCardPoweredOn) {
            val powerOnCommand = powerOn()
            val powerOnResponseBytes = sendAndReceive(powerOnCommand)
            val powerOnResponse = parseDataBlockResponse(powerOnResponseBytes)
            // The ATR is returned on power on. A valid ATR is typically longer than 2 bytes.
            if (powerOnResponse.size <= 2) {
                throw CcidException("Failed to power on card. Invalid ATR received.")
            }
            isCardPoweredOn = true
        }

        val maxCcidLen = maxCcidDataLength
        val response: ByteArray = if (commandApdu.size <= maxCcidLen) {
            val xfrBlock = createXfrBlock(commandApdu, wLevelParameter = 0)
            val responseBytes = sendAndReceive(xfrBlock)
            parseDataBlockResponse(responseBytes)
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
                val responseBytes = sendAndReceive(xfrBlock)
                lastResponse = parseDataBlockResponse(responseBytes)
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
        val out = ByteArray(10 + data.size)
        out[0] = PC_TO_RDR_XFRBLOCK
        writeInt32Le(out, 1, data.size)
        out[5] = 0x00 // bSlot
        out[6] = nextSequence()
        out[7] = 0x00 // bBWI
        writeInt16Le(out, 8, wLevelParameter.toInt())
        data.copyInto(out, destinationOffset = 10)
        return out
    }

    private fun powerOn(): ByteArray {
        val out = ByteArray(10)
        out[0] = PC_TO_RDR_ICCPOWERON
        writeInt32Le(out, 1, 0)
        out[5] = 0x00 // bSlot
        out[6] = nextSequence()
        out[7] = 0x00 // bPowerSelect
        return out
    }

    private fun powerOff(): ByteArray {
        val out = ByteArray(10)
        out[0] = PC_TO_RDR_ICCPOWEROFF
        writeInt32Le(out, 1, 0)
        out[5] = 0x00 // bSlot
        out[6] = nextSequence()
        return out
    }

    private fun createEscapeCommand(data: ByteArray): ByteArray {
        val out = ByteArray(10 + data.size)
        out[0] = PC_TO_RDR_ESCAPE
        writeInt32Le(out, 1, data.size)
        out[5] = 0x00 // bSlot
        out[6] = nextSequence()
        data.copyInto(out, destinationOffset = 10)
        return out
    }

    private fun createGetSlotStatusCommand(): ByteArray {
        val out = ByteArray(10)
        out[0] = PC_TO_RDR_GETSLOTSTATUS
        writeInt32Le(out, 1, 0)
        out[5] = 0x00 // bSlot
        out[6] = nextSequence()
        return out
    }

    private fun sendAndReceive(command: ByteArray): ByteArray {
        return usbLock.withLock {
            val bytesWritten = transport.bulkTransferOut(command, TIMEOUT)
            if (bytesWritten < 0) {
                throw IOException("Failed to send command over bulk-out endpoint: error $bytesWritten")
            }
            readNextResponseBlock()
        }
    }

    private fun readNextResponseBlock(): ByteArray {
        while (true) {
            val responseBytes = ByteArray(MAX_RESPONSE_LENGTH)
            val bytesRead = transport.bulkTransferIn(responseBytes, TIMEOUT)

            if (bytesRead < 0) throw IOException("Failed to read response from bulk-in endpoint: error $bytesRead")
            if (bytesRead < 10) throw IOException("Invalid response length: $bytesRead")

            val statusByte = responseBytes[7].toInt() and 0xFF
            val commandStatus = statusByte and 0xC0
            if (commandStatus == 0x80) {
                // Time Extension requested by reader (bmCommandStatus = 10b), wait for next response
                continue
            }
            return responseBytes.copyOfRange(0, bytesRead)
        }
    }

    val supportsCcidChaining: Boolean
        get() = cachedSupportsCcidChaining ?: computeSupportsCcidChaining()

    private fun computeSupportsCcidChaining(): Boolean {
        val raw = try {
            transport.rawDescriptors
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val ifaceId = transport.interfaceId
        if (raw != null) {
            val ccidDesc = getCcidDescriptorForInterface(raw, ifaceId)
            if (ccidDesc != null && ccidDesc.size >= 44) {
                val dwFeatures = readInt32Le(ccidDesc, 40)
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
            transport.rawDescriptors
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val ifaceId = transport.interfaceId
        if (raw != null) {
            val ccidDesc = getCcidDescriptorForInterface(raw, ifaceId)
            if (ccidDesc != null && ccidDesc.size >= 48) {
                val maxMsgLen = readInt32Le(ccidDesc, 44)
                if (maxMsgLen > 10) {
                    return maxMsgLen - 10
                }
            }
        }
        return 255
    }

    override val maxCommandLength: Int
        get() {
            cachedMaxCommandLength?.let { return it }
            if (supportsCcidChaining) {
                return 65535
            }
            val maxCcidLen = maxCcidDataLength
            return if (maxCcidLen > 20) maxCcidLen - 10 else 255
        }

    private fun parseDataBlockResponse(responseBytes: ByteArray): ByteArray {
        val messageType = responseBytes[0].toInt() and 0xFF
        if (messageType == RDR_TO_PC_SLOTSTATUS.toInt() and 0xFF) {
            val statusByte = responseBytes[7].toInt() and 0xFF
            val errorCode = responseBytes[8].toInt() and 0xFF
            throw CcidException(
                "CCID reader returned SlotStatus (0x81) instead of DataBlock: status 0x${statusByte.toString(16)}, error 0x${errorCode.toString(16)}"
            )
        }
        if (messageType != RDR_TO_PC_DATABLOCK.toInt() and 0xFF) {
            throw IOException(
                "Unexpected response message type, expected Data Block (0x80) but got 0x${messageType.toString(16)}"
            )
        }
        val statusByte = responseBytes[7].toInt() and 0xFF
        val commandStatus = statusByte and 0xC0
        if (commandStatus == 0x40) {
            val errorCode = responseBytes[8].toInt() and 0xFF
            throw CcidException("CCID command failed with status 0x${statusByte.toString(16)}, error code 0x${errorCode.toString(16)}")
        }
        val length = readInt32Le(responseBytes, 1)
        if (length < 0 || length > responseBytes.size - 10) {
            throw IOException("Incomplete or invalid response data length: $length")
        }
        val data = responseBytes.copyOfRange(10, 10 + length)

        // Handle CCID response block chaining per CCID Spec Rev 1.1 Section 6.2.1:
        // bChainParameter values: 0x01 (First block), 0x03 (Intermediate block).
        // Host requests next block using PC_to_RDR_XfrBlock with wLevelParameter = 0x0010.
        val bChainParameter = responseBytes[9].toInt() and 0xFF
        val isChainedResponse = supportsCcidChaining && (bChainParameter == 0x01 || bChainParameter == 0x03)
        if (isChainedResponse) {
            Logger.i(TAG, "CCID response chaining: length=$length, bChainParameter=0x${bChainParameter.toString(16)}, requesting next chunk with wLevelParameter=0x0010")
            val getNextChunkBlock = createXfrBlock(data = ByteArray(0), wLevelParameter = 0x0010)
            val nextBytes = sendAndReceive(getNextChunkBlock)
            val nextData = parseDataBlockResponse(nextBytes)
            return data + nextData
        }

        return data
    }

    private fun startInterruptListener() {
        if (!transport.hasInterruptEndpoint) {
            Logger.i(TAG, "No interrupt endpoint found for interface ${transport.interfaceId}")
            return
        }
        val packetSize = transport.interruptMaxPacketSize.coerceAtLeast(8)
        interruptJob?.cancel()
        interruptJob = backgroundScope.launch {
            val buffer = ByteArray(packetSize)
            while (isActive && isConnected) {
                val bytesRead = try {
                    transport.interruptTransferIn(buffer, 500)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    -1
                }
                if (bytesRead > 0) {
                    Logger.i(TAG, "USB interrupt received ($bytesRead bytes): ${buffer.copyOf(bytesRead).toHex()}")
                    handleInterrupt(buffer)
                } else if (bytesRead < 0) {
                    if (!isConnected) break
                    delay(50.milliseconds)
                }
            }
        }
    }

    private fun handleInterrupt(data: ByteArray) {
        if (data.size >= 2 && data[0] == RDR_TO_PC_NOTIFYSLOTCHANGE) {
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

    companion object {
        private const val TAG = "CcidDriver"
        private const val TIMEOUT = 5000
        private const val MAX_RESPONSE_LENGTH = 65546

        // USB Vendor IDs
        internal const val VENDOR_ID_ACS = 0x072F // Advanced Card Systems Ltd. (1839)

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

        internal fun readInt32Le(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        }

        internal fun writeInt32Le(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        internal fun writeInt16Le(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        /**
         * Extracts the 54-byte class-specific CCID functional descriptor (`bDescriptorType == 0x21`)
         * belonging to USB interface [targetIfaceNum] from raw USB configuration descriptors.
         */
        internal fun getCcidDescriptorForInterface(raw: ByteArray, targetIfaceNum: Int): ByteArray? {
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
    }
}
