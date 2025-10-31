package org.multipaz.trustmanagement

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509KeyUsage
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.buildX509Cert
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.mdoc.vical.Vical
import org.multipaz.mdoc.vical.VicalCertificateInfo
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toHex
import org.multipaz.util.truncateToWholeSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes


class TrustManagerTest {

    val caCertificate: X509Cert
    val intermediateCertificate: X509Cert
    val dsCertificate: X509Cert

    val dsValidInThePastCertificate: X509Cert
    val dsValidInTheFutureCertificate: X509Cert

    val ca2Certificate: X509Cert
    val ds2Certificate: X509Cert

    init {
        val now = Clock.System.now().truncateToWholeSeconds()

        val caKey = Crypto.createEcPrivateKey(EcCurve.P384)
        caCertificate = runBlocking {
            buildX509Cert(
                publicKey = caKey.publicKey,
                signingKey = AsymmetricKey.anonymous(caKey, caKey.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(1L),
                subject = X500Name.fromName("CN=Test TrustManager CA"),
                issuer = X500Name.fromName("CN=Test TrustManager CA"),
                validFrom = now - 1.hours,
                validUntil = now + 1.hours
            ) {
                includeSubjectKeyIdentifier()
                setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            }
        }

        val intermediateKey = Crypto.createEcPrivateKey(EcCurve.P384)
        intermediateCertificate = runBlocking {
            buildX509Cert(
                publicKey = intermediateKey.publicKey,
                signingKey = AsymmetricKey.anonymous(caKey, caKey.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(1L),
                subject = X500Name.fromName("CN=Test TrustManager Intermediate CA"),
                issuer = caCertificate.subject,
                validFrom = now - 1.hours,
                validUntil = now + 1.hours
            ) {
                includeSubjectKeyIdentifier()
                setAuthorityKeyIdentifierToCertificate(caCertificate)
                setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            }
        }

        val dsKey = Crypto.createEcPrivateKey(EcCurve.P384)
        dsCertificate = runBlocking {
            buildX509Cert(
                publicKey = dsKey.publicKey,
                signingKey = AsymmetricKey.anonymous(intermediateKey, intermediateKey.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(1L),
                subject = X500Name.fromName("CN=Test TrustManager DS"),
                issuer = intermediateCertificate.subject,
                validFrom = now - 1.hours,
                validUntil = now + 1.hours
            ) {
                includeSubjectKeyIdentifier()
                setAuthorityKeyIdentifierToCertificate(intermediateCertificate)
                setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            }
        }

        val dsValidInThePastKey = Crypto.createEcPrivateKey(EcCurve.P384)
        dsValidInThePastCertificate = runBlocking {
            buildX509Cert(
                publicKey = dsValidInThePastKey.publicKey,
                signingKey = AsymmetricKey.anonymous(intermediateKey, intermediateKey.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(1L),
                subject = X500Name.fromName("CN=Test TrustManager DS Valid In The Past"),
                issuer = intermediateCertificate.subject,
                validFrom = now - 3.hours,
                validUntil = now - 1.hours
            ) {
                includeSubjectKeyIdentifier()
                setAuthorityKeyIdentifierToCertificate(intermediateCertificate)
                setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            }
        }

        val dsValidInTheFutureKey = Crypto.createEcPrivateKey(EcCurve.P384)
        dsValidInTheFutureCertificate = runBlocking {
            buildX509Cert(
                publicKey = dsValidInTheFutureKey.publicKey,
                signingKey = AsymmetricKey.anonymous(intermediateKey, intermediateKey.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(1L),
                subject = X500Name.fromName("CN=Test TrustManager Valid In The Future"),
                issuer = intermediateCertificate.subject,
                validFrom = now + 1.hours,
                validUntil = now + 3.hours
            ) {
                includeSubjectKeyIdentifier()
                setAuthorityKeyIdentifierToCertificate(intermediateCertificate)
                setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            }
        }

        val ca2Key = Crypto.createEcPrivateKey(EcCurve.P384)
        ca2Certificate = runBlocking {
            buildX509Cert(
                publicKey = ca2Key.publicKey,
                signingKey = AsymmetricKey.anonymous(ca2Key, ca2Key.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(1L),
                subject = X500Name.fromName("CN=Test TrustManager CA2"),
                issuer = X500Name.fromName("CN=Test TrustManager CA2"),
                validFrom = now - 1.hours,
                validUntil = now + 1.hours
            ) {
                includeSubjectKeyIdentifier()
                setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            }
        }

        val ds2Key = Crypto.createEcPrivateKey(EcCurve.P384)
        ds2Certificate = runBlocking {
            buildX509Cert(
                publicKey = ds2Key.publicKey,
                signingKey = AsymmetricKey.anonymous(ca2Key, ca2Key.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(1L),
                subject = X500Name.fromName("CN=Test TrustManager DS2"),
                issuer = ca2Certificate.subject,
                validFrom = now - 1.hours,
                validUntil = now + 1.hours
            ) {
                includeSubjectKeyIdentifier()
                setAuthorityKeyIdentifierToCertificate(ca2Certificate)
                setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            }
        }
    }

    @Test
    fun happyFlow() = runTest {
        val trustManager = TrustManagerLocal(EphemeralStorage())

        trustManager.addX509Cert(intermediateCertificate, TrustMetadata())
        trustManager.addX509Cert(caCertificate, TrustMetadata())

        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(3, it.trustChain!!.certificates.size)
            assertEquals(caCertificate, it.trustChain.certificates.last())
        }
    }

    @Test
    fun validInThePast() = runTest {
        val trustManager = TrustManagerLocal(EphemeralStorage())

        trustManager.addX509Cert(intermediateCertificate, TrustMetadata())
        trustManager.addX509Cert(caCertificate, TrustMetadata())

        trustManager.verify(listOf(dsValidInThePastCertificate)).let {
            assertTrue(it.error!!.message!!.startsWith("Certificate is no longer valid"))
            assertFalse(it.isTrusted)
            assertEquals(3, it.trustChain!!.certificates.size)
            assertEquals(caCertificate, it.trustChain.certificates.last())
        }
    }

    @Test
    fun validInTheFuture() = runTest {
        val trustManager = TrustManagerLocal(EphemeralStorage())

        trustManager.addX509Cert(intermediateCertificate, TrustMetadata())
        trustManager.addX509Cert(caCertificate, TrustMetadata())

        trustManager.verify(listOf(dsValidInTheFutureCertificate)).let {
            assertTrue(it.error!!.message!!.startsWith("Certificate is not yet valid"))
            assertFalse(it.isTrusted)
            assertEquals(3, it.trustChain!!.certificates.size)
            assertEquals(caCertificate, it.trustChain.certificates.last())
        }
    }

    @Test
    fun happyFlowWithOnlyIntermediateCertificate() = runTest {
        val trustManager = TrustManagerLocal(EphemeralStorage())

        trustManager.addX509Cert(intermediateCertificate, TrustMetadata())

        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(2, it.trustChain!!.certificates.size)
            assertEquals(intermediateCertificate, it.trustChain.certificates.last())
        }
    }

    @Test
    fun happyFlowWithChainOfTwo() = runTest {
        val trustManager = TrustManagerLocal(EphemeralStorage())

        trustManager.addX509Cert(caCertificate, TrustMetadata())

        trustManager.verify(listOf(dsCertificate, intermediateCertificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(3, it.trustChain!!.certificates.size)
            assertEquals(caCertificate, it.trustChain.certificates.last())
        }
    }

    @Test
    fun trustPointNotCaCert() = runTest {
        val trustManager = TrustManagerLocal(EphemeralStorage())

        trustManager.addX509Cert(dsCertificate, TrustMetadata())

        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(1, it.trustChain!!.certificates.size)
            assertEquals(dsCertificate, it.trustChain.certificates.last())
        }
    }

    @Test
    fun happyFlowMultipleCerts() = runTest {
        val trustManager = TrustManagerLocal(EphemeralStorage())

        trustManager.addX509Cert(intermediateCertificate, TrustMetadata())
        trustManager.addX509Cert(caCertificate, TrustMetadata())
        trustManager.addX509Cert(ca2Certificate, TrustMetadata())

        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(3, it.trustChain!!.certificates.size)
            assertEquals(caCertificate, it.trustChain.certificates.last())
        }

        trustManager.verify(listOf(ds2Certificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(2, it.trustChain!!.certificates.size)
            assertEquals(ca2Certificate, it.trustChain.certificates.last())
        }
    }

    @Test
    fun noTrustPoints() = runTest {
        val trustManager = TrustManagerLocal(EphemeralStorage())

        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }
    }

    @Test
    fun skiAlreadyExists() = runTest {
        val storage = EphemeralStorage()
        val trustManager = TrustManagerLocal(storage)

        trustManager.addX509Cert(intermediateCertificate, TrustMetadata())
        trustManager.addX509Cert(caCertificate, TrustMetadata())
        val e = assertFailsWith(TrustPointAlreadyExistsException::class) {
            trustManager.addX509Cert(caCertificate, TrustMetadata())
        }
        assertEquals("TrustPoint with given SubjectKeyIdentifier already exists", e.message)
    }

    private data class TestIaca(
        val elboniaIaca: X509Cert,
        val elboniaIacaKey: EcPrivateKey,
        val atlantisIaca: X509Cert,
        val atlantisIacaKey: EcPrivateKey,
        val encodedSignedVical: ByteString,

        val elboniaDs: X509Cert,
        val elboniaDsKey: EcPrivateKey,
    )

    private suspend fun createTestIaca(): TestIaca {
        val now = Clock.System.now()
        val validFrom = now - 10.minutes
        val validUntil = now + 10.minutes

        val elboniaIacaKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val elboniaIaca = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(elboniaIacaKey),
            subject = X500Name.fromName("CN=Elbonia TrustManager CA"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = validFrom,
            validUntil = validUntil,
            issuerAltNameUrl = "https://example.com/elbonia/altname",
            crlUrl = "https://example.com/elbonia/crl"
        )
        val elboniaDsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val elboniaDs = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(
                privateKey = elboniaIacaKey,
                certChain = X509CertChain(listOf(elboniaIaca))
            ),
            dsKey = elboniaDsKey.publicKey,
            subject = X500Name.fromName("CN=Elbonia DS"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = validFrom,
            validUntil = validUntil
        )

        val atlantisIacaKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val atlantisIaca = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(atlantisIacaKey),
            subject = X500Name.fromName("CN=Atlantis TrustManager CA"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = validFrom,
            validUntil = validUntil,
            issuerAltNameUrl = "https://example.com/atlantis/altname",
            crlUrl = "https://example.com/atlantis/crl"
        )

        val vical = Vical(
            version = "1",
            vicalProvider = "Test VICAL provider",
            date = now,
            nextUpdate = null,
            vicalIssueID = null,
            certificateInfos = listOf(
                VicalCertificateInfo(
                    certificate = elboniaIaca,
                    docTypes = listOf("org.iso.18013.5.1.mDL")
                ),
                VicalCertificateInfo(
                    certificate = atlantisIaca,
                    docTypes = listOf("org.iso.18013.5.1.mDL")
                )
            )
        )

        val vicalKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val vicalCert = buildX509Cert(
            publicKey = vicalKey.publicKey,
            signingKey = AsymmetricKey.anonymous(vicalKey, vicalKey.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1),
            subject = X500Name.fromName("CN=Test VICAL provider"),
            issuer = X500Name.fromName("CN=Test VICAL provider"),
            validFrom = validFrom,
            validUntil = validUntil
        ) {
            includeSubjectKeyIdentifier()
        }

        val signedVical = SignedVical(vical, X509CertChain(listOf(vicalCert)))
        return TestIaca(
            elboniaIaca = elboniaIaca,
            elboniaIacaKey = elboniaIacaKey,
            atlantisIaca = atlantisIaca,
            atlantisIacaKey = atlantisIacaKey,
            encodedSignedVical = ByteString(
                signedVical.generate(AsymmetricKey.anonymous(vicalKey))
            ),
            elboniaDs = elboniaDs,
            elboniaDsKey = elboniaDsKey,
        )
    }

    @Test
    fun updateMetadataX509() = runTest {
        val storage = EphemeralStorage()
        val trustManager = TrustManagerLocal(storage)

        val entry = trustManager.addX509Cert(intermediateCertificate, TrustMetadata())
        assertEquals(setOf(
            intermediateCertificate.subjectKeyIdentifier!!.toHex(),
        ), trustManager.getTrustPoints().map { it.certificate.subjectKeyIdentifier!!.toHex() }.toSet())
        assertEquals(listOf(entry), trustManager.getEntries())

        val newEntry = trustManager.updateMetadata(entry,
            TrustMetadata(
                displayName = "New Display Name",
                displayIcon = ByteString(1, 2, 3),
                privacyPolicyUrl = "https://example.com/privacypolicy",
                testOnly = true
            )
        )
        assertNotEquals(newEntry, entry)
        assertEquals(listOf(newEntry), trustManager.getEntries())
        assertEquals("New Display Name", newEntry.metadata.displayName)
        assertEquals(ByteString(1, 2, 3), newEntry.metadata.displayIcon)
        assertEquals("https://example.com/privacypolicy", newEntry.metadata.privacyPolicyUrl)
        assertTrue(newEntry.metadata.testOnly)

        // Check verification now gets the new data...
        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(2, it.trustChain!!.certificates.size)
            assertEquals(intermediateCertificate, it.trustChain.certificates.last())
            assertEquals(1, it.trustPoints.size)
            assertEquals(newEntry.metadata, it.trustPoints[0].metadata)
        }

        val otherTrustManager = TrustManagerLocal(storage)
        assertEquals(1, otherTrustManager.getEntries().size)
        val entryFromOtherTrustManager = otherTrustManager.getEntries().first()
        assertEquals(entryFromOtherTrustManager, newEntry)
    }

    @Test
    fun updateMetadataVical() = runTest {
        val storage = EphemeralStorage()
        val trustManager = TrustManagerLocal(storage)

        val testIaca = createTestIaca()
        val entry = trustManager.addVical(testIaca.encodedSignedVical, TrustMetadata())

        val newEntry = trustManager.updateMetadata(entry,
            TrustMetadata(
                displayName = "New Display Name",
                displayIcon = ByteString(1, 2, 3),
                privacyPolicyUrl = "https://example.com/privacypolicy",
                testOnly = true
            )
        )
        assertNotEquals(newEntry, entry)
        assertEquals(listOf(newEntry), trustManager.getEntries())
        assertEquals("New Display Name", newEntry.metadata.displayName)
        assertEquals(ByteString(1, 2, 3), newEntry.metadata.displayIcon)
        assertEquals("https://example.com/privacypolicy", newEntry.metadata.privacyPolicyUrl)
        assertTrue(newEntry.metadata.testOnly)

        val otherTrustManager = TrustManagerLocal(storage)
        assertEquals(1, otherTrustManager.getEntries().size)
        val entryFromOtherTrustManager = otherTrustManager.getEntries().first()
        assertEquals(entryFromOtherTrustManager, newEntry)
    }

    @Test
    fun deleteEntryX509() = runTest {
        val storage = EphemeralStorage()
        val trustManager = TrustManagerLocal(storage)

        val entry = trustManager.addX509Cert(intermediateCertificate, TrustMetadata())
        assertEquals(setOf(
            intermediateCertificate.subjectKeyIdentifier!!.toHex(),
        ), trustManager.getTrustPoints().map { it.certificate.subjectKeyIdentifier!!.toHex() }.toSet())
        assertEquals(listOf(entry), trustManager.getEntries())

        // Check verification works
        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(2, it.trustChain!!.certificates.size)
            assertEquals(intermediateCertificate, it.trustChain.certificates.last())
        }

        // Delete entry
        assertTrue(trustManager.deleteEntry(entry))

        // Check verification now fails
        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }

        // Check it's the same when reloading the TrustManager
        val otherTrustManager = TrustManagerLocal(storage)
        assertEquals(0, otherTrustManager.getEntries().size)

        otherTrustManager.verify(listOf(dsCertificate)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }
    }

    @Test
    fun deleteEntryVical() = runTest {
        val storage = EphemeralStorage()
        val trustManager = TrustManagerLocal(storage)

        val testIaca = createTestIaca()
        val entry = trustManager.addVical(testIaca.encodedSignedVical, TrustMetadata())

        // Check verification works
        trustManager.verify(listOf(testIaca.elboniaDs)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(2, it.trustChain!!.certificates.size)
            assertEquals(testIaca.elboniaIaca, it.trustChain.certificates.last())
        }

        // Delete entry
        assertTrue(trustManager.deleteEntry(entry))

        // Check verification now fails
        trustManager.verify(listOf(testIaca.elboniaDs)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }

        // Check it's the same when reloading the TrustManager
        val otherTrustManager = TrustManagerLocal(storage)
        assertEquals(0, otherTrustManager.getEntries().size)

        otherTrustManager.verify(listOf(testIaca.elboniaDs)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }
    }

    @Test
    fun deleteAll() = runTest {
        val storage = EphemeralStorage()
        val trustManager = TrustManagerLocal(storage)

        val entryX509 = trustManager.addX509Cert(intermediateCertificate, TrustMetadata())
        assertEquals(setOf(
            intermediateCertificate.subjectKeyIdentifier!!.toHex(),
        ), trustManager.getTrustPoints().map { it.certificate.subjectKeyIdentifier!!.toHex() }.toSet())

        val testIaca = createTestIaca()
        val entryVical = trustManager.addVical(testIaca.encodedSignedVical, TrustMetadata())

        assertEquals(listOf(entryX509, entryVical), trustManager.getEntries())

        // Check verification works
        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(2, it.trustChain!!.certificates.size)
            assertEquals(intermediateCertificate, it.trustChain.certificates.last())
        }
        trustManager.verify(listOf(testIaca.elboniaDs)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(2, it.trustChain!!.certificates.size)
            assertEquals(testIaca.elboniaIaca, it.trustChain.certificates.last())
        }

        trustManager.deleteAll()
        assertEquals(0, trustManager.getEntries().size)

        // Check verification now fails
        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }
        trustManager.verify(listOf(testIaca.elboniaDs)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }

