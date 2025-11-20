package org.multipaz.openid4vci.request

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.datetime.LocalDate
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.util.fromBase64Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.jwt.ChallengeInvalidException
import org.multipaz.openid4vci.credential.CredentialFactory
import org.multipaz.openid4vci.credential.Openid4VciFormat
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.openid4vci.util.OpaqueIdType
import org.multipaz.openid4vci.util.authorizeWithDpop
import org.multipaz.openid4vci.util.codeToId
import org.multipaz.openid4vci.util.extractAccessToken
import org.multipaz.openid4vci.util.getSystemOfRecordUrl
import org.multipaz.server.getBaseUrl
import org.multipaz.jwt.JwtCheck
import org.multipaz.util.Logger
import org.multipaz.jwt.validateJwt
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.openid4vci.util.respondWithNewDPoPNonce
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Issues a credential based on DPoP authentication with access token.
 */
suspend fun credential(call: ApplicationCall) {
    val accessToken = extractAccessToken(call.request)
    val id = codeToId(OpaqueIdType.ACCESS_TOKEN, accessToken)
    val state = IssuanceState.getIssuanceState(id)
    try {
        authorizeWithDpop(
            call.request,
            state.dpopKey!!,
            state.clientId!!,
            accessToken
        )
    } catch (_: ChallengeInvalidException) {
        respondWithNewDPoPNonce(call)
        return
    }
    val byOfferId = CredentialFactory.getRegisteredFactories().byOfferId

    val requestString = call.receiveText()
    val json = Json.parseToJsonElement(requestString) as JsonObject
    val format = Openid4VciFormat.fromJson(json)
    val factory = byOfferId.values.find { factory ->
        (format == null || factory.format == format) && factory.scope == state.scope
    }
    if (factory == null) {
        throw IllegalStateException(
            "No credential can be created for scope '${state.scope}' and the given format")
    }

    state.purgeExpiredCredentials()

    val credentialData = readSystemOfRecord(state)
    val now = Clock.System.now()
    val credentialPlaceholderExpiration = now + 3.minutes
    val statusListUrl = BackendEnvironment.getBaseUrl() + "/status_list"

    if (factory.cryptographicBindingMethods.isEmpty()) {
        val credentialState = CredentialState(
            issuanceStateId = id,
            keyId = null,
            creation = now,
            expiration = credentialPlaceholderExpiration
        )
        val credentialIndex = CredentialState.recordNewCredential(credentialState)
        // Keyless credential: no need for proof/proofs parameter.
        val minted = factory.mint(credentialData, null, credentialIndex, statusListUrl)
        credentialState.creation = minted.creation
        credentialState.expiration = minted.expiration
        CredentialState.updateCredential(credentialIndex, credentialState)
        state.credentials.add(IssuanceState.CredentialData(credentialIndex, minted.expiration))
        // Do not extend session expiration
        IssuanceState.updateIssuanceState(id, state, expiration = null)
        call.respondText(
            text = buildJsonObject {
                putJsonArray("credentials") {
                    addJsonObject {
                        put("credential", minted.credential)
                    }
                }
            }.toString(),
            contentType = ContentType.Application.Json
        )
        return
    }

    val proofsObj = json["proofs"]?.jsonObject
    val proofs: JsonArray
    val proofType: String
    if (proofsObj == null) {
        val proof = json["proof"]
            ?: throw InvalidRequestException("neither 'proof' or 'proofs' parameter provided")
        proofType = proof.jsonObject["proof_type"]?.jsonPrimitive?.content!!
        proofs = buildJsonArray { add(proof.jsonObject[proofType]!!) }
    } else {
        proofType = if (proofsObj.containsKey("attestation")) {
            // prefer attestation
            "attestation"
        } else if (proofsObj.containsKey("jwt")) {
            "jwt"
        } else {
            throw InvalidRequestException("Unsupported proof type")
        }
        proofs = proofsObj[proofType]!!.jsonArray
        if (proofs.isEmpty()) {
            throw InvalidRequestException("'proofs' is empty")
        }
    }

    val authenticationKeysAndIds = when (proofType) {
        "attestation" -> {
            proofs.flatMap { proof ->
                val body = validateJwt(
                        jwt = proof.jsonPrimitive.content,
                        jwtName = "Key attestation",
                        publicKey = null,
                        checks = mapOf(
                            JwtCheck.TYP to "key-attestation+jwt",
                            JwtCheck.TRUST to "trusted_key_attestations"
                        )
                    )
                validateAndConsumeCredentialChallenge(body["nonce"]!!.jsonPrimitive.content)
                body["attested_keys"]!!.jsonArray.map { key ->
                    val publicKey = EcPublicKey.fromJwk(key.jsonObject)
                    KeyAndId(
                        key = publicKey,
                        id = key.jsonObject["kid"]?.jsonPrimitive?.content ?: keyHash(publicKey)
                    )
                }
            }
        }
        "jwt" -> {
            if (factory.requireKeyAttestation) {
                throw InvalidRequestException("jwt proof cannot be used for this credential")
            }
            val baseUrl = BackendEnvironment.getBaseUrl()
            var expectedNonce: String? = null
            proofs.map { proof ->
                val jwt = proof.jsonPrimitive.content
                val parts = jwt.split(".")
                if (parts.size != 3) {
                    throw InvalidRequestException("invalid value for 'proof.jwt' parameter")
                }
                val head = Json.parseToJsonElement(String(parts[0].fromBase64Url())) as JsonObject
                val jwk = head["jwk"]!!.jsonObject
                val authenticationKey = EcPublicKey.fromJwk(jwk)
                val body = validateJwt(
                    jwt = proof.jsonPrimitive.content,
                    jwtName = "Key attestation",
                    publicKey = authenticationKey,
                    checks = mapOf(
                        JwtCheck.TYP to "openid4vci-proof+jwt",
                        JwtCheck.AUD to baseUrl
                    )
                )
                val nonce = body["nonce"]!!.jsonPrimitive.content
                if (expectedNonce == null) {
                    expectedNonce = nonce
                    validateAndConsumeCredentialChallenge(nonce)
                } else if (nonce != expectedNonce) {
                    throw InvalidRequestException("nonce mismatch")
                }
                KeyAndId(
                    key = authenticationKey,
                    id = head["kid"]?.jsonPrimitive?.content
                        ?: jwk["kid"]?.jsonPrimitive?.content
                        ?: keyHash(authenticationKey)
                )
            }
        }
        else -> {
            throw InvalidRequestException("unsupported proof type")
        }
    }

    val credentials = authenticationKeysAndIds.map { (key, keyId) ->
        // TODO: in the long run, keys should come with `kid` from the client
        val credentialState = CredentialState(
            issuanceStateId = id,
            keyId = keyId,
            creation = now,
            expiration = credentialPlaceholderExpiration
        )
        val credentialIndex = CredentialState.recordNewCredential(credentialState)
        val minted = factory.mint(credentialData, key, credentialIndex, statusListUrl)
        credentialState.creation = minted.creation
        credentialState.expiration = minted.expiration
        CredentialState.updateCredential(credentialIndex, credentialState)
        state.credentials.add(IssuanceState.CredentialData(credentialIndex, minted.expiration))
        minted.credential
    }

    // Do not extend session expiration
    IssuanceState.updateIssuanceState(id, state, expiration = null)

    val result =
        buildJsonObject {
            putJsonArray("credentials") {
                for (credential in credentials) {
                    addJsonObject {
                        put("credential", credential)
                    }
                }
            }
        }
    call.respondText(
        text = Json.encodeToString(result),
        contentType = ContentType.Application.Json
    )
}

