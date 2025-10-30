package org.multipaz.openid4vci.util

import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.provisioning.SecretCodeRequest
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.getBaseUrl
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

suspend fun generatePreauthorizedOffer(
    offerSchema: String,
    id: String,
    state: IssuanceState,
    expiresIn: Duration = 100.days
): String {
    val preauthCode = idToCode(OpaqueIdType.PRE_AUTHORIZED, id, expiresIn)
    return "$offerSchema://?credential_offer=" + buildJsonObject {
        put("credential_issuer", BackendEnvironment.getBaseUrl())
        putJsonArray("credential_configuration_ids") {
            add(state.configurationId)
        }
        putJsonObject("grants") {
            putJsonObject("urn:ietf:params:oauth:grant-type:pre-authorized_code") {
                put("pre-authorized_code", preauthCode)
                val txCodeSpec = state.txCodeSpec
                if (txCodeSpec != null) {
                    putJsonObject("tx_code") {
                        put("input_mode", if (txCodeSpec.isNumeric) "numeric" else "text")
                        put("length", txCodeSpec.length)
                        put("description", txCodeSpec.description)
                    }
                }
            }
        }
    }.toString().encodeURLParameter()
}

fun parseTxKind(txKind: String?, txPrompt: String?): SecretCodeRequest? {
    if (txKind == null || txKind == "none") {
        return null
    }
    val txNumeric = txKind.startsWith("n")
    val txLength = txKind.substring(1).toInt()
    return SecretCodeRequest(
        description = txPrompt ?: "Transaction Code",
        isNumeric = txNumeric,
        length = txLength
    )
}

private const val NUMERIC_ALPHABET = "0123456789"
private const val ALPHANUMERIC_ALPHABET = "23456789ABCDEFGHJKLMNPRSTUVWXYZ"  // skip 0/O/Q, 1/I

fun SecretCodeRequest.generateRandom(): String {
    val alphabet = if (isNumeric) NUMERIC_ALPHABET else ALPHANUMERIC_ALPHABET
    val code = StringBuilder()
    val length = this.length ?: 6
    for (i in 0..<length) {
        code.append(alphabet[Random.Default.nextInt(alphabet.length)])
    }
    return code.toString()
}
