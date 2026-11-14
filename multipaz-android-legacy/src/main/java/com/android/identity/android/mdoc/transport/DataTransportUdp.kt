/*
 * Copyright 2024 The Android Open Source Project
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
import android.util.Log
import android.util.Pair
import com.android.identity.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.util.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Arrays
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.TimeUnit

/**
 * UDP data transport.
 *
 *
 * This is a private non-standardized data transport. It is only here for testing purposes.
 */
class DataTransportUdp(
    context: Context,
    role: Role,
    options: DataTransportOptions
) : DataTransport(context, role, options) {
    var socket: DatagramSocket? = null
    var writerQueue: BlockingQueue<ByteArray> = LinkedTransferQueue()
    var serverSocket: DatagramSocket? = null
    var socketWriterThread: Thread? = null
    var host: String? = null
        private set
    var port = 0
        private set

    override fun setEDeviceKeyBytes(encodedEDeviceKeyBytes: ByteArray) {
        // Not used.
    }

    private var destinationAddress: InetAddress? = null
    private var destinationPort = 0
    private fun connectAsMdoc() {
        serverSocket = try {
            DatagramSocket()
        } catch (e: IOException) {
            reportError(e)
            return
        }
        val port = serverSocket!!.localPort
        val socketServerThread: Thread = object : Thread() {
            override fun run() {
                try {
                    setupWritingThread(serverSocket!!)
                    val e = processMessagesFromSocket(serverSocket!!, true)
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
    fun processMessagesFromSocket(socket: DatagramSocket, isMdoc: Boolean): Throwable? {
        var errorToReport: Throwable? = null
        var numMessagesReceived = 0
        try {
            while (!socket.isClosed) {
                val packetData = ByteArray(MAX_MESSAGE_SIZE)
                val packet = DatagramPacket(packetData, packetData.size)
                socket.receive(packet)
                val message = Arrays.copyOf(packetData, packet.length)
                if (isMdoc && numMessagesReceived == 0) {
                    destinationAddress = packet.address
                    destinationPort = packet.port
                    reportConnected()
                }
                reportMessageReceived(message)
                numMessagesReceived++
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
        destinationAddress = try {
            InetAddress.getByName(host)
        } catch (e: UnknownHostException) {
            reportError(e)
            return
        }
        destinationPort = port
        socket = try {
            DatagramSocket()
        } catch (e: IOException) {
            reportError(e)
            return
        }
        val socketReaderThread: Thread = object : Thread() {
            override fun run() {
                reportConnected()
                setupWritingThread(socket!!)
                val e = processMessagesFromSocket(socket!!, false)
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

    fun setupWritingThread(socket: DatagramSocket) {
        socketWriterThread = object : Thread() {
            override fun run() {
                while (true) {
                    var messageToSend: ByteArray?
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
                    Logger.iHex(
                        TAG,
                        "data to $destinationAddress port $destinationPort",
                        messageToSend
                    )
                    val packet = DatagramPacket(
                        messageToSend,
                        messageToSend.size,
                        destinationAddress,
                        destinationPort
                    )
                    try {
                        socket.send(packet)
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
                Logger.e(TAG, "Caught exception while joining writing thread: $e")
            }
        }
        if (serverSocket != null) {
            serverSocket!!.close()
            serverSocket = null
        }
        if (socket != null) {
            socket!!.close()
            socket = null
        }
    }

    override fun sendMessage(data: ByteArray) {
        writerQueue.add(data)
    }

    override fun sendTransportSpecificTerminationMessage() {
        reportError(Error("Transport-specific termination message not supported"))
    }

    override fun supportsTransportSpecificTerminationMessage(): Boolean {
        return false
    }

    override val connectionMethodForTransport: MdocConnectionMethod
        get() = MdocConnectionMethodUdp(host!!, port)

    companion object {
        private const val TAG = "DataTransportUdp"

        // The maximum message size we support.
        private const val MAX_MESSAGE_SIZE = 64 * 1024
        @Suppress("deprecation")
        private fun getWifiIpAddress(context: Context): String {
            val wifiManager = context.getSystemService(
                WifiManager::class.java
            )
            return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        }

        fun fromConnectionMethod(
            context: Context,
            cm: MdocConnectionMethodUdp,
            role: Role,
            options: DataTransportOptions
        ): DataTransport {
            val t = DataTransportUdp(context, role, options)
            t.setHostAndPort(cm.host, cm.port)
            return t
        }

        fun toNdefRecord(
            cm: MdocConnectionMethodUdp,
            auxiliaryReferences: List<String?>,
            isForHandoverSelect: Boolean
        ): Pair<NdefRecord, ByteArray> {
            val reference = "${MdocConnectionMethodUdp.METHOD_TYPE}".toByteArray()
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
    }
}
