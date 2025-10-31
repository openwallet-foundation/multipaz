package org.multipaz.securearea.cloud

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.multipaz.certext.CloudKeyAttestation
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.util.fromBase64Url
import org.multipaz.certext.MultipazExtension
import org.multipaz.certext.fromCbor
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.device.AndroidKeystoreSecurityLevel
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.KeyUnlockDataProvider
import org.multipaz.securearea.UnlockReason
import org.multipaz.securearea.SecureArea
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Security
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

private const val VCI_KA_ISSUER = "https://csa.example.org"
private const val VCI_KA_KEY_STORAGE = "ava_van.5"
private const val VCI_KA_USER_AUTHENTICATION = "ava_van.5"
private val VCI_KA_USER_AUTHENTICATION_NO_PASSPHRASE: String? = null
private const val VCI_KA_CERTIFICATION = "https://certification-agency.example.org/path/to/certification-letter.pdf"


// TODO: Move to commonTests once [CloudSecureAreaServer] is also multiplatform
class CloudSecureAreaTest {
    @Before
    fun setup() {
        // Load BouncyCastle to ensure we can test full curve support
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        println("Crypto.provider: ${Crypto.provider}")
        println("Crypto.supportedCurves: ${Crypto.supportedCurves}")
        initializeApplication(InstrumentationRegistry.getInstrumentation().context)
    }

    var serverTime = Instant.fromEpochMilliseconds(0)

