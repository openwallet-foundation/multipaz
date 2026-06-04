package org.multipaz.brewery.server

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.server.common.getBaseUrl
import org.multipaz.util.Logger
import org.multipaz.verifier.customization.VerifierAssistant
import org.multipaz.verifier.customization.VerifierPresentment
import java.util.UUID
import kotlin.time.Clock

private const val TAG = "BreweryHandler"

// ---------------------------------------------------------------------------
// /brewery/checkout handler
// ---------------------------------------------------------------------------

/**
 * Receives `{productName, price}` and returns `{dcql, transaction_data}` for the browser
 * to pass directly to `multipazVerifyCredentials()`.
 */
suspend fun breweryCheckout(call: ApplicationCall) {
    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
    val productName = body["productName"]?.jsonPrimitive?.contentOrNull
        ?: throw InvalidRequestException("'productName' is missing")
    val priceStr = body["price"]?.jsonPrimitive?.contentOrNull
        ?: throw InvalidRequestException("'price' is missing")
    val price = priceStr.toDoubleOrNull()
        ?: throw InvalidRequestException("'price' is not a number: $priceStr")

    val transactionData = buildJsonArray {
        add(buildJsonObject {
            put("type", PaymentTransaction.identifier)
            put("credential_ids", buildJsonArray { add(JsonPrimitive("payment")) })
            put("payload", buildJsonObject {
                put("transaction_id", UUID.randomUUID().toString())
                put("payee", buildJsonObject {
                    put("name", "Utopia Brewery")
                })
                put("amount", JsonPrimitive(price))
                put("currency", "USD")
            })
        })
    }
    Logger.i(TAG, "Checkout for '$productName' at $price USD")

    val responsePayload = buildJsonObject {
        put("dcql", BREWERY_DCQL_QUERY)
        put("protocols", buildJsonArray { add(JsonPrimitive("openid4vp-v1")) })
        put("transaction_data", transactionData)
    }
    call.respondText(responsePayload.toString(), ContentType.Application.Json)
}

// ---------------------------------------------------------------------------
// DCQL query — static; parsed once.
// ---------------------------------------------------------------------------
//
// Per credential:
//   - id: stable key used to look the credential up in the response.
//   - format/meta: pin to the mdoc doctype.
//   - claims: every age-related field the credential might carry, each with an id used by
//     claim_sets to express "any of these is sufficient".
//   - claim_sets: ordered preferences. age_over_18 first (the definitive 18+ flag),
//     then age_over_21 (positive signal only — false does not imply <18, see CheckAgeTest),
//     then age_in_years, then birth_date as the last-resort fallback.
// The "payment" credential has no claim_sets — all four fields are required for the DPC check.

