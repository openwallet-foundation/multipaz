package org.multipaz.certifiedkeys

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.truncateToWholeSeconds
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private data class CertifiedKeyTestData(
    var currentTime: Instant = Clock.System.now(),
    var certifyKeysNumCalled: Int = 0,
    var disableCertifyKeys: Boolean = false
)

class CertifiedKeyManagerTest {

    private suspend fun certifyReaderKeys(
        readerKeys: List<KeyAttestation>,
        readerRootKey: AsymmetricKey.X509Certified,
        random: Random = Random.Default,
        atTime: Instant = Clock.System.now(),
        validFor: Duration = 30.days,
        jitterSize: Duration = 12.hours
    ): List<X509CertChain> {
        return readerKeys.map { readerKey ->
            // Introduce a bit of jitter so it's not possible for someone to correlate two keys
            val jitterFrom = jitterSize * random.nextDouble()
            val jitterUntil = jitterSize * random.nextDouble()
            val validFrom = atTime - jitterFrom
            val validUntil = atTime + jitterUntil + validFor
            val readerCert = MdocUtil.generateReaderCertificate(
                readerRootKey = readerRootKey,
                readerKey = readerKey.publicKey,
                subject = X500Name.fromName("CN=Reader Key"),
                dnsName = "example.com",
                serial = ASN1Integer.fromRandom(numBits = 128, random = random),
                validFrom = validFrom.truncateToWholeSeconds(),
                validUntil = validUntil.truncateToWholeSeconds(),
                extensions = emptyList()
            )
            X509CertChain(certificates = listOf(readerCert))
        }
    }

    private suspend fun createKeyManager(
        clientStorage: Storage,
        clientSecureArea: SecureArea,
        testData: CertifiedKeyTestData,
        numKeys: Int = 10
    ): CertifiedKeyManager {
        val now = Clock.System.now()
        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.AnonymousExplicit(readerRootKey),
            subject = X500Name.fromName("CN=Test Reader Root CA"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = now - 30.days,
            validUntil = now + 120.days,
            crlUrl = "https://www.example.com/crl"
        )
        val readerRootKeyCertified = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(certificates = listOf(readerRootCert)),
            privateKey = readerRootKey
        )

