package org.multipaz.utopia.knowntypes

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.documenttype.CannedTransactionData
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

/**
 * Transaction type that round-trips some data through the presentment process for testing.
 */
object PingTransaction: TransactionType<PingTransaction.Payload>(
    displayName = "Ping",
    identifier = "org.multipaz.transaction.ping",
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

    data class Payload(
        val string: String?,
        val blob: ByteString?
    )

    override fun serializeIso18013Request(payload: Payload): DataItem = buildCborMap {
        payload.string?.let { put("string", it) }
        payload.blob?.let { put("blob", it.toByteArray().toDataItem()) }
    }

    override fun parseIso18013Request(dataItem: DataItem): Payload = Payload(
        string = dataItem.getOrNull("string")?.asTstr,
        blob = dataItem.getOrNull("blob")?.asBstr?.let { ByteString(it) }
    )

    override fun serializeOpenId4VpRequest(
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

    override fun parseOpenId4VpRequest(jsonString: String): Payload {
        val data = jsonFormat.decodeFromString<JsonData>(jsonString)
        return Payload(
            string = data.string,
            blob = data.blob?.fromBase64Url()?.let { ByteString(it) }
        )
    }

    override fun parseJson(serialized: ByteString): TransactionData<Payload> {
        val jsonString = serialized.decodeToString().fromBase64Url().decodeToString()
        val data = jsonFormat.decodeFromString<JsonData>(jsonString)
        return TransactionData(
            type = this,
            payload = Payload(
                string = data.string,
                blob = data.blob?.fromBase64Url()?.let { ByteString(it) }
            ),
            protocol = TransactionProtocol.OPENID4VP,
            rawBytes = serialized,
            hashAlgorithms = parseJoseHashAlgorithms(data.transactionDataHashesAlg),
        )
    }


    override suspend fun isApplicable(
        transactionData: TransactionData<Payload>,
        credential: Credential
    ): Boolean {
        // For the sake of testing, refuse UtopiaNaturalization
        return !(credential is KeyBoundSdJwtVcCredential
                    && credential.vct == UtopiaNaturalization.VCT)
                && super.isApplicable(transactionData, credential)
    }

    override suspend fun generateMdocResponseElements(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): Map<String, DataItem> = buildMap {
        putAll(super.generateMdocResponseElements(transactionData, credential, userInput, docRequestId))
        transactionData.payload.string?.let {
            put("string", it.toDataItem())
        }
        transactionData.payload.blob?.let {
            put("blob", it.toByteArray().toDataItem())
        }
    }

    override suspend fun generateSdJwtResponseClaims(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): Map<String, JsonElement> = buildMap {
        putAll(super.generateSdJwtResponseClaims(transactionData, credential, userInput, docRequestId))
        transactionData.payload.string?.let {
            put("string", JsonPrimitive(it))
        }
        transactionData.payload.blob?.let {
            put("blob", JsonPrimitive(it.toByteArray().toBase64Url()))
        }
    }

    override suspend fun verifyMdocResponse(
        transactionData: TransactionData<Payload>,
        responseElements: Map<String, DataItem>
    ) {
        super.verifyMdocResponse(transactionData, responseElements)
        if (transactionData.protocol == TransactionProtocol.ISO_18013_5) {
            transactionData.payload.string?.let { expectedString ->
                val actualString = responseElements["string"]?.asTstr
                if (actualString != expectedString) {
                    throw IllegalStateException("String mismatch: expected $expectedString, got $actualString")
                }
            }
            transactionData.payload.blob?.let { expectedBlob ->
                val actualBlob = responseElements["blob"]?.asBstr
                if (actualBlob == null || !ByteString(actualBlob).equals(expectedBlob)) {
                    throw IllegalStateException("Blob mismatch")
                }
            }
        }
    }

    override suspend fun verifySdJwtResponse(
        transactionData: TransactionData<Payload>,
        responseClaims: Map<String, JsonElement>
    ) {
        super.verifySdJwtResponse(transactionData, responseClaims)
        if (transactionData.protocol == TransactionProtocol.ISO_18013_5) {
            transactionData.payload.string?.let { expectedString ->
                val actualString = responseClaims["string"]?.jsonPrimitive?.contentOrNull
                if (actualString != expectedString) {
                    throw IllegalStateException("String mismatch: expected $expectedString, got $actualString")
                }
            }
            transactionData.payload.blob?.let { expectedBlob ->
                val actualBlob = responseClaims["blob"]?.jsonPrimitive?.contentOrNull?.fromBase64Url()
                if (actualBlob == null || !ByteString(actualBlob).equals(expectedBlob)) {
                    throw IllegalStateException("Blob mismatch")
                }
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