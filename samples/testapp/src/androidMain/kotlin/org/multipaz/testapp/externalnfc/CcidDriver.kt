package org.multipaz.testapp.externalnfc

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
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
class CcidDriver(
    private val usbManager: UsbManager,
    private val device: UsbDevice
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
        usbInterface = findCcidInterface(device)
            ?: throw IOException("CCID interface not found")

        findEndpoints(usbInterface!!)
        connection = usbManager.openDevice(device)
            ?: throw IOException("Could not open device connection")

        connection?.claimInterface(usbInterface, true)
        isConnected = true
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

        val xfrBlock = createXfrBlock(commandApdu)
        val responseBuffer = sendAndReceive(xfrBlock)
        val response = parseDataBlockResponse(responseBuffer)
        if (response.size < 2) {
            // If response is invalid, the card might have been removed.
            isCardPoweredOn = false
            throw CcidException("Invalid response from card")
        }

        return response
    }
    private fun createXfrBlock(data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(10 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(PC_TO_RDR_XFRBLOCK)
        buffer.putInt(data.size)
        buffer.put(0x00) // bSlot
        buffer.put(sequence.getAndIncrement().toByte())
        buffer.put(ByteArray(3)) // RFU
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
        connection?.bulkTransfer(bulkOutEndpoint, command, command.size, TIMEOUT)
            ?: throw IOException("Failed to send command over bulk-out endpoint.")

        val responseBytes = ByteArray(MAX_RESPONSE_LENGTH)
        val bytesRead = connection?.bulkTransfer(bulkInEndpoint, responseBytes, responseBytes.size, TIMEOUT)
            ?: throw IOException("Failed to read response from bulk-in endpoint.")

        if (bytesRead < 10) throw IOException("Invalid response length: $bytesRead")

        return ByteBuffer.wrap(responseBytes, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun parseDataBlockResponse(responseBuffer: ByteBuffer): ByteArray {
        val messageType = responseBuffer.get(0)
        if (messageType != RDR_TO_PC_DATABLOCK.toByte()) {
            throw IOException("Unexpected response message type, expected Data Block but got $messageType")
        }
        val length = responseBuffer.getInt(1)
        if (length < 0 || length > responseBuffer.limit() - 10) {
            throw IOException("Incomplete or invalid response data length: $length")
        }
        val data = ByteArray(length)
        responseBuffer.position(10)
        responseBuffer.get(data)
        return data
    }

    private fun startInterruptListener() {
        Thread {
            val buffer = ByteArray(interruptEndpoint?.maxPacketSize ?: 2)
            while (isConnected) {
                val bytesRead = connection?.bulkTransfer(interruptEndpoint, buffer, buffer.size, 0)
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
    private fun findCcidInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_CSCID) {
                return iface
            }
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
