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
package com.android.identity.android.mdoc.deviceretrieval

import android.content.Context
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Base64
import com.android.identity.android.legacy.Crypto
import com.android.identity.android.mdoc.deviceretrieval.VerificationHelper
import com.android.identity.android.mdoc.sessionencryption.SessionEncryption
import com.android.identity.android.mdoc.transport.DataTransport
import com.android.identity.android.mdoc.transport.DataTransportBle
import com.android.identity.android.mdoc.transport.DataTransportBleCentralClientMode
import com.android.identity.android.mdoc.transport.DataTransportNfc
import com.android.identity.android.mdoc.transport.DataTransportOptions
import com.android.identity.android.util.NfcUtil
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.engagement.EngagementGenerator
import org.multipaz.mdoc.engagement.EngagementParser
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import kotlin.time.Clock
import org.multipaz.mdoc.role.MdocRole
import java.io.IOException
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Helper used for engaging with and receiving documents from a remote mdoc verifier device.
 *
 * This class implements the interface between an _mdoc_ and _mdoc verifier_ using
 * the connection setup and device retrieval interfaces defined in
 * [ISO/IEC 18013-5:2021](https://www.iso.org/standard/69084.html).
 *
 * Reverse engagement as per drafts of 18013-7 and 23220-4 is supported. These protocols
 * are not finalized so should only be used for testing.
 */
// Suppress with NotCloseable since we actually don't hold any resources needing to be
// cleaned up at object finalization time.
class VerificationHelper internal constructor(
    private val context: Context,
    private val listener: Listener,
    private val listenerExecutor: Executor
) {
    private var negotiatedHandoverConnectionMethods: List<MdocConnectionMethod>? = null
    private var negotiatedHandoverListeningTransports = mutableListOf<DataTransport>()

    private var dataTransport: DataTransport? = null
    private var ephemeralKey: EcPrivateKey? = null
    private var sessionEncryptionReader: SessionEncryption? = null
    private var deviceEngagement: ByteArray? = null
    private var encodedSessionTranscript: ByteArray? = null
    private var nfcIsoDep: IsoDep? = null

    // The handover used
    //
    private var useTransportSpecificSessionTermination = false
    private var sendSessionTerminationMessage = true
    private var options: DataTransportOptions? = null

    // If this is non-null it means we're using Reverse Engagement
    //
    private var reverseEngagementConnectionMethods: List<MdocConnectionMethod>? = null
    private var reverseEngagementListeningTransports: MutableList<DataTransport>? = null
    private var connectionMethodsForReaderEngagement: MutableList<MdocConnectionMethod>? = null
    private var readerEngagementGenerator: EngagementGenerator? = null
    private var readerEngagement: ByteArray? = null

    private var timestampNfcTap: Long = 0
    private var timestampEngagementReceived: Long = 0
    private var timestampRequestSent: Long = 0
    private var timestampResponseReceived: Long = 0

    var engagementMethod: EngagementMethod = EngagementMethod.NOT_ENGAGED
        private set

    /**
     * The session transcript.
     *
     * This must not be called until engagement has been established with the mdoc device.
     */
    val sessionTranscript: ByteArray
        get() {
            checkNotNull(encodedSessionTranscript) { "Not engaging with mdoc device" }
            return encodedSessionTranscript!!
        }

    /**
     * The ephemeral key used by the reader for session encryption.
     *
     * This must not be called until engagement has been established with the mdoc device.
     */
    val eReaderKey: EcPrivateKey
        get() = ephemeralKey!!

    /**
     * The amount of time from first NFC interaction until Engagement has been received.
     */
    val tapToEngagementDurationMillis: Long
        get() = if (timestampNfcTap == 0L) {
            0
        } else timestampEngagementReceived - timestampNfcTap

    /**
     * The amount of time from when Engagement has been received until the request was sent.
     */
    val engagementToRequestDurationMillis: Long
        get() = timestampRequestSent - timestampEngagementReceived

    /**
     * The amount of time from when the request was sent until the response was received.
     */
    val requestToResponseDurationMillis: Long
        get() = timestampResponseReceived - timestampRequestSent

    /**
     * The amount of time spent BLE scanning or 0 if no scanning occurred.
     */
    val scanningTimeMillis: Long
        get() = if (dataTransport is DataTransportBle) {
            (dataTransport as DataTransportBle).scanningTimeMillis
        } else 0

    private fun start() {
        if (reverseEngagementConnectionMethods != null) {
            setupReverseEngagement()
        }
        if (negotiatedHandoverConnectionMethods != null) {
            for (cm in negotiatedHandoverConnectionMethods!!) {
                val t =
                    DataTransport.fromConnectionMethod(
                        context, cm, DataTransport.Role.MDOC_READER,
                        options!!
                    )
                t.connect()
                Logger.i(
                    TAG,
                    "Warming up transport for negotiated handover: " + t.connectionMethodForTransport
                )
                negotiatedHandoverListeningTransports.add(t)
            }
        }
    }

    private fun setupReverseEngagement() {
        Logger.d(TAG, "Setting up reverse engagement")
        reverseEngagementListeningTransports = mutableListOf()
        // Need to disambiguate the connection methods here to get e.g. two ConnectionMethods
        // if both BLE modes are available at the same time.
        val disambiguatedMethods = MdocConnectionMethod.disambiguate(
            reverseEngagementConnectionMethods!!,
            MdocRole.MDOC_READER
        )
        for (cm in disambiguatedMethods) {
            val transport = DataTransport.fromConnectionMethod(
                context, cm, DataTransport.Role.MDOC_READER, options!!
            )
            reverseEngagementListeningTransports!!.add(transport)
            // TODO: we may want to have the DataTransport actually give us a ConnectionMethod,
            //   for example consider the case where a HTTP-based transport uses a cloud-service
            //   to relay messages.
        }

        // Careful, we're using the user-provided Executor below so these callbacks might happen
        // in another thread than we're in right now. For example this happens if using
        // ThreadPoolExecutor.
        //

        // Calculate ReaderEngagement as we're setting up methods
        readerEngagementGenerator = EngagementGenerator(
            ephemeralKey!!.publicKey,
            EngagementGenerator.ENGAGEMENT_VERSION_1_1
        )
        connectionMethodsForReaderEngagement = mutableListOf()
        for (transport in reverseEngagementListeningTransports!!) {
            transport.setListener(object : DataTransport.Listener {
                override fun onConnecting() {
                    Logger.d(TAG, "onConnecting for $transport")
                    reverseEngagementPeerIsConnecting()
                }

                override fun onConnected() {
                    Logger.d(TAG, "onConnected for $transport")
                    reverseEngagementPeerHasConnected(transport)
                }

                override fun onDisconnected() {
                    Logger.d(TAG, "onDisconnected for $transport")
                    transport.close()
                }

                override fun onError(error: Throwable) {
                    transport.close()
                    reportError(error)
                }

                override fun onMessageReceived() {
                    Logger.d(TAG, "onMessageReceived for $transport")
                    handleOnMessageReceived()
                }

                override fun onTransportSpecificSessionTermination() {
                    Logger.d(TAG, "Received transport-specific session termination")
                    transport.close()
                    reportDeviceDisconnected(true)
                }
            }, listenerExecutor)
            Logger.d(TAG, "Connecting transport $transport")
            transport.connect()
            connectionMethodsForReaderEngagement!!.add(transport.connectionMethodForTransport)
        }
        Logger.d(TAG, "All reverse engagement listening transports are now set up")
        readerEngagementGenerator!!.addConnectionMethods(connectionMethodsForReaderEngagement!!)
        readerEngagement = readerEngagementGenerator!!.generate()
        readerEngagementGenerator = null
        reportReaderEngagementReady(readerEngagement!!)
    }

    fun reverseEngagementPeerIsConnecting() {}
    
    fun reverseEngagementPeerHasConnected(transport: DataTransport) {
        // stop listening on other transports
        //
        Logger.d(TAG, "Peer has connected on transport $transport - shutting down other transports")
        for (t in reverseEngagementListeningTransports!!) {
            if (t !== transport) {
                t.setListener(null, null)
                t.close()
            }
        }
        reverseEngagementListeningTransports!!.clear()
        dataTransport = transport

        // We're connected to the remote device but we don't want to let the application
        // know this until we've received the first message with DeviceEngagement CBOR...
    }

    /**
     * Processes a [Tag] received when in NFC reader mode.
     *
     * Applications should call this method in their
     * [android.nfc.NfcAdapter.ReaderCallback.onTagDiscovered] callback.
     *
     * @param tag the tag.
     * @throws IllegalStateException if called while not listening.
     */
    fun nfcProcessOnTagDiscovered(tag: Tag) {
        Logger.d(TAG, "Tag discovered!")
        timestampNfcTap = Clock.System.now().toEpochMilliseconds()

        // Find IsoDep since we're skipping NDEF checks and doing everything ourselves via APDUs
        for (tech in tag.techList) {
            if (tech == IsoDep::class.java.name) {
                nfcIsoDep = IsoDep.get(tag)
                // If we're doing QR code engagement _and_ NFC data transfer
                // it's possible that we're now in a state where we're
                // waiting for the reader to be in the NFC field... see
                // also comment in connect() for this case...
                if (dataTransport is DataTransportNfc) {
                    Logger.d(TAG, "NFC data transfer + QR engagement, reader is now in field")
                    startNfcDataTransport()

                    // At this point we're done, don't start NFC handover.
                    return
                }
            }
        }
        if (nfcIsoDep == null) {
            Logger.d(TAG, "no IsoDep technology found")
            return
        }
        startNfcHandover()
    }

    private fun startNfcDataTransport() {
        // connect() may block, run in thread
        val connectThread: Thread = object : Thread() {
            override fun run() {
                try {
                    nfcIsoDep!!.connect()
                    nfcIsoDep!!.timeout = 20 * 1000 // 20 seconds
                } catch (e: IOException) {
                    reportError(e)
                    return
                }
                listenerExecutor.execute { connectWithDataTransport(dataTransport, false) }
            }
        }
        connectThread.start()
    }

    /**
     * Set device engagement received via QR code.
     *
     * This method parses the textual form of QR code device engagement as specified in
     * ISO/IEC 18013-5 section 8.2 Device Engagement.
     *
     * If a valid device engagement is received the
     * [Listener.onDeviceEngagementReceived] will be called. If an error occurred it
     * is reported using the [Listener.onError] callback.
     *
     * This method must be called before [.connect].
     *
     * @param qrDeviceEngagement textual form of QR device engagement.
     * @throws IllegalStateException if called after [connect].
     */
    fun setDeviceEngagementFromQrCode(qrDeviceEngagement: String) {
        check(dataTransport == null) { "Cannot be called after connect()" }
        val uri = Uri.parse(qrDeviceEngagement)
        if (uri != null && uri.scheme != null && uri.scheme == "mdoc") {
            val encodedDeviceEngagement = Base64.decode(
                uri.encodedSchemeSpecificPart,
                Base64.URL_SAFE or Base64.NO_PADDING
            )
            if (encodedDeviceEngagement != null) {
                Logger.dCbor(TAG, "Device Engagement from QR code", encodedDeviceEngagement)
                val handover = Simple.NULL
                setDeviceEngagement(encodedDeviceEngagement, handover, EngagementMethod.QR_CODE)
                val engagementParser = EngagementParser(encodedDeviceEngagement)
                val engagement = engagementParser.parse()
                val connectionMethods: List<MdocConnectionMethod> = engagement.connectionMethods
                if (!connectionMethods.isEmpty()) {
                    reportDeviceEngagementReceived(connectionMethods)
                    return
                }
            }
        }
        reportError(
            IllegalArgumentException(
                "Invalid QR Code device engagement text: $qrDeviceEngagement"
            )
        )
    }

    private fun transceive(isoDep: IsoDep, apdu: ByteArray): ByteArray {
        Logger.dHex(TAG, "transceive: Sending APDU", apdu)
        val ret = isoDep.transceive(apdu)
        Logger.dHex(TAG, "transceive: Received APDU", ret)
        return ret
    }

    private fun readBinary(isoDep: IsoDep, offset: Int, size: Int): ByteArray? {
        val apdu: ByteArray
        val ret: ByteArray
        apdu = NfcUtil.createApduReadBinary(offset, size)
        ret = transceive(isoDep, apdu)
        if (ret.size < 2 || ret[ret.size - 2] != 0x90.toByte() || ret[ret.size - 1] != 0x00.toByte()) {
            Logger.eHex(TAG, "Error sending READ_BINARY command, ret", ret)
            return null
        }
        return Arrays.copyOfRange(ret, 0, ret.size - 2)
    }

    private fun ndefReadMessage(isoDep: IsoDep, tWaitMillis: Double, _nWait: Int): ByteArray? {
        var nWait = _nWait
        var apdu: ByteArray
        var ret: ByteArray
        var replyLen: Int
        do {
            apdu = NfcUtil.createApduReadBinary(0x0000, 2)
            ret = transceive(isoDep, apdu)
            if (ret.size != 4 || ret[2] != 0x90.toByte() || ret[3] != 0x00.toByte()) {
                Logger.eHex(TAG, "ndefReadMessage: Malformed response for first " +
                            "READ_BINARY command for length, ret", ret)
                return null
            }
            replyLen = (ret[0].toInt() and 0xff) * 256 + (ret[1].toInt() and 0xff)
            if (replyLen > 0) {
                break
            }

            // As per [TNEP] 4.1.7 if the tag sends an empty NDEF message it means that
            // it's requesting extra time... honor this if we can.
            if (nWait > 0) {
                Logger.d(TAG, "ndefReadMessage: NDEF message with length 0 and $nWait time extensions left")
                try {
                    val waitMillis = Math.ceil(tWaitMillis).toLong()
                    Logger.d(TAG,"ndefReadMessage: Sleeping $waitMillis ms")
                    Thread.sleep(waitMillis)
                } catch (e: InterruptedException) {
                    throw RuntimeException("Unexpected interrupt", e)
                }
                nWait--
            } else {
                Logger.e(TAG, "ndefReadMessage: NDEF message with length 0 but no time extensions left")
                return null
            }
        } while (true)
        apdu = NfcUtil.createApduReadBinary(0x0002, replyLen)
        ret = transceive(isoDep, apdu)
        if (ret.size != replyLen + 2 || ret[replyLen] != 0x90.toByte() || ret[replyLen + 1] != 0x00.toByte()) {
            Logger.eHex(TAG, "Malformed response for second READ_BINARY command for payload, ret", ret)
            return null
        }
        return ret.copyOfRange(0, ret.size - 2)
    }

    @Throws(IOException::class)
    private fun ndefTransact(
        isoDep: IsoDep, ndefMessage: ByteArray,
        tWaitMillis: Double, nWait: Int
    ): ByteArray? {
        var apdu: ByteArray
        var ret: ByteArray
        Logger.dHex(TAG, "ndefTransact: writing NDEF message", ndefMessage)

        // See Type 4 Tag Technical Specification Version 1.2 section 7.5.5 NDEF Write Procedure
        // for how this is done.

        // Check to see if we can merge the three UPDATE_BINARY messages into a single message.
        // This is allowed as per [T4T] 7.5.5 NDEF Write Procedure:
        //
        //   If the entire NDEF Message can be written with a single UPDATE_BINARY
        //   Command, the Reader/Writer MAY write NLEN and ENLEN (Symbol 6), as
        //   well as the entire NDEF Message (Symbol 5) using a single
        //   UPDATE_BINARY Command. In this case the Reader/Writer SHALL
        //   proceed to Symbol 5 and merge Symbols 5 and 6 operations into a single
        //   UPDATE_BINARY Command.
        //
        // For debugging, this optimization can be turned off by setting this to |true|:
        val bypassUpdateBinaryOptimization = false
        if (!bypassUpdateBinaryOptimization && ndefMessage.size < 256 - 2) {
            Logger.d(TAG, "ndefTransact: using single UPDATE_BINARY command")
            val data = ByteArray(ndefMessage.size + 2)
            data[0] = 0.toByte()
            data[1] = (ndefMessage.size and 0xff).toByte()
            System.arraycopy(ndefMessage, 0, data, 2, ndefMessage.size)
            apdu = NfcUtil.createApduUpdateBinary(0x0000, data)
            ret = transceive(isoDep, apdu)
            if (!ret.contentEquals(NfcUtil.STATUS_WORD_OK)) {
                Logger.eHex(TAG, "Error sending combined UPDATE_BINARY command, ret", ret)
                return null
            }
        } else {
            Logger.d(TAG, "ndefTransact: using 3+ UPDATE_BINARY commands")

            // First command is UPDATE_BINARY to reset length
            apdu = NfcUtil.createApduUpdateBinary(0x0000, byteArrayOf(0x00, 0x00))
            ret = transceive(isoDep, apdu)
            if (!ret.contentEquals(NfcUtil.STATUS_WORD_OK)) {
                Logger.eHex(TAG, "Error sending initial UPDATE_BINARY command, ret", ret)
                return null
            }

            // Subsequent commands are UPDATE_BINARY with payload, chopped into bits no longer
            // than 255 bytes each
            var offset = 0
            var remaining = ndefMessage.size
            while (remaining > 0) {
                val numBytesToWrite = remaining.coerceAtMost(255)
                val bytesToWrite = ndefMessage.copyOfRange(offset, offset + numBytesToWrite)
                apdu = NfcUtil.createApduUpdateBinary(offset + 2, bytesToWrite)
                ret = transceive(isoDep, apdu)
                if (!ret.contentEquals(NfcUtil.STATUS_WORD_OK)) {
                    Logger.eHex(TAG, "Error sending UPDATE_BINARY command with payload, ret", ret)
                    return null
                }
                remaining -= numBytesToWrite
                offset += numBytesToWrite
            }

            // Final command is UPDATE_BINARY to write the length
            val encodedLength = byteArrayOf(
                (ndefMessage.size / 0x100 and 0xff).toByte(),
                (ndefMessage.size and 0xff).toByte()
            )
            apdu = NfcUtil.createApduUpdateBinary(0x0000, encodedLength)
            ret = transceive(isoDep, apdu)
            if (!ret.contentEquals(NfcUtil.STATUS_WORD_OK)) {
                Logger.eHex(TAG, "Error sending final UPDATE_BINARY command, ret", ret)
                return null
            }
        }
        try {
            val waitMillis = Math.ceil(tWaitMillis).toLong() // Just round up to closest millisecond
            Logger.d(TAG, "ndefTransact: Sleeping $waitMillis ms")
            Thread.sleep(waitMillis)
        } catch (e: InterruptedException) {
            throw IllegalStateException("Unexpected interrupt", e)
        }

        // Now read NDEF file...
        val receivedNdefMessage = ndefReadMessage(isoDep, tWaitMillis, nWait) ?: return null
        Logger.dHex(TAG, "ndefTransact: read NDEF message", receivedNdefMessage)
        return receivedNdefMessage
    }

    private fun startNfcHandover() {
        Logger.i(TAG, "Starting NFC handover thread")
        val timeMillisBegin = System.currentTimeMillis()
        if (negotiatedHandoverListeningTransports.size == 0) {
            Logger.w(TAG, "Negotiated Handover will not work - no listening connections configured")
        }
        val isoDep = nfcIsoDep
        val transceiverThread: Thread = object : Thread() {
            override fun run() {
                var ret: ByteArray?
                var apdu: ByteArray
                try {
                    isoDep!!.connect()
                    isoDep.timeout = 20 * 1000 // 20 seconds
                    apdu =
                        NfcUtil.createApduApplicationSelect(NfcUtil.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION)
                    ret = transceive(isoDep, apdu)
                    if (!Arrays.equals(ret, NfcUtil.STATUS_WORD_OK)) {
                        Logger.eHex(TAG, "NDEF application selection failed, ret", ret)
                        throw IllegalStateException("NDEF application selection failed")
                    }
                    apdu = NfcUtil.createApduSelectFile(NfcUtil.CAPABILITY_CONTAINER_FILE_ID)
                    ret = transceive(isoDep, apdu)
                    if (!Arrays.equals(ret, NfcUtil.STATUS_WORD_OK)) {
                        Logger.eHex(TAG, "Error selecting capability file, ret", ret)
                        throw IllegalStateException("Error selecting capability file")
                    }

                    // CC file is 15 bytes long
                    val ccFile = readBinary(isoDep, 0, 15)
                        ?: throw IllegalStateException("Error reading CC file")
                    check(ccFile.size >= 15) {
                        String.format(
                            Locale.US, "CC file is %d bytes, expected 15",
                            ccFile.size
                        )
                    }

                    // TODO: look at mapping version in ccFile
                    val ndefFileId =
                        (ccFile[9].toInt() and 0xff) * 256 + (ccFile[10].toInt() and 0xff)
                    Logger.d(TAG, String.format(Locale.US, "NDEF file id: 0x%04x", ndefFileId))
                    apdu = NfcUtil.createApduSelectFile(NfcUtil.NDEF_FILE_ID)
                    ret = transceive(isoDep, apdu)
                    if (!Arrays.equals(ret, NfcUtil.STATUS_WORD_OK)) {
                        Logger.eHex(TAG, "Error selecting NDEF file, ret", ret)
                        throw IllegalStateException("Error selecting NDEF file")
                    }

                    // First see if we should use negotiated handover..
                    val initialNdefMessage = ndefReadMessage(isoDep, 1.0, 0)
                    if (initialNdefMessage == null) {
                        throw IllegalStateException("Error reading initial NDEF message")
                    }
                    val handoverServiceRecord =
                        NfcUtil.findServiceParameterRecordWithName(
                            initialNdefMessage,
                            "urn:nfc:sn:handover"
                        )
                    if (handoverServiceRecord == null) {
                        val elapsedTime = System.currentTimeMillis() - timeMillisBegin
                        Logger.i(TAG,"Time spent in NFC static handover: $elapsedTime ms")
                        Logger.d(TAG, "No urn:nfc:sn:handover record found - assuming NFC static handover")
                        val hs = NfcUtil.parseHandoverSelectMessage(initialNdefMessage)
                                ?: throw IllegalStateException("Error parsing Handover Select message")
                        check(!hs.connectionMethods.isEmpty()) { "No connection methods in Handover Select" }
                        if (Logger.isDebugEnabled) {
                            for (cm in hs.connectionMethods) {
                                Logger.d(TAG, "Connection method from static handover: $cm")
                            }
                        }
                        Logger.d(TAG, "Reporting Device Engagement through NFC")
                        val readerHandover = CborArray.builder()
                            .add(initialNdefMessage) // Handover Select message
                            .add(Simple.NULL)        // Handover Request message
                            .end()
                            .build()
                        setDeviceEngagement(
                            hs.encodedDeviceEngagement,
                            readerHandover,
                            EngagementMethod.NFC_STATIC_HANDOVER
                        )
                        reportDeviceEngagementReceived(hs.connectionMethods)
                        return
                    }
                    Logger.d(TAG, "Service Parameter for urn:nfc:sn:handover found - negotiated handover")
                    val spr = NfcUtil.parseServiceParameterRecord(handoverServiceRecord)
                    Logger.d(TAG, String.format(
                            "tWait is %.1f ms, nWait is %d, maxNdefSize is %d",
                            spr.tWaitMillis, spr.nWait, spr.maxNdefSize))

                    // Select the service, the resulting NDEF message is specified in
                    // in Tag NDEF Exchange Protocol Technical Specification Version 1.0
                    // section 4.3 TNEP Status Message
                    ret = ndefTransact(
                        isoDep,
                        NfcUtil.createNdefMessageServiceSelect("urn:nfc:sn:handover"),
                        spr.tWaitMillis, spr.nWait
                    )
                    checkNotNull(ret) { "Service selection: no response" }
                    val tnepStatusRecord = NfcUtil.findTnepStatusRecord(ret)
                        ?: throw IllegalArgumentException("Service selection: no TNEP status record")
                    val tnepStatusPayload = tnepStatusRecord.payload
                    require(!(tnepStatusPayload == null || tnepStatusPayload.size != 1)) {
                        "Service selection: Malformed payload for TNEP status record"
                    }
                    val statusType = tnepStatusPayload[0].toInt() and 0x0ff
                    // Status type is defined in 4.3.3 Status Type
                    require(statusType == 0x00) {
                        "Service selection: Unexpected status type $statusType"
                    }

                    // Now send Handover Request, the resulting NDEF message is Handover Response..
                    //
                    val hrConnectionMethods = mutableListOf<MdocConnectionMethod>()
                    for (t in negotiatedHandoverListeningTransports) {
                        hrConnectionMethods.add(t.connectionMethodForTransport)
                    }
                    val hrMessage: ByteArray = NfcUtil.createNdefMessageHandoverRequest(
                        hrConnectionMethods,
                        null,
                        options
                    ) // TODO: pass ReaderEngagement message
                    Logger.dHex(TAG, "Handover Request sent", hrMessage)
                    val hsMessage = ndefTransact(isoDep, hrMessage, spr.tWaitMillis, spr.nWait)
                        ?: throw IllegalStateException("Handover Request failed")
                    Logger.dHex(TAG, "Handover Select received", hsMessage)
                    val elapsedTime = System.currentTimeMillis() - timeMillisBegin
                    Logger.i(TAG, "Time spent in NFC negotiated handover: $elapsedTime ms")
                    var encodedDeviceEngagement: ByteArray? = null
                    var parsedCms = mutableListOf<MdocConnectionMethod>()
                    val ndefHsMessage = NdefMessage(hsMessage)
                    for (r in ndefHsMessage.records) {
                        // DeviceEngagement record
                        //
                        if (r.tnf == NdefRecord.TNF_EXTERNAL_TYPE &&
                            Arrays.equals(r.type, "iso.org:18013:deviceengagement".toByteArray())
                            && Arrays.equals(r.id, "mdoc".toByteArray())
                        ) {
                            encodedDeviceEngagement = r.payload
                            Logger.dCbor(TAG,
                                "Device Engagement from NFC negotiated handover",
                                encodedDeviceEngagement
                            )
                        } else if (r.tnf == NdefRecord.TNF_MIME_MEDIA || r.tnf == NdefRecord.TNF_EXTERNAL_TYPE) {
                            val cm = NfcUtil.fromNdefRecord(r, true)
                            if (cm != null) {
                                parsedCms.add(cm)
                                Logger.d(TAG, "CM: $cm")
                            }
                        }
                    }
                    checkNotNull(encodedDeviceEngagement) { "Device Engagement not found in HS message" }
                    check(parsedCms.size >= 1) { "No Alternative Carriers in HS message" }

                    // Now that we have DeviceEngagement, pass eDeviceKeyBytes to the transport
                    // since it's needed for the Ident characteristic as per ISO/IEC 18013-5:2021
                    // clause 8.3.3.1.1.3 Connection setup
                    val engagement = EngagementParser(encodedDeviceEngagement).parse()
                    for (t in negotiatedHandoverListeningTransports) {
                        t.setEDeviceKeyBytes(engagement.eSenderKeyBytes)
                    }

                    // TODO: use selected CMs to pick from the list we offered... why would we
                    //  have to do this? Because some mDL / wallets don't return the UUID in
                    //  the HS message.
                    //  For now just assume we only offered a single CM and the other side accepted.
                    //
                    parsedCms = hrConnectionMethods
                    val handover = CborArray.builder()
                        .add(hsMessage) // Handover Select message
                        .add(hrMessage) // Handover Request message
                        .end()
                        .build()
                    setDeviceEngagement(
                        encodedDeviceEngagement,
                        handover,
                        EngagementMethod.NFC_NEGOTIATED_HANDOVER
                    )
                    reportDeviceEngagementReceived(parsedCms)
                } catch (t: Throwable) {
                    reportError(t)
                }
            }
        }
        transceiverThread.start()
    }

    /**
     * Set device engagement data acquired from a secondary source; a source other than
     * Android NFC or QR code.
     *
     * This method initializes the session state, including the reader ephemeral key
     * and the session transcript.
     *
     * After calling this method, the caller is responsible for parsing the connection
     * methods from the engagement data and calling [reportDeviceEngagementReceived],
     * which will then trigger the [Listener.onDeviceEngagementReceived] callback.
     *
     * This method must be called before [connect].
     *
     * @param deviceEngagement, a [ByteArray], the raw CBOR-encoded
     *   [org.multipaz.mdoc.engagement.DeviceEngagement]
     *   structure as defined in ISO 18013-5. This contains the device's public key and supported
     *   connection methods.
     * @param handover, a [DataItem], the raw CBOR-encoded ISO 18013-5 handover structure to
     *   be included in the Session Transcript. For QR code engagement, this is typically
     *   [org.multipaz.cbor.Simple.NULL]. For NFC, it contains the hashes of the handover select
     *   and handover request messages.
     * @param engagementMethod, an [EngagementMethod], the [EngagementMethod] used to retrieve the
     *  engagement data, used for tracking the provenance of the session.
     *
     * @throws IllegalStateException if called after [connect].
     */
    fun setDeviceEngagement(
        deviceEngagement: ByteArray,
        handover: DataItem,
        engagementMethod: EngagementMethod
    ) {
        check(this.deviceEngagement == null) { "Device Engagement already set" }
        this.deviceEngagement = deviceEngagement
        this.engagementMethod = engagementMethod
        timestampEngagementReceived = Clock.System.now().toEpochMilliseconds()
        val engagementParser = EngagementParser(deviceEngagement)
        val engagement = engagementParser.parse()
        val eDeviceKey: EcPublicKey = engagement.eSenderKey

        // Create reader ephemeral key with key to match device ephemeral key's curve... this
        // can take a long time (hundreds of milliseconds) so use the precalculated key
        // to avoid delaying the transaction...
        ephemeralKey = Crypto.createEcPrivateKey(engagement.eSenderKey.curve)
        val encodedEReaderKeyPub: ByteArray = Cbor.encode(
            ephemeralKey!!.publicKey.toCoseKey().toDataItem()
        )
        encodedSessionTranscript = Cbor.encode(
            CborArray.builder()
                .add(Tagged(24, Bstr(this.deviceEngagement!!)))
                .add(Tagged(24, Bstr(encodedEReaderKeyPub)))
                .add(handover)
                .end()
                .build()
        )
        Logger.dCbor(TAG, "SessionTranscript", encodedSessionTranscript!!)
        sessionEncryptionReader = SessionEncryption(
            MdocRole.MDOC_READER,
            ephemeralKey!!,
            eDeviceKey,
            encodedSessionTranscript!!
        )
        if (readerEngagement != null) {
            // No need to include EReaderKey in first message...
            sessionEncryptionReader!!.setSendSessionEstablishment(false)
        }
    }

    /**
     * Establishes connection to remote mdoc using the given [MdocConnectionMethod].
     *
     * This method should be called after receiving the
     * [Listener.onDeviceEngagementReceived] callback with one of the addresses from
     * said callback.
     *
     * @param connectionMethod the address/method to connect to.
     */
    fun connect(connectionMethod: MdocConnectionMethod) {
        // First see if it's a warmed up transport...
        for (warmedUpTransport in negotiatedHandoverListeningTransports) {
            if (warmedUpTransport.connectionMethodForTransport.toString() == connectionMethod.toString()) {
                // Close other warmed-up transports and then connect.
                negotiatedHandoverListeningTransports.remove(warmedUpTransport)
                for (t in negotiatedHandoverListeningTransports) {
                    Logger.i(TAG, "Closing warmed-up transport: " + t.connectionMethodForTransport)
                    t.close()
                }
                negotiatedHandoverListeningTransports.clear()
                Logger.i(TAG, "Connecting to a warmed-up transport " +
                        "${warmedUpTransport.connectionMethodForTransport}")
                connectWithDataTransport(warmedUpTransport, true)
                return
            }
        }

        // Nope, not using a warmed-up transport. Close the warmed-up ones and connect.
        for (t in negotiatedHandoverListeningTransports) {
            Logger.i(TAG, "Closing warmed-up transport: " + t.connectionMethodForTransport)
            t.close()
        }
        negotiatedHandoverListeningTransports.clear()
        Logger.i(TAG, "Connecting to transport $connectionMethod")
        connectWithDataTransport(
            DataTransport.fromConnectionMethod(
                context, connectionMethod, DataTransport.Role.MDOC_READER, options!!),
            false
        )
    }

    private fun connectWithDataTransport(
        transport: DataTransport?,
        usingWarmedUpTransport: Boolean
    ) {
        dataTransport = transport
        if (dataTransport is DataTransportNfc) {
            if (nfcIsoDep == null) {
                // This can happen if using NFC data transfer with QR code engagement
                // which is allowed by ISO 18013-5:2021 (even though it's really
                // weird). In this case we just sit and wait until the tag (reader)
                // is detected... once detected, this routine can just call connect()
                // again.
                Logger.i(TAG,
                    "In connect() with NFC data transfer but no ISO dep has been set. " +
                            "Assuming QR engagement, waiting for mdoc to move into field")
                reportMoveIntoNfcField()
                return
            }
            (dataTransport as DataTransportNfc).setIsoDep(nfcIsoDep!!)
        } else if (dataTransport is DataTransportBle) {
            // Helpful warning
            if (options!!.bleClearCache && dataTransport is DataTransportBleCentralClientMode) {
                Logger.i(TAG,
                    "Ignoring bleClearCache flag since it only applies to " +
                            "BLE mdoc peripheral server mode when acting as a reader")
            }
        }

        // Careful, we're using the user-provided Executor below so these callbacks might happen
        // in another thread than we're in right now. For example this happens if using
        // ThreadPoolExecutor.
        //
        // If it turns out that we're going to access shared state we might need locking /
        // synchronization.
        //
        val listener: DataTransport.Listener = object : DataTransport.Listener {
            override fun onConnecting() {
                Logger.d(TAG, "onConnecting for $dataTransport")
            }

            override fun onConnected() {
                Logger.d(TAG, "onConnected for $dataTransport")
                reportDeviceConnected()
            }

            override fun onDisconnected() {
                Logger.d(TAG, "onDisconnected for $dataTransport")
                dataTransport!!.close()
                reportError(Error("Peer disconnected without proper session termination"))
            }

            override fun onError(error: Throwable) {
                Logger.d(TAG, "onError for $dataTransport: $error")
                dataTransport!!.close()
                reportError(error)
            }

            override fun onMessageReceived() {
                handleOnMessageReceived()
            }

            override fun onTransportSpecificSessionTermination() {
                Logger.d(TAG, "Received onTransportSpecificSessionTermination")
                dataTransport!!.close()
                reportDeviceDisconnected(true)
            }
        }
        dataTransport!!.setListener(listener, listenerExecutor)

        // It's entirely possible the other side already connected to us...
        if (usingWarmedUpTransport) {
            if (dataTransport!!.isConnected) {
                listener.onConnected()
            }
        } else {
            try {
                val deviceEngagementDataItem = Cbor.decode(deviceEngagement!!)
                val security = deviceEngagementDataItem[1]
                val encodedEDeviceKeyBytes = Cbor.encode(security[1])
                dataTransport!!.setEDeviceKeyBytes(encodedEDeviceKeyBytes)
                dataTransport!!.connect()
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    private fun handleReverseEngagementMessageData(data: ByteArray) {
        Logger.dCbor(TAG, "MessageData", data)
        val map = Cbor.decode(data)
        if (!map.hasKey("deviceEngagementBytes")) {
            dataTransport!!.close()
            reportError(Error("Error extracting DeviceEngagement from MessageData"))
            return
        }
        val encodedDeviceEngagement = map["deviceEngagementBytes"].asTagged.asBstr
        Logger.dCbor(TAG, "Extracted DeviceEngagement", encodedDeviceEngagement)

        // 18013-7 says to use ReaderEngagementBytes for Handover when ReaderEngagement
        // is available and neither QR or NFC is used.
        val handover = Tagged(24, Bstr(readerEngagement!!))
        setDeviceEngagement(encodedDeviceEngagement, handover, EngagementMethod.REVERSE)

        // Tell the application it can start sending requests...
        reportDeviceConnected()
    }

    private fun handleOnMessageReceived() {
        val data = dataTransport!!.getMessage()
        if (data == null) {
            reportError(Error("onMessageReceived but no message"))
            return
        }
        Logger.dCbor(TAG, "SessionData received", data)
        if (deviceEngagement == null) {
            // DeviceEngagement is delivered in the first message...
            handleReverseEngagementMessageData(data)
            return
        }
        if (sessionEncryptionReader == null) {
            reportError(
                IllegalStateException(
                    "Message received but no session "
                            + "establishment with the remote device."
                )
            )
            return
        }
        val decryptedMessage = try {
            sessionEncryptionReader!!.decryptMessage(data)
        } catch (e: Exception) {
            dataTransport!!.close()
            reportError(Error("Error decrypting message from device", e))
            return
        }

        // If there's data in the message, assume it's DeviceResponse (ISO 18013-5
        // currently does not define other kinds of messages).
        //
        if (decryptedMessage.first != null) {
            Logger.dCbor(TAG, "DeviceResponse received", decryptedMessage.first!!)
            timestampResponseReceived = Clock.System.now().toEpochMilliseconds()
            reportResponseReceived(decryptedMessage.first!!)
        } else {
            // No data, so status must be set...
            if (decryptedMessage.second == null) {
                dataTransport!!.close()
                reportError(Error("No data and no status in SessionData"))
                return
            }
        }

        // It's possible both data and status is set, for example if the holder only
        // wants to serve a single response.
        if (decryptedMessage.second != null) {
            val statusCode = decryptedMessage.second
            Logger.d(TAG, "SessionData with status code $statusCode")
            if (statusCode == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                dataTransport!!.close()
                reportDeviceDisconnected(false)
            } else {
                dataTransport!!.close()
                reportError(Error("Expected status code 20, got $statusCode instead"))
            }
        }
    }

    fun reportDeviceDisconnected(transportSpecificTermination: Boolean) {
        Logger.d(
            TAG, "reportDeviceDisconnected: transportSpecificTermination: "
                    + transportSpecificTermination
        )
        listenerExecutor.execute {
            listener.onDeviceDisconnected(
                transportSpecificTermination
            )
        }
    }

    fun reportResponseReceived(deviceResponseBytes: ByteArray) {
        Logger.d(TAG, "reportResponseReceived (" + deviceResponseBytes.size + " bytes)")
        listenerExecutor.execute { listener.onResponseReceived(deviceResponseBytes) }
    }

    fun reportMoveIntoNfcField() {
        Logger.d(TAG, "reportMoveIntoNfcField")
        listenerExecutor.execute { listener.onMoveIntoNfcField() }
    }

    fun reportDeviceConnected() {
        Logger.d(TAG, "reportDeviceConnected")
        listenerExecutor.execute { listener.onDeviceConnected() }
    }

    fun reportReaderEngagementReady(readerEngagement: ByteArray) {
        Logger.dCbor(TAG, "reportReaderEngagementReady", readerEngagement)
        listenerExecutor.execute { listener.onReaderEngagementReady(readerEngagement) }
    }

    fun reportDeviceEngagementReceived(connectionMethods: List<MdocConnectionMethod>) {
        if (Logger.isDebugEnabled) {
            Logger.d(TAG, "reportDeviceEngagementReceived")
            for (cm in connectionMethods) {
                Logger.d(TAG, "  ConnectionMethod: $cm")
            }
        }
        listenerExecutor.execute { listener.onDeviceEngagementReceived(connectionMethods) }
    }

    fun reportError(error: Throwable) {
        Logger.d(TAG, "reportError: error: $error")
        error.printStackTrace()
        listenerExecutor.execute { listener.onError(error) }
    }

    /**
     * Ends the session with the remote device.
     *
     * By default, ending a session involves sending a message to the remote device with empty
     * data and the status code set to 20, meaning _session termination_ as per
     * ISO/IEC 18013-5. This can be configured using [setSendSessionTerminationMessage] and
     * [setUseTransportSpecificSessionTermination].
     *
     * Some transports - such as BLE - supports a transport-specific session termination
     * message instead of the generic one. By default this is not used but it can be enabled using
     * [setUseTransportSpecificSessionTermination].
     *
     * After calling this the current object can no longer be used to send requests.
     *
     * This method is idempotent so it is safe to call multiple times.
     */
    fun disconnect() {
        for (t in negotiatedHandoverListeningTransports!!) {
            Logger.d(TAG, "Shutting down Negotiated Handover warmed-up transport $t")
            t.close()
        }
        negotiatedHandoverListeningTransports.clear()
        if (reverseEngagementListeningTransports != null) {
            for (transport in reverseEngagementListeningTransports!!) {
                transport.close()
            }
            reverseEngagementListeningTransports = null
        }
        if (dataTransport != null) {
            // Only send session termination message if the session was actually established.
            val sessionEstablished = sessionEncryptionReader!!.numMessagesEncrypted > 0
            if (sendSessionTerminationMessage && sessionEstablished) {
                if (useTransportSpecificSessionTermination &&
                    dataTransport!!.supportsTransportSpecificTerminationMessage()
                ) {
                    Logger.d(TAG, "Sending transport-specific termination message")
                    dataTransport!!.sendTransportSpecificTerminationMessage()
                } else {
                    Logger.d(TAG, "Sending generic session termination message")
                    val sessionTermination = sessionEncryptionReader!!.encryptMessage(
                        null, Constants.SESSION_DATA_STATUS_SESSION_TERMINATION
                    )
                    dataTransport!!.sendMessage(sessionTermination)
                }
            } else {
                Logger.d(TAG, "Not sending session termination message")
            }
            Logger.d(TAG, "Shutting down transport")
            dataTransport!!.close()
            dataTransport = null
        }
    }

    /**
     * Sends a request to the remote mdoc device.
     *
     * The `deviceRequestBytes` parameter must be `DeviceRequest` [CBOR](http://cbor.io/)
     * as specified in ISO/IEC 18013-5 section 8.3 Device Retrieval.
     *
     * If a response to the request is received from the remote mdoc device, the
     * [Listener.onResponseReceived] method is invoked. This will usually take
     * several seconds as it typically involves authenticating the holder and asking for their
     * consent to release identity data.
     *
     * This is usually called in response to [Listener.onDeviceConnected] callback.
     *
     * @param deviceRequestBytes request message to the remote device
     */
    fun sendRequest(deviceRequestBytes: ByteArray) {
        checkNotNull(deviceEngagement) { "Device engagement is null" }
        checkNotNull(ephemeralKey) { "New object must be created" }
        checkNotNull(dataTransport) { "Not connected to a remote device" }
        Logger.dCbor(TAG, "DeviceRequest to send", deviceRequestBytes)
        val message = sessionEncryptionReader!!.encryptMessage(
            deviceRequestBytes, null
        )
        Logger.dCbor(TAG, "SessionData to send", message)
        dataTransport!!.sendMessage(message)
        timestampRequestSent = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * Sets whether to use transport-specific session termination.
     *
     *
     * By default this is set to `false`.
     *
     *
     * As per [ISO/IEC 18013-5](https://www.iso.org/standard/69084.html)
     * transport-specific session-termination is currently only supported for BLE. The
     * [.isTransportSpecificTerminationSupported] method can be used to determine whether
     * it's available for the current transport.
     *
     *
     * If [.isTransportSpecificTerminationSupported] indicates that this is not
     * available for the current transport this is a noop.
     *
     * @param useTransportSpecificSessionTermination Whether to use transport-specific session
     * termination.
     */
    fun setUseTransportSpecificSessionTermination(
        useTransportSpecificSessionTermination: Boolean
    ) {
        this.useTransportSpecificSessionTermination = useTransportSpecificSessionTermination
    }

    val isTransportSpecificTerminationSupported: Boolean
        /**
         * Returns whether transport specific termination is available for the current connection.
         *
         * See [.setUseTransportSpecificSessionTermination] for more information about
         * what transport specific session termination is.
         *
         * @return `true` if transport specific termination is available, `false`
         * if not or if not connected.
         */
        get() = if (dataTransport == null) {
            false
        } else dataTransport!!.supportsTransportSpecificTerminationMessage()

    /**
     * Sets whether to send session termination message.
     *
     *
     * This controls whether a session termination message is sent when
     * [.disconnect] is called. Most applications would want to do
     * this as it is required by
     * [ISO/IEC 18013-5](https://www.iso.org/standard/69084.html).
     *
     *
     * By default this is set to `true`.
     *
     * @param sendSessionTerminationMessage Whether to send session termination message.
     */
    fun setSendSessionTerminationMessage(
        sendSessionTerminationMessage: Boolean
    ) {
        this.sendSessionTerminationMessage = sendSessionTerminationMessage
    }


    /**
     * Interface for listening to messages from the remote mdoc device.
     */
    interface Listener {
        /**
         * Called when using reverse engagement and the reader engagement is ready.
         *
         * The app can display this as QR code (for 23220-4) or send it to a remote
         * user agent as an mdoc:// URI (for 18013-7).
         *
         * @param readerEngagement the bytes of reader engagement.
         */
        fun onReaderEngagementReady(readerEngagement: ByteArray)

        /**
         * Called when a valid device engagement is received from QR code of NFC.
         *
         *
         * This is called either in response to [.setDeviceEngagementFromQrCode]
         * or as a result of a NFC tap. The application should call
         * [.connect] in response to this callback to establish a
         * connection.
         *
         *
         * If reverse engagement is used, this is never called.
         *
         * @param connectionMethods a list of connection methods that can be used to establish
         * a connection to the mdoc.
         */
        fun onDeviceEngagementReceived(connectionMethods: List<MdocConnectionMethod>)

        /**
         * Called when NFC data transfer has been selected but the mdoc reader device isn't yet
         * in the NFC field of the mdoc device.
         *
         *
         * The application should instruct the user to move the mdoc reader device into
         * the NFC field.
         */
        fun onMoveIntoNfcField()

        /**
         * Called when connected to a remote mdoc device.
         *
         *
         * At this point the application can start sending requests using
         * [.sendRequest].
         */
        fun onDeviceConnected()

        /**
         * Called when the remote mdoc device disconnects normally, that is
         * using the session termination functionality in the underlying protocols.
         *
         *
         * If this is called the application should call [.disconnect] and the
         * object should no longer be used.
         *
         * @param transportSpecificTermination set to `true` if the termination
         * mechanism used was transport specific.
         */
        fun onDeviceDisconnected(transportSpecificTermination: Boolean)

        /**
         * Called when the remote mdoc device sends a response.
         *
         * The `deviceResponseBytes` parameter contains the bytes of the
         * `DeviceResponse` [CBOR](http://cbor.io/)
         * as specified in *ISO/IEC 18013-5* section 8.3 *Device Retrieval*. The
         * class [DeviceResponseParser] can be used to parse these bytes.
         *
         * @param deviceResponseBytes the device response.
         */
        fun onResponseReceived(deviceResponseBytes: ByteArray)

        /**
         * Called when an unrecoverable error happens, for example if the remote device
         * disconnects unexpectedly (e.g. without first sending a session termination request).
         *
         *
         * If this is called the application should call [.disconnect] and the
         * object should no longer be used.
         *
         * @param error the error.
         */
        fun onError(error: Throwable)
    }

    /**
     * Builder for [VerificationHelper].
     */
    class Builder(
        context: Context,
        listener: Listener,
        executor: Executor
    ) {
        private val mHelper: VerificationHelper

        /**
         * Creates a new Builder for [VerificationHelper].
         *
         * @param context application context.
         * @param listener listener.
         * @param executor executor.
         */
        init {
            mHelper = VerificationHelper(context, listener, executor)
        }

        /**
         * Sets the options to use when setting up transports.
         *
         * @param options the options to use.
         * @return the builder.
         */
        fun setDataTransportOptions(options: DataTransportOptions): Builder {
            mHelper.options = options
            return this
        }

        /**
         * Configures the verification helper to use reverse engagement.
         *
         * @param connectionMethods a list of connection methods to offer via reverse engagement
         * @return the builder.
         */
        fun setUseReverseEngagement(connectionMethods: List<MdocConnectionMethod>): Builder {
            mHelper.reverseEngagementConnectionMethods = connectionMethods
            return this
        }

        fun setNegotiatedHandoverConnectionMethods(connectionMethods: List<MdocConnectionMethod>): Builder {
            mHelper.negotiatedHandoverConnectionMethods = connectionMethods
            return this
        }

        /**
         * Builds a [VerificationHelper] with the configuration specified in the builder.
         *
         * If using normal engagement and the application wishes to use NFC engagement it
         * should use [NfcAdapter] and pass received tags to
         * [nfcProcessOnTagDiscovered]. For QR engagement the application is
         * expected to capture the QR code itself and pass it using
         * [setDeviceEngagementFromQrCode]. When engagement with a mdoc is detected
         * [Listener.onDeviceEngagementReceived] is called with a list of possible
         * transports and the application is expected to pick a transport and pass it to
         * [connect].
         *
         * If using reverse engagement, the application should wait for the
         * [Listener.onReaderEngagementReady] callback and then convey the
         * reader engagement to the mdoc, for example via QR code or sending an mdoc:// URI
         * to a suitable user agent. After this, application should wait for the
         * [Listener.onDeviceConnected] callback which is called when the mdoc
         * connects via one of the connection methods specified using
         * [setUseReverseEngagement].
         *
         * When the application is done interacting with the mdoc it should call
         * [disconnect].
         *
         * @return A [VerificationHelper].
         */
        fun build(): VerificationHelper {
            mHelper.start()
            return mHelper
        }
    }

    companion object {
        private const val TAG = "VerificationHelper"
    }
    
    enum class EngagementMethod {
        NOT_ENGAGED,
        QR_CODE,
        NFC_STATIC_HANDOVER,
        NFC_NEGOTIATED_HANDOVER,
        REVERSE
    }
}
