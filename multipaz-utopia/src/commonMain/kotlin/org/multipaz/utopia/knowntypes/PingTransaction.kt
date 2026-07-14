package org.multipaz.utopia.knowntypes

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.cbor.toDataItem
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.documenttype.CannedTransactionData
import org.multipaz.documenttype.TransactionType
import org.multipaz.presentment.TransactionData
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url

/**
 * Transaction type that round-trips some data through the presentment process for testing.
 */
object PingTransaction: TransactionType<PingTransaction.Payload>(
    displayName = "Ping",
    identifier = "org.multipaz.transaction.ping",
    mdocRequestInfoKeyName = "org.multipaz.transaction.ping.mdoc_request",
    mdocResponseNamespace = "org.multipaz.transaction.ping.mdoc_response",
    kbJwtResponseClaimName = "org.multipaz.transaction.ping.response"
) {
    @Serializable
    data class JsonData(
        val type: String,
        val credentialIds: List<String>,
        val transactionDataHashesAlg: List<String>?,
        val string: String?,
        val blob: String?  // use base64url-encoded String, could also write custom KSerializer
    )

    @CborSerializable
    data class CborData(
        val transactionDataHashesAlg: List<Long>?,
        val string: String?,
        val blob: ByteString?
    ) {
        companion object
    }

    data class Payload(
        val string: String?,
        val blob: ByteString?
    )

    override fun serializeCbor(
        payload: Payload,
        hashAlgorithms: List<Algorithm>?
    ): ByteString = ByteString(
        data = CborData(
            transactionDataHashesAlg = coseHashAlgorithms(hashAlgorithms),
            string = payload.string,
            blob = payload.blob
        ).toCbor()
    )

    override fun serializeJson(
        payload: Payload,
        credentialIds: List<String>,
        hashAlgorithms: List<Algorithm>?
    ): String = jsonFormat.encodeToString(
        value = JsonData(
            type = identifier,
            transactionDataHashesAlg = joseHashAlgorithms(hashAlgorithms),
            credentialIds = credentialIds,
            string = payload.string,
            blob = payload.blob?.toByteArray()?.toBase64Url()
        )
    )

    override fun parseJson(serialized: ByteString): TransactionData<Payload> {
        val jsonString = serialized.decodeToString().fromBase64Url().decodeToString()
        val data = jsonFormat.decodeFromString<JsonData>(jsonString)
        return TransactionData(
            type = this,
            serialized = serialized,
            hashAlgorithms = parseJoseHashAlgorithms(data.transactionDataHashesAlg),
            payload = Payload(
                string = data.string,
                blob = data.blob?.fromBase64Url()?.let { ByteString(it) }
            ),
        )
    }

    override fun parseCbor(serialized: ByteString): TransactionData<Payload> {
        val data = CborData.fromCbor(serialized.toByteArray())
        return TransactionData(
            type = this,
            serialized = serialized,
            hashAlgorithms = parseCoseHashAlgorithms(data.transactionDataHashesAlg),
            payload = Payload(
                string = data.string,
                blob = data.blob
            )
        )
    }

    override suspend fun isApplicable(
        transactionData: TransactionData<Payload>,
        credential: Credential
    ): Boolean {
        // For the sake of testing, refuse UtopiaNaturalization
        return !(credential is KeyBoundSdJwtVcCredential
                && credential.vct == UtopiaNaturalization.VCT)
    }

    override suspend fun applyCbor(
        transactionData: TransactionData<Payload>,
        credential: Credential
    ): Map<String, DataItem> {
        return buildMap {
            transactionData.payload.string?.let {
                put("string", it.toDataItem())
            }
            transactionData.payload.blob?.let {
                put("blob", it.toByteArray().toDataItem())
            }
        }
    }

    /** Sample transaction data for this transaction type */
    val sampleData = CannedTransactionData<Payload>(
        transactionType = PingTransaction,
        payload = Payload("string data", null)
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val jsonFormat = Json {
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }
}