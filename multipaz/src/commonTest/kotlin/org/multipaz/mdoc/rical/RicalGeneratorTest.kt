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
}