        // Check it's the same when reloading the TrustManager
        val otherTrustManager = TrustManagerLocal(storage)
        assertEquals(0, otherTrustManager.getEntries().size)

        otherTrustManager.verify(listOf(dsCertificate)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }
        otherTrustManager.verify(listOf(testIaca.elboniaDs)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }
    }

    @Test
    fun persistence() = runTest {
        val storage = EphemeralStorage()
        val trustManager = TrustManagerLocal(storage)

        val intermediaCertificateEntry = trustManager.addX509Cert(intermediateCertificate, TrustMetadata())
        val caCertificateEntry = trustManager.addX509Cert(caCertificate, TrustMetadata())
        val ca2CertificateEntry = trustManager.addX509Cert(ca2Certificate, TrustMetadata())
        assertEquals(setOf(
            caCertificate.subjectKeyIdentifier!!.toHex(),
            ca2Certificate.subjectKeyIdentifier!!.toHex(),
            intermediateCertificate.subjectKeyIdentifier!!.toHex(),
        ), trustManager.getTrustPoints().map { it.certificate.subjectKeyIdentifier!!.toHex() }.toSet())

        assertEquals(listOf(
            intermediaCertificateEntry,
            caCertificateEntry,
            ca2CertificateEntry
        ), trustManager.getEntries())

        assertTrue(trustManager.deleteEntry(ca2CertificateEntry))

        assertEquals(listOf(
            intermediaCertificateEntry,
            caCertificateEntry,
        ), trustManager.getEntries())

        assertEquals(setOf(
            caCertificate.subjectKeyIdentifier!!.toHex(),
            intermediateCertificate.subjectKeyIdentifier!!.toHex(),
        ), trustManager.getTrustPoints().map { it.certificate.subjectKeyIdentifier!!.toHex() }.toSet())
        assertFalse(trustManager.deleteEntry(ca2CertificateEntry))

        assertEquals(listOf(
            intermediaCertificateEntry,
            caCertificateEntry,
        ), trustManager.getEntries())

        // Now add the VICAL into the mix...
        val testIaca = createTestIaca()
        val vicalEntry = trustManager.addVical(testIaca.encodedSignedVical, TrustMetadata())
        assertEquals(setOf(
            caCertificate.subjectKeyIdentifier!!.toHex(),
            intermediateCertificate.subjectKeyIdentifier!!.toHex(),
            testIaca.elboniaIaca.subjectKeyIdentifier!!.toHex(),
            testIaca.atlantisIaca.subjectKeyIdentifier!!.toHex(),
        ), trustManager.getTrustPoints().map { it.certificate.subjectKeyIdentifier!!.toHex() }.toSet())

        assertEquals(listOf(
            intermediaCertificateEntry,
            caCertificateEntry,
            vicalEntry
        ), trustManager.getEntries())

        // Check we can remove the VICAL
        assertTrue(trustManager.deleteEntry(vicalEntry))
        assertFalse(trustManager.deleteEntry(vicalEntry))
        assertEquals(setOf(
            caCertificate.subjectKeyIdentifier!!.toHex(),
            intermediateCertificate.subjectKeyIdentifier!!.toHex(),
        ), trustManager.getTrustPoints().map { it.certificate.subjectKeyIdentifier!!.toHex() }.toSet())

        assertEquals(listOf(
            intermediaCertificateEntry,
            caCertificateEntry,
        ), trustManager.getEntries())

        // Add the VICAL back to check persistence...
        val vicalEntry2 = trustManager.addVical(testIaca.encodedSignedVical, TrustMetadata())
        assertEquals(setOf(
            caCertificate.subjectKeyIdentifier!!.toHex(),
            intermediateCertificate.subjectKeyIdentifier!!.toHex(),
            testIaca.elboniaIaca.subjectKeyIdentifier!!.toHex(),
            testIaca.atlantisIaca.subjectKeyIdentifier!!.toHex(),
        ), trustManager.getTrustPoints().map { it.certificate.subjectKeyIdentifier!!.toHex() }.toSet())

        assertEquals(listOf(
            intermediaCertificateEntry,
            caCertificateEntry,
            vicalEntry2
        ), trustManager.getEntries())

        val otherTrustManager = TrustManagerLocal(storage)
        assertEquals(setOf(
            caCertificate.subjectKeyIdentifier!!.toHex(),
            intermediateCertificate.subjectKeyIdentifier!!.toHex(),
            testIaca.elboniaIaca.subjectKeyIdentifier!!.toHex(),
            testIaca.atlantisIaca.subjectKeyIdentifier!!.toHex(),
        ), otherTrustManager.getTrustPoints().map { it.certificate.subjectKeyIdentifier!!.toHex() }.toSet())

        assertEquals(listOf(
            intermediaCertificateEntry,
            caCertificateEntry,
            vicalEntry2
        ), otherTrustManager.getEntries())

        val otherStorage = EphemeralStorage()
        val yetAnotherTrustManager = TrustManagerLocal(otherStorage)
        assertEquals(emptySet(), yetAnotherTrustManager.getTrustPoints().map { it.certificate.subjectKeyIdentifier!!.toHex() }.toSet())
    }

    @Test
    fun testVicalTrustManager() = runTest {
        val now = Clock.System.now()
        val validFrom = now - 10.minutes
        val validUntil = now + 10.minutes

        val vicalKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val vicalCert = buildX509Cert(
            publicKey = vicalKey.publicKey,
            signingKey = AsymmetricKey.anonymous(vicalKey, vicalKey.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1),
            subject = X500Name.fromName("CN=Test VICAL provider"),
            issuer = X500Name.fromName("CN=Test VICAL provider"),
            validFrom = validFrom,
            validUntil = validUntil
        ) {
            includeSubjectKeyIdentifier()
        }

        val vical = Vical(
            version = "1",
            vicalProvider = "Test VICAL provider",
            date = now,
            nextUpdate = null,
            vicalIssueID = null,
            certificateInfos = listOf(
                VicalCertificateInfo(
                    certificate = caCertificate,
                    docTypes = listOf("org.iso.18013.5.1.mDL")
                ),
                VicalCertificateInfo(
                    certificate = ca2Certificate,
                    docTypes = listOf("org.iso.18013.5.1.mDL")
                )
            )
        )
        val signedVical = SignedVical(vical, X509CertChain(listOf(vicalCert)))
        val trustManager = VicalTrustManager(signedVical)

        // Happy flow
        //
        trustManager.verify(listOf(dsCertificate, intermediateCertificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(3, it.trustChain!!.certificates.size)
            assertEquals(caCertificate, it.trustChain.certificates.last())
        }
        trustManager.verify(listOf(ds2Certificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(2, it.trustChain!!.certificates.size)
            assertEquals(ca2Certificate, it.trustChain.certificates.last())
        }

        // Valid in the past
        //
        trustManager.verify(listOf(dsValidInThePastCertificate, intermediateCertificate)).let {
            assertTrue(it.error!!.message!!.startsWith("Certificate is no longer valid"))
            assertFalse(it.isTrusted)
            assertEquals(3, it.trustChain!!.certificates.size)
            assertEquals(caCertificate, it.trustChain.certificates.last())
        }

        // Valid in the future
        //
        trustManager.verify(listOf(dsValidInTheFutureCertificate, intermediateCertificate)).let {
            assertTrue(it.error!!.message!!.startsWith("Certificate is not yet valid"))
            assertFalse(it.isTrusted)
            assertEquals(3, it.trustChain!!.certificates.size)
            assertEquals(caCertificate, it.trustChain.certificates.last())
        }

        // No trust point
        //
        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }
    }

    @Test
    fun testCompositeTrustManager() = runTest {
        val tm1 = TrustManagerLocal(EphemeralStorage()).apply { addX509Cert(caCertificate, TrustMetadata()) }
        val tm2 = TrustManagerLocal(EphemeralStorage()).apply { addX509Cert(ca2Certificate, TrustMetadata()) }
        val trustManager = CompositeTrustManager(listOf(tm1, tm2))

        // Happy flow
        //
        trustManager.verify(listOf(dsCertificate, intermediateCertificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(3, it.trustChain!!.certificates.size)
            assertEquals(caCertificate, it.trustChain.certificates.last())
            assertEquals(1, it.trustPoints.size)
        }
        trustManager.verify(listOf(ds2Certificate)).let {
            assertEquals(null, it.error)
            assertTrue(it.isTrusted)
            assertEquals(2, it.trustChain!!.certificates.size)
            assertEquals(ca2Certificate, it.trustChain.certificates.last())
            assertEquals(1, it.trustPoints.size)
        }

        // No trust point
        //
        trustManager.verify(listOf(dsCertificate)).let {
            assertEquals("No trusted root certificate could not be found", it.error?.message)
            assertFalse(it.isTrusted)
            assertNull(it.trustChain)
            assertEquals(0, it.trustPoints.size)
        }
    }
}