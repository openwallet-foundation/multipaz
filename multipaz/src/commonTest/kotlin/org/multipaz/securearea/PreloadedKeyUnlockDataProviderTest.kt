package org.multipaz.securearea

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.multipaz.crypto.Algorithm
import org.multipaz.prompt.Reason
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareKeyUnlockData
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreloadedKeyUnlockDataProviderTest {

    @Test
    fun testPreloadedKeyUnlockData() = runTest {
        val storage = EphemeralStorage()
        val sa = SoftwareSecureArea.create(storage)

        sa.createKey(
            "key1",
            SoftwareCreateKeySettings.Builder()
                .setPassphraseRequired(true, "pass1", PassphraseConstraints.NONE)
                .build()
        )
        sa.createKey(
            "key2",
            SoftwareCreateKeySettings.Builder()
                .setPassphraseRequired(true, "pass2", PassphraseConstraints.NONE)
                .build()
        )
        sa.createKey("unlockedKey", CreateKeySettings())

        val unlockData1 = SoftwareKeyUnlockData(sa, "key1", "pass1")
        val unlockData2 = SoftwareKeyUnlockData(sa, "key2", "pass2")
        val nullUnlockDataList = sa.unlockKey("unlockedKey")
        assertTrue(nullUnlockDataList.isEmpty())

        val preloadedProvider = buildPreloadedKeyUnlockDataProvider {
            add(sa, "key1", unlockData1)
            add(sa, "key2", unlockData2)
            add(sa, "unlockedKey", nullUnlockDataList)
        }

        withContext(preloadedProvider) {
            // sign key1 using preloaded provider
            val sig1 = sa.sign("key1", byteArrayOf(1, 2, 3))
            assertEquals(32, sig1.r.size)

            // sign key2 using preloaded provider
            val sig2 = sa.sign("key2", byteArrayOf(4, 5, 6))
            assertEquals(32, sig2.r.size)
        }
    }

    @Test
    fun testPreloadedKeyUnlockDataMissingKey() = runTest {
        val storage = EphemeralStorage()
        val sa = SoftwareSecureArea.create(storage)

        sa.createKey(
            "key1",
            SoftwareCreateKeySettings.Builder()
                .setPassphraseRequired(true, "pass1", PassphraseConstraints.NONE)
                .build()
        )

        val preloadedProvider = buildPreloadedKeyUnlockDataProvider {
            // Don't add key1
        }

        withContext(preloadedProvider) {
            assertFailsWith(KeyLockedException::class) {
                sa.sign("key1", byteArrayOf(1, 2, 3))
            }
        }
    }

    @Test
    fun testPreloadedKeyUnlockDataFallback() = runTest {
        val storage = EphemeralStorage()
        val sa = SoftwareSecureArea.create(storage)

        sa.createKey(
            "key1",
            SoftwareCreateKeySettings.Builder()
                .setPassphraseRequired(true, "pass1", PassphraseConstraints.NONE)
                .build()
        )

        val fallbackProvider = object : KeyUnlockDataProvider {
            override suspend fun getKeyUnlockData(
                secureArea: SecureArea,
                alias: String,
                algorithm: Algorithm,
                unlockReason: Reason
            ): KeyUnlockData = SoftwareKeyUnlockData(secureArea as SoftwareSecureArea, alias, "pass1")
        }

        val preloadedProvider = PreloadedKeyUnlockDataProvider.Builder()
            .build(fallbackProvider = fallbackProvider)

        withContext(preloadedProvider) {
            val sig = sa.sign("key1", byteArrayOf(1, 2, 3))
            assertEquals(32, sig.r.size)
        }
    }
}
