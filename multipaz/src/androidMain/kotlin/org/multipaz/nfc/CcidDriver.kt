package org.multipaz.nfc

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.math.min
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
) {
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    private var interruptEndpoint: UsbEndpoint? = null
    private val sequence = AtomicInteger(0)
    @Volatile
    private var isConnected = false
    @Volatile
    private var isCardPoweredOn = false
    private var listener: CcidDriverListener? = null

    /**
     * Connects to the CCID reader. This method must be called before any other
     * operations can be performed. It requests permission to access the USB device,
     * opens a connection, and starts listening for card events.
     *
     * @throws IOException if the connection to the device fails.
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
            ?: throw IOException("CCID interface not found")
        usbInterface = iface

        findEndpoints(iface)
        conn.claimInterface(iface, true)
        isConnected = true
        Logger.i(TAG, "Connected to CCID device ${device.deviceName} (interface id=${iface.id}), maxCommandLength=$maxCommandLength")
        startInterruptListener()
    }
    /**
     * Disconnects from the CCID reader. This method should be called when the
     * application is finished with the device. It releases all resources and closes
     * the connection.
     */
    fun disconnect() {
        isConnected = false
        isCardPoweredOn = false
        connection?.releaseInterface(usbInterface)
        connection?.close()
        connection = null
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
        val bytesWritten = connection?.bulkTransfer(bulkOutEndpoint, command, command.size, TIMEOUT)
            ?: throw IOException("Failed to send command over bulk-out endpoint.")
        if (bytesWritten < 0) {
            throw IOException("Failed to send command over bulk-out endpoint: error $bytesWritten")
        }
        return readNextResponseBlock()
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
        get() {
            val raw = try { connection?.rawDescriptors } catch (_: Throwable) { null }
            val ifaceId = usbInterface?.id ?: 0
            if (raw != null) {
                val ccidDesc = getCcidDescriptorForInterface(raw, ifaceId)
                if (ccidDesc != null && ccidDesc.size >= 44) {
                    val dwFeatures = (ccidDesc[40].toInt() and 0xFF) or
                            ((ccidDesc[41].toInt() and 0xFF) shl 8) or
                            ((ccidDesc[42].toInt() and 0xFF) shl 16) or
                            ((ccidDesc[43].toInt() and 0xFF) shl 24)
                    val exchangeLevel = dwFeatures and 0x000F0000
                    val supportsChaining = (exchangeLevel == 0x00040000) || (exchangeLevel == 0x00080000)
                    return supportsChaining
                }
            }
            return false
        }

    val maxCcidDataLength: Int
        get() {
            val raw = try { connection?.rawDescriptors } catch (_: Throwable) { null }
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
            if (supportsCcidChaining) {
                Logger.i(TAG, "Reader supports CCID block chaining (dwFeatures exchange level extended APDU) -> maxCommandLength=65535")
                return 65535
            }
            val maxCcidLen = maxCcidDataLength
            val maxCmdLen = if (maxCcidLen > 20) maxCcidLen - 10 else 255
            Logger.i(TAG, "Reader does not report CCID chaining -> maxCcidDataLength=$maxCcidLen, maxCommandLength=$maxCmdLen")
            return maxCmdLen
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
                val bytesRead = connection?.bulkTransfer(endpoint, buffer, buffer.size, 0)
                if (bytesRead != null && bytesRead > 0) {
                    handleInterrupt(buffer)
                }
            }
        }.start()
    }

    private fun handleInterrupt(data: ByteArray) {
        if(data[0] == RDR_TO_PC_NOTIFYSLOTCHANGE.toByte()) {
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
        val iface = try { device.getInterface(interfaceIndex) } catch (_: Throwable) { null }
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

        // CCID Message Types
        private const val PC_TO_RDR_ICCPOWERON: Byte = 0x62
        private const val PC_TO_RDR_GETSLOTSTATUS: Byte = 0x65
        private const val PC_TO_RDR_XFRBLOCK: Byte = 0x6F
        private const val RDR_TO_PC_DATABLOCK: Byte = 0x80.toByte()
        private const val RDR_TO_PC_SLOTSTATUS: Byte = 0x81.toByte()
        private const val RDR_TO_PC_NOTIFYSLOTCHANGE: Byte = 0x50
    }
}
