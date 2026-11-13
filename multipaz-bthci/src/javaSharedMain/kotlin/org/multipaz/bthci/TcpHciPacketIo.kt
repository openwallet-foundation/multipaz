package org.multipaz.bthci

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An [HciPacketIo] implementation that reads and writes H4-framed Bluetooth HCI packets (`0x01`
 * Command, `0x02` ACL Data, `0x03` Synchronous Data, `0x04` Event, `0x05` ISO Data) over a TCP
 * [Socket], such as Android's `netsimd` (`--hci-port`) or Rootcanal virtual Bluetooth controller.
 */
class TcpHciPacketIo private constructor(
    private val socket: Socket,
) : HciPacketIo {
    private val inputStream = socket.getInputStream()
    private val outputStream = socket.getOutputStream()
    private val writeMutex = Mutex()

    @Volatile
    private var closed = false

    override suspend fun readPacket(): ByteArray? = withContext(Dispatchers.IO) {
        if (closed) return@withContext null
        try {
            val packetTypeByte = inputStream.read()
            if (packetTypeByte == -1) {
                return@withContext null
            }
            val packetType = packetTypeByte and 0xFF
            val headerLen: Int
            val payloadLenExtractor: (ByteArray) -> Int
            when (packetType) {
                0x01 -> {
                    // HCI Command Packet: [0..1]=Opcode, [2]=Parameter Total Length
                    headerLen = 3
                    payloadLenExtractor = { hdr -> hdr[2].toInt() and 0xFF }
                }
                0x02 -> {
                    // HCI ACL Data Packet: [0..1]=Handle/Flags, [2..3]=Data Total Length (u16le)
                    headerLen = 4
                    payloadLenExtractor = { hdr -> u16le(hdr, 2) }
                }
                0x03 -> {
                    // HCI Synchronous Data Packet: [0..1]=Handle/Flags, [2]=Data Total Length
                    headerLen = 3
                    payloadLenExtractor = { hdr -> hdr[2].toInt() and 0xFF }
                }
                0x04 -> {
                    // HCI Event Packet: [0]=Event Code, [1]=Parameter Total Length
                    headerLen = 2
                    payloadLenExtractor = { hdr -> hdr[1].toInt() and 0xFF }
                }
                0x05 -> {
                    // HCI ISO Data Packet: [0..1]=Handle/Flags, [2..3]=Data_Total_Length (14 bits)
                    headerLen = 4
                    payloadLenExtractor = { hdr -> u16le(hdr, 2) and 0x3FFF }
                }
                else -> throw IOException("Unsupported H4 packet indicator 0x${packetType.toString(16)}")
            }

            val header = ByteArray(headerLen)
            readFully(inputStream, header, 0, headerLen)
            val payloadLen = payloadLenExtractor(header)
            val packet = ByteArray(1 + headerLen + payloadLen)
            packet[0] = packetTypeByte.toByte()
            header.copyInto(packet, destinationOffset = 1)
            if (payloadLen > 0) {
                readFully(inputStream, packet, 1 + headerLen, payloadLen)
            }
            packet
        } catch (e: SocketException) {
            if (closed) null else throw IOException("TCP HCI read failed", e)
        } catch (e: EOFException) {
            if (closed) null else throw IOException("Unexpected EOF while reading H4 packet", e)
        } catch (e: IOException) {
            if (closed) null else throw e
        }
    }

    override suspend fun writePacket(packet: ByteArray): Unit = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed) {
                throw IOException("TcpHciPacketIo is closed")
            }
            try {
                outputStream.write(packet)
                outputStream.flush()
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw IOException("TCP HCI write failed", e)
            }
        }
    }

    override fun close() {
        closed = true
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * Default TCP port used for `netsimd --hci-port` / Rootcanal HCI sockets.
         */
        const val DEFAULT_NETSIM_HCI_PORT: Int = 6402

        /**
         * Special alias IP inside the Android Emulator that routes to the host's `127.0.0.1`
         * loopback interface.
         */
        const val EMULATOR_HOST_LOOPBACK: String = "10.0.2.2"

        /**
         * Connects a new [TcpHciPacketIo] to a TCP H4 HCI endpoint (such as `netsimd --hci-port`
         * or Rootcanal).
         *
         * @param host target hostname or IP address (defaults to `"127.0.0.1"`).
         * @param port target TCP port (defaults to [DEFAULT_NETSIM_HCI_PORT]).
         * @param connectTimeout maximum time to wait for the TCP connection to establish.
         * @return a connected [TcpHciPacketIo].
         * @throws IOException if the connection fails or times out.
         * @throws CancellationException if the coroutine is cancelled.
         */
        @Throws(IOException::class, CancellationException::class)
        suspend fun connect(
            host: String = "127.0.0.1",
            port: Int = DEFAULT_NETSIM_HCI_PORT,
            connectTimeout: Duration = 3.seconds,
        ): TcpHciPacketIo = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                val timeoutMs = connectTimeout.inWholeMilliseconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                TcpHciPacketIo(socket)
            } catch (e: Exception) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                if (e is CancellationException) throw e
                throw e as? IOException ?: IOException("Failed connecting to HCI TCP port $host:$port", e)
            }
        }

        /**
         * Wraps an already-connected [Socket] as a [TcpHciPacketIo].
         *
         * @param socket the connected TCP socket speaking H4 HCI.
         * @return a [TcpHciPacketIo] wrapping [socket].
         */
        fun fromSocket(socket: Socket): TcpHciPacketIo {
            socket.tcpNoDelay = true
            return TcpHciPacketIo(socket)
        }

        /**
         * Checks whether an H4 HCI TCP server (such as `netsimd --hci-port`) is reachable at
         * [host]:[port] without issuing an HCI command that would allocate a persistent controller.
         */
        suspend fun isReachable(
            host: String = "127.0.0.1",
            port: Int = DEFAULT_NETSIM_HCI_PORT,
            connectTimeout: Duration = 1.seconds,
        ): Boolean = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                val timeoutMs = connectTimeout.inWholeMilliseconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            } catch (_: Exception) {
                false
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }

        private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
            var remaining = length
            var pos = offset
            while (remaining > 0) {
                val read = input.read(buffer, pos, remaining)
                if (read < 0) {
                    throw EOFException("Unexpected EOF with $remaining of $length bytes remaining")
                }
                pos += read
                remaining -= read
            }
        }
    }
}
