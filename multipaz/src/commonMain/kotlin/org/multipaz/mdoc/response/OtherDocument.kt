package org.multipaz.mdoc.response

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.presentment.TransactionData
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import org.multipaz.util.zlibInflate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val TAG = "OtherDocument"

/**
 * A document in a [DeviceResponse] which isn't an ISO mdoc.
 *
 * @property docFormat the format of the document, e.g. "sd-jwt+kb".
 * @property data the compressed data of the document, using the DEFLATE algorithm according
 * to [RFC 1951](https://www.ietf.org/rfc/rfc1951.txt).
 */
data class OtherDocument(
    val docFormat: String,
    val data: ByteString
) {
    internal fun toDataItem() = buildCborMap {
        put("docFormat", docFormat)
        put("data", data.toByteArray())
    }

    internal suspend fun verify(
        sessionTranscript: DataItem,
        eReaderKey: AsymmetricKey?,
        transactionData: List<TransactionData>,
        atTime: Instant,
    ): Map<String, Map<String, DataItem>> {
        when (docFormat) {
            "sd-jwt+kb" -> return verifySdJwtVc(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderKey,
                transactionData = transactionData,
                atTime = atTime
            )
        }
        return emptyMap()
    }

    internal suspend fun verifySdJwtVc(
        sessionTranscript: DataItem,
        eReaderKey: AsymmetricKey?,
        transactionData: List<TransactionData>,
        atTime: Instant,
    ): Map<String, Map<String, DataItem>> {
        val sdJwtKb = SdJwtKb.fromCompactSerialization(data.toByteArray().zlibInflate().decodeToString())

        val issuerCertChain = sdJwtKb.sdJwt.x5c
            ?: throw IllegalStateException("Issuer-signed key not in `x5c` in header")

        val sessionTranscriptBytes = Tagged(
            tagNumber = Tagged.ENCODED_CBOR,
            taggedItem = Bstr(Cbor.encode(sessionTranscript))
        )
        val expectedNonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(sessionTranscriptBytes)).toBase64Url()

        val processedPayload = sdJwtKb.verify(
            issuerKey = issuerCertChain.certificates.first().ecPublicKey,
            checkNonce = { nonce ->
                expectedNonce == nonce
            },
            checkAudience = { audience ->
                // TODO: check audience when DeviceResponse.verify() takes a DeviceRequest.
                true
            },
            checkCreationTime = { creationTime ->
                val drift = (atTime - creationTime).absoluteValue
                drift < 5.minutes
            },
            transactionData = emptyList()  // TODO: handle transaction data
        )
        // Check validity
        val validFrom = sdJwtKb.sdJwt.validFrom ?: throw IllegalStateException("No nbf claim")
        val validUntil = sdJwtKb.sdJwt.validUntil ?: throw IllegalStateException("No exp claim")
        if (atTime < validFrom) {
            throw IllegalStateException("SD-JWT is not yet valid")
        }
        if (atTime > validUntil) {
            throw IllegalStateException("SD-JWT is not valid anymore")
        }
        // TODO: Check transaction data and return transaction processing responses
        return emptyMap()
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): OtherDocument {
            val docFormat = dataItem["docFormat"].asTstr
            val data = ByteString(dataItem["data"].asBstr)
            return OtherDocument(
                docFormat = docFormat,
                data = data
            )
        }
    }
}
