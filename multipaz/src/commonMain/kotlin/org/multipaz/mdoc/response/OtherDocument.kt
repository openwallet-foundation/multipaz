package org.multipaz.mdoc.response

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.mdoc.util.asDocRequestIds
import org.multipaz.mdoc.util.validateDocRequestIds
import org.multipaz.presentment.TransactionData
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.util.toBase64Url
import org.multipaz.util.zlibInflate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val TAG = "OtherDocument"

/**
 * A document in a [DeviceResponse] which isn't an ISO mdoc.
 *
 * @property docFormat the format of the document, e.g. "dc+sd-jwt".
 * @property data the compressed data of the document, using ZLIB (RFC 1950 + RFC 1951).
 * @property docRequestIds optional list of `DocRequest` indices in `DeviceRequest.docRequests`
 *   that this document responds to, or `null` if not specified.
 */
data class OtherDocument(
    val docFormat: String,
    val data: ByteString,
    val docRequestIds: List<Int>? = null,
) {
    init {
        if (docRequestIds != null) {
            validateDocRequestIds(docRequestIds)
        }
    }

    /**
     * List of verified transaction data which was sent in the request
     */
    lateinit var transactionData: List<TransactionData<*>>

    private var _expectedAudience: String? = null
    private var _keyBindingRequired: Boolean = true

    /**
     * The expected audience value that was verified against the Key Binding JWT's `aud` claim,
     * or `null` if [DeviceResponse.verify] (or [EncryptedDocuments.decrypt]) has not been called
     * yet, if the credential is keyless, or if [docFormat] is not `"dc+sd-jwt"`.
     */
    val expectedAudience: String?
        get() = _expectedAudience

    internal val keyBindingRequired: Boolean
        get() = _keyBindingRequired

    internal fun clearVerificationState() {
        _expectedAudience = null
        _keyBindingRequired = true
    }

    internal suspend fun parseSdJwtPresentation(): Pair<SdJwt, SdJwtKb?> {
        val compactSerialization = data.toByteArray().zlibInflate().decodeToString()
        return if (compactSerialization.endsWith("~")) {
            Pair(SdJwt.fromCompactSerialization(compactSerialization), null)
        } else {
            val sdJwtKb = SdJwtKb.fromCompactSerialization(compactSerialization)
            Pair(sdJwtKb.sdJwt, sdJwtKb)
        }
    }

    internal fun toDataItem() = buildCborMap {
        put("docFormat", docFormat)
        put("data", data.toByteArray())
        if (docRequestIds != null) {
            putCborArray("docRequestIds") {
                docRequestIds.forEach { add(it) }
            }
        }
    }

    internal suspend fun verify(
        sessionTranscript: DataItem,
        eReaderKey: AsymmetricKey?,
        transactionData: List<TransactionData<*>>,
        atTime: Instant,
        keyBindingRequired: Boolean = true,
    ) {
        _expectedAudience = null
        _keyBindingRequired = keyBindingRequired
        when (docFormat) {
            "dc+sd-jwt" -> verifySdJwtVc(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderKey,
                transactionData = transactionData,
                atTime = atTime,
                keyBindingRequired = keyBindingRequired,
            )
            else -> throw IllegalStateException("Unsupported OtherDocument format '$docFormat'")
        }
    }

    internal suspend fun verifySdJwtVc(
        sessionTranscript: DataItem,
        eReaderKey: AsymmetricKey?,
        transactionData: List<TransactionData<*>>,
        atTime: Instant,
        keyBindingRequired: Boolean = true,
    ) {
        val (sdJwt, sdJwtKb) = parseSdJwtPresentation()

        val issuerCertChain = sdJwt.x5c
            ?: throw IllegalStateException("Issuer-signed key not in `x5c` in header")

        val expectedAudience: String?
        if (sdJwtKb != null) {
            val sessionTranscriptBytes = Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(Cbor.encode(sessionTranscript))
            )
            // Per ISO/IEC 18013-5 Second Edition Annex H.2, both `nonce` and `aud` in the
            // Key Binding JWT are `base64url(SHA-256(SessionTranscriptBytes))`.
            val expectedSessionTranscriptHash =
                Crypto.digest(Algorithm.SHA256, Cbor.encode(sessionTranscriptBytes)).toBase64Url()
            expectedAudience = expectedSessionTranscriptHash

            sdJwtKb.verify(
                issuerKey = issuerCertChain.certificates.first().publicKey,
                checkNonce = { nonce ->
                    expectedSessionTranscriptHash == nonce
                },
                checkAudience = { audience ->
                    expectedSessionTranscriptHash == audience
                },
                checkCreationTime = { creationTime ->
                    val drift = (atTime - creationTime).absoluteValue
                    drift < 5.minutes
                },
                transactionData = transactionData
            )
        } else {
            expectedAudience = null
            if (sdJwt.kbKey != null || sdJwt.jwtBody.containsKey("cnf")) {
                throw IllegalStateException("Key-bound SD-JWT VC presented without Key Binding JWT")
            }
            if (keyBindingRequired) {
                throw IllegalStateException("Keyless SD-JWT VC is not permitted when nonKeyBoundSupported is not true")
            }
            if (transactionData.isNotEmpty()) {
                throw IllegalStateException("Transaction data is not supported with keyless SD-JWT VC")
            }
            val processedPayload = sdJwt.verify(issuerCertChain.certificates.first().publicKey)
            if (processedPayload.containsKey("cnf")) {
                throw IllegalStateException("Key-bound SD-JWT VC presented without Key Binding JWT")
            }
        }
        // Check validity
        val validFrom = sdJwt.validFrom ?: throw IllegalStateException("No nbf claim")
        val validUntil = sdJwt.validUntil ?: throw IllegalStateException("No exp claim")
        if (atTime < validFrom) {
            throw IllegalStateException("SD-JWT is not yet valid")
        }
        if (atTime >= validUntil) {
            throw IllegalStateException("SD-JWT is not valid anymore")
        }
        this.transactionData = transactionData
        this._expectedAudience = expectedAudience
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): OtherDocument {
            val docFormat = dataItem["docFormat"].asTstr
            val data = ByteString(dataItem["data"].asBstr)
            val docRequestIds = dataItem.getOrNull("docRequestIds")?.asDocRequestIds()
            return OtherDocument(
                docFormat = docFormat,
                data = data,
                docRequestIds = docRequestIds,
            )
        }
    }
}
