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
package org.multipaz.mdoc.sessionencryption

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Hkdf
import org.multipaz.mdoc.role.MdocRole

/**
 * Helper class for implementing session encryption according to ISO/IEC 18013-5:2021
 * section 9.1.1 Session encryption.
 *
 * The `DeviceEngagement` and `Handover` CBOR referenced in the
 * parameters below must conform to the CDDL in ISO 18013-5.
 *
 * All references to a "remote" device refer to a device with the opposite role. For example,
 * [SessionEncryption] objects with the [MdocRole.MDOC] role will encrypt messages
 * with the remote mdoc reader as the intended receiver, so the reader is the remote device.
 *
 * @param role the role that the object should act as.
 * @param eSelfKey The ephemeral private key e.g. in the [MdocRole.MDOC_READER] role,
 * it's the ephemeral private key for the mdoc reader, and in the [MdocRole.MDOC] role
 * it's the for the mdoc.
 * @param remotePublicKey The public ephemeral key of the other end.
 * @param encodedSessionTranscript The bytes of the `SessionTranscript` CBOR.
 */
class SessionEncryption(
    val role: MdocRole,
    private val eSelfKey: EcPrivateKey,
    remotePublicKey: EcPublicKey,
    encodedSessionTranscript: ByteArray
) {
    private var sessionEstablishmentSent = false
    private val skRemote: ByteArray
    private val skSelf: ByteArray
    private var decryptedCounter = 1
    private var encryptedCounter = 1
    private var sendSessionEstablishment = true

    init {
        val sharedSecret = Crypto.keyAgreement(eSelfKey, remotePublicKey)
        val sessionTranscriptBytes = Cbor.encode(Tagged(24, Bstr(encodedSessionTranscript)))
        val salt = Crypto.digest(Algorithm.SHA256, sessionTranscriptBytes)
        var info = "SKDevice".encodeToByteArray()
        val deviceSK = Hkdf.deriveKey(Algorithm.HMAC_SHA256, sharedSecret, salt, info, 32)
        info = "SKReader".encodeToByteArray()
        val readerSK = Hkdf.deriveKey(Algorithm.HMAC_SHA256, sharedSecret, salt, info, 32)
        if (role == MdocRole.MDOC) {
            skSelf = deviceSK
            skRemote = readerSK
        } else {
            skSelf = readerSK
            skRemote = deviceSK
        }
    }

    /**
     * Configure whether to send `SessionEstablishment` as the first message. Only an
     * object with the role [MdocRole.MDOC_READER] will want to do this.
     *
     * If set to false the first message to the mdoc will *not* contain `eReaderKey`. This is
     * useful for situations where this key has already been conveyed out-of-band, for example
     * via reverse engagement.
     *
     * The default value for this is `true`.
     *
     * @param sendSessionEstablishment whether to send `SessionEstablishment` as the first message.
     * @throws IllegalStateException if role is [MdocRole.MDOC_READER]
     */
    fun setSendSessionEstablishment(sendSessionEstablishment: Boolean) {
        check(role != MdocRole.MDOC) {
            "Only mdoc readers should be sending sessionEstablishment messages " +
                    " but this object was constructed with role MDOC"
        }
        this.sendSessionEstablishment = sendSessionEstablishment
    }

    /**
     * Encrypt a message intended for the remote device.
     *
     *
     * This method returns `SessionEstablishment` CBOR for the first call and
     * `SessionData` CBOR for subsequent calls. These CBOR data structures are
     * defined in ISO 18013-5 9.1.1 Session encryption.
     *
     * @param messagePlaintext if not `null`, the message to encrypt and include
     * in `SessionData`.
     * @param statusCode if set, the status code to include in `SessionData`.
     * @return the bytes of the `SessionEstablishment` or `SessionData`
     * CBOR as described above.
     */
    fun encryptMessage(
        messagePlaintext: ByteArray?,
        statusCode: Long?
    ): ByteArray {
        var messageCiphertext: ByteArray? = null
        if (messagePlaintext != null) {
            // The IV and these constants are specified in ISO/IEC 18013-5:2021 clause 9.1.1.5.
            val iv = ByteStringBuilder(12)
            iv.append(0x00000000U)
            val ivIdentifier = if (role == MdocRole.MDOC) 0x00000001U else 0x00000000U
            iv.append(ivIdentifier)
            iv.append(encryptedCounter.toUInt())
            messageCiphertext = Crypto.encrypt(
                Algorithm.A128GCM,
                skSelf,
                iv.toByteString().toByteArray(),
                messagePlaintext
            )
            encryptedCounter += 1
        }

        val messageData = Cbor.encode(buildCborMap {
            if (!sessionEstablishmentSent && sendSessionEstablishment && role == MdocRole.MDOC_READER) {
                var eReaderKey = eSelfKey.publicKey
                putTaggedEncodedCbor("eReaderKey", Cbor.encode(eReaderKey.toCoseKey().toDataItem()))
                checkNotNull(messageCiphertext) { "Data cannot be empty in initial message" }
            }
            if (messageCiphertext != null) {
                put("data", messageCiphertext)
            }
            if (statusCode != null) {
                put("status", statusCode)
            }
        })
        sessionEstablishmentSent = true
        return messageData
    }

    /**
     * Decrypts a message received from the remote device.
     *
     *
     * This method expects the passed-in data to conform to the `SessionData`
     * DDL as defined in ISO 18013-5 9.1.1 Session encryption.
     *
     * @param messageData the bytes of the `SessionData` CBOR as described above.
     * @return The decrypted message as data and status.
     * @exception IllegalArgumentException if the passed in data does not conform to the CDDL.
     * @exception IllegalStateException if decryption fails.
     */
    fun decryptMessage(
        messageData: ByteArray
    ): Pair<ByteArray?, Long?> {
        val map = Cbor.decode(messageData)
        val dataDataItem = map.getOrNull("data")
        var messageCiphertext: ByteArray? = null
        if (dataDataItem != null) {
            messageCiphertext = dataDataItem.asBstr
        }
        val statusDataItem = map.getOrNull("status")
        val status = statusDataItem?.asNumber
        var plainText: ByteArray? = null
        if (messageCiphertext != null) {
            val iv = ByteStringBuilder(12)
            iv.append(0x00000000U)
            val ivIdentifier = if (role == MdocRole.MDOC) 0x00000000U else 0x00000001U
            iv.append(ivIdentifier)
            iv.append(decryptedCounter.toUInt())
            plainText = Crypto.decrypt(
                Algorithm.A128GCM,
                skRemote,
                iv.toByteString().toByteArray(),
                messageCiphertext
            )
            decryptedCounter += 1
        }
        return Pair(plainText, status)
    }

    /**
     * The number of messages encrypted with [.encryptMessage].
     */
    val numMessagesEncrypted: Int
        get() = encryptedCounter - 1

    /**
     * The number of messages decrypted with [.decryptMessage].
     */
    val numMessagesDecrypted: Int
        get() = decryptedCounter - 1

    companion object {
        /**
         * Create a SessionData message (as defined in ISO/IEC 18013-5 9.1.1.4 Procedure) with a status
         * code and no data.
         *
         * @param statusCode the intended status code, with value as defined in ISO/IEC 18013-5 Table 20.
         * @return a byte array with the encoded CBOR message
         */
        fun encodeStatus(statusCode: Long): ByteArray = Cbor.encode(
            buildCborMap {
                put("status", statusCode)
            }
        )

        /**
         * Gets the ephemeral reader key in a `SessionEstablishment` message.
         *
         * @param sessionEstablishmentMessage the bytes of a `SessionEstablishment` message.
         * @return the reader key, as an [EReaderKey], which contains both the decoded
         * public key and original encoded bytes.
         */
        fun getEReaderKey(sessionEstablishmentMessage: ByteArray): EReaderKey {
            val map = Cbor.decode(sessionEstablishmentMessage)
            val encodedCoseKey = map["eReaderKey"].asTagged.asBstr
            val publicKey = Cbor.decode(encodedCoseKey).asCoseKey.ecPublicKey
            return EReaderKey(publicKey, encodedCoseKey)
        }
    }
}

private fun ByteStringBuilder.append(value: UInt) = apply {
    append((value shr 24).and(0xffU).toByte())
    append((value shr 16).and(0xffU).toByte())
    append((value shr 8).and(0xffU).toByte())
    append((value shr 0).and(0xffU).toByte())
}

