package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.OID
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromHex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class X509CrlTests {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun roundtrip() = runTest {
        val signingKey = AsymmetricKey.ephemeral()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val newCrl = buildX509Crl(
            signingKey = signingKey,
            issuer = X500Name.fromName("CN=foobar"),
            thisUpdate = now,
            nextUpdate = now + 5.hours
        ) {
            addRevoked(ASN1Integer(100L), now)
            addRevoked(ASN1Integer(101L), now)
        }
        val crl = X509Crl(newCrl.encoded)
        crl.verify(signingKey.publicKey)
        assertEquals("foobar", crl.issuer.components["2.5.4.3"]!!.value)
        assertEquals(now, crl.thisUpdate)
        assertEquals(now + 5.hours, crl.nextUpdate)
        assertEquals(2, crl.revokedSerials.size)
        assertEquals(ASN1Integer(100L), crl.revokedSerials[0])
        assertEquals(ASN1Integer(101L), crl.revokedSerials[1])
    }

    @Test
    fun extensions() = runTest {
        val signingKey = AsymmetricKey.ephemeral()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val newCrl = buildX509Crl(
            signingKey = signingKey,
            issuer = X500Name.fromName("CN=foobar"),
            thisUpdate = now,
            nextUpdate = null
        ) {
            addExtension("1.2.3", false, ByteString(3, 1, 2))
            addRevoked(ASN1Integer(1001L), now)
        }
        val crl = X509Crl(newCrl.encoded)
        crl.verify(signingKey.publicKey)
        assertEquals("foobar", crl.issuer.components["2.5.4.3"]!!.value)
        assertEquals(now, crl.thisUpdate)
        assertNull(crl.nextUpdate)
        assertEquals(1, crl.revokedSerials.size)
        assertEquals(ASN1Integer(1001L), crl.revokedSerials[0])
        assertEquals(1, crl.extensions.size)
        assertEquals("1.2.3", crl.extensions[0].oid)
        assertEquals(ByteString(3, 1, 2), crl.extensions[0].data)
    }

    @Test
    fun roundtripNoRevokedCertificates() = runTest {
        val signingKey = AsymmetricKey.ephemeral()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val newCrl = buildX509Crl(
            signingKey = signingKey,
            issuer = X500Name.fromName("CN=foobar"),
            thisUpdate = now,
            nextUpdate = now + 5.hours
        ) {}
        val crl = X509Crl(newCrl.encoded)
        crl.verify(signingKey.publicKey)
        // RFC 5280 section 5.1.2.6: revokedCertificates must be absent when empty,
        // so the TBS is version, signature, issuer, thisUpdate, nextUpdate.
        val tbs = (ASN1.decode(crl.encoded.toByteArray()) as ASN1Sequence).elements[0] as ASN1Sequence
        assertEquals(5, tbs.elements.size)
        assertEquals(now + 5.hours, crl.nextUpdate)
        assertTrue(crl.revokedSerials.isEmpty())
    }

    @Test
    fun roundtripNoRevokedCertificatesWithExtensions() = runTest {
        val signingKey = AsymmetricKey.ephemeral()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val newCrl = buildX509Crl(
            signingKey = signingKey,
            issuer = X500Name.fromName("CN=foobar"),
            thisUpdate = now,
            nextUpdate = null
        ) {
            addExtension("1.2.3", false, ByteString(3, 1, 2))
        }
        val crl = X509Crl(newCrl.encoded)
        crl.verify(signingKey.publicKey)
        assertNull(crl.nextUpdate)
        assertTrue(crl.revokedSerials.isEmpty())
        assertEquals(1, crl.extensions.size)
        assertEquals("1.2.3", crl.extensions[0].oid)
    }

    @Test
    fun testOpenssl() = runTest {
        // Test openssl-created certificate and crl
        val cert = X509Cert.fromPem(
            """
                -----BEGIN CERTIFICATE-----
                MIIBdzCB/qADAgECAgkAxnFXbbfRdGIwCgYIKoZIzj0EAwMwFzEVMBMGA1UEAwwM
                VGVzdCBSb290IENBMB4XDTI1MTEwNzE4MjE0NVoXDTM1MTEwNTE4MjE0NVowFzEV
                MBMGA1UEAwwMVGVzdCBSb290IENBMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEy78T
                JLUFEFnQOVHwehn3a8zcastkit5M9aP8GdX/6DepiQoO3DH3EuMNdW9TH/xN/HyE
                RL7xZ8IvdCVVgFMlgbjJGoCJ6YT0UAsmYSzIW8/KMFXkCT3413WaoxMa0z/voxYw
                FDASBgNVHRMBAf8ECDAGAQH/AgEBMAoGCCqGSM49BAMDA2gAMGUCMQCKpZLxWAoW
                9knh3HBftfBTM0HtjMBhXaAh0kle/r7pAj5srIlMuAuzh89qjE8xbKoCMEpRvHCQ
                ED7RsZprWidU++/4CA+qqopplTAkecgOP/C9e4clYFmhRtIWOtmPnCrgaA==
                -----END CERTIFICATE-----
            """.trimIndent()
        )
        // NB: this is v1 CRL, no version element in tbs, no extensions
        val crl = X509Crl.fromPem(
            """
                -----BEGIN X509 CRL-----
                MIHYMGEwCgYIKoZIzj0EAwMwFzEVMBMGA1UEAwwMVGVzdCBSb290IENBFw0yNTEx
                MDcxODUzNDRaFw0yNjAyMTUxODUzNDRaMBwwGgIJANBJaBrsBCc9Fw0yNTExMDcx
                ODUzMjVaMAoGCCqGSM49BAMDA2cAMGQCMAG3AQWHiEC6HVsVte1pSkiHscH9ECM0
                wD9BeT/Z3MriagFviTjEkl7EKTu57RwJkgIwHJc1zkZxryjjZ0dpUnKwR6L9ryDU
                pIfOPUtBx5HERJkza8Us1L864SE9gGO/DhyP
                -----END X509 CRL-----
            """.trimIndent()
        )
        crl.verify(cert.ecPublicKey)
        assertEquals(0, crl.version)
        assertEquals(cert.subject, crl.issuer)
        val expectedSerial = ASN1.decode("020900d049681aec04273d".fromHex()) as ASN1Integer
        assertEquals(expectedSerial, crl.revokedSerials[0])
    }

    @Test
    fun testOpensslNoRevokedCertificates() = runTest {
        // Test openssl-created certificate and crl
        val cert = X509Cert.fromPem(
            """
                -----BEGIN CERTIFICATE-----
                MIIBgzCCASmgAwIBAgIUI/4l9PGA99RwM0t61oUXlF7aURgwCgYIKoZIzj0EAwIw
                FzEVMBMGA1UEAwwMVGVzdCBSb290IENBMB4XDTI2MTAwMTA4NTMxNFoXDTM2MDky
                ODA4NTMxNFowFzEVMBMGA1UEAwwMVGVzdCBSb290IENBMFkwEwYHKoZIzj0CAQYI
                KoZIzj0DAQcDQgAEx8/MynFdzQcYgRIv5hZuorOo2HAe35YXsN7vCcDpPiV1/17e
                DXqPqaAhyIIv7fcJ+4JFLDgXxefDUNKr8X//WaNTMFEwHQYDVR0OBBYEFBpzTYLV
                sLBhLkaisLATPsb2WTYBMB8GA1UdIwQYMBaAFBpzTYLVsLBhLkaisLATPsb2WTYB
                MA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAJ6IPLDznPmcTn2N
                Q6CpkrgHhS1bt++pxmPI9ApaDHaDAiBBarO+A+QfmQOsoGCeEvFPeLSK0s/6TzZW
                UKZnta1vjA==
                -----END CERTIFICATE-----
            """.trimIndent()
        )
        // NB: this is v2 CRL with the CRL Number extension and no revoked certificates,
        // so the revokedCertificates element is absent from tbs (RFC 5280 section 5.1.2.6)
        val crl = X509Crl.fromPem(
            """
                -----BEGIN X509 CRL-----
                MIGuMFYCAQEwCgYIKoZIzj0EAwIwFzEVMBMGA1UEAwwMVGVzdCBSb290IENBFw0y
                NjEwMDEwODUzMTRaFw0zNjA5MjgwODUzMTRaoA4wDDAKBgNVHRQEAwIBATAKBggq
                hkjOPQQDAgNIADBFAiEAn4oQoFfoyKhm6GyeS0fAjQ050+fMYVuZQHVdOvChZVkC
                IEeIfRppuAjcRzI4iPUUKZtCuF2Kjjt4qpcoNPpOSBvi
                -----END X509 CRL-----
            """.trimIndent()
        )
        crl.verify(cert.ecPublicKey)
        assertEquals(1, crl.version)
        assertEquals(cert.subject, crl.issuer)
        assertEquals(Instant.parse("2036-09-28T08:53:14Z"), crl.nextUpdate)
        assertTrue(crl.revokedSerials.isEmpty())
        assertEquals(1, crl.extensions.size)
        // CRL Number
        assertEquals("2.5.29.20", crl.extensions[0].oid)
    }

    @Test
    fun testNist() {
        // NB: this is v2 CRL, has extension
        val crl = X509Crl.fromPem(
            """
                -----BEGIN X509 CRL-----
                MIIBYDCBygIBATANBgkqhkiG9w0BAQUFADBDMRMwEQYKCZImiZPyLGQBGRYDY29t
                MRcwFQYKCZImiZPyLGQBGRYHZXhhbXBsZTETMBEGA1UEAxMKRXhhbXBsZSBDQRcN
                MDUwMjA1MTIwMDAwWhcNMDUwMjA2MTIwMDAwWjAiMCACARIXDTA0MTExOTE1NTcw
                M1owDDAKBgNVHRUEAwoBAaAvMC0wHwYDVR0jBBgwFoAUCGivhTPIOUp6+IKTjnBq
                SiCELDIwCgYDVR0UBAMCAQwwDQYJKoZIhvcNAQEFBQADgYEAItwYffcIzsx10NBq
                m60Q9HYjtIFutW2+DvsVFGzIF20f7pAXom9g5L2qjFXejoRvkvifEBInr0rUL4Xi
                NkR9qqNMJTgV/wD9Pn7uPSYS69jnK2LiK8NGgO94gtEVxtCccmrLznrtZ5mLbnCB
                fUNCdMGmr8FVF6IzTNYGmCuk/C4=
                -----END X509 CRL-----
            """.trimIndent()
        )
        assertEquals(1, crl.version)
        assertEquals(ASN1Integer(0x12L), crl.revokedSerials[0])
        assertEquals(2, crl.extensions.size)
        assertEquals(OID.X509_EXTENSION_AUTHORITY_KEY_IDENTIFIER.oid, crl.extensions[0].oid)
    }
}