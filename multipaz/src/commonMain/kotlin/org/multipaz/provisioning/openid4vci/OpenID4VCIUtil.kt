package org.multipaz.provisioning.openid4vci

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.webtoken.buildJwt
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.util.toBase64Url
import kotlin.random.Random

internal object OpenID4VCIUtil {
    suspend fun generateDPoP(
        dpopKey: AsymmetricKey,
        clientId: String,
        requestUrl: String,
        dpopNonce: String?,
        accessToken: String? = null,
        random: Random = Crypto.secureRandom
    ): String {
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
            put("jti", random.nextBytes(15).toBase64Url())
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
     * @param random random provider to use (defaults to [Crypto.secureRandom])
     * @return client attestation proof-of-possession JWT
     */
    suspend fun createWalletAttestationPoP(
        clientId: String,
        key: AsymmetricKey,
        authenticationServerIdentifier: String,
        challenge: String?,
        random: Random = Crypto.secureRandom
    ): String = buildJwt(
            type = "oauth-client-attestation-pop+jwt",
            key = key
        ) {
            put("iss", clientId)
            put("aud", authenticationServerIdentifier)
            put("jti", random.nextBytes(15).toBase64Url())
            if (challenge != null) {
                put("challenge", challenge)
            }
        }
}