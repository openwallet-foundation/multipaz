package org.multipaz.utopia.knowntypes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.documenttype.TransactionType
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.util.toBase64Url

class TextAcceptanceTransactionTest {
    // Building the CBOR request validates the payload up-front (fresh request is not expired).
    private val payload = TextAcceptanceTransaction.Payload(
        text = "I accept contract number PLUMB-2026-0042.\nIt is a fair deal.",
        requestId = "request-123",
        expiresAt = Clock.System.now() + 5.minutes,
    ).also { TextAcceptanceTransaction.serializeIso18013Request(it) }

    @Test
    fun iso18013RoundTrip() {
        assertEquals(
            payload,
            TextAcceptanceTransaction.parseIso18013Request(
                TextAcceptanceTransaction.serializeIso18013Request(payload)
            )
        )
    }

    @Test
    fun iso18013RequestRejectsUnknownFields() {
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.parseIso18013Request(
                buildCborMap {
                    put("text", payload.text)
                    put("requestId", payload.requestId)
                    put("expiresAt", payload.expiresAt.toString())
                    put("unexpectedCriticalField", true)
                }
            )
        }
    }

    @Test
    fun openId4VpRoundTrip() {
        val request = TextAcceptanceTransaction.serializeOpenId4VpRequest(payload, listOf("pid"), null)
        assertEquals(payload, TextAcceptanceTransaction.parseOpenId4VpRequest(request))
    }

    @Test
    fun parseJsonPreservesHashAlgorithmsAndBytes() {
        val request = TextAcceptanceTransaction.serializeOpenId4VpRequest(
            payload, listOf("pid"), listOf(Algorithm.SHA256))
        val serialized = ByteString(request.encodeToByteArray().toBase64Url().encodeToByteArray())
        val transactionData = TextAcceptanceTransaction.parseJson(serialized)
        assertEquals(payload, transactionData.payload)
        assertEquals(TransactionProtocol.OPENID4VP, transactionData.protocol)
        assertEquals(serialized, transactionData.rawBytes)
        assertEquals(listOf(Algorithm.SHA256), transactionData.hashAlgorithms)
    }

    @Test
    fun openId4VpRequestRejectsUnknownFields() {
        val request = TextAcceptanceTransaction.serializeOpenId4VpRequest(payload, listOf("pid"), null)
        val jsonWithExtraField = request.removeSuffix("}") + ", \"unexpected_critical_field\": true}"
        assertFailsWith<SerializationException> {
            TextAcceptanceTransaction.parseOpenId4VpRequest(jsonWithExtraField)
        }
    }

    @Test
    fun responseMustMatchTextRequestIdAndExpiry() = runTest {
        val transactionData = TransactionData(
            type = TextAcceptanceTransaction,
            payload = payload,
            protocol = TransactionProtocol.ISO_18013_5,
            rawBytes = ByteString(Cbor.encode(TextAcceptanceTransaction.serializeIso18013Request(payload)))
        )
        TextAcceptanceTransaction.verifyMdocResponse(transactionData, mapOf(
            "text" to payload.text.toDataItem(),
            "requestId" to payload.requestId.toDataItem(),
            "expiresAt" to payload.expiresAt.toString().toDataItem(),
        ))
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifyMdocResponse(transactionData, mapOf(
                "text" to "Changed".toDataItem(),
                "requestId" to payload.requestId.toDataItem(),
                "expiresAt" to payload.expiresAt.toString().toDataItem(),
            ))
        }
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifyMdocResponse(transactionData, mapOf(
                "text" to payload.text.toDataItem(),
                "requestId" to "different-request".toDataItem(),
                "expiresAt" to payload.expiresAt.toString().toDataItem(),
            ))
        }
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifyMdocResponse(transactionData, mapOf(
                "text" to payload.text.toDataItem(),
                "requestId" to payload.requestId.toDataItem(),
                "expiresAt" to (payload.expiresAt + 1.seconds).toString().toDataItem(),
            ))
        }
    }

    @Test
    fun sdJwtResponseMustMatchTextRequestIdAndExpiry() = runTest {
        val transactionData = TransactionData(
            type = TextAcceptanceTransaction,
            payload = payload,
            protocol = TransactionProtocol.ISO_18013_5,
            rawBytes = ByteString(Cbor.encode(TextAcceptanceTransaction.serializeIso18013Request(payload)))
        )
        TextAcceptanceTransaction.verifySdJwtResponse(transactionData, mapOf(
            "text" to JsonPrimitive(payload.text),
            "request_id" to JsonPrimitive(payload.requestId),
            "expires_at" to JsonPrimitive(payload.expiresAt.toString()),
        ))
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifySdJwtResponse(transactionData, mapOf(
                "text" to JsonPrimitive("Changed"),
                "request_id" to JsonPrimitive(payload.requestId),
                "expires_at" to JsonPrimitive(payload.expiresAt.toString()),
            ))
        }
    }

    @Test
    fun openId4VpMdocResponseMustMatchTextRequestIdAndExpiry() = runTest {
        val transactionData = openId4VpTransactionData()
        val hash = transactionData.computeHash()
        fun response(
            text: String = payload.text,
            requestId: String = payload.requestId,
            expiresAt: String = payload.expiresAt.toString(),
        ) = mapOf(
            "text" to text.toDataItem(),
            "requestId" to requestId.toDataItem(),
            "expiresAt" to expiresAt.toDataItem(),
            "transactionDataHash" to hash.toByteArray().toDataItem(),
        )
        TextAcceptanceTransaction.verifyMdocResponse(transactionData, response())
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifyMdocResponse(transactionData, response(text = "Changed"))
        }
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifyMdocResponse(
                transactionData, response(requestId = "different-request"))
        }
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifyMdocResponse(
                transactionData, response(expiresAt = (payload.expiresAt + 1.seconds).toString()))
        }
    }

    @Test
    fun openId4VpSdJwtResponseMustMatchTextRequestIdAndExpiry() = runTest {
        val transactionData = openId4VpTransactionData()
        fun response(
            text: String = payload.text,
            requestId: String = payload.requestId,
            expiresAt: String = payload.expiresAt.toString(),
        ) = mapOf(
            "text" to JsonPrimitive(text),
            "request_id" to JsonPrimitive(requestId),
            "expires_at" to JsonPrimitive(expiresAt),
        )
        TextAcceptanceTransaction.verifySdJwtResponse(transactionData, response())
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifySdJwtResponse(transactionData, response(text = "Changed"))
        }
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifySdJwtResponse(
                transactionData, response(requestId = "different-request"))
        }
        assertFailsWith<IllegalStateException> {
            TextAcceptanceTransaction.verifySdJwtResponse(
                transactionData, response(expiresAt = (payload.expiresAt + 1.seconds).toString()))
        }
    }

    private fun openId4VpTransactionData(): TransactionData<TextAcceptanceTransaction.Payload> {
        val request = TextAcceptanceTransaction.serializeOpenId4VpRequest(payload, listOf("pid"), null)
        return TextAcceptanceTransaction.parseJson(
            ByteString(request.encodeToByteArray().toBase64Url().encodeToByteArray()))
    }

    @Test
    fun sampleDataGetsAFreshRequestIdPerAccess() {
        val first = TextAcceptanceTransaction.sampleData
        val second = TextAcceptanceTransaction.sampleData
        assertNotEquals(first.payload.requestId, second.payload.requestId)
        TextAcceptanceTransaction.serializeIso18013Request(first.payload)
        assertTrue(first.payload.text.length > 500)
    }

    @Test
    fun cachedSampleDataSerializesAFreshRequestEveryTime() {
        // The document type repository keeps a single CannedTransactionData instance for the
        // lifetime of the process. Serializing it must therefore use a fresh payload rather than
        // the one captured when the canned request was created.
        val canned = TextAcceptanceTransaction.sampleData
        val first = TextAcceptanceTransaction.parseOpenId4VpRequest(
            canned.getSerializedJson(listOf("cred1")))
        val second = TextAcceptanceTransaction.parseOpenId4VpRequest(
            canned.getSerializedJson(listOf("cred1")))
        assertNotEquals(first.requestId, second.requestId)
    }

    @Test
    fun rejectsBlankOversizedAndControlCharacterText() {
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(text = " "))
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(text = "a".repeat(4_097)))
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(text = "accept\u202e"))
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(text = "one\u0000two"))
        }
    }

    @Test
    fun rejectsBlankAndOversizedRequestIds() {
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(requestId = " "))
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(payload.copy(requestId = "r".repeat(129)))
        }
    }

    @Test
    fun rejectsExpiredAndExcessivelyLongLivedRequests() {
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(
                payload.copy(expiresAt = Clock.System.now())
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.serializeIso18013Request(
                payload.copy(expiresAt = Clock.System.now() + 10.minutes + 1.seconds)
            )
        }
    }

    @Test
    fun expiredRequestRejectedWhenParsed() {
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.parseIso18013Request(
                buildCborMap {
                    put("text", payload.text)
                    put("requestId", payload.requestId)
                    put("expiresAt", (Clock.System.now() - 1.seconds).toString())
                }
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextAcceptanceTransaction.parseIso18013Request(
                buildCborMap {
                    put("text", payload.text)
                    put("requestId", payload.requestId)
                    put("expiresAt", "not-a-timestamp")
                }
            )
        }
    }

    @Test
    fun presentmentRequiresExplicitAcceptance() {
        assertTrue(TextAcceptanceTransaction.requiresUserAcceptance)
        // No consent prompt or no application renderer means no user input.
        assertFalse(TextAcceptanceTransaction.isPresentationAllowed(null))
        assertFalse(TextAcceptanceTransaction.isPresentationAllowed(TextAcceptanceTransaction.UserInput()))
        assertFalse(
            TextAcceptanceTransaction.isPresentationAllowed(
                TextAcceptanceTransaction.UserInput(accepted = false)
            )
        )
        assertTrue(
            TextAcceptanceTransaction.isPresentationAllowed(
                TextAcceptanceTransaction.UserInput(accepted = true)
            )
        )
    }

    @Test
    fun transactionTypesWithoutAcceptanceDoNotRequireUserInput() {
        assertFalse(PingTransaction.requiresUserAcceptance)
        assertTrue(PingTransaction.isPresentationAllowed(null))
    }

    @Test
    fun requiringAcceptanceWithoutAnOverrideRejectsAllInput() {
        // A type that requires acceptance but forgets to override isUserInputAccepted() must
        // fail closed rather than silently present.
        val type = object : TransactionType<String>(
            displayName = "Acceptance without override",
            identifier = "org.multipaz.test.acceptance_without_override",
        ) {
            override val requiresUserAcceptance: Boolean = true
        }
        assertFalse(type.isPresentationAllowed(null))
        assertFalse(type.isPresentationAllowed(TextAcceptanceTransaction.UserInput(accepted = true)))
    }
}