private val BREWERY_DCQL_QUERY: JsonObject = Json.parseToJsonElement("""
{
  "credentials": [
    {
      "id": "photoid",
      "format": "mso_mdoc",
      "meta": { "doctype_value": "org.iso.23220.photoid.1" },
      "claims": [
        { "id": "given_name",   "path": ["org.iso.23220.1",         "given_name"] },
        { "id": "family_name",  "path": ["org.iso.23220.1",         "family_name"] },
        { "id": "birth_date",   "path": ["org.iso.23220.1",         "birth_date"] },
        { "id": "age_in_years", "path": ["org.iso.23220.1",         "age_in_years"] },
        { "id": "age_over_18",  "path": ["org.iso.23220.photoid.1", "age_over_18"] }
      ],
      "claim_sets": [
        ["age_over_18"],
        ["age_in_years"],
        ["birth_date"]
      ]
    },
    {
      "id": "mdl",
      "format": "mso_mdoc",
      "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
      "claims": [
        { "id": "given_name",   "path": ["org.iso.18013.5.1", "given_name"] },
        { "id": "family_name",  "path": ["org.iso.18013.5.1", "family_name"] },
        { "id": "birth_date",   "path": ["org.iso.18013.5.1", "birth_date"] },
        { "id": "age_in_years", "path": ["org.iso.18013.5.1", "age_in_years"] },
        { "id": "age_over_21",  "path": ["org.iso.18013.5.1", "age_over_21"] },
        { "id": "age_over_18",  "path": ["org.iso.18013.5.1", "age_over_18"] }
      ],
      "claim_sets": [
        ["age_over_18"],
        ["age_over_21"],
        ["age_in_years"],
        ["birth_date"]
      ]
    },
    {
      "id": "eupid",
      "format": "mso_mdoc",
      "meta": { "doctype_value": "eu.europa.ec.eudi.pid.1" },
      "claims": [
        { "id": "given_name",   "path": ["eu.europa.ec.eudi.pid.1", "given_name"] },
        { "id": "family_name",  "path": ["eu.europa.ec.eudi.pid.1", "family_name"] },
        { "id": "birth_date",   "path": ["eu.europa.ec.eudi.pid.1", "birth_date"] },
        { "id": "age_in_years", "path": ["eu.europa.ec.eudi.pid.1", "age_in_years"] },
        { "id": "age_over_21",  "path": ["eu.europa.ec.eudi.pid.1", "age_over_21"] },
        { "id": "age_over_18",  "path": ["eu.europa.ec.eudi.pid.1", "age_over_18"] }
      ],
      "claim_sets": [
        ["age_over_18"],
        ["age_over_21"],
        ["age_in_years"],
        ["birth_date"]
      ]
    },
    {
      "id": "aadhaar",
      "format": "mso_mdoc",
      "meta": { "doctype_value": "in.gov.uidai.aadhaar.1" },
      "claims": [
        { "id": "resident_name", "path": ["in.gov.uidai.aadhaar.1", "resident_name"] },
        { "id": "age_above18",   "path": ["in.gov.uidai.aadhaar.1", "age_above18"] },
        { "id": "birth_date",    "path": ["in.gov.uidai.aadhaar.1", "birth_date"] }
      ],
      "claim_sets": [
        ["age_above18"],
        ["birth_date"]
      ]
    },
    {
      "id": "payment",
      "format": "mso_mdoc",
      "meta": { "doctype_value": "org.multipaz.payment.sca.1" },
      "claims": [
        { "path": ["org.multipaz.payment.sca.1", "issuer_name"] },
        { "path": ["org.multipaz.payment.sca.1", "masked_account_reference"] },
        { "path": ["org.multipaz.payment.sca.1", "holder_name"] },
        { "path": ["org.multipaz.payment.sca.1", "expiry_date"] }
      ]
    }
  ],
  "credential_sets": [
    {
      "purpose": "Age verification for alcohol purchase",
      "options": [
        ["photoid"],
        ["mdl"],
        ["eupid"],
        ["aadhaar"]
      ]
    },
    {
      "purpose": "Payment",
      "options": [
        ["payment"]
      ]
    }
  ]
}
""".trimIndent()).jsonObject

// ---------------------------------------------------------------------------
// VerifierAssistant — runs business logic after credential verification
// ---------------------------------------------------------------------------

class BreweryVerifierAssistant : VerifierAssistant {
    /** Accept all requests unchanged. */
    override suspend fun processRequest(request: JsonObject): VerifierAssistant.ExpandedRequest? = null

