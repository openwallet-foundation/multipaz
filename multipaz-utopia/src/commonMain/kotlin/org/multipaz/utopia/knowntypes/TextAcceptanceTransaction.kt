package org.multipaz.utopia.knowntypes

import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.CannedTransactionData
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.presentment.TransactionData
import org.multipaz.util.toBase64Url

/**
 * A transaction type that binds acceptance of an exact plain-text statement to a
 * credential presentation.
 *
 * The text is deliberately restricted to plain text. Newlines are preserved; all other control
 * characters and Unicode bidirectional controls are rejected to prevent a holder and verifier
 * from seeing different statements.
 *
 * The holder-facing acceptance flow is not part of the wire format. An application signals that
 * the holder has read and accepted the statement, for example after showing the full text on a
 * separate page with a confirmation checkbox, by disabling the consent prompt's continue action
 * until the acceptance is complete. See [UserInput].
 */
object TextAcceptanceTransaction : TransactionType<TextAcceptanceTransaction.Payload>(
    displayName = "Text acceptance",
    identifier = "org.multipaz.transaction.text_acceptance",
) {
    /**
     * The holder must explicitly accept the statement: the built-in generic rendering only shows
     * the transaction type name, so presentment is refused unless the wallet supplies a renderer
     * that shows the full text and collects [UserInput].
     */
    override val requiresUserAcceptance: Boolean = true

    override fun isUserInputAccepted(userInput: TransactionUserInput?): Boolean =
        userInput is UserInput && userInput.accepted

    private const val MAX_TEXT_LENGTH = 4_096
    private const val MAX_REQUEST_ID_LENGTH = 128
    private val MAX_VALIDITY = 10.minutes

    /**
     * User input recording that the holder has read and accepted the statement.
     *
     * The value is not included in the device-signed response: the response already contains the
     * statement itself and is signed by the selected credential's device key, which proves that
     * the holder approved that exact statement.
     */
    data class UserInput(
        val accepted: Boolean = false,
    ) : TransactionUserInput() {
        override fun generateMdocResponseElements(
            transactionData: TransactionData<*>,
            credential: Credential
        ): Map<String, DataItem> = emptyMap()

        override fun generateSdJwtResponseClaims(
            transactionData: TransactionData<*>,
            credential: Credential
        ): Map<String, JsonElement> = emptyMap()
    }

    @Serializable
    data class JsonData(
        val type: String,
        val credentialIds: List<String>,
        val transactionDataHashesAlg: List<String>?,
        val text: String,
        val requestId: String,
        val expiresAt: String,
    )

    data class Payload(
        val text: String,
        val requestId: String,
        val expiresAt: Instant,
    )

    override fun serializeIso18013Request(payload: Payload): DataItem {
        validate(payload)
        return buildCborMap {
            put("text", payload.text)
            put("requestId", payload.requestId)
            put("expiresAt", payload.expiresAt.toString())
        }
    }

    override fun parseIso18013Request(dataItem: DataItem): Payload {
        require(dataItem is CborMap) { "Text acceptance request must be a CBOR map" }
        require(dataItem.items.keys.map { it.asTstr }.toSet() == REQUIRED_FIELDS) {
            "Text acceptance request has missing or unrecognized fields"
        }
        return Payload(
            text = dataItem.getOrNull("text")!!.asTstr,
            requestId = dataItem.getOrNull("requestId")!!.asTstr,
            expiresAt = parseExpiry(dataItem.getOrNull("expiresAt")!!.asTstr)
        ).also(::validate)
    }

    override fun serializeOpenId4VpRequest(
        payload: Payload,
        credentialIds: List<String>,
        hashAlgorithms: List<Algorithm>?
    ): String {
        validate(payload)
        return jsonFormat.encodeToString(
            value = JsonData(
                type = identifier,
                credentialIds = credentialIds,
                transactionDataHashesAlg = joseHashAlgorithms(hashAlgorithms),
                text = payload.text,
                requestId = payload.requestId,
                expiresAt = payload.expiresAt.toString(),
            )
        )
    }

    override fun parseOpenId4VpRequest(jsonString: String): Payload {
        val data = jsonFormat.decodeFromString<JsonData>(jsonString)
        require(data.type == identifier) { "Unexpected transaction type '${data.type}'" }
        return Payload(
            text = data.text,
            requestId = data.requestId,
            expiresAt = parseExpiry(data.expiresAt)
        ).also(::validate)
    }

    override suspend fun generateMdocResponseElements(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): Map<String, DataItem> = buildMap {
        putAll(super.generateMdocResponseElements(transactionData, credential, userInput, docRequestId))
        put("text", transactionData.payload.text.toDataItem())
        put("requestId", transactionData.payload.requestId.toDataItem())
        put("expiresAt", transactionData.payload.expiresAt.toString().toDataItem())
    }

    override suspend fun generateSdJwtResponseClaims(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): Map<String, JsonElement> = buildMap {
        putAll(super.generateSdJwtResponseClaims(transactionData, credential, userInput, docRequestId))
        put("text", JsonPrimitive(transactionData.payload.text))
        put("request_id", JsonPrimitive(transactionData.payload.requestId))
        put("expires_at", JsonPrimitive(transactionData.payload.expiresAt.toString()))
    }

    override suspend fun verifyMdocResponse(
        transactionData: TransactionData<Payload>,
        responseElements: Map<String, DataItem>
    ) {
        super.verifyMdocResponse(transactionData, responseElements)
        verify(
            transactionData.payload,
            responseElements["text"]?.asTstr,
            responseElements["requestId"]?.asTstr,
            responseElements["expiresAt"]?.asTstr
        )
    }

    override suspend fun verifySdJwtResponse(
        transactionData: TransactionData<Payload>,
        responseClaims: Map<String, JsonElement>
    ) {
        super.verifySdJwtResponse(transactionData, responseClaims)
        verify(
            transactionData.payload,
            responseClaims["text"]?.jsonPrimitive?.contentOrNull,
            responseClaims["request_id"]?.jsonPrimitive?.contentOrNull,
            responseClaims["expires_at"]?.jsonPrimitive?.contentOrNull
        )
    }

    /**
     * Sample transaction data for this transaction type. A short-lived statement is generated for
     * every serialization, so each request gets a fresh cryptographically random request ID and a
     * new expiry, even when the canned request is retained for the lifetime of the process.
     * Production callers should build the [Payload] themselves with statement text from their own
     * backend.
     */
    val sampleData: CannedTransactionData<Payload>
        get() = CannedTransactionData(
            transactionType = this,
            payloadProvider = ::newSamplePayload,
        )

    private fun newSamplePayload() = Payload(
        text = SAMPLE_TEXT,
        requestId = Crypto.secureRandom.nextBytes(REQUEST_ID_NUM_BYTES).toBase64Url(),
        expiresAt = Clock.System.now() + MAX_VALIDITY,
    )

    private fun parseExpiry(value: String): Instant = try {
        Instant.parse(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid text acceptance expiry", e)
    }

    private fun validate(payload: Payload) {
        require(payload.text.isNotBlank() && payload.text.length <= MAX_TEXT_LENGTH) {
            "Text acceptance text must be between 1 and $MAX_TEXT_LENGTH characters"
        }
        require(payload.requestId.isNotBlank() && payload.requestId.length <= MAX_REQUEST_ID_LENGTH) {
            "Text acceptance request ID must be between 1 and $MAX_REQUEST_ID_LENGTH characters"
        }
        require(payload.text.none(::isDisallowedTextCharacter)) {
            "Text acceptance text contains a disallowed character"
        }
        require(payload.requestId.none(::isDisallowedTextCharacter)) {
            "Text acceptance request ID contains a disallowed character"
        }
        val now = Clock.System.now()
        require(payload.expiresAt > now) { "Text acceptance request has expired" }
        require(payload.expiresAt - now <= MAX_VALIDITY) {
            "Text acceptance request validity exceeds $MAX_VALIDITY"
        }
    }

    private fun isDisallowedTextCharacter(character: Char): Boolean =
        (character.isISOControl() && character != '\n') || character in BIDI_CONTROLS

    private fun verify(expected: Payload, text: String?, requestId: String?, expiresAt: String?) {
        validate(expected)
        if (text != expected.text ||
            requestId != expected.requestId ||
            expiresAt != expected.expiresAt.toString()) {
            throw IllegalStateException(
                "Text acceptance transaction response does not match the request"
            )
        }
    }

    private val REQUIRED_FIELDS = setOf("text", "requestId", "expiresAt")
    private val BIDI_CONTROLS = setOf(
        '\u061c', '\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
        '\u2066', '\u2067', '\u2068', '\u2069'
    )

    private const val REQUEST_ID_NUM_BYTES = 16
    private const val SAMPLE_TEXT =
        "I accept contract number PLUMB-2026-0042 from Acme Plumbing Ltd.\n" +
        "\n" +
        "Scope of work: repair the kitchen sink trap, replace the flexible hose, and test the " +
        "drain for leaks.\n" +
        "\n" +
        "Price: 2,000 Rupees, payable on completion. No deposit is required.\n" +
        "\n" +
        "Work date: 16 October 2026, between 09:00 and 12:00. If the contractor cannot attend, " +
        "we will agree a new date in writing.\n" +
        "\n" +
        "The contractor will protect the work area, remove all replaced parts, and clean up on " +
        "completion.\n" +
        "\n" +
        "Any additional parts or labour require my approval before they are installed or " +
        "performed.\n" +
        "\n" +
        "The contractor confirms that the work will be performed by a qualified plumber and " +
        "that the repaired drain will be free from leaks for 30 days.\n" +
        "\n" +
        "I may cancel this agreement before the work date without charge. Cancelling on the " +
        "work date may incur a call-out fee of 500 Rupees.\n" +
        "\n" +
        "Payment: I will pay by bank transfer within 2 business days of completion. Late " +
        "payment may incur a 2% monthly charge.\n" +
        "\n" +
        "Access: I will provide safe access to the kitchen sink and a working power outlet. The " +
        "contractor will bring all tools and materials needed for the work.\n" +
        "\n" +
        "Changes: Any change to the scope, price, or date must be agreed in writing before the " +
        "change is made.\n" +
        "\n" +
        "Liability: The contractor is insured for accidental damage caused during the work. " +
        "Pre-existing faults are excluded.\n" +
        "\n" +
        "This acceptance covers the work, price, and date described above and no other work. " +
        "It does not create an ongoing maintenance contract."

    @OptIn(ExperimentalSerializationApi::class)
    private val jsonFormat = Json {
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
        ignoreUnknownKeys = false
    }
}