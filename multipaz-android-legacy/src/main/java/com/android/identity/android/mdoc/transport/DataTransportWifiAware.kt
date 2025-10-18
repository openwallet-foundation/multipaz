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

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.wifi.WifiManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.Characteristics
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.nfc.NdefRecord
import android.os.Build
import android.util.Base64
import android.util.Pair
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Hkdf
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodWifiAware
import org.multipaz.util.HexUtil
import org.multipaz.util.Logger
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.TimeUnit

/**
 * Wifi Aware data transport.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class DataTransportWifiAware(
    context: Context,
    role: Role,
    private val connectionMethod: MdocConnectionMethodWifiAware,
    options: DataTransportOptions
) : DataTransport(context, role, options) {

    private var writerQueue: BlockingQueue<ByteArray> = LinkedTransferQueue()
    private var serviceName: String? = null
    private var session: WifiAwareSession? = null
    private var listenerServerSocket: ServerSocket? = null
    private var initiatorSocket: Socket? = null
    private var listenerSocket: Socket? = null

    private var mEncodedEDeviceKeyBytes: ByteArray? = null
    private var wifiAwareManager: WifiAwareManager? = null
    private var mInitiatorIPv6HostString: String? = null
    private var mPassphrase: String? = null

    @Suppress("unused")
    private var mCipherSuites = 0
    override fun setEDeviceKeyBytes(encodedEDeviceKeyBytes: ByteArray) {
        mEncodedEDeviceKeyBytes = encodedEDeviceKeyBytes

        // Service name is always derived from EReaderKey as per 18013-5.
        var ikm = mEncodedEDeviceKeyBytes
        var info = "NANService".toByteArray()
        var salt = null
        serviceName = HexUtil.toHex(Hkdf.deriveKey(Algorithm.HMAC_SHA256, ikm!!, salt, info, 16), true)
        Logger.d(TAG, String.format("Using calculated service name '$serviceName'"))

        // If the passphrase isn't given, derive as per 18013-5.
        if (mPassphrase == null) {
            ikm = mEncodedEDeviceKeyBytes
            info = "NANPassphrase".toByteArray()
            salt = null
            mPassphrase = Base64.encodeToString(
                Hkdf.deriveKey(Algorithm.HMAC_SHA256, ikm!!, salt, info, 32),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            Logger.d(TAG, String.format("Using calculated passphrase '$mPassphrase'"))
        } else {
            Logger.d(TAG, String.format("Using provided passphrase '$mPassphrase'"))
        }
    }

    private fun connectAsMdoc() {
        wifiAwareManager = context.getSystemService(WifiAwareManager::class.java)!!
        wifiAwareManager!!.attach(
            object : AttachCallback() {
                override fun onAttachFailed() {
                    Logger.d(TAG, "onAttachFailed")
                    reportError(Error("Wifi-Aware attach failed"))
                }

                @SuppressLint("ClassVerificationFailure")
                override fun onAttached(session: WifiAwareSession) {
                    Logger.d(TAG, "onAttached: $session")
                    this@DataTransportWifiAware.session = session
                    val config = PublishConfig.Builder()
                        .setServiceName(serviceName!!)
                        .build()
                    this@DataTransportWifiAware.session!!.publish(config, object : DiscoverySessionCallback() {
                        private var mPublishDiscoverySession: PublishDiscoverySession? = null
                        override fun onPublishStarted(session: PublishDiscoverySession) {
                            Logger.d(TAG, "onPublishStarted")
                            mPublishDiscoverySession = session
                        }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            Logger.dHex(TAG,"onMessageReceived: peer: $peerHandle", message)
                            listenerOnMessageReceived(mPublishDiscoverySession, peerHandle)
                        }
                    }, null)
                }
            },
            null
        )

        // Passphrase is mandatory for NFC so we always set it...
        //
        val passphraseBytes = ByteArray(16)
        val r: Random = SecureRandom()
        r.nextBytes(passphraseBytes)
        val passphraseInfoPassphrase = HexUtil.toHex(passphraseBytes, true)
        val wm = context.getSystemService(
            WifiManager::class.java
        )
        var supportedBandsBitmap = 0x04 // Bit 2: 2.4 GHz
        if (wm.is5GHzBandSupported) {
            supportedBandsBitmap = supportedBandsBitmap or 0x10 // Bit 4: 4.9 and 5 GHz
        }
        val bandInfoSupportedBands = byteArrayOf((supportedBandsBitmap and 0xff).toByte())
        val characteristics = wifiAwareManager!!.getCharacteristics()
        if (characteristics != null) {
            mCipherSuites = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Api30Impl.getSupportedCipherSuites(characteristics)
            } else {
                // Pre-R, just assume that only NCS-SK-128 works.
                Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128
            }
        }
    }

    fun listenerOnMessageReceived(session: PublishDiscoverySession?, peerHandle: PeerHandle?) {
        reportConnecting()
        listenerServerSocket = try {
            ServerSocket(0)
        } catch (e: IOException) {
            reportError(e)
            return
        }
        val port = listenerServerSocket!!.localPort
        Logger.d(TAG, "Listener on port $port")
        listenOnServerSocket()
        val networkSpecifier: NetworkSpecifier = WifiAwareNetworkSpecifier.Builder(
            session!!,
            peerHandle!!
        )
            .setPskPassphrase(mPassphrase!!)
            .setPort(port)
            .build()
        val myNetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()
        val callback: ConnectivityManager.NetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Logger.d(TAG, "onAvailable")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    Logger.d(TAG, "onCapabilitiesChanged $networkCapabilities")
                }

                override fun onLost(network: Network) {
                    Logger.d(TAG, "onLost")
                }
            }
        val cm = context.getSystemService(
            ConnectivityManager::class.java
        )
        cm.requestNetwork(myNetworkRequest, callback)
        session.sendMessage(
            peerHandle,
            0,
            "helloSub".toByteArray()
        )
    }

    private fun listenOnServerSocket() {
        val socketServerThread: Thread = object : Thread() {
            override fun run() {
                try {
                    // We only accept a single client with this server socket...
                    //
                    listenerSocket = listenerServerSocket!!.accept()
                    val writingThread: Thread = object : Thread() {
                        override fun run() {
                            writeToSocket(true, listenerSocket)
                        }
                    }
                    writingThread.start()
                    reportConnected()
                    readFromSocket(true, listenerSocket)
                } catch (e: IOException) {
                    reportError(e)
                }
            }
        }
        socketServerThread.start()
    }

    fun setPassphrase(passphrase: String) {
        mPassphrase = passphrase
    }

    private fun connectAsMdocReader() {
        wifiAwareManager = context.getSystemService(
            WifiAwareManager::class.java
        )
        wifiAwareManager!!.attach(
            object : AttachCallback() {
                override fun onAttachFailed() {
                    Logger.d(TAG, "onAttachFailed")
                    reportError(Error("Wifi-Aware attach failed"))
                }

                @SuppressLint("ClassVerificationFailure")
                override fun onAttached(session: WifiAwareSession) {
                    Logger.d(TAG, "onAttached: $session")
                    this@DataTransportWifiAware.session = session
                    val config = SubscribeConfig.Builder()
                        .setServiceName(serviceName!!)
                        .build()
                    this@DataTransportWifiAware.session!!.subscribe(config, object : DiscoverySessionCallback() {
                        private var mSubscribeDiscoverySession: SubscribeDiscoverySession? = null
                        override fun onMessageSendFailed(messageId: Int) {
                            Logger.d(TAG, "onMessageSendFailed")
                        }

                        override fun onMessageSendSucceeded(messageId: Int) {
                            Logger.d(TAG, "onMessageSendSucceeded")
                        }

                        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                            mSubscribeDiscoverySession = session
                            Logger.d(TAG, "onSubscribeStarted")
                        }

                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray, matchFilter: List<ByteArray>
                        ) {
                            Logger.dHex(TAG, "onServiceDiscovered: peer: $peerHandle "
                                        + " serviceSpecificInfo: ", serviceSpecificInfo)
                            mSubscribeDiscoverySession!!.sendMessage(
                                peerHandle,
                                0,
                                "helloPub".toByteArray()
                            )
                        }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            Logger.dHex(TAG, "onMessageReceived: peer: $peerHandle"
                                        + " message: ", message)
                            initiatorOnMessageReceived(mSubscribeDiscoverySession, peerHandle)
                        }
                    }, null)
                }
            },
            null
        )
    }

    override fun connect() {
        if (role === Role.MDOC) {
            connectAsMdoc()
        } else {
            connectAsMdocReader()
        }
    }

    fun initiatorOnMessageReceived(session: SubscribeDiscoverySession?, peerHandle: PeerHandle?) {
        val networkSpecifier: NetworkSpecifier = WifiAwareNetworkSpecifier.Builder(
            session!!,
            peerHandle!!
        )
            .setPskPassphrase(mPassphrase!!)
            .build()
        val myNetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()
        val callback: ConnectivityManager.NetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                private var mNetworkCapabilities: NetworkCapabilities? = null
                private var mIsAvailable = false
                private var mInitiatedConnection = false
                override fun onAvailable(network: Network) {
                    Logger.d(TAG, "onAvailable sub")
                    mIsAvailable = true
                    if (mInitiatedConnection) {
                        return
                    }
                    if (mIsAvailable && mNetworkCapabilities != null) {
                        initiatorConnect(network, mNetworkCapabilities!!)
                        mInitiatedConnection = true
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    Logger.d(TAG, "onCapabilitiesChanged sub $networkCapabilities")
                    mNetworkCapabilities = networkCapabilities
                    if (mInitiatedConnection) {
                        return
                    }
                    if (mIsAvailable && mNetworkCapabilities != null) {
                        initiatorConnect(network, mNetworkCapabilities!!)
                        mInitiatedConnection = true
                    }
                }

                override fun onLost(network: Network) {
                    Logger.d(TAG, "onLost sub")
                }
            }
        val cm = context.getSystemService(
            ConnectivityManager::class.java
        )
        cm.requestNetwork(myNetworkRequest, callback)

        //session.sendMessage(peerHandle,
        //        0,
        //        "helloSub".getBytes(UTF_8));
    }

    fun initiatorConnect(network: Network, networkCapabilities: NetworkCapabilities) {
        val peerAwareInfo = networkCapabilities.transportInfo as WifiAwareNetworkInfo?
        val peerIpv6 = peerAwareInfo!!.peerIpv6Addr
        val peerPort = peerAwareInfo.port

        // peerIpv6.getHostAddress() returns something like "fe80::75:baff:fedd:ce16%aware_data0",
        // this is how we get rid of it...
        val strippedAddress: InetAddress
        strippedAddress = try {
            InetAddress.getByAddress(peerIpv6!!.address)
        } catch (e: UnknownHostException) {
            reportError(e)
            return
        }

        // TODO: it's not clear whether port should be included here, we include it for now...
        //
        mInitiatorIPv6HostString = "[${strippedAddress.hostAddress}]:$peerPort"
        Logger.d(TAG, "Connecting to $mInitiatorIPv6HostString")
        try {
            initiatorSocket = network.socketFactory.createSocket(peerIpv6, peerPort)
            val writingThread: Thread = object : Thread() {
                override fun run() {
                    writeToSocket(false, initiatorSocket)
                }
            }
            writingThread.start()
            val listenerThread: Thread = object : Thread() {
                override fun run() {
                    readFromSocket(false, initiatorSocket)
                }
            }
            listenerThread.start()
            reportConnected()
        } catch (e: IOException) {
            reportError(e)
        }
    }

    override fun close() {
        Logger.d(TAG, "close() called")
        inhibitCallbacks()
        if (wifiAwareManager != null) {
            // TODO: any way to detach?
            wifiAwareManager = null
        }
        if (session != null) {
            session!!.close()
            session = null
        }
        if (listenerServerSocket != null) {
            try {
                listenerServerSocket!!.close()
            } catch (e: IOException) {
                Logger.w(TAG, "Error closing listener's server socket")
                e.printStackTrace()
            }
            listenerServerSocket = null
        }
        if (initiatorSocket != null) {
            try {
                initiatorSocket!!.close()
            } catch (e: IOException) {
                Logger.w(TAG, "Error closing initiator's socket")
                e.printStackTrace()
            }
            initiatorSocket = null
        }
        if (listenerSocket != null) {
            try {
                listenerSocket!!.close()
            } catch (e: IOException) {
                Logger.w(TAG, "Error closing listener's socket")
                e.printStackTrace()
            }
            listenerSocket = null
        }
    }

    override fun sendMessage(data: ByteArray) {
        writerQueue.add(data)
    }

    fun writeToSocket(isListener: Boolean, socket: Socket?) {
        val os: OutputStream
        os = try {
            socket!!.getOutputStream()
        } catch (e: IOException) {
            reportError(e)
            return
        }
        Logger.d(TAG, "Writing socket isListener=$isListener")
        while (socket.isConnected) {
            var messageToSend: ByteArray? = null
            try {
                messageToSend = writerQueue.poll(1000, TimeUnit.MILLISECONDS)
                if (messageToSend == null) {
                    continue
                }
            } catch (e: InterruptedException) {
                continue
            }
            Logger.d(TAG, "Sending ${messageToSend.size} bytes")
            try {
                if (isListener) {
                    os.write(
                        """HTTP/1.1 200 OK
Content-Length: ${messageToSend.size}
Content-Type: application/CBOR

""".toByteArray()
                    )
                } else {
                    os.write(
                        """POST /mdoc HTTP/1.1
Host: $mInitiatorIPv6HostString
Content-Length: ${messageToSend.size}
Content-Type: application/CBOR

""".toByteArray()
                    )
                }
                os.write(messageToSend)
                os.flush()
            } catch (e: IOException) {
                Logger.d(TAG, "Caught exception while writing isListener=$isListener")
                reportError(e)
                return
            }
        }
    }

    @Suppress("deprecation")
    fun readFromSocket(isListener: Boolean, socket: Socket?) {
        var inputStream: InputStream? = null
        inputStream = try {
            socket!!.getInputStream()
        } catch (e: IOException) {
            Logger.d(TAG, "Caught exception while getting inputstream isListener=$isListener")
            e.printStackTrace()
            reportError(e)
            return
        }
        Logger.d(TAG, "Reading from socket isListener=$isListener")
        val dis = DataInputStream(inputStream)
        val keepGoing = true
        var contentLength = -1
        try {
            while (keepGoing) {
                Logger.d(TAG, "Calling readLine()...")
                val line = dis.readLine()
                if (line == null) {
                    // End of stream...
                    if (isListener) {
                        reportDisconnected()
                    } else {
                        reportDisconnected()
                    }
                    return
                }
                Logger.d(TAG, "read line '$line'")
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = try {
                        line.substring(15).trim { it <= ' ' }.toInt()
                    } catch (e: NumberFormatException) {
                        Logger.w(TAG, "Error parsing Content-Length line '$line'")
                        reportError(e)
                        return
                    }
                }
                if (line.length == 0) {
                    // End of headers...
                    if (contentLength == -1) {
                        reportError(Error("No Content-Length header"))
                        return
                    }
                    if (contentLength > 1024 * 1024) {
                        reportError(Error("Content-Length $contentLength rejected"))
                        return
                    }
                    Logger.d(
                        TAG,
                        "Going to read $contentLength bytes, isListener=$isListener"
                    )
                    val data = ByteArray(contentLength)
                    dis.readFully(data)
                    reportMessageReceived(data)
                    contentLength = -1
                }
            }
        } catch (e: IOException) {
            Logger.d(TAG, "Caught exception while reading isListener=$isListener")
            e.printStackTrace()
            reportError(e)
        }
    }

    override fun sendTransportSpecificTerminationMessage() {
        reportError(Error("Transport-specific termination message not supported"))
    }

    override fun supportsTransportSpecificTerminationMessage(): Boolean {
        return false
    }

    @TargetApi(30)
    @RequiresApi(30)
    internal object Api30Impl {
        @DoNotInline
        fun getSupportedCipherSuites(characteristics: Characteristics): Int {
            return characteristics.supportedCipherSuites
        }
    }

    override val connectionMethodForTransport: MdocConnectionMethod
        get() = connectionMethod

    companion object {
        private const val TAG = "DataTransportWifiAware"
        @JvmStatic
        fun fromNdefRecord(
            record: NdefRecord,
            isForHandoverSelect: Boolean
        ): MdocConnectionMethodWifiAware? {
            var passphraseInfoPassphrase: String? = null
            var bandInfoSupportedBands: ByteArray? = null
            val channelInfoChannelNumber: Long? = null
            val channelInfoOperatingClass: Long? = null
            val payload = ByteBuffer.wrap(record.payload).order(ByteOrder.LITTLE_ENDIAN)
            while (payload.remaining() > 0) {
                val len = payload.get().toInt()
                val type = payload.get().toInt()
                val offset = payload.position()
                if (type == 0x03 && len > 1) {
                    // passphrase
                    val encodedPassphrase = ByteArray(len - 1)
                    payload[encodedPassphrase, 0, len - 1]
                    passphraseInfoPassphrase = String(encodedPassphrase, )
                } else if (type == 0x04 && len > 1) {
                    bandInfoSupportedBands = ByteArray(len - 1)
                    payload[bandInfoSupportedBands, 0, len - 1]
                } else {
                    // TODO: add support for other options...
                    Logger.d(TAG, "Skipping unknown type $type of length $len")
                }
                payload.position(offset + len - 1)
            }
            return MdocConnectionMethodWifiAware(
                passphraseInfoPassphrase,
                channelInfoChannelNumber,
                channelInfoOperatingClass,
                bandInfoSupportedBands?.let { ByteString(it) }
            )
        }

        fun fromConnectionMethod(
            context: Context,
            cm: MdocConnectionMethodWifiAware,
            role: Role,
            options: DataTransportOptions
        ): DataTransport {
            val t = DataTransportWifiAware(context, role, cm, options)
            if (cm.passphraseInfoPassphrase != null) {
                t.setPassphrase(cm.passphraseInfoPassphrase!!)
            }
            // TODO: set mBandInfoSupportedBands, mChannelInfoChannelNumber, mChannelInfoOperatingClass
            return t
        }

        fun toNdefRecord(
            cm: MdocConnectionMethodWifiAware,
            auxiliaryReferences: List<String>,
            isForHandoverSelect: Boolean
        ): Pair<NdefRecord, ByteArray>? {
            // The NdefRecord and its OOB data is defined in "Wi-Fi Aware Specification", table 142.
            //
            var baos = ByteArrayOutputStream()
            try {
                // TODO: use mCipherSuites
                val cipherSuites = Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128

                // Spec says: The NFC Handover Selector shall include the Cipher Suite Info field
                // with one or multiple NAN Cipher Suite IDs in the WiFi Aware Carrier Configuration
                // Record to indicate the supported NAN cipher suite(s)."
                //
                var numCipherSuitesSupported = 0
                if (cipherSuites and Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128 != 0) {
                    numCipherSuitesSupported++
                }
                if (cipherSuites and Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256 != 0) {
                    numCipherSuitesSupported++
                }
                baos.write(1 + numCipherSuitesSupported)
                baos.write(0x01) // Data Type 0x01 - Cipher Suite Info
                if (cipherSuites and Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128 != 0) {
                    baos.write(0x01) // NCS-SK-128
                }
                if (cipherSuites and Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256 != 0) {
                    baos.write(0x02) // NCS-SK-256
                }

                // Spec says: "If the NFC Handover Selector indicates an NCS-SK cipher suite, it
                // shall include a Pass-phrase Info field in the Wi-Fi Aware Carrier Configuration Record
                // to specify the selected pass-phrase for the supported cipher suite."
                //
                // Additionally, 18013-5 says: "If NFC is used for device engagement, either the
                // Pass-phrase Info or the DH Info shall be explicitly transferred from the mdoc to
                // the mdoc reader during device engagement according to the Wi-Fi Alliance
                // Neighbor Awareness Networking Specification section 12."
                //
                if (cm.passphraseInfoPassphrase != null) {
                    val encodedPassphrase = cm.passphraseInfoPassphrase!!.toByteArray(
                        
                    )
                    baos.write(1 + encodedPassphrase.size)
                    baos.write(0x03) // Data Type 0x03 - Pass-phrase Info
                    baos.write(encodedPassphrase)
                }

                // Spec says: "The NFC Handover Selector shall also include a Band Info field in the
                // Wi-Fi Aware Configuration Record to indicate the supported NAN operating band
                // (s)."
                //
                if (cm.bandInfoSupportedBands != null) {
                    baos.write(1 + cm.bandInfoSupportedBands!!.size)
                    baos.write(0x04) // Data Type 0x04 - Band Info
                    baos.write(cm.bandInfoSupportedBands!!.toByteArray())
                }

                // Spec says: "The Channel Info field serves as a placeholder for future
                // extension, and
                // may optionally be included in the Wi-Fi Aware Carrier Configuration Record in the
                // NFC Handover Select message."
                //
                // We don't include this for now.
                //
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
            val oobData = baos.toByteArray()
            val record = NdefRecord(
                NdefRecord.TNF_MIME_MEDIA,
                "application/vnd.wfa.nan".toByteArray(),
                "W".toByteArray(),
                oobData
            )

            // From 7.1 Alternative Carrier Record
            //
            baos = ByteArrayOutputStream()
            baos.write(0x01) // CPS: active
            baos.write(0x01) // Length of carrier data reference ("0")
            baos.write('W'.code) // Carrier data reference
            for (auxRef in auxiliaryReferences) {
                // Each auxiliary reference consists of a single byte for the length and then as
                // many bytes for the reference itself.
                val auxRefUtf8 = auxRef.toByteArray()
                baos.write(auxRefUtf8.size)
                baos.write(auxRefUtf8, 0, auxRefUtf8.size)
            }
            val acRecordPayload = baos.toByteArray()
            return Pair(record, acRecordPayload)
        }
    }
}
