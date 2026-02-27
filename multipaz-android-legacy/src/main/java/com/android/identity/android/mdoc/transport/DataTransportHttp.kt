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
import android.text.format.Formatter
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.util.Logger
import com.android.volley.DefaultRetryPolicy
import com.android.volley.NetworkResponse
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.toolbox.HttpHeaderParser
import com.android.volley.toolbox.Volley
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodHttp
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.TimeUnit

/**
 * HTTP data transport.
 *
 * TODO: Maybe make it possible for the application to check the TLS root certificate via
 * DataTransportOptions
 */
class DataTransportHttp(
    context: Context,
    role: Role,
    private var connectionMethod: MdocConnectionMethodHttp?,
    options: DataTransportOptions
) : DataTransport(context, role, options) {
    var socket: Socket? = null
    var writerQueue: BlockingQueue<ByteArray> = LinkedTransferQueue()
    var serverSocket: ServerSocket? = null
    var socketWriterThread: Thread? = null
    private var _host: String? = null
    var port = 0
    private var _path: String? = null
    var useTls = false

    override fun setEDeviceKeyBytes(encodedEDeviceKeyBytes: ByteArray) {
        // Not used.
    }

    // Returns the SessionEstablishment/SessionData CBOR received
    //
    // On end-of-stream returns the empty array
    //
    // Returns null on error.
    fun readMessageFromSocket(inputStream: InputStream?): ByteArray? {
        // TODO: Add tests to this method.
        if (inputStream == null) {
            throw IllegalArgumentException("inputStream cannot be null")
        }

        val reader = BufferedReader(InputStreamReader(inputStream))
        var contentLength = -1

        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.lowercase().startsWith("content-length:")) {
                    val lengthStr = line.substring(15).trim()
                    contentLength = lengthStr.toIntOrNull() ?: continue
                }
                if (line.isEmpty() && contentLength >= 0) {
                    if (contentLength > MAX_MESSAGE_SIZE) {
                        throw IOException("Content length exceeds MAX_MESSAGE_SIZE")
                    }
                    val charArray = CharArray(contentLength)
                    var charsRead = 0
                    while (charsRead < contentLength) {
                        val readChars = reader.read(charArray, charsRead, contentLength - charsRead)
                        if (readChars == -1) { // End of stream reached before reading all characters
                            throw IOException("Unexpected end of stream")
                        }
                        charsRead += readChars
                    }
                    return String(charArray, 0, charsRead).toByteArray()
                }
            }
        } catch (e: IOException) {
            Logger.w(TAG, "Caught exception while reading", e)
        }
        return null
    }

    private fun connectAsMdocReader() {
        serverSocket = try {
            ServerSocket(0)
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
                    setupWritingThread(true)
                    reportConnected()
                    val inputStream = socket!!.getInputStream()
                    while (!socket!!.isClosed()) {
                        val data = readMessageFromSocket(inputStream)
                        if (data == null) {
                            reportError(Error("Error reading message from socket"))
                            break
                        } else if (data.size == 0) {
                            // End Of Stream
                            reportDisconnected()
                            break
                        }
                        reportMessageReceived(data)
                    }
                } catch (e: Exception) {
                    reportError(Error("Error reading from socket", e))
                }
            }
        }
        socketServerThread.start()
        // Use http://<ip>:<port>/mdocreader as the URI
        //
        _host = getWifiIpAddress(context)
        this.port = port
        Logger.d(TAG, "Listening with host=$_host port=$port useTls=$useTls")
        connectionMethod = MdocConnectionMethodHttp("http://$_host:$port/mdocreader")
    }

    var host: String
        get() = _host!!
        set(host) {
            _host = host
        }
    var path: String
        get() = _path!!
        set(path) {
            _path = path
        }

    private var requestQueue: RequestQueue? = null

    private fun connectAsMdoc() {
        Logger.d(TAG, "Connecting to uri=${connectionMethod!!.uri} (host=$_host port=$port useTls=$useTls)")
        requestQueue = Volley.newRequestQueue(context)

        // We're not really connected yet but if it doesn't work, we'll fail later...
        reportConnected()
    }

    private fun sendMessageAsMdoc(data: ByteArray) {
        if (requestQueue == null) {
            Logger.w(TAG, "Not sending message since the connection is closed.")
            return
        }
        if (Logger.isDebugEnabled) {
            Logger.d(
                TAG, String.format(
                    Locale.US, "HTTP POST to %s with payload of length %d",
                    connectionMethod!!.uri, data.size
                )
            )
        }
        val request = CborRequest(
            Request.Method.POST,
            connectionMethod!!.uri,
            data,
            "application/cbor",
            { response ->
                if (Logger.isDebugEnabled) {
                    Logger.d(TAG, "Received response to HTTP request payload of length " + response.size)
                }
                reportMessageReceived(response)
            }
        ) { error ->
            Logger.d(TAG, "Received error in response to HTTP request", error)
            error.printStackTrace()
            reportError(Error("Error sending HTTP request", error))
        }
        // We use long-polling because the duration between delivering a HTTP Request (containing
        // DeviceResponse) and receiving the response (containing DeviceRequest) may be spent
        // by the verifier configuring the mdoc reader which request to make next... set this
        // to two minutes and no retries.
        request.setRetryPolicy(
            DefaultRetryPolicy(
                2 * 60 * 1000,
                0,
                1.0f
            )
        )
        requestQueue!!.add(request)
    }

    override fun connect() {
        if (role === Role.MDOC) {
            connectAsMdoc()
        } else {
            connectAsMdocReader()
        }
    }

    fun setupWritingThread(isListener: Boolean) {
        socketWriterThread = object : Thread() {
            override fun run() {
                while (socket!!.isConnected) {
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
                    try {
                        val os = socket!!.getOutputStream()
                        if (isListener) {
                            os.write(
                                """HTTP/1.1 200 OK
Content-Length: ${messageToSend.size}
Content-Type: application/cbor

""".toByteArray()
                            )
                        } else {
                            os.write(
                                """POST $_path HTTP/1.1
Host: $_host
Content-Length: ${messageToSend.size}
Content-Type: application/cbor

""".toByteArray()
                            )
                        }
                        os.write(messageToSend)
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
        if (requestQueue != null) {
            requestQueue!!.cancelAll { request: Request<*>? -> true }
            requestQueue = null
        }
    }

    override fun sendMessage(data: ByteArray) {
        if (role == Role.MDOC) {
            sendMessageAsMdoc(data)
        } else {
            writerQueue.add(data)
        }
    }

    override fun sendTransportSpecificTerminationMessage() {
        reportError(Error("Transport-specific termination message not supported"))
    }

    override fun supportsTransportSpecificTerminationMessage(): Boolean {
        return false
    }

    override val connectionMethodForTransport: MdocConnectionMethod
        get() = connectionMethod!!

    /**
     * Creates a new request with the given method.
     *
     * @param method        the request method ID to use.
     * @param url           URL to fetch the string at.
     * @param body          the data to POST.
     * @param listener      Listener to receive the String response.
     * @param errorListener Error mListener, or null to ignore errors.
     */
    internal class CborRequest(
        method: Int,
        url: String,
        private val body: ByteArray,
        private val bodyContentType: String,
        private val listener: Response.Listener<ByteArray>,
        errorListener: Response.ErrorListener?
    ) : Request<ByteArray>(method, url, errorListener) {

        override fun deliverResponse(response: ByteArray) {
            listener.onResponse(response)
        }

        override fun parseNetworkResponse(response: NetworkResponse): Response<ByteArray> {
            Logger.d(TAG, "response.data")
            return Response.success(response.data, HttpHeaderParser.parseCacheHeaders(response))
        }

        override fun getBody(): ByteArray {
            return body
        }

        override fun getBodyContentType(): String {
            return bodyContentType
        }

        companion object {
            private const val TAG = "CborRequest"
        }
    }

    companion object {
        private const val TAG = "DataTransportHttp"

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
            cm: MdocConnectionMethodHttp,
            role: Role,
            options: DataTransportOptions
        ): DataTransport {
            // For the mdoc reader role, this should be empty since DataTransportHttp will return
            // an ConnectionMethodHttp object containing the local IP address and the TCP port that
            // was assigned.
            if (role == Role.MDOC_READER) {
                require(cm.uri == "") { "URI must be empty for mdoc reader role" }
                return DataTransportHttp(context, role, cm, options)
            }

            // For the mdoc role, this should be an URI pointing to a server on the Internet.
            val uri = try {
                URI(cm.uri)
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException(e)
            }
            val transport = DataTransportHttp(context, role, cm, options)
            if (uri.getScheme() == "http") {
                transport.host = uri.getHost()
                var port = uri.getPort()
                if (port == -1) {
                    port = 80
                }
                transport.port = port
                transport.path = uri.getPath()
                return transport
            } else if (uri.getScheme() == "https") {
                transport.host = uri.getHost()
                var port = uri.getPort()
                if (port == -1) {
                    port = 443
                }
                transport.port = port
                transport.path = uri.getPath()
                transport.useTls = true
                return transport
            }
            throw IllegalArgumentException("Unsupported scheme " + uri.getScheme())
        }
    }
}
