package org.multipaz.mdoc.rical

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class RicalGeneratorTest {
    private suspend fun createSelfsignedCert(
        key: EcPrivateKey,
        subjectAndIssuer: X500Name
    ): X509Cert {
        val now = Clock.System.now()
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
        val ricalCert = createSelfsignedCert(ricalKey, X500Name.fromName("CN=Test VICAL"))

        val rp1Cert = createSelfsignedCert(
            Crypto.createEcPrivateKey(EcCurve.P256), X500Name.fromName("CN=Issuer 1 IACA"))
        val rp2Cert = createSelfsignedCert(
            Crypto.createEcPrivateKey(EcCurve.P256), X500Name.fromName("CN=Issuer 2 IACA"))
        val rp3Cert = createSelfsignedCert(
            Crypto.createEcPrivateKey(EcCurve.P256), X500Name.fromName("CN=Issuer 3 IACA"))

        val ricalDate = Clock.System.now()
        val ricalNextUpdate = ricalDate + 30.days
        val ricalNotAfter = ricalDate + 40.days
        val ricalIssueID = 42L
        val ricalExtensions = buildMap {
            put("org.example.foo", Tstr("blah"))
            put("org.example.bar", Uint(42UL))
        }

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
                    RicalCertificateInfo(certificate = rp2Cert),
                    RicalCertificateInfo(certificate = rp3Cert),
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

        assertEquals(
            rp2Cert,
            decodedSignedRical.rical.certificateInfos[1].certificate
        )

        assertEquals(
            rp3Cert,
            decodedSignedRical.rical.certificateInfos[2].certificate
        )
    }
}