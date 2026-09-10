package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.cbor.Tstr
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseSign1
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.webtoken.WebTokenClaim
import org.multipaz.webtoken.WebTokenClaim.Companion.get
import org.multipaz.webtoken.WebTokenClaim.Companion.put
import org.multipaz.webtoken.buildCwt
import org.multipaz.webtoken.buildJwt
import org.multipaz.webtoken.validateCwt
import org.multipaz.webtoken.validateJwt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Clock

class RsaTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun testRsaJwkThumbprintRfc7638Vector() = runTest {
        // Test vector from RFC 7638 Section 3.1
        val nB64Url = "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAt" +
                "VT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMstn6" +
                "4tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FD" +
                "W2QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n" +
                "91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINH" +
                "aQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw"
        val eB64Url = "AQAB"
        val expectedThumbprintB64Url = "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs"

        val pubKey = RsaPublicKey(nB64Url.fromBase64Url(), eB64Url.fromBase64Url())
        val thumbprint = pubKey.toJwkThumbprint(Algorithm.SHA256)
        assertEquals(expectedThumbprintB64Url, thumbprint.toByteArray().toBase64Url())
    }

    @Test
    fun testCreateKeyAndSignVerify() = runTest {
        val privKey = Crypto.createRsaPrivateKey(2048)
        val pubKey = privKey.publicKey

        assertEquals(pubKey.modulus.size, 2048 / 8)
        assertNotNull(privKey.p)
        assertNotNull(privKey.q)
        assertNotNull(privKey.dp)
        assertNotNull(privKey.dq)
        assertNotNull(privKey.qInv)

        val message = "Hello RSA World!".encodeToByteArray()

        for (alg in listOf(
            Algorithm.RS256, Algorithm.RS384, Algorithm.RS512,
            Algorithm.PS256, Algorithm.PS384, Algorithm.PS512
        )) {
            val signature = Crypto.sign(privKey, alg, message)
            assertTrue(signature.signature.isNotEmpty())

            // Verify with publicKey
            Crypto.checkSignature(pubKey, message, alg, signature)

            // Verify signature using superclass PublicKey and Signature
            val genericPubKey: PublicKey = pubKey
            assertTrue(genericPubKey is RsaPublicKey)
            val genericSignature: Signature = signature
            Crypto.checkSignature(genericPubKey, message, alg, genericSignature)

            // Negative test 1: tampered message
            val tamperedMessage = "Hello RSA World?".encodeToByteArray()
            assertFailsWith<SignatureVerificationException> {
                Crypto.checkSignature(pubKey, tamperedMessage, alg, signature)
            }

            // Negative test 2: tampered signature
            val tamperedSignature = signature.signature.copyOf()
            tamperedSignature[tamperedSignature.size - 1] =
                (tamperedSignature[tamperedSignature.size - 1].toInt() xor 0x01).toByte()
            assertFailsWith<SignatureVerificationException> {
                Crypto.checkSignature(pubKey, message, alg, RsaSignature(tamperedSignature))
            }

            // Negative test 3: signature from another key
            val otherPrivKey = Crypto.createRsaPrivateKey(2048)
            val otherSignature = Crypto.sign(otherPrivKey, alg, message)
            assertFailsWith<SignatureVerificationException> {
                Crypto.checkSignature(pubKey, message, alg, otherSignature)
            }
        }
    }

    @Test
    fun testSerializationRoundtrips() = runTest {
        val privKey = Crypto.createRsaPrivateKey(2048)
        val pubKey = privKey.publicKey

        // PKCS#1 DER roundtrip
        val pkcs1PubBytes = pubKey.toPkcs1()
        val pubFromPkcs1 = RsaPublicKey.fromPkcs1(pkcs1PubBytes)
        assertEquals(pubKey, pubFromPkcs1)

        val pkcs1PrivBytes = privKey.toPkcs1()
        val privFromPkcs1 = RsaPrivateKey.fromPkcs1(pkcs1PrivBytes)
        assertEquals(privKey, privFromPkcs1)

        // SubjectPublicKeyInfo roundtrip
        val spkiBytes = pubKey.toSubjectPublicKeyInfo()
        val pubFromSpki = RsaPublicKey.fromSubjectPublicKeyInfo(spkiBytes)
        assertEquals(pubKey, pubFromSpki)

        // PrivateKeyInfo (PKCS#8) roundtrip
        val pkcs8Bytes = privKey.toPrivateKeyInfo()
        val privFromPkcs8 = RsaPrivateKey.fromPrivateKeyInfo(pkcs8Bytes)
        assertEquals(privKey, privFromPkcs8)

        // PEM roundtrip (RsaPublicKey and PublicKey companion)
        val pubPem = pubKey.toPem()
        assertTrue(pubPem.startsWith("-----BEGIN PUBLIC KEY-----"))
        val pubFromPem = RsaPublicKey.fromPem(pubPem)
        assertEquals(pubKey, pubFromPem)
        val pubFromGenericPem = PublicKey.fromPem(pubPem)
        assertEquals(pubKey, pubFromGenericPem)

        // PEM roundtrip (RsaPrivateKey and PrivateKey companion)
        val privPem = privKey.toPem()
        assertTrue(privPem.startsWith("-----BEGIN PRIVATE KEY-----"))
        val privFromPem = RsaPrivateKey.fromPem(privPem)
        assertEquals(privKey, privFromPem)
        val privFromGenericPem = PrivateKey.fromPem(privPem)
        assertEquals(privKey, privFromGenericPem)

        // JWK roundtrip (RsaPublicKey and PublicKey companion)
        val pubJwk = pubKey.toJwk()
        val pubFromJwk = RsaPublicKey.fromJwk(pubJwk)
        assertEquals(pubKey, pubFromJwk)
        val pubFromGenericJwk = PublicKey.fromJwk(pubJwk)
        assertEquals(pubKey, pubFromGenericJwk)

        // JWK roundtrip (RsaPrivateKey and PrivateKey companion)
        val privJwk = privKey.toJwk()
        val privFromJwk = RsaPrivateKey.fromJwk(privJwk)
        assertEquals(privKey, privFromJwk)
        val privFromGenericJwk = PrivateKey.fromJwk(privJwk)
        assertEquals(privKey, privFromGenericJwk)

        // COSE Key roundtrip (RsaPublicKey and PublicKey companion)
        val pubCoseKey = pubKey.toCoseKey()
        val pubFromCose = RsaPublicKey.fromCoseKey(pubCoseKey)
        assertEquals(pubKey, pubFromCose)
        val pubFromGenericCose = PublicKey.fromCoseKey(pubCoseKey)
        assertEquals(pubKey, pubFromGenericCose)
        assertEquals(pubKey, pubCoseKey.rsaPublicKey)
        assertEquals(pubKey, pubCoseKey.publicKey)

        // COSE Key roundtrip (RsaPrivateKey and PrivateKey companion)
        val privCoseKey = privKey.toCoseKey()
        val privFromCose = RsaPrivateKey.fromCoseKey(privCoseKey)
        assertEquals(privKey, privFromCose)
        val privFromGenericCose = PrivateKey.fromCoseKey(privCoseKey)
        assertEquals(privKey, privFromGenericCose)
        assertEquals(privKey, privCoseKey.rsaPrivateKey)
        assertEquals(privKey, privCoseKey.privateKey)

        // DataItem roundtrip
        val pubDataItem = pubKey.toDataItem()
        assertEquals(pubKey, RsaPublicKey.fromDataItem(pubDataItem))
        assertEquals(pubKey, PublicKey.fromDataItem(pubDataItem))

        val privDataItem = privKey.toDataItem()
        assertEquals(privKey, RsaPrivateKey.fromDataItem(privDataItem))
        assertEquals(privKey, PrivateKey.fromDataItem(privDataItem))
    }

    @Test
    fun testX509CertWithRsaPublicKey() = runTest {
        val rsaPrivKey = Crypto.createRsaPrivateKey(2048)
        val rsaPubKey = rsaPrivKey.publicKey

        val caEcKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val caAsymmetricKey = AsymmetricKey.AnonymousExplicit(
            privateKey = caEcKey,
            algorithm = Algorithm.ES256
        )

        val now = Clock.System.now()
        val cert = X509Cert.Builder(
            publicKey = rsaPubKey,
            signingKey = caAsymmetricKey,
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=RSA End Entity"),
            issuer = X500Name.fromName("CN=EC CA"),
            validFrom = now,
            validUntil = now + 30.days
        ).includeSubjectKeyIdentifier()
            .build()

        // Verify the extracted public key is RsaPublicKey
        val extractedPubKey = cert.publicKey
        assertTrue(extractedPubKey is RsaPublicKey)
        assertEquals(rsaPubKey, extractedPubKey)

        // Verify cert signature using the CA's public key
        cert.verify(caEcKey.publicKey)
        cert.verify(caEcKey.publicKey as PublicKey)

        // Verify that calling ecPublicKey on RSA cert throws
        assertFailsWith<IllegalStateException> {
            cert.ecPublicKey
        }
    }

    @Test
    fun testAsymmetricKeyRsaEphemeral() = runTest {
        for (alg in listOf(Algorithm.RS256, Algorithm.PS256)) {
            val key = AsymmetricKey.ephemeral(alg)
            assertEquals(alg, key.algorithm)
            assertNull(key.algorithm.curve)
            assertTrue(key.publicKey is RsaPublicKey)
            assertEquals(key.publicKey, key.rsaPublicKey)
            assertFailsWith<IllegalStateException> { key.ecPublicKey }
            assertFailsWith<IllegalStateException> { key.signEc("test".encodeToByteArray()) }
            assertFailsWith<UnsupportedOperationException> {
                key.keyAgreement(Crypto.createEcPrivateKey(EcCurve.P256).publicKey)
            }

            // Test signing
            val message = "Hello AsymmetricKey RSA".encodeToByteArray()
            val signature = key.sign(message)
            assertTrue(signature is RsaSignature)
            Crypto.checkSignature(key.rsaPublicKey, message, alg, signature)
        }

        // Test parseExplicit with JWK
        val privKey = Crypto.createRsaPrivateKey(2048)
        val jwk = buildJsonObject {
            for ((k, v) in privKey.toJwk()) {
                put(k, v)
            }
            put("kid", "rsa-kid-1")
        }
        val parsed = AsymmetricKey.parseExplicit(jwk)
        assertEquals("rsa-kid-1", parsed.subject)
        assertEquals(privKey.publicKey, parsed.publicKey)
        assertEquals(Algorithm.RS256, parsed.algorithm)
    }

    @Test
    fun testAsymmetricKeyRsaNamedAndCertified() = runTest {
        val privKey = Crypto.createRsaPrivateKey(2048)
        val named = AsymmetricKey.NamedExplicit("rsa-key-1", privKey)
        assertEquals("rsa-key-1", named.keyId)
        assertEquals(Algorithm.RS256, named.algorithm)
        assertEquals(privKey, named.rsaPrivateKey)
        assertEquals(privKey, named.privateKey)

        val now = Clock.System.now()
        val cert = X509Cert.Builder(
            publicKey = privKey.publicKey,
            signingKey = named,
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Self-Signed RSA"),
            issuer = X500Name.fromName("CN=Self-Signed RSA"),
            validFrom = now,
            validUntil = now + 30.days
        ).includeSubjectKeyIdentifier()
            .build()

        val chain = X509CertChain(listOf(cert))
        val certified = AsymmetricKey.X509CertifiedExplicit(chain, privKey)
        assertEquals(chain, certified.certChain)
        assertEquals(Algorithm.RS256, certified.algorithm)
        assertEquals(privKey.publicKey, certified.publicKey)
    }

    @Test
    fun testX509CertWithRsaCaSigning() = runTest {
        val caPrivKey = Crypto.createRsaPrivateKey(2048)
        val caSigningKey = AsymmetricKey.AnonymousExplicit(caPrivKey, Algorithm.RS256)
        val now = Clock.System.now()

        // 1. Self-signed RSA Root CA
        val caCert = X509Cert.Builder(
            publicKey = caPrivKey.publicKey,
            signingKey = caSigningKey,
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=RSA Root CA"),
            issuer = X500Name.fromName("CN=RSA Root CA"),
            validFrom = now,
            validUntil = now + 365.days
        ).setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN, X509KeyUsage.CRL_SIGN))
            .setBasicConstraints(true, 1)
            .includeSubjectKeyIdentifier()
            .build()

        // Verify self-signed root
        caCert.verify(caPrivKey.publicKey)
        caCert.verify(caCert.publicKey)

        // 2. RSA End Entity signed by RSA CA
        val eePrivKey = Crypto.createRsaPrivateKey(2048)
        val eeCert = X509Cert.Builder(
            publicKey = eePrivKey.publicKey,
            signingKey = caSigningKey,
            serialNumber = ASN1Integer(2L),
            subject = X500Name.fromName("CN=RSA End Entity"),
            issuer = X500Name.fromName("CN=RSA Root CA"),
            validFrom = now,
            validUntil = now + 30.days
        ).setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .includeSubjectKeyIdentifier()
            .setAuthorityKeyIdentifierToCertificate(caCert)
            .build()

        eeCert.verify(caPrivKey.publicKey)
        eeCert.verify(caCert.publicKey)

        // Validate chain
        val certChain = X509CertChain(listOf(eeCert, caCert))
        certChain.validate(now)

        // 3. EC End Entity signed by RSA CA
        val ecPrivKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val ecEeCert = X509Cert.Builder(
            publicKey = ecPrivKey.publicKey,
            signingKey = caSigningKey,
            serialNumber = ASN1Integer(3L),
            subject = X500Name.fromName("CN=EC End Entity"),
            issuer = X500Name.fromName("CN=RSA Root CA"),
            validFrom = now,
            validUntil = now + 30.days
        ).setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .includeSubjectKeyIdentifier()
            .setAuthorityKeyIdentifierToCertificate(caCert)
            .build()

        ecEeCert.verify(caPrivKey.publicKey)
        val ecCertChain = X509CertChain(listOf(ecEeCert, caCert))
        ecCertChain.validate(now)
    }

    @Test
    fun testCoseSign1WithRsa() = runTest {
        val privKey = Crypto.createRsaPrivateKey(2048)
        for (alg in listOf(Algorithm.RS256, Algorithm.PS256)) {
            val signingKey = AsymmetricKey.AnonymousExplicit(privKey, alg)
            val message = "Hello COSE with RSA!".encodeToByteArray()

            val sign1 = Cose.coseSign1Sign(
                signingKey = signingKey,
                message = message,
                includeMessageInPayload = true
            )

            // Verify with generic PublicKey
            Cose.coseSign1Check(
                publicKey = signingKey.publicKey,
                detachedData = null,
                signature = sign1,
                signatureAlgorithm = alg
            )

            // Negative test: detached data mismatch
            assertFailsWith<SignatureVerificationException> {
                Cose.coseSign1Check(
                    publicKey = signingKey.publicKey,
                    detachedData = "Tampered message".encodeToByteArray(),
                    signature = CoseSign1(
                        protectedHeaders = sign1.protectedHeaders,
                        unprotectedHeaders = sign1.unprotectedHeaders,
                        payload = null,
                        signature = sign1.signature
                    ),
                    signatureAlgorithm = alg
                )
            }
        }
    }

    @Test
    fun testJwtWithRsa() = runTest {
        val privKey = Crypto.createRsaPrivateKey(2048)
        for (alg in listOf(Algorithm.RS256, Algorithm.PS256)) {
            val signingKey = AsymmetricKey.AnonymousExplicit(privKey, alg)

            val jwt = buildJwt(
                type = "JWT",
                key = signingKey
            ) {
                put("sub", "user_rsa_123")
                put("iss", "test_issuer")
            }

            val validated = validateJwt(
                jwt = jwt,
                jwtName = "test_jwt",
                publicKey = signingKey.publicKey
            )
            assertEquals("user_rsa_123", (validated["sub"] as JsonPrimitive).content)
            assertEquals("test_issuer", (validated["iss"] as JsonPrimitive).content)

            // Negative test: tampered JWT
            val parts = jwt.split(".")
            val tamperedPayload = (parts[1].fromBase64Url().decodeToString() + " ").encodeToByteArray().toBase64Url()
            val tamperedJwt = "${parts[0]}.$tamperedPayload.${parts[2]}"
            assertFailsWith<IllegalArgumentException> {
                validateJwt(
                    jwt = tamperedJwt,
                    jwtName = "test_jwt",
                    publicKey = signingKey.publicKey
                )
            }
        }
    }

    @Test
    fun testCwtWithRsa() = runTest {
        val privKey = Crypto.createRsaPrivateKey(2048)
        for (alg in listOf(Algorithm.RS256, Algorithm.PS256)) {
            val signingKey = AsymmetricKey.AnonymousExplicit(privKey, alg)

            val cwt = buildCwt(
                type = "CWT",
                key = signingKey
            ) {
                put(WebTokenClaim.Sub, "user_rsa_456")
            }

            val validated = validateCwt(
                cwt = cwt,
                cwtName = "test_cwt",
                publicKey = signingKey.publicKey
            )
            assertEquals("user_rsa_456", validated[WebTokenClaim.Sub])
        }
    }

    @Test
    fun testJwtWithRsaNamedAndCertified() = runTest {
        val privKey = Crypto.createRsaPrivateKey(2048)
        val namedKey = AsymmetricKey.NamedExplicit("rsa-kid-42", privKey)
        val jwtNamed = buildJwt(type = "JWT", key = namedKey) {
            put("data", "named")
        }
        val validatedNamed = validateJwt(
            jwt = jwtNamed,
            jwtName = "named_jwt",
            publicKey = namedKey.publicKey
        )
        assertEquals("named", (validatedNamed["data"] as JsonPrimitive).content)

        val now = Clock.System.now()
        val cert = X509Cert.Builder(
            publicKey = privKey.publicKey,
            signingKey = namedKey,
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=RSA Cert"),
            issuer = X500Name.fromName("CN=RSA Cert"),
            validFrom = now,
            validUntil = now + 30.days
        ).includeSubjectKeyIdentifier().build()
        val chain = X509CertChain(listOf(cert))
        val certKey = AsymmetricKey.X509CertifiedExplicit(chain, privKey)

        val jwtCert = buildJwt(type = "JWT", key = certKey) {
            put("data", "certified")
        }
        val validatedCert = validateJwt(
            jwt = jwtCert,
            jwtName = "cert_jwt",
            publicKey = certKey.publicKey
        )
        assertEquals("certified", (validatedCert["data"] as JsonPrimitive).content)
    }
}