        return CertifiedKeyManager(
            secureArea = clientSecureArea,
            storage = clientStorage,
            numKeys = numKeys
        ) { readerKeys ->
            if (testData.disableCertifyKeys) {
                throw IllegalStateException("Server is disabled")
            }
            testData.certifyKeysNumCalled += 1
            certifyReaderKeys(
                readerKeys = readerKeys,
                readerRootKey = readerRootKeyCertified,
                atTime = testData.currentTime
            )
        }
    }

    @Test
    fun testCertifiedKeysHappyPath() = runTest {
        val testData = CertifiedKeyTestData()
        val clientStorage = EphemeralStorage()
        val clientSecureArea = SoftwareSecureArea.create(clientStorage)
        val manager = createKeyManager(clientStorage, clientSecureArea, testData)

        val (_, certChain) = manager.getKey(atTime = testData.currentTime)
        certChain.validate(validateAt = testData.currentTime)
        assertEquals(1, certChain.certificates.size)

        assertEquals(1, testData.certifyKeysNumCalled)

        // Check validity dates, taking into account the jitter
        val readerCert = certChain.certificates[0]
        assertTrue(readerCert.validityNotBefore <= testData.currentTime)
        assertTrue(readerCert.validityNotBefore >= testData.currentTime - 12.hours)
        assertTrue(readerCert.validityNotAfter >= testData.currentTime + 30.days)
        assertTrue(readerCert.validityNotAfter <= testData.currentTime + 30.days + 12.hours)

        // Use up just enough keys to cause replenishing on the call following the next call
        repeat(5) {
            val (keyInfo, _) = manager.getKey(atTime = testData.currentTime)
            manager.markKeyAsUsed(keyInfo)
        }

        assertEquals(1, testData.certifyKeysNumCalled)
        val (keyInfo, _) = manager.getKey(atTime = testData.currentTime)
        assertEquals(2, testData.certifyKeysNumCalled)
    }

    @Test
    fun testCertifiedKeysReplenish() = runTest {
        val testData = CertifiedKeyTestData()
        val clientStorage = EphemeralStorage()
        val clientSecureArea = SoftwareSecureArea.create(clientStorage)
        val manager = createKeyManager(clientStorage, clientSecureArea, testData)

        // First call should cause 1 certify call
        val (_, _) = manager.getKey(atTime = testData.currentTime)
        assertEquals(1, testData.certifyKeysNumCalled)

        // Unless we use the key, it won't get replenished, so check getKey() can be called 100 times
        // without causing any additional certify calls.
        repeat(100) { manager.getKey(atTime = testData.currentTime) }
        assertEquals(1, testData.certifyKeysNumCalled)

        // Now use up 5 keys, and make sure we saw different keys everytime. Because we only
        // replenish when we fall below 50% this means no additional certify call is done. Check this.
        val seenAliases = mutableSetOf<String>()
        repeat(5) {
            val (keyInfo, _) = manager.getKey(atTime = testData.currentTime)
            manager.markKeyAsUsed(keyInfo)
            seenAliases.add(keyInfo.alias)
        }
        assertEquals(5, seenAliases.size)
        assertEquals(1, testData.certifyKeysNumCalled)

        // Next time we'll use a key it'll cause a certify call to replenish. Check this.
        val (keyInfo, _) = manager.getKey(atTime = testData.currentTime)
        manager.markKeyAsUsed(keyInfo)
        assertEquals(2, testData.certifyKeysNumCalled)

        // Check replenishing works ad infinitum (example: 100 uses) and we only do certify calls once half empty.
        seenAliases.clear()
        repeat(100) {
            val (kInfo, _) = manager.getKey(atTime = testData.currentTime)
            manager.markKeyAsUsed(kInfo)
            seenAliases.add(kInfo.alias)
        }
        assertEquals(100, seenAliases.size)
        assertEquals(22, testData.certifyKeysNumCalled)
    }

    @Test
    fun testCertifiedKeysExpiration() = runTest {
        val testData = CertifiedKeyTestData()
        val clientStorage = EphemeralStorage()
        val clientSecureArea = SoftwareSecureArea.create(clientStorage)
        val manager = createKeyManager(clientStorage, clientSecureArea, testData)

        // First call should cause 1 certify call
        val (_, _) = manager.getKey(atTime = testData.currentTime)
        assertEquals(1, testData.certifyKeysNumCalled)

        // Advance the time to 15 days past, should not cause certify call.
        testData.currentTime += 15.days
        val (_, _) = manager.getKey(atTime = testData.currentTime)
        assertEquals(1, testData.certifyKeysNumCalled)

        // Another 6 days to bring us to 21 days. This will cause 1 certify call since all keys will be
        // replaced after two thirds of 30 days which is 20 days.
        testData.currentTime += 6.days
        val (_, certChain) = manager.getKey(atTime = testData.currentTime)
        assertEquals(2, testData.certifyKeysNumCalled)
        certChain.validate(validateAt = testData.currentTime)
        assertEquals(1, certChain.certificates.size)

        // Check validity dates, taking into account the jitter.
        val readerCert = certChain.certificates[0]
        assertTrue(readerCert.validityNotBefore <= testData.currentTime)
        assertTrue(readerCert.validityNotBefore >= testData.currentTime - 12.hours)
        assertTrue(readerCert.validityNotAfter >= testData.currentTime + 30.days)
        assertTrue(readerCert.validityNotAfter <= testData.currentTime + 30.days + 12.hours)
    }

    @Test
    fun testCertifiedKeysNoInternetConnectivity() = runTest {
        val testData = CertifiedKeyTestData()
        val clientStorage = EphemeralStorage()
        val clientSecureArea = SoftwareSecureArea.create(clientStorage)
        val manager = createKeyManager(clientStorage, clientSecureArea, testData)

        // First call should cause 1 certify call
        val (_, _) = manager.getKey(atTime = testData.currentTime)
        assertEquals(1, testData.certifyKeysNumCalled)

        // Now simulate not having Internet connectivity and use up all keys. This should work.
        testData.disableCertifyKeys = true
        val seenAliases = mutableSetOf<String>()
        repeat(10) {
            val (keyInfo, _) = manager.getKey(atTime = testData.currentTime)
            manager.markKeyAsUsed(keyInfo)
            seenAliases.add(keyInfo.alias)
        }
        assertEquals(10, seenAliases.size)
        assertEquals(1, testData.certifyKeysNumCalled)

        // Because we want operations to keep working even if there is no connectivity,
        // we leave a single key around for reuse.
        //
        // This means that if we get another key it'll be the one we just used. Consequently,
        // `seenAliases` set will not grow. Check this.
        repeat(10) {
            val (keyInfo, _) = manager.getKey(atTime = testData.currentTime)
            manager.markKeyAsUsed(keyInfo)
            seenAliases.add(keyInfo.alias)
        }
        assertEquals(10, seenAliases.size)

        // If we advance the clock so even this single key isn't valid anymore, getKey()
        // will stop working.
        testData.currentTime += 31.days
        try {
            manager.getKey(atTime = testData.currentTime)
            fail("Expected getKey() to fail")
        } catch (_: IllegalStateException) {
            // expected path
        }

        // If we turn connectivity back on, we'll get fresh never-used keys.
        testData.disableCertifyKeys = false
        repeat(10) {
            val (keyInfo, _) = manager.getKey(atTime = testData.currentTime)
            manager.markKeyAsUsed(keyInfo)
            seenAliases.add(keyInfo.alias)
        }
        assertEquals(20, seenAliases.size)
    }

    @Test
    fun testCertifiedKeysStaleKeyInSecureArea() = runTest {
        val testData = CertifiedKeyTestData()
        val clientStorage = EphemeralStorage()
        val clientSecureArea = SoftwareSecureArea.create(clientStorage)
        val manager = createKeyManager(clientStorage, clientSecureArea, testData)

        // Get a key to ensure keys are created
        val (keyInfo, _) = manager.getKey(atTime = testData.currentTime)

        // Delete the key directly in SecureArea to simulate a missing key alias in SecureArea
        clientSecureArea.deleteKey(keyInfo.alias)

        // Calling getKey() should clean up the stale key entry and return a valid key
        val (newKeyInfo, _) = manager.getKey(atTime = testData.currentTime)
        assertNotEquals(keyInfo.alias, newKeyInfo.alias)
    }

    @Test
    fun testSingleKeyCertifierConstructor() = runTest {
        val testData = CertifiedKeyTestData()
        val clientStorage = EphemeralStorage()
        val clientSecureArea = SoftwareSecureArea.create(clientStorage)

        val now = Clock.System.now()
        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.AnonymousExplicit(readerRootKey),
            subject = X500Name.fromName("CN=Test Reader Root CA"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = now - 30.days,
            validUntil = now + 120.days,
            crlUrl = "https://www.example.com/crl"
        )
        val readerRootKeyCertified = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(certificates = listOf(readerRootCert)),
            privateKey = readerRootKey
        )

        val manager = CertifiedKeyManager.createWithSingleKeyCertifier(
            secureArea = clientSecureArea,
            storage = clientStorage,
            numKeys = 5,
            certifySingleKey = { keyAttestation ->
                testData.certifyKeysNumCalled += 1
                val readerCert = MdocUtil.generateReaderCertificate(
                    readerRootKey = readerRootKeyCertified,
                    readerKey = keyAttestation.publicKey,
                    subject = X500Name.fromName("CN=Single Reader Key"),
                    dnsName = "example.com",
                    serial = ASN1Integer.fromRandom(numBits = 128),
                    validFrom = testData.currentTime.truncateToWholeSeconds(),
                    validUntil = (testData.currentTime + 30.days).truncateToWholeSeconds(),
                    extensions = emptyList()
                )
                X509CertChain(certificates = listOf(readerCert))
            }
        )

        val (keyInfo, certChain) = manager.getKey(atTime = testData.currentTime)
        assertEquals(5, testData.certifyKeysNumCalled)
        assertEquals(1, certChain.certificates.size)
        assertEquals(keyInfo.alias, manager.getKey(atTime = testData.currentTime).alias)
    }

    @Test
    fun testClearKeys() = runTest {
        val testData = CertifiedKeyTestData()
        val clientStorage = EphemeralStorage()
        val clientSecureArea = SoftwareSecureArea.create(clientStorage)
        val manager = createKeyManager(clientStorage, clientSecureArea, testData)

        val (keyInfo, _) = manager.getKey(atTime = testData.currentTime)
        assertEquals(1, testData.certifyKeysNumCalled)

        manager.clearKeys()

        // SecureArea should no longer have the key
        try {
            clientSecureArea.getKeyInfo(keyInfo.alias)
            fail("Expected getKeyInfo to throw IllegalArgumentException after clearKeys")
        } catch (_: IllegalArgumentException) {
            // expected path
        }

        // Getting a key now should replenish anew
        val (newKeyInfo, _) = manager.getKey(atTime = testData.currentTime)
        assertEquals(2, testData.certifyKeysNumCalled)
        assertNotEquals(keyInfo.alias, newKeyInfo.alias)
    }

    @Test
    fun testCustomReplenishThresholdFraction() = runTest {
        val testData = CertifiedKeyTestData()
        val clientStorage = EphemeralStorage()
        val clientSecureArea = SoftwareSecureArea.create(clientStorage)
        val now = Clock.System.now()
        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.AnonymousExplicit(readerRootKey),
            subject = X500Name.fromName("CN=Test Reader Root CA"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = now - 30.days,
            validUntil = now + 120.days,
            crlUrl = "https://www.example.com/crl"
        )
        val readerRootKeyCertified = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(certificates = listOf(readerRootCert)),
            privateKey = readerRootKey
        )

        // Configure replenishThresholdFraction = 0.8 on 10 keys (triggers when <= 8 keys left)
        val manager = CertifiedKeyManager(
            secureArea = clientSecureArea,
            storage = clientStorage,
            numKeys = 10,
            replenishThresholdFraction = 0.8
        ) { readerKeys ->
            testData.certifyKeysNumCalled += 1
            certifyReaderKeys(
                readerKeys = readerKeys,
                readerRootKey = readerRootKeyCertified,
                atTime = testData.currentTime
            )
        }

        // First call generates 10 keys
        val (k1, _) = manager.getKey(atTime = testData.currentTime)
        assertEquals(1, testData.certifyKeysNumCalled)
        manager.markKeyAsUsed(k1) // 9 left (9 > 8: no replenish)

        val (k2, _) = manager.getKey(atTime = testData.currentTime)
        assertEquals(1, testData.certifyKeysNumCalled)
        manager.markKeyAsUsed(k2) // 8 left (8 > 8 is false: next getKey will replenish!)

        assertEquals(1, testData.certifyKeysNumCalled)
        manager.getKey(atTime = testData.currentTime)
        assertEquals(2, testData.certifyKeysNumCalled)
    }

    @Test
    fun testCustomRefreshThresholdFraction() = runTest {
        val testData = CertifiedKeyTestData()
        val clientStorage = EphemeralStorage()
        val clientSecureArea = SoftwareSecureArea.create(clientStorage)
        val now = Clock.System.now()
        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.AnonymousExplicit(readerRootKey),
            subject = X500Name.fromName("CN=Test Reader Root CA"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = now - 30.days,
            validUntil = now + 120.days,
            crlUrl = "https://www.example.com/crl"
        )
        val readerRootKeyCertified = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(certificates = listOf(readerRootCert)),
            privateKey = readerRootKey
        )

        // Configure refreshThresholdFraction = 0.4 on 30-day keys (refresh after 12 days)
        val manager = CertifiedKeyManager(
            secureArea = clientSecureArea,
            storage = clientStorage,
            numKeys = 10,
            refreshThresholdFraction = 0.4
        ) { readerKeys ->
            testData.certifyKeysNumCalled += 1
            certifyReaderKeys(
                readerKeys = readerKeys,
                readerRootKey = readerRootKeyCertified,
                atTime = testData.currentTime
            )
        }

        manager.getKey(atTime = testData.currentTime)
        assertEquals(1, testData.certifyKeysNumCalled)

        // Advance by 10 days (less than 12 days: no replenish)
        testData.currentTime += 10.days
        manager.getKey(atTime = testData.currentTime)
        assertEquals(1, testData.certifyKeysNumCalled)

        // Advance by another 4 days (total 14 days > 12 days: triggers replenish)
        testData.currentTime += 4.days
        manager.getKey(atTime = testData.currentTime)
        assertEquals(2, testData.certifyKeysNumCalled)
    }
}
