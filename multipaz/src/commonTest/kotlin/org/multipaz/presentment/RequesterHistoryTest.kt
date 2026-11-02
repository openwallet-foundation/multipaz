package org.multipaz.presentment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.request.Iso18013RequesterIdentity
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustManager
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.toHex

class RequesterHistoryTest {
    private class FakeClock(var nowInstant: Instant) : Clock {
        override fun now(): Instant = nowInstant
    }

    @Test
    fun trustedRequesterMatchesAcrossRotatedLeafCerts() = runTest {
        val storage = EphemeralStorage()
        val clock = FakeClock(Instant.parse("2026-10-30T10:00:00Z"))
        val history = SimpleRequesterHistory(storage = storage, clock = clock)
        val trustManager = TrustManager(storage = storage, identifier = "readers")

        val validFrom = clock.now() - 1.days
        val validUntil = clock.now() + 10.days
        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(rootKey),
            subject = X500Name.fromName("C=US,CN=Test Reader Root"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = validFrom,
            validUntil = validUntil,
            crlUrl = "https://verifier.example.com/crl",
        )
        val rootSigningKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(rootCert)),
            privateKey = rootKey,
        )

        trustManager.addX509Cert(
            certificate = rootCert,
            metadata = TrustMetadata(displayName = "Example Verifier"),
        )

        val leafKey1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val leafCert1 = MdocUtil.generateReaderCertificate(
            readerRootKey = rootSigningKey,
            readerKey = leafKey1.publicKey,
            subject = X500Name.fromName("CN=Leaf 1"),
            dnsName = null,
            serial = ASN1Integer.fromRandom(128),
            validFrom = validFrom,
            validUntil = validUntil,
        )
        val leafKey2 = Crypto.createEcPrivateKey(EcCurve.P256)
        val leafCert2 = MdocUtil.generateReaderCertificate(
            readerRootKey = rootSigningKey,
            readerKey = leafKey2.publicKey,
            subject = X500Name.fromName("CN=Leaf 2"),
            dnsName = null,
            serial = ASN1Integer.fromRandom(128),
            validFrom = validFrom,
            validUntil = validUntil,
        )

        val trustResult1 = trustManager.verify(listOf(leafCert1), atTime = clock.now())
        val trustMetadata1 = trustResult1.trustPoints.single().metadata
        assertEquals(rootCert.subjectKeyIdentifier!!.toHex(), trustMetadata1.identifier)

        val requester1 = Requester(
            requesterIdentities = listOf(Iso18013RequesterIdentity(X509CertChain(listOf(leafCert1))))
        )
        val trusted1 = TrustedRequesterIdentity(requester1.requesterIdentities.single(), trustMetadata1)

        assertNull(history.getEntry(requester1, trusted1))

        val firstShareTime = clock.now()
        history.recordShare(requester1, trusted1)

        // Second visit uses a newly rotated leaf certificate under the same root CA
        clock.nowInstant = firstShareTime + 15.minutes
        val trustResult2 = trustManager.verify(listOf(leafCert2), atTime = clock.now())
        val requester2 = Requester(
            requesterIdentities = listOf(Iso18013RequesterIdentity(X509CertChain(listOf(leafCert2))))
        )
        val trusted2 = TrustedRequesterIdentity(
            requester2.requesterIdentities.single(),
            trustResult2.trustPoints.single().metadata,
        )

        val entryAfterFirst = assertNotNull(history.getEntry(requester2, trusted2))
        assertEquals(1, entryAfterFirst.shareCount)
        assertEquals(firstShareTime, entryAfterFirst.firstSharedAt)
        assertEquals(firstShareTime, entryAfterFirst.lastSharedAt)

        val secondShareTime = clock.now()
        history.recordShare(requester2, trusted2)

        val entryAfterSecond = assertNotNull(history.getEntry(requester1, trusted1))
        assertEquals(2, entryAfterSecond.shareCount)
        assertEquals(firstShareTime, entryAfterSecond.firstSharedAt)
        assertEquals(secondShareTime, entryAfterSecond.lastSharedAt)
    }

    @Test
    fun webOriginAndAnonymousRequesters() = runTest {
        val storage = EphemeralStorage()
        val clock = FakeClock(Instant.parse("2026-10-30T10:00:00Z"))
        val history = SimpleRequesterHistory(storage = storage, clock = clock)

        val webRequester = Requester(origin = "https://shop.example.com")
        assertNull(history.getEntry(webRequester, null))
        history.recordShare(webRequester, null)
        val webEntry = assertNotNull(history.getEntry(webRequester, null))
        assertEquals(1, webEntry.shareCount)

        // Anonymous proximity reader cannot be tracked across visits
        val anonymousProximity = Requester()
        history.recordShare(anonymousProximity, null)
        assertNull(history.getEntry(anonymousProximity, null))
    }
}
