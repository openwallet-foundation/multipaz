package org.multipaz.securearea.cloud

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.io.bytestring.ByteString
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.Hkdf
import org.multipaz.crypto.MlDsaPublicKey
import org.multipaz.crypto.MlDsaSignature
import org.multipaz.crypto.MlKemPublicKey
import org.multipaz.crypto.RsaPublicKey
import org.multipaz.crypto.RsaSignature
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.device.AndroidKeystoreSecurityLevel
import org.multipaz.device.AssertionNonce
import org.multipaz.device.DeviceAssertion
import org.multipaz.device.DeviceAttestationSoftware
import org.multipaz.device.toCbor
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.CreateKeyRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.CreateKeyRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.CreateKeyResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.CreateKeyResponse1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EERequest
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EEResponse
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EESetupRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EESetupRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EESetupResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EESetupResponse1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.KemDecapsulateRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.KemDecapsulateRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.KemDecapsulateResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.KemDecapsulateResponse1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.RegisterRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.RegisterRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.RegisterResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.RegisterResponse1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.RegisterStage2Request0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.RegisterStage2Response0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.SignRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.SignRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.SignResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.SignResponse1
import java.nio.ByteBuffer
import java.security.Security
import kotlin.random.Random
import kotlin.time.Clock
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

        val cloudBindingKeyAttestationKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val cloudBindingKeyAttestationCertificates = X509CertChain(
            listOf(
                X509Cert.Builder(
                    publicKey = cloudBindingKeyAttestationKey.publicKey,
                    signingKey = attestationSigningKey,
                    serialNumber = ASN1Integer(1L),
                    subject = X500Name.fromName("CN=Cloud Secure Area Cloud Binding Key Attestation Root"),
                    issuer = X500Name.fromName("CN=Cloud Secure Area Cloud Binding Key Attestation Root"),
                    validFrom = now,
                    validUntil = validUntil
                )
                    .includeSubjectKeyIdentifier()
                    .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
                    .setBasicConstraints(true, 1)
                    .build(),
            )
        )
        val cloudBindingKeyAttestationSigningKey = AsymmetricKey.X509CertifiedExplicit(
            privateKey = cloudBindingKeyAttestationKey,
            certChain = cloudBindingKeyAttestationCertificates
        )

        return CloudSecureAreaServer(
            serverSecureAreaBoundKey = Random.Default.nextBytes(32),
            attestationKey = attestationSigningKey,
            cloudRootAttestationKey = cloudBindingKeyAttestationSigningKey,
            e2eeKeyLimitSeconds = 10 * 60,
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
        var e2eeContext: ByteArray? = null
        var skDevice: ByteArray? = null
        var skCloud: ByteArray? = null
        var clientEncryptedCounter = 1
        var clientDecryptedCounter = 1

        suspend fun registerAndSetupE2EE() {
            val regReq0 = RegisterRequest0("1.0")
            val (status0, data0) = server.handleCommand(regReq0.toCbor(), "localhost")
            assertEquals(200, status0)
            val regResp0 = CloudSecureAreaProtocol.Command.fromCbor(data0) as RegisterResponse0

            val deviceBindingKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val deviceAttestationKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val deviceAttestation = DeviceAttestationSoftware(deviceAttestationKey.publicKey)
            val deviceChallenge = Random.Default.nextBytes(32)

            val regReq1 = RegisterRequest1(
                deviceChallenge = deviceChallenge,
                deviceAttestation = deviceAttestation,
                deviceBindingKey = deviceBindingKey.publicKey.toCoseKey(),
                deviceBindingKeyAttestation = null,
                serverState = regResp0.serverState
            )
            val (status1, data1) = server.handleCommand(regReq1.toCbor(), "localhost")
            assertEquals(200, status1)
            val regResp1 = CloudSecureAreaProtocol.Command.fromCbor(data1) as RegisterResponse1

            val e2eeReq0 = E2EESetupRequest0(regResp1.serverState)
            val (statusE0, dataE0) = server.handleCommand(e2eeReq0.toCbor(), "localhost")
            assertEquals(200, statusE0)
            val e2eeResp0 = CloudSecureAreaProtocol.Command.fromCbor(dataE0) as E2EESetupResponse0

            val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val deviceNonce = Random.Default.nextBytes(32)
            val dataToSign = Cbor.encode(
                buildCborArray {
                    add(eDeviceKey.publicKey.toCoseKey().toDataItem())
                    add(e2eeResp0.cloudNonce)
                    add(deviceNonce)
                }
            )
            val signature = Crypto.sign(deviceBindingKey, Algorithm.ES256, dataToSign) as EcSignature

            val assertionData = AssertionNonce(ByteString(e2eeResp0.cloudNonce)).toCbor()
            val assertionSig = Crypto.sign(deviceAttestationKey, Algorithm.ES256, assertionData) as EcSignature
            val deviceAssertion = DeviceAssertion(
                assertionData = ByteString(assertionData),
                platformAssertion = ByteString(assertionSig.toCoseEncoded())
            )

            val e2eeReq1 = E2EESetupRequest1(
                eDeviceKey = eDeviceKey.publicKey.toCoseKey(),
                deviceNonce = deviceNonce,
                signature = signature,
                deviceAssertion = deviceAssertion,
                serverState = e2eeResp0.serverState
            )
            val (statusE1, dataE1) = server.handleCommand(e2eeReq1.toCbor(), "localhost")
            assertEquals(200, statusE1)
            val e2eeResp1 = CloudSecureAreaProtocol.Command.fromCbor(dataE1) as E2EESetupResponse1

            val zab = Crypto.keyAgreement(eDeviceKey, e2eeResp1.eCloudKey.ecPublicKey)
            val salt = Crypto.digest(
                Algorithm.SHA256,
                Cbor.encode(
                    buildCborArray {
                        add(deviceNonce)
                        add(e2eeResp0.cloudNonce)
                    }
                )
            )
            skDevice = Hkdf.deriveKey(Algorithm.HMAC_SHA256, zab, salt, "SKDevice".toByteArray(), 32)
            skCloud = Hkdf.deriveKey(Algorithm.HMAC_SHA256, zab, salt, "SKCloud".toByteArray(), 32)
            e2eeContext = e2eeResp1.serverState
            clientEncryptedCounter = 1
            clientDecryptedCounter = 1

            val stage2Resp = sendE2EE(RegisterStage2Request0(passphrase))
            assertTrue(stage2Resp is RegisterStage2Response0)
        }

        suspend fun sendE2EE(command: CloudSecureAreaProtocol.Command): CloudSecureAreaProtocol.Command {
            val iv = ByteBuffer.allocate(12)
            iv.putInt(0, 0x00000000)
            iv.putInt(4, 0x00000001)
            iv.putInt(8, clientEncryptedCounter)
            clientEncryptedCounter += 1

            val encrypted = Crypto.encrypt(Algorithm.A256GCM, skDevice!!, iv.array(), command.toCbor())
            val req = E2EERequest(encrypted, e2eeContext!!)
            val (status, data) = server.handleCommand(req.toCbor(), "localhost")
            assertEquals(200, status)
            val e2eeResp = CloudSecureAreaProtocol.Command.fromCbor(data) as E2EEResponse
            e2eeContext = e2eeResp.e2eeContext

            val ivResp = ByteBuffer.allocate(12)
            ivResp.putInt(0, 0x00000000)
            ivResp.putInt(4, 0x00000000)
            ivResp.putInt(8, clientDecryptedCounter)
            clientDecryptedCounter += 1

            val decrypted = Crypto.decrypt(Algorithm.A256GCM, skCloud!!, ivResp.array(), e2eeResp.encryptedResponse)
            return CloudSecureAreaProtocol.Command.fromCbor(decrypted)
        }

        data class KeyHandle(
            val attestationChain: X509CertChain,
            val serverState: ByteArray,
            val localKey: EcPrivateKey
        )

        suspend fun createKey(
            algorithm: Algorithm,
            passphraseRequired: Boolean = false
        ): KeyHandle {
            val validFrom = System.currentTimeMillis()
            val validUntil = validFrom + 30L * 24 * 3600 * 1000
            val req0 = CreateKeyRequest0(
                algorithm = algorithm.name,
                validFromMillis = validFrom,
                validUntilMillis = validUntil,
                passphraseRequired = passphraseRequired,
                userAuthenticationRequired = false,
                userAuthenticationTypes = 0L,
                challenge = byteArrayOf(1, 2, 3),
            )
            val resp0 = sendE2EE(req0) as CreateKeyResponse0

            val localKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val req1 = CreateKeyRequest1(
                localKey = localKey.publicKey.toCoseKey(),
                localKeyAttestation = null,
                serverState = resp0.serverState
            )
            val resp1 = sendE2EE(req1) as CreateKeyResponse1
            return KeyHandle(resp1.remoteKeyAttestation, resp1.serverState, localKey)
        }

        suspend fun sign(
            key: KeyHandle,
            dataToSign: ByteArray,
            passphrase: String? = null
        ): SignResponse1 {
            val req0 = SignRequest0(dataToSign, key.serverState)
            val resp0 = sendE2EE(req0) as SignResponse0

            val dataToSignLocally = Cbor.encode(
                buildCborArray {
                    add(resp0.cloudNonce)
                }
            )
            val sig = Crypto.sign(key.localKey, Algorithm.ES256, dataToSignLocally) as EcSignature
            val req1 = SignRequest1(sig, passphrase, resp0.serverState)
            return sendE2EE(req1) as SignResponse1
        }

        suspend fun kemDecapsulate(
            key: KeyHandle,
            ciphertext: ByteArray,
            passphrase: String? = null
        ): KemDecapsulateResponse1 {
            val req0 = KemDecapsulateRequest0(ciphertext, key.serverState)
            val resp0 = sendE2EE(req0) as KemDecapsulateResponse0

            val dataToSignLocally = Cbor.encode(
                buildCborArray {
                    add(resp0.cloudNonce)
                }
            )
            val sig = Crypto.sign(key.localKey, Algorithm.ES256, dataToSignLocally) as EcSignature
            val req1 = KemDecapsulateRequest1(sig, passphrase, resp0.serverState)
            return sendE2EE(req1) as KemDecapsulateResponse1
        }
    }

    @Test
    fun testRsaSigning() = runTest {
        val server = createServer()
        val client = CsaClient(server, "testPassphrase")
        client.registerAndSetupE2EE()

        for (alg in listOf(Algorithm.RS256_2048, Algorithm.PS256_2048)) {
            val keyHandle = client.createKey(alg)
            val leafCert = keyHandle.attestationChain.certificates[0]
            assertEquals(setOf(X509KeyUsage.DIGITAL_SIGNATURE), leafCert.keyUsage)
            assertTrue(leafCert.publicKey is RsaPublicKey)

            val dataToSign = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            val signResp = client.sign(keyHandle, dataToSign)
            assertEquals(CloudSecureAreaProtocol.RESULT_OK, signResp.result)
            assertNotNull(signResp.signature)
            assertTrue(signResp.signature is RsaSignature)

            Crypto.checkSignature(
                leafCert.publicKey as RsaPublicKey,
                dataToSign,
                alg,
                signResp.signature as RsaSignature
            )
        }
    }

    @Test
    fun testMlDsaSigning() = runTest {
        val server = createServer()
        val client = CsaClient(server, "testPassphrase")
        client.registerAndSetupE2EE()

        for (alg in listOf(Algorithm.ML_DSA_44, Algorithm.ML_DSA_65, Algorithm.ML_DSA_87)) {
            if (alg !in Crypto.supportedMlDsaAlgorithms) {
                continue
            }
            val keyHandle = client.createKey(alg)
            val leafCert = keyHandle.attestationChain.certificates[0]
            assertEquals(setOf(X509KeyUsage.DIGITAL_SIGNATURE), leafCert.keyUsage)
            assertTrue(leafCert.publicKey is MlDsaPublicKey)

            val dataToSign = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1)
            val signResp = client.sign(keyHandle, dataToSign)
            assertEquals(CloudSecureAreaProtocol.RESULT_OK, signResp.result)
            assertNotNull(signResp.signature)
            assertTrue(signResp.signature is MlDsaSignature)

            Crypto.checkSignature(
                leafCert.publicKey as MlDsaPublicKey,
                dataToSign,
                alg,
                signResp.signature as MlDsaSignature
            )
        }
    }

    @Test
    fun testMlKemDecapsulation() = runTest {
        val server = createServer()
        val client = CsaClient(server, "testPassphrase")
        client.registerAndSetupE2EE()

        for (alg in listOf(Algorithm.ML_KEM_512, Algorithm.ML_KEM_768, Algorithm.ML_KEM_1024)) {
            if (alg !in Crypto.supportedMlKemAlgorithms) {
                continue
            }
            val keyHandle = client.createKey(alg)
            val leafCert = keyHandle.attestationChain.certificates[0]
            assertEquals(setOf(X509KeyUsage.KEY_ENCIPHERMENT), leafCert.keyUsage)
            assertTrue(leafCert.publicKey is MlKemPublicKey)

            val kemResult = Crypto.kemEncapsulate(leafCert.publicKey as MlKemPublicKey)
            val decapsResp = client.kemDecapsulate(keyHandle, kemResult.ciphertext)
            assertEquals(CloudSecureAreaProtocol.RESULT_OK, decapsResp.result)
            assertNotNull(decapsResp.sharedSecret)
            assertArrayEquals(kemResult.sharedSecret, decapsResp.sharedSecret)
        }
    }

    @Test
    fun testPassphraseProtectionWithMlKemAndRsa() = runTest {
        var serverTime = Instant.fromEpochMilliseconds(0)
        val server = createServer { serverTime }
        val client = CsaClient(server, "correctPassphrase")
        client.registerAndSetupE2EE()

        // Test with ML-KEM if supported, otherwise RS256
        if (Algorithm.ML_KEM_768 in Crypto.supportedMlKemAlgorithms) {
            val kemKeyHandle = client.createKey(Algorithm.ML_KEM_768, passphraseRequired = true)
            val kemResult = Crypto.kemEncapsulate(
                kemKeyHandle.attestationChain.certificates[0].publicKey as MlKemPublicKey
            )

            // Wrong passphrase
            val wrongResp = client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "wrongPassphrase")
            assertEquals(CloudSecureAreaProtocol.RESULT_WRONG_PASSPHRASE, wrongResp.result)

            // Correct passphrase
            val correctResp = client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "correctPassphrase")
            assertEquals(CloudSecureAreaProtocol.RESULT_OK, correctResp.result)
            assertArrayEquals(kemResult.sharedSecret, correctResp.sharedSecret)

            // Test lockout: 3 failed attempts
            serverTime = Instant.fromEpochMilliseconds(1000)
            client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "wrong1")
            serverTime = Instant.fromEpochMilliseconds(2000)
            client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "wrong2")
            serverTime = Instant.fromEpochMilliseconds(3000)
            client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "wrong3")

            // 4th attempt should be locked out even with correct passphrase
            serverTime = Instant.fromEpochMilliseconds(4000)
            val lockedOutResp = client.kemDecapsulate(kemKeyHandle, kemResult.ciphertext, "correctPassphrase")
            assertEquals(CloudSecureAreaProtocol.RESULT_TOO_MANY_PASSPHRASE_ATTEMPTS, lockedOutResp.result)
            assertTrue(lockedOutResp.waitDurationMillis > 0)
        }

        // Test RSA with passphrase
        val rsaKeyHandle = client.createKey(Algorithm.RS256_2048, passphraseRequired = true)
        val dataToSign = byteArrayOf(1, 3, 5, 7, 9)

        // Clear lockout by jumping time forward
        serverTime = Instant.fromEpochMilliseconds(1000 * 1000)
        val wrongSignResp = client.sign(rsaKeyHandle, dataToSign, "wrongPassphrase")
        assertEquals(CloudSecureAreaProtocol.RESULT_WRONG_PASSPHRASE, wrongSignResp.result)

        val correctSignResp = client.sign(rsaKeyHandle, dataToSign, "correctPassphrase")
        assertEquals(CloudSecureAreaProtocol.RESULT_OK, correctSignResp.result)
        assertNotNull(correctSignResp.signature)
        Crypto.checkSignature(
            rsaKeyHandle.attestationChain.certificates[0].publicKey as RsaPublicKey,
            dataToSign,
            Algorithm.RS256_2048,
            correctSignResp.signature as RsaSignature
        )
    }
}