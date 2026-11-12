package org.multipaz.documenttype

import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.util.toBase64Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TransactionTypeParseJsonTest {

    // Relies on the base parseJson(), as a transaction type does unless it overrides it.
    private object NoteTransaction : TransactionType<String>(
        displayName = "Note",
        identifier = "com.example.note",
    ) {
        override fun serializeOpenId4VpRequest(
            payload: String,
            credentialIds: List<String>,
            hashAlgorithms: List<Algorithm>?
        ): String = buildJsonObject {
            put("type", identifier)
            put("credential_ids", JsonArray(credentialIds.map { JsonPrimitive(it) }))
            joseHashAlgorithms(hashAlgorithms)?.let {
                put("transaction_data_hashes_alg", JsonArray(it.map { alg -> JsonPrimitive(alg) }))
            }
            put("note", payload)
        }.toString()

        override fun parseOpenId4VpRequest(jsonString: String): String =
            Json.parseToJsonElement(jsonString).jsonObject.getValue("note").jsonPrimitive.content
    }

    private fun parse(json: String): TransactionData<String> =
        NoteTransaction.parseJson(json.encodeToByteArray().toBase64Url().encodeToByteString())

    private fun parseWithHashesAlg(hashesAlg: JsonElement): TransactionData<String> =
        parse(buildJsonObject {
            put("type", NoteTransaction.identifier)
            put("credential_ids", JsonArray(listOf(JsonPrimitive("pid"))))
            put("transaction_data_hashes_alg", hashesAlg)
            put("note", "hello")
        }.toString())

    private fun algs(vararg names: String) = JsonArray(names.map { JsonPrimitive(it) })

    @Test
    fun requestedHashAlgorithmsSurviveTheRoundTrip() {
        val data = parse(NoteTransaction.serializeOpenId4VpRequest(
            payload = "hello",
            credentialIds = listOf("pid"),
            hashAlgorithms = listOf(Algorithm.SHA384, Algorithm.SHA256)
        ))
        assertEquals("hello", data.payload)
        assertEquals(TransactionProtocol.OPENID4VP, data.protocol)
        assertEquals(listOf(Algorithm.SHA384, Algorithm.SHA256), data.hashAlgorithms)
    }

    @Test
    fun noHashAlgorithmsLeavesTheDefault() {
        val data = parse(NoteTransaction.serializeOpenId4VpRequest("hello", listOf("pid"), null))
        assertNull(data.hashAlgorithms)
        assertNull(parseWithHashesAlg(JsonNull).hashAlgorithms)
    }

    @Test
    fun unknownHashAlgorithmsAreSkipped() {
        assertEquals(listOf(Algorithm.SHA512), parseWithHashesAlg(algs("sha-3000", "sha-512")).hashAlgorithms)
    }

    @Test
    fun noKnownHashAlgorithmIsRefused() {
        assertFailsWith<IllegalArgumentException> { parseWithHashesAlg(algs("md5")) }
        assertFailsWith<IllegalArgumentException> { parseWithHashesAlg(algs()) }
    }

    @Test
    fun malformedHashAlgorithmsAreRefused() {
        assertFailsWith<IllegalArgumentException> { parseWithHashesAlg(JsonPrimitive("sha-384")) }
        assertFailsWith<IllegalArgumentException> { parseWithHashesAlg(JsonArray(listOf(JsonPrimitive(384)))) }
    }
}
