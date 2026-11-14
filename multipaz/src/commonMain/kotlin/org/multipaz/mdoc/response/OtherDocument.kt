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
 * @property data the compressed data of the document, using the DEFLATE algorithm according
 * to [RFC 1951](https://www.ietf.org/rfc/rfc1951.txt).
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

    /**
     * The expected audience value that was verified against the Key Binding JWT's `aud` claim,
     * or `null` if [DeviceResponse.verify] (or [EncryptedDocuments.decrypt]) has not been called
     * yet or if [docFormat] is not `"dc+sd-jwt"`.
     */
    val expectedAudience: String?
        get() = _expectedAudience

    internal fun clearVerificationState() {
        _expectedAudience = null
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
        expectedAudience: String?,
    ) {
        _expectedAudience = null
        when (docFormat) {
            "dc+sd-jwt" -> verifySdJwtVc(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderKey,
                transactionData = transactionData,
                atTime = atTime,
                expectedAudience = expectedAudience
            )
            else -> throw IllegalStateException("Unsupported OtherDocument format '$docFormat'")
        }
    }

    internal suspend fun verifySdJwtVc(
        sessionTranscript: DataItem,
        eReaderKey: AsymmetricKey?,
        transactionData: List<TransactionData<*>>,
        atTime: Instant,
        expectedAudience: String?,
    ) {
        val sdJwtKb = SdJwtKb.fromCompactSerialization(data.toByteArray().zlibInflate().decodeToString())

        val issuerCertChain = sdJwtKb.sdJwt.x5c
            ?: throw IllegalStateException("Issuer-signed key not in `x5c` in header")

        val sessionTranscriptBytes = Tagged(
            tagNumber = Tagged.ENCODED_CBOR,
            taggedItem = Bstr(Cbor.encode(sessionTranscript))
        )
        val expectedNonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(sessionTranscriptBytes)).toBase64Url()

        sdJwtKb.verify(
            issuerKey = issuerCertChain.certificates.first().publicKey,
            checkNonce = { nonce ->
                expectedNonce == nonce
            },
            checkAudience = { audience ->
                expectedAudience != null && expectedAudience == audience
            },
            checkCreationTime = { creationTime ->
                val drift = (atTime - creationTime).absoluteValue
                drift < 5.minutes
            },
            transactionData = transactionData
        )
        // Check validity
        val validFrom = sdJwtKb.sdJwt.validFrom ?: throw IllegalStateException("No nbf claim")
        val validUntil = sdJwtKb.sdJwt.validUntil ?: throw IllegalStateException("No exp claim")
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
