package org.multipaz.provisioning.openid4vci

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.jwt.JwtCheck
import org.multipaz.jwt.validateJwt
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.storage.Storage
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.Test

class OpenIDBackendUtilTest {
    @Test
    fun testClientAssertion() = runTest {
        val signingKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val env = TestBackendEnvironment("client", signingKey.publicKey)
        withContext(env) {
            val assertionJwt = OpenID4VCIBackendUtil.createJwtClientAssertion(
                signingKey = AsymmetricKey.NamedExplicit("client", signingKey),
                clientId = CLIENT_ID,
                authorizationServerIdentifier = "http://example.com",
            )
            validateJwt(
                jwt = assertionJwt,
                jwtName = "client_assertion",
                publicKey = null,
                checks = mapOf(
                    JwtCheck.TRUST to "fake_trust",  // where to find CA
                    JwtCheck.JTI to CLIENT_ID,
                    JwtCheck.SUB to CLIENT_ID,
                    JwtCheck.ISS to CLIENT_ID,
                    JwtCheck.AUD to "http://example.com"
                )
            )
        }
    }

    @Test
    fun testWalletAttestation() = runTest {
        val signingKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val attestedKey = Crypto.createEcPrivateKey(EcCurve.P256).publicKey
        val env = TestBackendEnvironment("wallet", signingKey.publicKey)
        withContext(env) {
            val attestationJwt = OpenID4VCIBackendUtil.createWalletAttestation(
                signingKey = AsymmetricKey.NamedExplicit("wallet", signingKey),
                clientId = CLIENT_ID,
                attestationIssuer = "wallet",
                attestedKey = attestedKey,
                nonce = NONCE,
                walletName = "test",
                walletLink = "http://example.com"
            )
            val attestationBody = validateJwt(
                jwt = attestationJwt,
                jwtName = "Client Attestation",
                publicKey = null,
                checks = mapOf(
                    JwtCheck.TRUST to "fake_trust",
                    JwtCheck.TYP to "oauth-client-attestation+jwt",
                    JwtCheck.SUB to CLIENT_ID
                )
            )
            val key = EcPublicKey.fromJwk(attestationBody["cnf"]!!.jsonObject["jwk"]!!.jsonObject)
            Assert.assertEquals(attestedKey, key)
        }
    }

    @Test
    fun testKeyAttestation() = runTest {
        val signingKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val env = TestBackendEnvironment("key", signingKey.publicKey)
        withContext(env) {
            val ephemeralStorage = EphemeralStorage()
            val softwareSecureArea = SoftwareSecureArea.create(ephemeralStorage)
            val attestedKeyInfo = softwareSecureArea.createKey(
                alias = null,
                createKeySettings = CreateKeySettings()
            )
            val attestationJwt = OpenID4VCIBackendUtil.createJwtKeyAttestation(
                signingKey = AsymmetricKey.NamedExplicit("key", signingKey),
                attestationIssuer = "key",
                keysToAttest = listOf(attestedKeyInfo.attestation),
                challenge = NONCE
            )
            val body = validateJwt(
                jwt = attestationJwt,
                jwtName = "Key attestation",
                publicKey = null,
                checks = mapOf(
                    JwtCheck.TYP to "key-attestation+jwt",
                    JwtCheck.TRUST to "fake_trust"
                )
            )
            val key = EcPublicKey.fromJwk(body["attested_keys"]!!.jsonArray[0].jsonObject)
            Assert.assertEquals(attestedKeyInfo.publicKey, key)
        }
    }

    inner class TestBackendEnvironment(
        val trustedKeyId: String,
        val trustedKey: EcPublicKey
    ): BackendEnvironment, Configuration {
        val storage = EphemeralStorage()

        override fun <T : Any> getInterface(clazz: KClass<T>): T {
            return clazz.cast(when (clazz) {
                Configuration::class -> this
                Storage::class -> storage
                else -> throw IllegalArgumentException("no such class available: ${clazz.simpleName}")
            })
        }

        override fun getValue(key: String): String? {
            if (key == "fake_trust") {
                return buildJsonObject {
                    put(trustedKeyId, trustedKey.toJwk())
                }.toString()
            }
            return null
        }
    }

    companion object {
        const val CLIENT_ID = "testClientId"
        const val NONCE = "myNonce"
    }
}