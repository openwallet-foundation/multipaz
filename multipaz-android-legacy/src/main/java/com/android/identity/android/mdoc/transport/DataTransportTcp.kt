/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.identity.android.mdoc.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.nfc.NdefRecord
import android.text.format.Formatter
import android.util.Pair
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.util.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.TimeUnit

/**
 * TCP data transport.
 *
 *
 * This is a private non-standardized data transport. It is only here for testing purposes.
 */
class DataTransportTcp(
    context: Context,
    role: Role,
    options: DataTransportOptions
) : DataTransport(context, role, options) {
    var socket: Socket? = null
    var writerQueue: BlockingQueue<ByteArray> = LinkedTransferQueue()
    var serverSocket: ServerSocket? = null
    var socketWriterThread: Thread? = null

    var host: String? = null
        private set
    var port = 0
        private set

    private var messageRewriter: MessageRewriter? = null
    override fun setEDeviceKeyBytes(encodedEDeviceKeyBytes: ByteArray) {
        // Not used.
    }

    private fun connectAsMdoc() {
        serverSocket = try {
            ServerSocket(port)
        } catch (e: IOException) {
            reportError(e)
            return
        }
        val port = serverSocket!!.localPort
        val socketServerThread: Thread = object : Thread() {
            override fun run() {
                try {
                    // We only accept a single client with this server socket...
                    //
                    socket = serverSocket!!.accept()
                    serverSocket = null
                    setupWritingThread()
                    reportConnected()
                    val e = processMessagesFromSocket()
                    if (e != null) {
                        reportError(e)
                    } else {
                        reportDisconnected()
                    }
                } catch (e: Exception) {
                    reportError(e)
                }
            }
        }
        socketServerThread.start()
        if (host == null || host!!.length == 0) {
            host = getWifiIpAddress(context)
        }
        if (this.port == 0) {
            this.port = port
        }
    }

    // Should be called from worker thread to handle incoming messages from the peer.
    //
    // Will call reportMessageReceived() when a new message arrives.
    //
    // Returns a Throwable if an error occurred, null if the peer disconnects.
    //
    fun processMessagesFromSocket(): Throwable? {
        var errorToReport: Throwable? = null
        try {
            val inputStream = socket!!.getInputStream()
            while (!socket!!.isClosed) {
                val encodedHeader = readBytes(inputStream, 8)
                    ?: // End Of Stream
                    break
                if (!(encodedHeader[0] == 'G'.code.toByte() && encodedHeader[1] == 'm'.code.toByte() && encodedHeader[2] == 'D'.code.toByte() && encodedHeader[3] == 'L'.code.toByte())) {
                    errorToReport = Error("Unexpected header")
                    break
                }
                encodedHeader.order(ByteOrder.BIG_ENDIAN)
                val dataLen = encodedHeader.getInt(4)
                if (dataLen > MAX_MESSAGE_SIZE) {
                    // This is mostly to avoid clients trying to fool us into e.g. allocating and
                    // reading 2 GiB worth of data.
                    errorToReport = Error("Maximum message size exceeded")
                    break
                }
                val data = readBytes(inputStream, dataLen)
                if (data == null) {
                    // End Of Stream
                    errorToReport = Error("End of stream, expected $dataLen bytes")
                    break
                }
                var incomingMessage = data.array()
                if (messageRewriter != null) {
                    incomingMessage = messageRewriter!!.rewrite(incomingMessage)
                }
                reportMessageReceived(incomingMessage)
            }
        } catch (e: IOException) {
            errorToReport = e
        }
        return errorToReport
    }

    fun setHostAndPort(host: String?, port: Int) {
        this.host = host
        this.port = port
    }

    private fun connectAsMdocReader() {
        socket = Socket()
        val socketReaderThread: Thread = object : Thread() {
            override fun run() {
                val endpoint: SocketAddress = InetSocketAddress(host, port)
                try {
                    socket!!.connect(endpoint)
                } catch (e: IOException) {
                    reportError(e)
                    return
                }
                reportConnected()
                setupWritingThread()
                val e = processMessagesFromSocket()
                if (e != null) {
                    reportError(e)
                } else {
                    reportDisconnected()
                }
            }
        }
        socketReaderThread.start()
    }

    override fun connect() {
        if (role === Role.MDOC) {
            connectAsMdoc()
        } else {
            connectAsMdocReader()
        }
    }

    fun setupWritingThread() {
        socketWriterThread = object : Thread() {
            override fun run() {
                while (socket!!.isConnected) {
                    var messageToSend: ByteArray? = null
                    try {
                        messageToSend = writerQueue.poll(1000, TimeUnit.MILLISECONDS)
                        if (messageToSend == null) {
                            continue
                        }
                        // An empty message is used to convey that the writing thread should be
                        // shut down.
                        if (messageToSend.size == 0) {
                            Logger.d(TAG, "Empty message, shutting down writer")
                            break
                        }
                    } catch (e: InterruptedException) {
                        continue
                    }
                    try {
                        socket!!.getOutputStream().write(messageToSend)
                    } catch (e: IOException) {
                        reportError(e)
                        break
                    }
                }
            }
        }
        socketWriterThread!!.start()
    }

    override fun close() {
        inhibitCallbacks()
        if (socketWriterThread != null) {
            writerQueue.add(ByteArray(0))
            try {
                socketWriterThread!!.join()
            } catch (e: InterruptedException) {
                Logger.e(TAG, "Caught exception while joining writing thread", e)
            }
        }
        if (serverSocket != null) {
            try {
                serverSocket!!.close()
            } catch (e: IOException) {
                Logger.e(TAG, "Caught exception while shutting down", e)
            }
        }
        if (socket != null) {
            try {
                socket!!.close()
            } catch (e: IOException) {
                Logger.e(TAG, "Caught exception while shutting down", e)
            }
        }
    }

    override fun sendMessage(data: ByteArray) {
        val bb = ByteBuffer.allocate(8 + data.size)
        bb.put("GmDL".toByteArray())
        bb.putInt(data.size)
        bb.put(data)
        writerQueue.add(bb.array())
    }

    override fun sendTransportSpecificTerminationMessage() {
        reportError(Error("Transport-specific termination message not supported"))
    }

    override fun supportsTransportSpecificTerminationMessage(): Boolean {
        return false
    }

    override val connectionMethodForTransport: MdocConnectionMethod
        get() = MdocConnectionMethodTcp(host!!, port)

    // Function to rewrite incoming messages, used only for testing to inject errors
    // which will cause decryption to fail.
    fun setMessageRewriter(rewriter: MessageRewriter?) {
        messageRewriter = rewriter
    }

    interface MessageRewriter {
        fun rewrite(message: ByteArray): ByteArray
    }

    companion object {
        private const val TAG = "DataTransportTcp"

        // The maximum message size we support.
        private const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024

        @Suppress("deprecation")
        private fun getWifiIpAddress(context: Context): String {
            val wifiManager = context.getSystemService(
                WifiManager::class.java
            )
            return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        }

        fun fromConnectionMethod(
            context: Context,
            cm: MdocConnectionMethodTcp,
            role: Role,
            options: DataTransportOptions
        ): DataTransport {
            val t = DataTransportTcp(context, role, options)
            t.setHostAndPort(cm.host, cm.port)
            return t
        }

        fun toNdefRecord(
            cm: MdocConnectionMethodTcp,
            auxiliaryReferences: List<String?>,
            isForHandoverSelect: Boolean
        ): Pair<NdefRecord, ByteArray> {
            val reference = "${MdocConnectionMethodTcp.METHOD_TYPE}".toByteArray()
            val record = NdefRecord(
                0x02.toShort(),  // type = RFC 2046 (MIME)
                "application/vnd.android.ic.dmr".toByteArray(),
                reference,
                cm.toDeviceEngagement()
            )

            // From 7.1 Alternative Carrier Record
            //
            val baos = ByteArrayOutputStream()
            baos.write(0x01) // CPS: active
            baos.write(reference.size)
            try {
                baos.write(reference)
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
            baos.write(0x01) // Number of auxiliary references
            val auxReference = "mdoc".toByteArray()
            baos.write(auxReference.size)
            baos.write(auxReference, 0, auxReference.size)
            val acRecordPayload = baos.toByteArray()
            return Pair(record, acRecordPayload)
        }

        private fun readBytes(inputStream: InputStream, numBytes: Int): ByteBuffer? {
            val data = ByteBuffer.allocate(numBytes)
            var offset = 0
            var numBytesRemaining = numBytes
            while (numBytesRemaining > 0) {
                val numRead = inputStream.read(data.array(), offset, numBytesRemaining)
                if (numRead == -1) {
                    return null
                }
                check(numRead != 0) { "read() returned zero bytes" }
                numBytesRemaining -= numRead
                offset += numRead
            }
            return data
        }

    }
}