    /**
     * After credential verification succeeds, check age and DPC validity.
     * Returns a custom JsonObject with `approved`, `holderName`, `issuerName`, and optionally `error`.
     */
    override suspend fun processResponse(presentment: VerifierPresentment): JsonObject {
        val response = presentment.response
        Logger.i(TAG, "Credential response keys: ${response.keys}")
        Logger.i(TAG, "Credential response: $response")

        // Find which ID credential was presented
        val (idKey, idClaims) = findIdClaims(response)
            ?: return buildJsonObject {
                put("approved", false)
                put("error", "No recognized identity credential was presented")
            }

        // Age verification
        if (!checkAge(idClaims)) {
            Logger.i(TAG, "Age check failed for credential '$idKey'")
            return buildJsonObject {
                put("approved", false)
                put("error", "Age verification failed: must be 18 or older to purchase alcohol")
            }
        }

        // DPC verification — credential_sets requires a "payment" entry, so its absence is a
        // contract violation, not a user-facing error.
        check(response["payment"] != null) { "DCQL guaranteed a 'payment' entry but none was returned" }
        val paymentEntry = response["payment"]!!.jsonObject
        val paymentClaims = paymentEntry["claims"]?.jsonObject
        val holderName = paymentClaims?.get("holder_name")?.jsonPrimitive?.contentOrNull
        val issuerName = paymentClaims?.get("issuer_name")?.jsonPrimitive?.contentOrNull

        if (holderName.isNullOrBlank() || issuerName.isNullOrBlank()) {
            Logger.i(TAG, "DPC check failed: holder_name='$holderName' issuer_name='$issuerName'")
            return buildJsonObject {
                put("approved", false)
                put("error", "Payment credential is missing required fields (holder_name or issuer_name).")
            }
        }

        // Stub: notify records server (fire-and-forget; errors are logged)
        postToRecordsServer(holderName, issuerName)

        Logger.i(TAG, "Purchase approved for $holderName via $issuerName")
        return buildJsonObject {
            put("approved", true)
            put("holderName", holderName)
            put("issuerName", issuerName)
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Returns the first recognized ID credential's key and its claims object,
 * or null if none is found.
 */
private fun findIdClaims(response: JsonObject): Pair<String, JsonObject>? {
    for (id in listOf("photoid", "mdl", "eupid", "aadhaar")) {
        val claims = response[id]?.jsonObject?.get("claims")?.jsonObject
        if (claims != null) return id to claims
    }
    return null
}

/**
 * Returns true if the age claims indicate the holder is 18 or older.
 *
 * Priority:
 * 1. age_over_18 / age_above18 (Boolean) — definitive 18+ check
 * 2. age_over_21 (Boolean) — true implies 18+; false does NOT mean under 18, so only used as a
 *    positive signal when no age_over_18 claim is present
 * 3. age_in_years (Integer >= 18)
 * 4. birth_date (ISO date string)
 */
fun checkAge(claims: JsonObject): Boolean {
    // Definitive 18+ flags — check these first per the verification flow spec
    claims["age_over_18"]?.jsonPrimitive?.booleanOrNull?.let { return it }
    claims["age_above18"]?.jsonPrimitive?.booleanOrNull?.let { return it }

    // age_over_21 = true implies 18+, but age_over_21 = false does NOT mean under 18
    // (e.g. a 20-year-old has age_over_21=false yet is still over 18)
    claims["age_over_21"]?.jsonPrimitive?.booleanOrNull?.let { if (it) return true }

    // Integer age
    claims["age_in_years"]?.jsonPrimitive?.intOrNull?.let { return it >= 18 }

    // Birth date fallback
    claims["birth_date"]?.jsonPrimitive?.contentOrNull?.let { dateStr ->
        return try {
            val birthDate = LocalDate.parse(dateStr)
            val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
            val age = today.year - birthDate.year -
                if (today.month < birthDate.month ||
                    (today.month == birthDate.month && today.day < birthDate.day)
                ) 1 else 0
            age >= 18
        } catch (e: Exception) {
            Logger.e(TAG, "Could not parse birth_date '$dateStr'", e)
            false
        }
    }

    // No usable age claim found
    return false
}

/**
 * Stub: POST purchase info to the records server.
 * The OOB interface is not yet available; errors are logged and swallowed.
 */
private suspend fun postToRecordsServer(holderName: String, issuerName: String) {
    try {
        val recordsUrl = BackendEnvironment.getBaseUrl() + "/records/purchase"
        Logger.i(TAG, "Stub: would POST purchase to $recordsUrl for $holderName via $issuerName")
        // TODO: implement actual HTTP POST once the records server OOB interface is available
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "Records server stub call failed", e)
    }
}
