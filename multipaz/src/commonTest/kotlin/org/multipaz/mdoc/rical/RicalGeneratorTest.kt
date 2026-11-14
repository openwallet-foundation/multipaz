package org.multipaz.mdoc.rical

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.truncateToWholeSeconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class RicalGeneratorTest {
    private suspend fun createSelfsignedCert(
        key: EcPrivateKey,
        subjectAndIssuer: X500Name
    ): X509Cert {
        val now = Clock.System.now().truncateToWholeSeconds()
        val validFrom = now - 10.minutes
        val validUntil = now + 10.minutes

        return X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key, key.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1),
            subject = subjectAndIssuer,
            issuer = subjectAndIssuer,
            validFrom = validFrom,
            validUntil = validUntil
        ).includeSubjectKeyIdentifier().build()
    }

    @Test
    fun testRicalGenerator() = runTest {
        val ricalKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val ricalCert = createSelfsignedCert(ricalKey, X500Name.fromName("CN=Test RICAL"))

        val rp1Cert = createSelfsignedCert(
            Crypto.createEcPrivateKey(EcCurve.P256), X500Name.fromName("CN=RP 1"))
        val rp2Cert = createSelfsignedCert(
            Crypto.createEcPrivateKey(EcCurve.P256), X500Name.fromName("CN=RP 2"))
        val rp3Cert = createSelfsignedCert(
            Crypto.createEcPrivateKey(EcCurve.P256), X500Name.fromName("CN=RP 3"))

        val ricalDate = Clock.System.now()
        val ricalNextUpdate = ricalDate + 30.days
        val ricalNotAfter = ricalDate + 40.days
        val ricalIssueID = 42L
        val ricalExtensions = buildMap {
            put("org.example.foo", Tstr("blah"))
            put("org.example.bar", Uint(42UL))
        }

        val lastCertExt = buildMap {
            put("org.example2.foo", Tstr("bah"))
            put("org.example2.bar", Uint(43UL))
        }

        val middleCertTrustConstraints = listOf(
            RicalTrustConstraint(
                extensions = buildMap {
                    put("x", "foo".toDataItem())
                    put("y", 44.toDataItem())
                }
            ),
            RicalTrustConstraint(
                extensions = buildMap {
                    put("y", "bar".toDataItem())
                    put("v", 45.toDataItem())
                }
            )
        )

        val signedRical = SignedRical(
            rical = Rical(
                type = Rical.RICAL_TYPE_READER_AUTHENTICATION,
                version = "1.0",
                provider = "Test RICAL Provider",
                date = ricalDate,
                nextUpdate = ricalNextUpdate,
                notAfter = ricalNotAfter,
                certificateInfos = listOf(
                    RicalCertificateInfo(certificate = rp1Cert),
                    RicalCertificateInfo(
                        certificate = rp2Cert,
                        trustConstraints = middleCertTrustConstraints,
                    ),
                    RicalCertificateInfo(
                        certificate = rp3Cert,
                        extensions = lastCertExt
                    ),
                ),
                id = ricalIssueID,
                latestRicalUrl = null,
                extensions = ricalExtensions
            ),
            ricalProviderCertificateChain = X509CertChain(listOf(ricalCert))
        )
        val encodedSignedRical = signedRical.generate(
            signingKey = AsymmetricKey.anonymous(ricalKey, ricalKey.curve.defaultSigningAlgorithm)
        )

        val decodedSignedRical = SignedRical.parse(
            encodedSignedRical = encodedSignedRical
        )

        assertEquals(listOf(ricalCert), decodedSignedRical.ricalProviderCertificateChain.certificates)
        assertEquals("Test RICAL Provider", decodedSignedRical.rical.provider)
        assertEquals("1.0", decodedSignedRical.rical.version)
        assertEquals(Rical.RICAL_TYPE_READER_AUTHENTICATION, decodedSignedRical.rical.type)
        assertEquals(ricalDate, decodedSignedRical.rical.date)
        assertEquals(ricalNextUpdate, decodedSignedRical.rical.nextUpdate)
        assertEquals(ricalNotAfter, decodedSignedRical.rical.notAfter)
        assertEquals(ricalIssueID, decodedSignedRical.rical.id)
        assertEquals(ricalExtensions,decodedSignedRical.rical.extensions)
        assertEquals(3, decodedSignedRical.rical.certificateInfos.size)

        assertEquals(
            rp1Cert,
            decodedSignedRical.rical.certificateInfos[0].certificate
        )
        assertTrue(decodedSignedRical.rical.certificateInfos[0].trustConstraints.isEmpty())
        assertTrue(decodedSignedRical.rical.certificateInfos[0].extensions.isEmpty())

        assertEquals(
            rp2Cert,
            decodedSignedRical.rical.certificateInfos[1].certificate
        )
        assertEquals(middleCertTrustConstraints, decodedSignedRical.rical.certificateInfos[1].trustConstraints)
        assertTrue(decodedSignedRical.rical.certificateInfos[1].extensions.isEmpty())

        assertEquals(
            rp3Cert,
            decodedSignedRical.rical.certificateInfos[2].certificate
        )
        assertTrue(decodedSignedRical.rical.certificateInfos[2].trustConstraints.isEmpty())
        assertEquals(lastCertExt, decodedSignedRical.rical.certificateInfos[2].extensions)
    }

    @Test
    fun testRicalSecondEditionFieldsDefaultsOmissionAndRootExclusion() = runTest {
        val now = Clock.System.now().truncateToWholeSeconds()
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootCert = X509Cert.Builder(
            publicKey = rootKey.publicKey,
            signingKey = AsymmetricKey.anonymous(rootKey, rootKey.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(20),
            subject = X500Name.fromName("C=US,CN=RICAL Root CA"),
            issuer = X500Name.fromName("C=US,CN=RICAL Root CA"),
            validFrom = now - 10.minutes,
            validUntil = now + 10.minutes,
        ).includeSubjectKeyIdentifier().setBasicConstraints(ca = true, pathLenConstraint = null).build()

        val signerKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val signerCert = X509Cert.Builder(
            publicKey = signerKey.publicKey,
            signingKey = AsymmetricKey.anonymous(rootKey, rootKey.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(21),
            subject = X500Name.fromName("C=US,CN=RICAL Signer"),
            issuer = rootCert.subject,
            validFrom = now - 10.minutes,
            validUntil = now + 10.minutes,
        ).includeSubjectKeyIdentifier().setAuthorityKeyIdentifierToCertificate(rootCert).build()

        val rpCert = createSelfsignedCert(
            Crypto.createEcPrivateKey(EcCurve.P256),
            X500Name.fromName("C=US,ST=US-NY,CN=NY Reader CA")
        )
        val constraintWithOtherInfo = RicalTrustConstraint(
            extensions = emptyMap(),
            otherInfo = mapOf("futureConstraint" to "allowed".toDataItem())
        )

        val signedRical = SignedRical(
            rical = Rical(
                type = Rical.RICAL_TYPE_READER_AUTHENTICATION,
                version = "1.0",
                provider = "Test RICAL Provider",
                date = now,
                nextUpdate = null,
                notAfter = null,
                certificateInfos = listOf(
                    RicalCertificateInfo(
                        certificate = rpCert,
                        trustConstraints = listOf(constraintWithOtherInfo),
                    ),
                    RicalCertificateInfo(
                        certificate = rpCert,
                        issuingCountry = null,
                        stateOrProvinceName = null,
                        issuer = null,
                        subject = null,
                        notBefore = null,
                        notAfter = null,
                    )
                ),
                id = 7L,
                latestRicalUrl = "https://example.com/rical",
                extensions = emptyMap()
            ),
            ricalProviderCertificateChain = X509CertChain(listOf(signerCert, rootCert))
        )

        val encoded = signedRical.generate(
            signingKey = AsymmetricKey.anonymous(signerKey, signerKey.curve.defaultSigningAlgorithm)
        )
        // Verify wire CBOR omits "isTrustAnchor" and omits empty "extensions"
        val coseSign1 = CoseSign1.fromDataItem(Cbor.decode(encoded))
        val payloadMap = Cbor.decode(coseSign1.payload!!)
        assertTrue(!payloadMap.hasKey("extensions"))
        val certInfo0Map = payloadMap["certificateInfos"].asArray[0]
        assertTrue(!certInfo0Map.hasKey("isTrustAnchor"))
        assertTrue(!certInfo0Map.hasKey("extensions"))
        assertTrue(!certInfo0Map["trustConstraints"].asArray[0].hasKey("extensions"))

        val parsed = SignedRical.parse(encoded)
        assertEquals(listOf(signerCert), parsed.ricalProviderCertificateChain.certificates)
        assertEquals("https://example.com/rical", parsed.rical.latestRicalUrl)

        val info0 = parsed.rical.certificateInfos[0]
        assertEquals("US", info0.issuingCountry)
        assertEquals("US-NY", info0.stateOrProvinceName)
        assertEquals(rpCert.encodedIssuer, info0.issuer)
        assertEquals(rpCert.encodedSubject, info0.subject)
        assertEquals(rpCert.validityNotBefore, info0.notBefore)
        assertEquals(rpCert.validityNotAfter, info0.notAfter)
        assertEquals(listOf(constraintWithOtherInfo), info0.trustConstraints)

        val info1 = parsed.rical.certificateInfos[1]
        assertEquals(null, info1.issuingCountry)
        assertEquals(null, info1.stateOrProvinceName)
        assertEquals(null, info1.issuer)
        assertEquals(null, info1.subject)
        assertEquals(null, info1.notBefore)
        assertEquals(null, info1.notAfter)
    }
}