    val mockEngineFactory =  object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine {
            error("Mock engine should not receive any requests")
        }
    }

    internal inner class LoopbackCloudSecureArea(
        storageTable: StorageTable,
        private val packageToAllow: String?,
    ) : CloudSecureArea(storageTable, "CloudSecureArea", "uri-not-used", mockEngineFactory) {
        val context = applicationContext
        private lateinit var server: CloudSecureAreaServer

        lateinit var attestationKey: EcPublicKey
        lateinit var attestationKeyCertChain: X509CertChain

        public override suspend fun initialize() {
            super.initialize()
            val enclaveBoundKey = Random.nextBytes(32)

            val attestationKeySubject = "CN=Cloud Secure Area Attestation Root"
            val attestationKeyValidFrom = Clock.System.now()
            val attestationKeyValidUntil = attestationKeyValidFrom + 365.days*5
            val attestationKeyPrivate = Crypto.createEcPrivateKey(EcCurve.P256)
            attestationKey = attestationKeyPrivate.publicKey
            attestationKeyCertChain = X509CertChain(
                listOf(
                    X509Cert.Builder(
                        publicKey = attestationKeyPrivate.publicKey,
                        signingKey = AsymmetricKey.anonymous(attestationKeyPrivate),
                        serialNumber = ASN1Integer(1L),
                        subject = X500Name.fromName(attestationKeySubject),
                        issuer = X500Name.fromName(attestationKeySubject),
                        validFrom = attestationKeyValidFrom,
                        validUntil = attestationKeyValidUntil
                    )
                        .includeSubjectKeyIdentifier()
                        .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
                        .build(),
                )
            )
            val attestationSigningKey = AsymmetricKey.X509CertifiedExplicit(
                privateKey = attestationKeyPrivate,
                certChain = attestationKeyCertChain
            )

            val cloudBindingKeySubject = "CN=Cloud Secure Area Cloud Binding Key Attestation Root"
            val cloudBindingKeyValidFrom = Clock.System.now()
            val cloudBindingKeyValidUntil = cloudBindingKeyValidFrom + 365.days*5
            val cloudBindingKeyAttestationKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val cloudBindingKeyAttestationCertificates = X509CertChain(
                listOf(
                    X509Cert.Builder(
                        publicKey = cloudBindingKeyAttestationKey.publicKey,
                        signingKey = attestationSigningKey,
                        serialNumber = ASN1Integer(1L),
                        subject = X500Name.fromName(cloudBindingKeySubject),
                        issuer = X500Name.fromName(cloudBindingKeySubject),
                        validFrom = cloudBindingKeyValidFrom,
                        validUntil = cloudBindingKeyValidUntil
                    )
                        .includeSubjectKeyIdentifier()
                        .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
                        .build(),
                )
            )
            val cloudBindingKeyAttestationSigningKey = AsymmetricKey.X509CertifiedExplicit(
                privateKey = cloudBindingKeyAttestationKey,
                certChain = cloudBindingKeyAttestationCertificates
            )

            val digestOfSignatures: MutableList<ByteArray> = ArrayList()
            if (packageToAllow != null) {
                try {
                    val pkg = context.packageManager
                        .getPackageInfo(packageToAllow, PackageManager.GET_SIGNING_CERTIFICATES)
                    val digester = MessageDigest.getInstance("SHA-256")
                    for (n in pkg.signingInfo!!.apkContentsSigners.indices) {
                        digestOfSignatures.add(digester.digest(pkg.signingInfo!!.apkContentsSigners[n].toByteArray()))
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    throw CloudException(e)
                } catch (e: NoSuchAlgorithmException) {
                    throw CloudException(e)
                }
            }
            server = CloudSecureAreaServer(
                serverSecureAreaBoundKey = enclaveBoundKey,
                attestationKey = attestationSigningKey,
                cloudRootAttestationKey = cloudBindingKeyAttestationSigningKey,
                e2eeKeyLimitSeconds = 10 * 60,
                iosReleaseBuild = false,
                iosAppIdentifiers = listOf(),
                androidGmsAttestation = false,
                androidVerifiedBootGreen = false,
                androidAppSignatureCertificateDigests = digestOfSignatures.map { it -> ByteString(it) },
                androidAppPackageNames = listOf(),
                androidKeystoreSecurityLevel = AndroidKeystoreSecurityLevel.SOFTWARE,
                openid4vciKeyAttestationIssuer = VCI_KA_ISSUER,
                openid4vciKeyAttestationKeyStorage = VCI_KA_KEY_STORAGE,
                openid4vciKeyAttestationUserAuthentication = VCI_KA_USER_AUTHENTICATION,
                openid4vciKeyAttestationUserAuthenticationNoPassphrase = VCI_KA_USER_AUTHENTICATION_NO_PASSPHRASE,
                openid4vciKeyAttestationCertification = VCI_KA_CERTIFICATION,
                passphraseFailureEnforcer = SimplePassphraseFailureEnforcer(
                    lockoutNumFailedAttempts = 3,
                    lockoutDuration = 1.minutes,
                    clockFunction = { serverTime }
                )
            )
        }

        override suspend fun communicateLowlevel(
            endpointUri: String,
            requestData: ByteArray
        ): Pair<Int, ByteArray> {
            return server.handleCommand(requestData, endpointUri)
        }

        override suspend fun delayForBruteforceMitigation(duration: Duration) {
            serverTime = serverTime + duration
        }
    }

    @Test
    fun testAttestationCorrectness() = runTest {
        // This test only works if a Device Lock is setup.
        val aksCapabilities = AndroidKeystoreSecureArea.Capabilities()
        assumeTrue(aksCapabilities.secureLockScreenSetup)

        val csa = LoopbackCloudSecureArea(EphemeralStorage().getTable(tableSpec), null)
        csa.initialize()
        csa.register("", PassphraseConstraints.NONE) { true }

        val validFrom = Instant.parse("2025-02-05T23:36:10Z")
        val validUntil = validFrom + 30.days
        for (algorithm in csa.supportedAlgorithms) {
            if (!Crypto.supportedCurves.contains(algorithm.curve!!)) {
                println("Curve ${algorithm.curve} not supported on platform")
                continue
            }

            val expectedKeyUsage = if (algorithm.isSigning) {
                setOf(X509KeyUsage.DIGITAL_SIGNATURE)
            } else {
                setOf(X509KeyUsage.KEY_AGREEMENT)
            }
            val challenge = ByteString(1, 2, 3)
            val tests = mapOf(
                CloudCreateKeySettings.Builder(challenge)
                    .setAlgorithm(algorithm)
                    .setValidityPeriod(validFrom, validUntil)
                    .build() to
                        MultipazExtension(
                            cloudKeyAttestation = CloudKeyAttestation(
                                challenge = challenge,
                                passphrase = false,
                                userAuthentication = setOf()
                            )
                        ),

                CloudCreateKeySettings.Builder(challenge)
                    .setAlgorithm(algorithm)
                    .setValidityPeriod(validFrom, validUntil)
                    .setPassphraseRequired(true)
                    .build() to
                        MultipazExtension(
                            cloudKeyAttestation = CloudKeyAttestation(
                                challenge = challenge,
                                passphrase = true,
                                userAuthentication = setOf()
                            )
                        ),

                CloudCreateKeySettings.Builder(challenge)
                    .setAlgorithm(algorithm)
                    .setValidityPeriod(validFrom, validUntil)
                    .setUserAuthenticationRequired(true, setOf(CloudUserAuthType.PASSCODE))
                    .build() to
                        MultipazExtension(
                            cloudKeyAttestation = CloudKeyAttestation(
                                challenge = challenge,
                                passphrase = false,
                                userAuthentication = setOf(CloudUserAuthType.PASSCODE)
                            )
                        ),

                CloudCreateKeySettings.Builder(challenge)
                    .setAlgorithm(algorithm)
                    .setValidityPeriod(validFrom, validUntil)
                    .setUserAuthenticationRequired(true, setOf(CloudUserAuthType.BIOMETRIC))
                    .build() to
                        MultipazExtension(
                            cloudKeyAttestation = CloudKeyAttestation(
                                challenge = challenge,
                                passphrase = false,
                                userAuthentication = setOf(CloudUserAuthType.BIOMETRIC)
                            )
                        ),

                CloudCreateKeySettings.Builder(challenge)
                    .setAlgorithm(algorithm)
                    .setValidityPeriod(validFrom, validUntil)
                    .setUserAuthenticationRequired(
                        true, setOf(
                            CloudUserAuthType.BIOMETRIC, CloudUserAuthType.PASSCODE
                        )
                    )
                    .build() to
                        MultipazExtension(
                            cloudKeyAttestation = CloudKeyAttestation(
                                challenge = challenge,
                                passphrase = false,
                                userAuthentication = setOf(
                                    CloudUserAuthType.BIOMETRIC, CloudUserAuthType.PASSCODE
                                )
                            )
                        ),

                CloudCreateKeySettings.Builder(challenge)
                    .setAlgorithm(algorithm)
                    .setValidityPeriod(validFrom, validUntil)
                    .setPassphraseRequired(true)
                    .setUserAuthenticationRequired(
                        true, setOf(
                            CloudUserAuthType.BIOMETRIC, CloudUserAuthType.PASSCODE
                        )
                    )
                    .build() to
                        MultipazExtension(
                            cloudKeyAttestation = CloudKeyAttestation(
                                challenge = challenge,
                                passphrase = true,
                                userAuthentication = setOf(
                                    CloudUserAuthType.BIOMETRIC, CloudUserAuthType.PASSCODE
                                )
                            )
                        ),
            )
            for ((settings, expectedAttestation) in tests) {
                val keyInfo = csa.createKey("test", settings)
                // First check the certificate chain is well-formed and has the expected root
                val attestationCertificateChain = keyInfo.attestation.certChain!!
                Assert.assertTrue(attestationCertificateChain.validate())
                Assert.assertEquals(csa.attestationKey, attestationCertificateChain.certificates.last().ecPublicKey)

                // Then check for the extensions in the leaf certificate
                val attestationCertificate = attestationCertificateChain.certificates[0]
                val extension = MultipazExtension.fromCbor(
                    attestationCertificate.getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid)!!
                )
                Assert.assertEquals(expectedAttestation, extension)
                Assert.assertEquals(expectedKeyUsage, attestationCertificate.keyUsage)
                Assert.assertEquals(validFrom, attestationCertificate.validityNotBefore)
                Assert.assertEquals(validUntil, attestationCertificate.validityNotAfter)
                Assert.assertEquals(1L, attestationCertificate.serialNumber.toLong())
                Assert.assertEquals("CN=Cloud Secure Area Key", attestationCertificate.subject.name)
                Assert.assertEquals("CN=Cloud Secure Area Attestation Root", attestationCertificate.issuer.name)
            }
        }
    }

    @Test
    fun testKeyCreation() = runTest {
        val csa = LoopbackCloudSecureArea(
            EphemeralStorage().getTable(tableSpec),
            null
        )
        csa.initialize()
        csa.register(
            "",
            PassphraseConstraints.NONE) { true }
        val challenge = ByteString(1, 2, 3)
        val settings = CloudCreateKeySettings.Builder(challenge).build()
        csa.createKey("test", settings)

        val extension = MultipazExtension.fromCbor(
            csa.getKeyInfo("test").attestation.certChain!!.certificates[0]
                .getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid)!!
        )
        Assert.assertEquals(challenge, extension.cloudKeyAttestation!!.challenge)
    }

    @Test fun testBatchKeyCreationWithPassphrase() = testBatchKeyCreation(true)
    @Test fun testBatchKeyCreationWithoutPassphrase() = testBatchKeyCreation(false)

    fun testBatchKeyCreation(usePassphrase: Boolean) = runTest {
        val csa = LoopbackCloudSecureArea(
            EphemeralStorage().getTable(tableSpec),
            null
        )
        csa.initialize()
        csa.register(
            "xyz123",
            PassphraseConstraints.NONE) { true }
        val challenge = ByteString(1, 2, 3)
        val validFrom = Instant.parse("2025-02-05T23:36:10Z")
        val validUntil = validFrom + 30.days
        val settings = CloudCreateKeySettings.Builder(challenge)
            .setValidityPeriod(validFrom, validUntil)
            .setPassphraseRequired(usePassphrase)
            .build()
        val result = csa.batchCreateKey(10, settings)
        Assert.assertEquals(10, result.keyInfos.size)
        for (keyInfo in result.keyInfos) {
            val extension = MultipazExtension.fromCbor(
                    keyInfo.attestation.certChain!!.certificates[0]
                        .getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid)!!
            )
            Assert.assertEquals(challenge, extension.cloudKeyAttestation!!.challenge)

            // Also check we can use the key
            val dataToSign = byteArrayOf(4, 5, 6)
            val keyUnlockInfo = if (usePassphrase) {
                CloudKeyUnlockData(
                    csa,
                    keyInfo.alias
                ).apply {
                    passphrase = "xyz123"
                }
            } else {
                null
            }
            val signature = try {
                if (keyUnlockInfo != null) {
                    withContext(MockKeyUnlockDataProvider(keyUnlockInfo)) {
                        csa.sign(keyInfo.alias, dataToSign)
                    }
                } else {
                    csa.sign(keyInfo.alias, dataToSign)
                }
            } catch (e: KeyLockedException) {
                throw AssertionError(e)
            }
            Crypto.checkSignature(
                keyInfo.publicKey, dataToSign, Algorithm.ES256, signature
            )
        }

        // OpenID4VCI attestation checks... first check that it's signed by the expected key
        //
        JsonWebSignature.verify(
            jws = result.openid4vciKeyAttestationJws!!,  // We always return the JWS
            publicKey = csa.attestationKey
        )

        // Then that the x5c is set as expected
        val info = JsonWebSignature.getInfo(result.openid4vciKeyAttestationJws)
        Assert.assertEquals(csa.attestationKeyCertChain, info.x5c)

        // Finally check the body is as expected
        Assert.assertEquals(VCI_KA_ISSUER, info.claimsSet["iss"]!!.jsonPrimitive.content)
        val iat = info.claimsSet["iat"]!!.jsonPrimitive.long
        val now = Clock.System.now().epochSeconds
        Assert.assertTrue((iat - now).absoluteValue < 10)
        Assert.assertEquals(validFrom.epochSeconds, info.claimsSet["nbf"]!!.jsonPrimitive.long)
        Assert.assertEquals(validUntil.epochSeconds, info.claimsSet["exp"]!!.jsonPrimitive.long)
        val attestedKeysJwkArray = info.claimsSet["attested_keys"]!!.jsonArray
        Assert.assertEquals(10, attestedKeysJwkArray.size)
        var n = 0
        for (attestedKeyJwk in attestedKeysJwkArray) {
            val attestedKey = EcPublicKey.fromJwk(attestedKeyJwk.jsonObject)
            Assert.assertEquals(result.keyInfos[n++].publicKey, attestedKey)
        }
        val keyStorageArray = info.claimsSet["key_storage"]!!.jsonArray
        Assert.assertEquals(1, keyStorageArray.size)
        Assert.assertEquals(VCI_KA_KEY_STORAGE, keyStorageArray[0].jsonPrimitive.content)
        if (usePassphrase) {
            val userAuthenticationArray = info.claimsSet["user_authentication"]!!.jsonArray
            Assert.assertEquals(1, userAuthenticationArray.size)
            Assert.assertEquals(VCI_KA_USER_AUTHENTICATION, userAuthenticationArray[0].jsonPrimitive.content)
        } else {
            Assert.assertNull(info.claimsSet["user_authentication"])
        }
        Assert.assertEquals(VCI_KA_CERTIFICATION, info.claimsSet["certification"]!!.jsonPrimitive.content)
        Assert.assertEquals(challenge, ByteString(info.claimsSet["nonce"]!!.jsonPrimitive.content.fromBase64Url()))
    }

    @Test
    fun testKeySigning() = runTest {
        val csa = LoopbackCloudSecureArea(
            EphemeralStorage().getTable(tableSpec),
            null
        )
        csa.initialize()
        csa.register(
            "",
            PassphraseConstraints.NONE) { true }
        val settings = CloudCreateKeySettings.Builder(ByteString(1, 2, 3)).build()
        csa.createKey("testKey", settings)
        val keyInfo = csa.getKeyInfo("testKey")
        Assert.assertNotNull(keyInfo)
        Assert.assertTrue(keyInfo.attestation.certChain!!.certificates.isNotEmpty())
        Assert.assertEquals(Algorithm.ESP256, keyInfo.algorithm)
        Assert.assertFalse(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(setOf<Any>(), keyInfo.userAuthenticationTypes)
        Assert.assertNull(keyInfo.validFrom)
        Assert.assertNull(keyInfo.validUntil)
        val dataToSign = byteArrayOf(4, 5, 6)
        val signature = try {
            csa.sign("testKey", dataToSign)
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }
        Crypto.checkSignature(
            keyInfo.publicKey, dataToSign, Algorithm.ES256, signature
        )
    }

    @Test
    fun testKeyAgreement() = runTest {
        val csa = LoopbackCloudSecureArea(
            EphemeralStorage().getTable(tableSpec),
            null
        )
        csa.initialize()
        csa.register(
            "",
            PassphraseConstraints.NONE) { true }
        val otherKeyPair = Crypto.createEcPrivateKey(EcCurve.P256)
        val settings = CloudCreateKeySettings.Builder(ByteString(1, 2, 3))
            .setAlgorithm(Algorithm.ECDH_P256)
            .build()
        csa.createKey("testKey", settings)
        val keyInfo = csa.getKeyInfo("testKey")
        Assert.assertNotNull(keyInfo)
        Assert.assertTrue(keyInfo.attestation.certChain!!.certificates.isNotEmpty())
        Assert.assertEquals(Algorithm.ECDH_P256, keyInfo.algorithm)
        Assert.assertFalse(keyInfo.isUserAuthenticationRequired)
        Assert.assertEquals(setOf<Any>(), keyInfo.userAuthenticationTypes)
        Assert.assertNull(keyInfo.validFrom)
        Assert.assertNull(keyInfo.validUntil)

        // First do the ECDH from the perspective of our side...
        val ourSharedSecret = try {
            csa.keyAgreement("testKey", otherKeyPair.publicKey)
        } catch (e: KeyLockedException) {
            throw AssertionError(e)
        }

        // ...now do it from the perspective of the other side...
        val theirSharedSecret = Crypto.keyAgreement(otherKeyPair, keyInfo.publicKey)

        // ... finally, check that both sides compute the same shared secret.
        Assert.assertArrayEquals(theirSharedSecret, ourSharedSecret)
    }

    @Test
    fun testPassphraseCannotBeChanged() = runTest {
        val csa = LoopbackCloudSecureArea(
            EphemeralStorage().getTable(tableSpec),
            null
        )
        csa.initialize()
        csa.register(
            "",
            PassphraseConstraints.NONE) { true }

        // NOTE: [CloudSecureArea] doesn't provide API to change the passphrase but an
        // attacker could conceivable just try to re-run stage 2 of the registration
        // process with a new passphrase and hope that we're gullible enough to take it.
        //
        // Check that this doesn't work.
        //
        try {
            val spRequest0 = CloudSecureAreaProtocol.RegisterStage2Request0("New Passphrase")
            CloudSecureAreaProtocol.Command.fromCbor(csa.communicateE2EE(spRequest0.toCbor()))
                    as CloudSecureAreaProtocol.RegisterStage2Response0
            Assert.fail("Expected exception")
        } catch (e: CloudException) {
            Assert.assertEquals("Excepted status code 200 or 400, got 403", e.message)
        }
    }

    @Test
    fun testServerChecksAttestationForApplication() = runTest {
        // Setup the server to only accept our package name. This should cause register to succeed().
        val csa = LoopbackCloudSecureArea(
            EphemeralStorage().getTable(tableSpec),
            applicationContext.packageName
        )
        csa.initialize()
        csa.register(
            "",
            PassphraseConstraints.NONE) { true }
    }

    @Test
    fun testServerChecksAttestationForApplicationNegative() = runTest {
        // Setup the server to only accept something which isn't our package name but still
        // exists on the system. We use com.android.externalstorage for that. This should
        // cause register() to fail.
        val csa = LoopbackCloudSecureArea(
            EphemeralStorage().getTable(tableSpec),
            "com.android.externalstorage"
        )
        csa.initialize()
        try {
            csa.register(
                "",
                PassphraseConstraints.NONE
            ) { true }
            Assert.fail("Expected exception")
        } catch (_: CloudException) {
            // Expected path.
        }
    }

    @Test
    @Throws(IOException::class)
    fun testUsingGenericCreateKeySettings() = runTest {
        val csa = LoopbackCloudSecureArea(
            EphemeralStorage().getTable(tableSpec),
            null
        )
        val challenge = ByteString(4, 5, 6)
        csa.initialize()
        csa.register(
            "",
            PassphraseConstraints.NONE) { true }
        csa.createKey(
            "testKey",
             CreateKeySettings(Algorithm.ESP256, challenge)
        )
        val keyInfo = csa.getKeyInfo("testKey")
        Assert.assertNotNull(keyInfo)
        Assert.assertEquals(Algorithm.ESP256, keyInfo.algorithm)

        // Check that the challenge is empty.
        val extension = MultipazExtension.fromCbor(
            csa.getKeyInfo("testKey").attestation.certChain!!.certificates[0]
                .getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid)!!
        )
        Assert.assertEquals(challenge, extension.cloudKeyAttestation!!.challenge)

        // Now delete it...
        csa.deleteKey("testKey")
    }

    @Test
    fun testWrongPassphraseDelay_signing() = runTest {
        testWrongPassphraseDelayHelper(
            algorithm = Algorithm.ESP256,
            useKey = { alias, csa, unlockData ->
                if (unlockData == null) {
                    csa.sign(alias, byteArrayOf(1, 2, 3))
                } else {
                    withContext(MockKeyUnlockDataProvider(unlockData)) {
                        csa.sign(alias, byteArrayOf(1, 2, 3))
                    }
                }
        })
    }

    @Test
    fun testWrongPassphraseDelay_keyAgreement() = runTest {
        val otherKey = Crypto.createEcPrivateKey(EcCurve.P256)

        testWrongPassphraseDelayHelper(
            algorithm = Algorithm.ECDH_P256,
            useKey = { alias, csa, unlockData ->
                if (unlockData == null) {
                    csa.keyAgreement(alias, otherKey.publicKey)
                } else {
                    withContext(MockKeyUnlockDataProvider(unlockData)) {
                        csa.keyAgreement(alias, otherKey.publicKey)
                    }
                }
        })
    }

    suspend fun testWrongPassphraseDelayHelper(
        algorithm: Algorithm,
        useKey: suspend (alias: String,
                 csa: CloudSecureArea,
                 unlockData: CloudKeyUnlockData?) -> Unit
    ) {
        val csa = LoopbackCloudSecureArea(
            EphemeralStorage().getTable(tableSpec),
            null
        )
        csa.initialize()

        csa.register(
            "1111",
            PassphraseConstraints.PIN_FOUR_DIGITS
        ) { true }

        csa.createKey("testKey1",
            CloudCreateKeySettings.Builder(ByteString())
                .setAlgorithm(algorithm)
                .setPassphraseRequired(true)
                .build()
        )

        csa.createKey("testKey2",
            CloudCreateKeySettings.Builder(ByteString())
                .setAlgorithm(algorithm)
                .setPassphraseRequired(true)
                .build()
        )

        csa.createKey("testKey3NoPassphrase",
            CloudCreateKeySettings.Builder(ByteString())
                .setAlgorithm(algorithm)
                .build()
        )

        val correctPassphrase = CloudKeyUnlockData(csa,"testKey1")
        correctPassphrase.passphrase = "1111"

        val incorrectPassphrase = CloudKeyUnlockData(csa,"testKey1")
        incorrectPassphrase.passphrase = "1112"

        val correctPassphraseTestKey2 = CloudKeyUnlockData(csa,"testKey2")
        correctPassphraseTestKey2.passphrase = "1111"

        // ----

        // Policy is configured above to be
        //
        //   lockoutNumFailedAttempts = 3,
        //   lockoutDuration = 1.minutes,
        //
        // so this is what we're going to test. We can also control the clock used by the
        // enforcer via [serverTime].
        //

        // First make three attempts at T = 0, 15, 30 to unlock... the third attempt will
        // will cause us to be locked out until there are no unlock failures inside the
        // last minute.
        serverTime = Instant.fromEpochMilliseconds(0)
        useKey("testKey1", csa, correctPassphrase)
        assertThrows(KeyLockedException::class) {
            useKey("testKey1", csa, incorrectPassphrase)
        }
        serverTime = Instant.fromEpochMilliseconds(15 * 1000)
        assertThrows(KeyLockedException::class) {
            useKey("testKey1", csa, incorrectPassphrase)
        }
        serverTime = Instant.fromEpochMilliseconds(30 * 1000)
        assertThrows(KeyLockedException::class) {
            useKey("testKey1", csa, incorrectPassphrase)
        }

        // Unlock attempts should now start blocking EVEN if we supply the right passphrase.
        //
        // Note, this is subtle: `serverTime` is increased due to the test overriding
        // CloudSecureArea.delayForBruteforceMitigation() method which does just that
        // instead of blocking the calling thread.
        //
        serverTime = Instant.fromEpochMilliseconds(31 * 1000)
        useKey("testKey1", csa, correctPassphrase)
        Assert.assertEquals(Instant.fromEpochMilliseconds(60 * 1000), serverTime)

        // Let's do another failed attempt and then try again... this should block
        // until T = 75 seconds because we had failed attempts at T=15 and T=30 already.
        assertThrows(KeyLockedException::class) {
            useKey("testKey1", csa, incorrectPassphrase)
        }
        useKey("testKey1", csa, correctPassphrase)
        Assert.assertEquals(Instant.fromEpochMilliseconds(75 * 1000), serverTime)

        // Jump forward in time to clear out failed attempts
        serverTime = Instant.fromEpochMilliseconds(1000 * 1000)

        // Also check that if one key from the client is blocked, so are all others. Also
        // check here that operations on keys w/o passphrases aren't blocked
        assertThrows(KeyLockedException::class) {
            useKey("testKey1", csa, incorrectPassphrase)
        }
        assertThrows(KeyLockedException::class) {
            useKey("testKey1", csa, incorrectPassphrase)
        }
        assertThrows(KeyLockedException::class) {
            useKey("testKey1", csa, incorrectPassphrase)
        }
        // testKey3NoPassphrase shouldn't be blocked
        useKey("testKey3NoPassphrase", csa, null)
        Assert.assertEquals(Instant.fromEpochMilliseconds(1000 * 1000), serverTime)
        // Now we're blocked b/c of testKey1, even for testKey2
        useKey("testKey2", csa, correctPassphraseTestKey2)
        Assert.assertEquals(Instant.fromEpochMilliseconds(1060 * 1000), serverTime)

        // Jump forward in time to clear out failed attempts
        serverTime = Instant.fromEpochMilliseconds(2000 * 1000)

        // Finally, we want to check that different clients don't affect each other. To this
        // end we're creating a new client w/ a passphrase protected key.
        val csa2 = LoopbackCloudSecureArea(
            EphemeralStorage().getTable(tableSpec),
            null
        )
        csa2.initialize()
        csa2.register(
            "9876",
            PassphraseConstraints.PIN_FOUR_DIGITS,
        ) { true }
        csa2.createKey("client2_testKey1",
            CloudCreateKeySettings.Builder(ByteString())
                .setAlgorithm(algorithm)
                .setPassphraseRequired(true)
                .build()
        )
        val correctPassphraseClient2 = CloudKeyUnlockData(csa2, "client2_testKey1")
        correctPassphraseClient2.passphrase = "9876"

        // Now use up all failed attempts on client 1
        assertThrows(KeyLockedException::class) {
            useKey("testKey1", csa, incorrectPassphrase)
        }
        assertThrows(KeyLockedException::class) {
            useKey("testKey1", csa, incorrectPassphrase)
        }
        assertThrows(KeyLockedException::class) {
            useKey("testKey1", csa, incorrectPassphrase)
        }
        Assert.assertEquals(Instant.fromEpochMilliseconds(2000 * 1000), serverTime)
        // Check client 2 isn't affected
        useKey("client2_testKey1", csa2, correctPassphraseClient2)
        Assert.assertEquals(Instant.fromEpochMilliseconds(2000 * 1000), serverTime)
        useKey("testKey1", csa, correctPassphrase)
        Assert.assertEquals(Instant.fromEpochMilliseconds(2060 * 1000), serverTime)
    }

    private class MockKeyUnlockDataProvider(
        val keyUnlockData: KeyUnlockData
    ): KeyUnlockDataProvider {
        override suspend fun getKeyUnlockData(
            secureArea: SecureArea,
            alias: String,
            unlockReason: UnlockReason
        ): KeyUnlockData = keyUnlockData
    }

    companion object {
        private suspend fun assertThrows(clazz: KClass<out Throwable>, body: suspend () -> Unit) {
            try {
                body()
                Assert.fail("Expected exception $clazz, no exception was thrown")
            } catch (err: Throwable) {
                if (!clazz.isInstance(err)) {
                    throw err
                }
            }
        }

        private val tableSpec = StorageTableSpec(
            name = "TestCloudSecureArea",
            supportPartitions = true,
            supportExpiration = false
        )
    }
}