private const val TAG = "credential"

private suspend fun readSystemOfRecord(state: IssuanceState): DataItem {
    val systemOfRecordAccess = state.systemOfRecordAccess!!
    val systemOfRecordUrl = BackendEnvironment.getSystemOfRecordUrl()
    if (systemOfRecordUrl == null) {
        // Running without System of Record (demo/dev mode). Expect basic data encoded
        // as fake access token
        val (givenName, familyName, birthDate) = systemOfRecordAccess.accessToken.split(":")
        return buildCborMap {
            putCborMap("core") {
                put("given_name", givenName)
                put("family_name", familyName)
                put("birth_date", LocalDate.parse(birthDate).toDataItemFullDate())
            }
            putCborMap("records") {
                putCborMap("mDL") {
                    putCborMap("") {}
                }
                putCborMap("naturalization") {
                    putCborMap("") {}
                }
                putCborMap("movie") {
                    putCborMap("") {}
                }
                putCborMap("wholesale") {
                    putCborMap("") {}
                }
            }
        }
    } else {
        val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
        val request = httpClient.get("$systemOfRecordUrl/data") {
            headers {
                bearerAuth(systemOfRecordAccess.accessToken)
            }
        }
        if (request.status != HttpStatusCode.OK) {
            val text = request.readBytes().decodeToString()
            Logger.e(TAG, "Error accessing data from the System of Record: $text")
            throw IllegalStateException("Could not access data from System of Record")
        }
        return Cbor.decode(request.readBytes())
    }
}

private fun keyHash(key: EcPublicKey): String =
    key.toJwkThumbprint(Algorithm.SHA256).toByteArray().toBase64Url()

private data class KeyAndId(val key: EcPublicKey, val id: String)