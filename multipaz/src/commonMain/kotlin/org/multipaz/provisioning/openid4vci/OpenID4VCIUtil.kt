package org.multipaz.provisioning.openid4vci

import io.ktor.http.Url
import io.ktor.http.protocolWithAuthority
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.jwt.buildJwt
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.util.toBase64Url
import kotlin.random.Random

internal object OpenID4VCIUtil {
    const val TAG = "OpenidUtil"

    private val keyCreationMutex = Mutex()

    private suspend fun ensureKey(secureArea: SecureArea, alias: String) {
        val secureArea = BackendEnvironment.getInterface(SecureAreaProvider::class)!!.get()
        try {
            secureArea.getKeyInfo(alias)
        } catch (_: Exception) {
            keyCreationMutex.withLock {
                try {
                    secureArea.getKeyInfo(alias)
                } catch (_: Exception) {
                    secureArea.createKey(alias, CreateKeySettings())
                }
            }
        }
    }

    suspend fun generateDPoP(
        clientId: String,
        requestUrl: String,
        dpopNonce: String?,
        accessToken: String? = null
    ): String {
        val secureArea = BackendEnvironment.getInterface(SecureAreaProvider::class)!!.get()
        val dpopAlias = "dpop:$clientId"
        ensureKey(secureArea, dpopAlias)
        val dpopKey = AsymmetricKey.anonymous(secureArea, dpopAlias)
        return buildJwt(
            type = "dpop+jwt",
            key = dpopKey,
            header = {
                put(
                    "jwk",
                    dpopKey.publicKey.toJwk(additionalClaims = buildJsonObject {
                        put(
                            "kid",
                            JsonPrimitive(clientId)
                        )
                    })
                )
            }
        ) {
            put("htm", "POST")
            put("htu", requestUrl)
            if (dpopNonce != null) {
                put("nonce", dpopNonce)
            }
            put("jti", Random.Default.nextBytes(15).toBase64Url())
            if (accessToken != null) {
                val hash = Crypto.digest(Algorithm.SHA256, accessToken.encodeToByteArray())
                put("ath", hash.toBase64Url())
            }
        }
    }

    suspend fun createClientAssertion(authorizationServerIdentifier: String): String {
        val backend = BackendEnvironment.getInterface(OpenID4VCIBackend::class)!!
        return backend.createJwtClientAssertion(authorizationServerIdentifier)
    }

    /**
     * Generates Client Attestation proof-of-possession JWT.
     *
     * See OpenID4VCI specification, Appendix E. Wallet Attestations in JWT format.
     *
     * @param clientId OpenID `client_id` value
     * @param key client private key (that was previously attested by Wallet Attestation)
     * @param authenticationServerIdentifier authentication server identifier (URL)
     * @param challenge optional value for `challenge` claim
     * @return client attestation proof-of-possession JWT
     */
    suspend fun createWalletAttestationPoP(
        clientId: String,
        key: AsymmetricKey,
        authenticationServerIdentifier: String,
        challenge: String?
    ): String = buildJwt(
            type = "oauth-client-attestation-pop+jwt",
            key = key
        ) {
            put("iss", clientId)
            put("aud", authenticationServerIdentifier)
            put("jti", Random.Default.nextBytes(15).toBase64Url())
            if (challenge != null) {
                put("challenge", challenge)
            }
        }
}