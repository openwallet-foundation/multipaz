package org.multipaz.securearea.cloud

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.io.bytestring.ByteString
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.MlDsaPublicKey
import org.multipaz.crypto.MlDsaSignature
import org.multipaz.crypto.HybridPublicKey
import org.multipaz.crypto.HybridSignature
import org.multipaz.crypto.MlKemPublicKey
import org.multipaz.crypto.RsaPublicKey
import org.multipaz.crypto.RsaSignature
import org.multipaz.crypto.SecretKey
import org.multipaz.crypto.Signature
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.device.AndroidKeystoreSecurityLevel
import org.multipaz.device.DeviceCheck
import org.multipaz.rpc.auth.ClientRegistrationStub
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcAuthIssuerAssertion
import org.multipaz.rpc.handler.RpcDispatcher
import org.multipaz.rpc.handler.RpcDispatcherAuth
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import java.security.Security
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CloudSecureAreaProtocolTest {

    @Before
    fun setup() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    private suspend fun createServer(clock: () -> Instant = { Instant.fromEpochMilliseconds(0) }): CloudSecureAreaServer {
        val now = Clock.System.now()
        val validUntil = now.plus(DateTimePeriod(years = 5), TimeZone.currentSystemDefault())
        val attestationKeyPrivate = Crypto.createEcPrivateKey(EcCurve.P256)
        val attestationRootKey = AsymmetricKey.ephemeral()
        val attestationRootCert = buildX509Cert(
            publicKey = attestationRootKey.publicKey,
            signingKey = attestationRootKey,
            serialNumber = ASN1Integer(42L),
            subject = X500Name.fromName("CN=Cloud Secure Area Attestation Root"),
            issuer = X500Name.fromName("CN=Cloud Secure Area Attestation Root"),
            validFrom = now,
            validUntil = validUntil
        ) {
            includeSubjectKeyIdentifier()
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            setBasicConstraints(true, 1)
        }
        val attestationKeyCertChain = X509CertChain(
            listOf(
                X509Cert.Builder(
                    publicKey = attestationKeyPrivate.publicKey,
                    signingKey = attestationRootKey,
                    serialNumber = ASN1Integer(1L),
                    subject = X500Name.fromName("CN=Cloud Secure Area Attestation"),
                    issuer = X500Name.fromName("CN=Cloud Secure Area Attestation Root"),
                    validFrom = now,
                    validUntil = validUntil
                )
                    .includeSubjectKeyIdentifier()
                    .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
                    .setBasicConstraints(true, 0)
                    .build(),
                attestationRootCert
            )
        )
        val attestationSigningKey = AsymmetricKey.X509CertifiedExplicit(
            privateKey = attestationKeyPrivate,
            certChain = attestationKeyCertChain
        )

        return CloudSecureAreaServer(
            serverSecureAreaBoundKey = SecretKey(Crypto.secureRandom.nextBytes(32)),
            attestationKey = attestationSigningKey,
            iosReleaseBuild = false,
            iosAppIdentifiers = emptyList(),
            androidGmsAttestation = false,
            androidVerifiedBootGreen = false,
            androidAppSignatureCertificateDigests = emptyList(),
            androidAppPackageNames = emptyList(),
            androidKeystoreSecurityLevel = AndroidKeystoreSecurityLevel.SOFTWARE,
            openid4vciKeyAttestationIssuer = "https://csa.example.org",
            openid4vciKeyAttestationKeyStorage = "ava_van.5",
            openid4vciKeyAttestationUserAuthentication = "ava_van.5",
            openid4vciKeyAttestationUserAuthenticationNoPassphrase = null,
            openid4vciKeyAttestationCertification = "https://example.org/cert.pdf",
            passphraseFailureEnforcer = SimplePassphraseFailureEnforcer(
                lockoutNumFailedAttempts = 3,
                lockoutDuration = 1.minutes,
                clockFunction = clock
            ),
            allowSoftwareAttestation = true
        )
    }

    private class CsaClient(
        val server: CloudSecureAreaServer,
        val passphrase: String
    ) {
        private lateinit var authDispatcher: RpcDispatcher
        private lateinit var registeredStubCbor: ByteArray
        private val rpcSession = RpcAuthClientSession()

        suspend fun register() {
            val secureArea = SoftwareSecureArea.create(EphemeralStorage())
            val registrationStub = ClientRegistrationStub(
                endpoint = "client_registration",
                dispatcher = server.dispatcher,
                notifier = RpcNotifier.SILENT
            )
            val challenge = registrationStub.challenge()
            val attestationResult = DeviceCheck.generateAttestation(secureArea, challenge)
            val clientId = registrationStub.register(attestationResult.deviceAttestation)

            val authIssuer = RpcAuthIssuerAssertion(
                clientId = clientId,
                secureArea = secureArea,
                deviceAttestationId = attestationResult.deviceAttestationId
            )
            authDispatcher = RpcDispatcherAuth(server.dispatcher, authIssuer)

            registeredStubCbor = withContext(rpcSession) {
                val stub = CloudSecureAreaProtocolStub(
                    endpoint = "cloud_secure_area",
                    dispatcher = authDispatcher,
                    notifier = RpcNotifier.SILENT
                )
                stub.register(passphrase)
                stub.toCbor()
            }
        }

        private suspend fun <T> withStub(block: suspend (CloudSecureAreaProtocol) -> T): T {
            return withContext(rpcSession) {
                val stub = CloudSecureAreaProtocolStub.fromCbor(
                    registeredStubCbor,
                    authDispatcher,
                    RpcNotifier.SILENT
                )
                block(stub)
            }
        }

        data class KeyHandle(
            val attestationChain: X509CertChain,
            val keyContext: ByteString,
            val localKey: EcPrivateKey
        )

        suspend fun createKey(
            algorithm: Algorithm,
            passphraseRequired: Boolean = false
        ): KeyHandle = withStub { stub ->
            val validFrom = Clock.System.now()
            val validUntil = validFrom + 30.days
            stub.createKey0(
                CloudKeyConfiguration(
                    algorithm = algorithm,
                    validFrom = validFrom,
                    validUntil = validUntil,
                    passphraseRequired = passphraseRequired,
                    userAuthenticationRequired = false,
                    userAuthenticationTypes = emptySet(),
                    challenge = ByteString(1, 2, 3),
                )
            )
            val localKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val created = stub.createKey1(
                KeyAttestation(
                    publicKey = localKey.publicKey,
                    certChain = null
                )
            )
            KeyHandle(created.remoteKeyAttestation, created.serverState, localKey)
        }

        suspend fun sign(
            key: KeyHandle,
            dataToSign: ByteArray,
            passphrase: String? = null
        ): Signature = withStub { stub ->
            val cloudNonce = stub.sign0(
                dataToSign = ByteString(dataToSign),
                keyContext = key.keyContext
            )
            val dataToSignLocally = Cbor.encode(
                buildCborArray {
                    add(cloudNonce.toByteArray())
                }
            )
            val sig = Crypto.sign(key.localKey, Algorithm.ES256, dataToSignLocally) as EcSignature
            stub.sign1(sig, passphrase)
        }

        suspend fun kemDecapsulate(
            key: KeyHandle,
            ciphertext: ByteArray,
            passphrase: String? = null
        ): ByteString = withStub { stub ->
            val cloudNonce = stub.kemDecapsulate0(
                ciphertext = ByteString(ciphertext),
                keyContext = key.keyContext
            )
            val dataToSignLocally = Cbor.encode(
                buildCborArray {
                    add(cloudNonce.toByteArray())
                }
            )
            val sig = Crypto.sign(key.localKey, Algorithm.ES256, dataToSignLocally) as EcSignature
            stub.kemDecapsulate1(sig, passphrase)
        }

        suspend fun keyAgreement(
            key: KeyHandle,
            otherPublicKey: EcPublicKey,
            passphrase: String? = null
        ): ByteString = withStub { stub ->
            val cloudNonce = stub.keyAgreement0(
                otherPublicKey = otherPublicKey,
                keyContext = key.keyContext
            )
            val dataToSignLocally = Cbor.encode(
                buildCborArray {
                    add(cloudNonce.toByteArray())
                }
            )
            val sig = Crypto.sign(key.localKey, Algorithm.ES256, dataToSignLocally) as EcSignature
            stub.keyAgreement1(sig, passphrase)
        }

        suspend fun checkPassphrase(passphrase: String) = withStub { stub ->
            stub.checkPassphrase(passphrase)
        }
    }

    @Test
    fun testRsaSigning() = runTest {
        val server = createServer()
        val client = CsaClient(server, "testPassphrase")
        client.register()

        for (alg in listOf(Algorithm.RS256_2048, Algorithm.PS256_2048)) {
            val keyHandle = client.createKey(alg)
            val leafCert = keyHandle.attestationChain.certificates[0]
            assertEquals(setOf(X509KeyUsage.DIGITAL_SIGNATURE), leafCert.keyUsage)
            assertTrue(leafCert.publicKey is RsaPublicKey)

            val dataToSign = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            val signature = client.sign(keyHandle, dataToSign)
            assertTrue(signature is RsaSignature)

            Crypto.checkSignature(
                leafCert.publicKey as RsaPublicKey,
                dataToSign,
                alg,
                signature as RsaSignature
            )
        }
    }

    @Test
    fun testMlDsaSigning() = runTest {
        val server = createServer()
        val client = CsaClient(server, "testPassphrase")
        client.register()

        for (alg in listOf(Algorithm.ML_DSA_44, Algorithm.ML_DSA_65, Algorithm.ML_DSA_87)) {
            if (alg !in Crypto.supportedMlDsaAlgorithms) {
                continue
            }
            val keyHandle = client.createKey(alg)
            val leafCert = keyHandle.attestationChain.certificates[0]
            assertEquals(setOf(X509KeyUsage.DIGITAL_SIGNATURE), leafCert.keyUsage)
            assertTrue(leafCert.publicKey is MlDsaPublicKey)

            val dataToSign = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1)
            val signature = client.sign(keyHandle, dataToSign)
            assertTrue(signature is MlDsaSignature)

            Crypto.checkSignature(
                leafCert.publicKey as MlDsaPublicKey,
                dataToSign,
                alg,
                signature as MlDsaSignature
            )
        }
    }

    @Test
    fun testHybridSigning() = runTest {
        val server = createServer()
        val client = CsaClient(server, "testPassphrase")
        client.register()

        for (alg in Crypto.supportedHybridSigningAlgorithms) {
            val keyHandle = client.createKey(alg)
            val leafCert = keyHandle.attestationChain.certificates[0]
            assertEquals(setOf(X509KeyUsage.DIGITAL_SIGNATURE), leafCert.keyUsage)
            assertTrue(leafCert.publicKey is HybridPublicKey)

            val dataToSign = "Hybrid CSA signing $alg".encodeToByteArray()
            val signature = client.sign(keyHandle, dataToSign)
            assertTrue(signature is HybridSignature)

            Crypto.checkSignature(
                leafCert.publicKey as HybridPublicKey,
                dataToSign,
                alg,
                signature as HybridSignature
            )
        }
    }

    @Test
    fun testMlKemDecapsulation() = runTest {
        val server = createServer()
        val client = CsaClient(server, "testPassphrase")
        client.register()

        for (alg in listOf(Algorithm.ML_KEM_512, Algorithm.ML_KEM_768, Algorithm.ML_KEM_1024)) {
            if (alg !in Crypto.supportedMlKemAlgorithms) {
                continue
            }
            val keyHandle = client.createKey(alg)
            val leafCert = keyHandle.attestationChain.certificates[0]
            assertEquals(setOf(X509KeyUsage.KEY_ENCIPHERMENT), leafCert.keyUsage)
            assertTrue(leafCert.publicKey is MlKemPublicKey)

            val kemResult = Crypto.kemEncapsulate(leafCert.publicKey as MlKemPublicKey)
            val sharedSecret = client.kemDecapsulate(keyHandle, kemResult.ciphertext)
            assertArrayEquals(kemResult.sharedSecret.encoded, sharedSecret.toByteArray())
            kemResult.close()
        }
    }

    @Test
    fun testPassphraseProtectionWithMlKemAndRsa() = runTest {
        var serverTime = Instant.fromEpochMilliseconds(0)
        val server = createServer { serverTime }
        val client = CsaClient(server, "correctPassphrase")
        client.register()

        // Test with ML-KEM if supported, otherwise RS256
        if (Algorithm.ML_KEM_768 in Crypto.supportedMlKemAlgorithms) {
            val kemKeyHandle = client.createKey(Algorithm.ML_KEM_768, passphraseRequired = true)
            val kemResult = Crypto.kemEncapsulate(
                kemKeyHandle.attestationChain.certificates[0].publicKey as MlKemPublicKey
            )

            // Wrong passphrase
            try {
                client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "wrongPassphrase")
                fail("Expected CloudWrongPassphraseException")
            } catch (_: CloudWrongPassphraseException) {
                // Expected
            }

            // Correct passphrase
            val sharedSecret = client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "correctPassphrase")
            assertArrayEquals(kemResult.sharedSecret.encoded, sharedSecret.toByteArray())
            kemResult.close()

            // Test lockout: 3 failed attempts (advance past previous 1-minute window first)
            serverTime = Instant.fromEpochMilliseconds(100_000)
            try {
                client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "wrong1")
                fail("Expected CloudWrongPassphraseException")
            } catch (_: CloudWrongPassphraseException) {}

            serverTime = Instant.fromEpochMilliseconds(101_000)
            try {
                client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "wrong2")
                fail("Expected CloudWrongPassphraseException")
            } catch (_: CloudWrongPassphraseException) {}

            serverTime = Instant.fromEpochMilliseconds(102_000)
            try {
                client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "wrong3")
                fail("Expected CloudWrongPassphraseException")
            } catch (_: CloudWrongPassphraseException) {}

            // 4th attempt should be locked out even with correct passphrase
            serverTime = Instant.fromEpochMilliseconds(103_000)
            try {
                client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "correctPassphrase")
                fail("Expected CloudTooManyPassphraseAttemptsException")
            } catch (e: CloudTooManyPassphraseAttemptsException) {
                assertTrue(e.waitDuration > Duration.ZERO)
            }
        }

        // Test RSA with passphrase
        val rsaKeyHandle = client.createKey(Algorithm.RS256_2048, passphraseRequired = true)
        val dataToSign = byteArrayOf(1, 3, 5, 7, 9)

        // Clear lockout by jumping time forward
        serverTime = Instant.fromEpochMilliseconds(1000 * 1000)
        try {
            client.sign(rsaKeyHandle, dataToSign, "wrongPassphrase")
            fail("Expected CloudWrongPassphraseException")
        } catch (_: CloudWrongPassphraseException) {
            // Expected
        }

        val signature = client.sign(rsaKeyHandle, dataToSign, "correctPassphrase")
        Crypto.checkSignature(
            rsaKeyHandle.attestationChain.certificates[0].publicKey as RsaPublicKey,
            dataToSign,
            Algorithm.RS256_2048,
            signature as RsaSignature
        )
    }

    @Test
    fun testKeyAgreementAndCheckPassphrase() = runTest {
        val server = createServer()
        val client = CsaClient(server, "secret123")
        client.register()

        client.checkPassphrase("secret123")
        try {
            client.checkPassphrase("wrong")
            fail("Expected CloudWrongPassphraseException")
        } catch (_: CloudWrongPassphraseException) {
            // Expected
        }

        val ecdhKeyHandle = client.createKey(Algorithm.ECDH_P256, passphraseRequired = true)
        val otherKeyPair = Crypto.createEcPrivateKey(EcCurve.P256)
        val sharedSecret1 = client.keyAgreement(ecdhKeyHandle, otherKeyPair.publicKey, "secret123")
        val sharedSecret2 = Crypto.keyAgreement(
            otherKeyPair,
            ecdhKeyHandle.attestationChain.certificates[0].ecPublicKey
        )
        assertArrayEquals(sharedSecret2.encoded, sharedSecret1.toByteArray())
        sharedSecret2.close()
        otherKeyPair.close()
    }
}