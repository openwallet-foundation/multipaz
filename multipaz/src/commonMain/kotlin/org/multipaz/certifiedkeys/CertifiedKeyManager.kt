package org.multipaz.certifiedkeys

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.X509CertChain
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.KeyInfo
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTableSpec
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.Logger
import kotlin.time.Clock
import kotlin.time.Instant

private const val TAG = "CertifiedKeyManager"

/**
 * A certified key consisting of a key created in a [SecureArea] and its certification certificate chain.
 *
 * @property keyInfo information about the key in the [SecureArea].
 * @property certChain the certificate chain certifying the key.
 */
data class CertifiedKey(
    val keyInfo: KeyInfo,
    val certChain: X509CertChain
) {
    /**
     * The alias of the key in the [SecureArea].
     */
    val alias: String
        get() = keyInfo.alias
}

@CborSerializable
internal data class CertifiedKeyRecord(
    val alias: String,
    val certification: X509CertChain,
    val validFrom: Instant,
    val validUntil: Instant,
    val refreshAt: Instant
) {
    companion object
}

/**
 * Manager for a pool of keys generated locally from a [SecureArea] and certified by a certifying authority.
 *
 * The manager maintains a pool of certified keys, automatically replenishing them when the number of fresh,
 * valid keys drops below 50% of [numKeys]. It also handles key expiration, offline resilience, and cleanup
 * of stale keys.
 *
 * @property secureArea the [SecureArea] to generate and hold private keys.
 * @property storage the [Storage] used to persist certification metadata.
 * @property numKeys the target number of keys to maintain in the pool, defaults to 10.
 * @property createKeySettings settings passed when creating keys in the [SecureArea].
 * @property tableName the storage table name for persisting certified keys.
 * @property replenishThresholdFraction pool threshold fraction below which replenishing is triggered, defaults to 0.5.
 * @property refreshThresholdFraction certificate lifetime fraction after which a key is refreshed, defaults to 2/3.
 * @property certify a suspend function that certifies a batch of [KeyAttestation]s and returns their [X509CertChain]s.
 */
class CertifiedKeyManager(
    val secureArea: SecureArea,
    val storage: Storage = EphemeralStorage(),
    val numKeys: Int = 10,
    val createKeySettings: CreateKeySettings = CreateKeySettings(),
    val tableName: String = "CertifiedKeys",
    val replenishThresholdFraction: Double = 0.5,
    val refreshThresholdFraction: Double = 2.0 / 3.0,
    val certify: suspend (keys: List<KeyAttestation>) -> List<X509CertChain>
) {
    init {
        require(numKeys >= 0) { "numKeys must not be negative" }
        require(replenishThresholdFraction in 0.0..1.0) {
            "replenishThresholdFraction must be between 0.0 and 1.0"
        }
        require(refreshThresholdFraction in 0.0..1.0) {
            "refreshThresholdFraction must be between 0.0 and 1.0"
        }
    }

    private val tableSpec = StorageTableSpec(
        name = tableName,
        supportExpiration = false,
        supportPartitions = false
    )

    private val lock = Mutex()
    private var certifiedKeys: MutableMap<String, CertifiedKeyRecord>? = null

    private suspend fun ensureCertifiedKeys() {
        check(lock.isLocked)

        if (certifiedKeys != null) {
            return
        }
        val map = mutableMapOf<String, CertifiedKeyRecord>()
        val certifiedKeysTable = storage.getTable(tableSpec)
        for ((key, encodedData) in certifiedKeysTable.enumerateWithData()) {
            map[key] = CertifiedKeyRecord.fromCbor(encodedData.toByteArray())
        }
        certifiedKeys = map
    }

    private suspend fun clearKeysLocked() {
        check(lock.isLocked)
        ensureCertifiedKeys()
        val keys = certifiedKeys ?: return
        for (certifiedKey in keys.values) {
            try {
                secureArea.deleteKey(certifiedKey.alias)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Error deleting key from SecureArea", e)
            }
        }
        val certifiedKeysTable = storage.getTable(tableSpec)
        certifiedKeysTable.deleteAll()
        certifiedKeys = null
        ensureCertifiedKeys()
    }

    /**
     * Ensures we have at least numKeys/2 fresh keys. Also removes expired keys.
     */
    private suspend fun ensureReplenished(
        atTime: Instant = Clock.System.now()
    ): Int {
        check(lock.isLocked) { "Called without holding lock" }
        if (numKeys == 0) {
            return 0
        }

        ensureCertifiedKeys()
        val keys = certifiedKeys!!
        val certifiedKeysTable = storage.getTable(tableSpec)

        var numGoodKeys = 0
        val toDelete = mutableListOf<Pair<String, CertifiedKeyRecord>>()
        for ((id, certifiedKey) in keys.entries) {
            if (atTime > certifiedKey.refreshAt) {
                toDelete.add(Pair(id, certifiedKey))
            } else if (atTime > certifiedKey.validFrom && atTime < certifiedKey.validUntil) {
                numGoodKeys += 1
            }
        }

        Logger.i(TAG, "Before key replenishing: $numKeys keys ($numGoodKeys good)")

        // Only replenish if we are running below replenishThresholdFraction...
        if (numGoodKeys > numKeys * replenishThresholdFraction) {
            toDelete.forEach {
                try {
                    secureArea.deleteKey(it.second.alias)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Error deleting key from SecureArea", e)
                }
                certifiedKeysTable.delete(it.first)
                keys.remove(it.first)
            }
            Logger.i(TAG, "Not replenishing keys")
            return 0
        }
        val numKeysNeeded = numKeys - numGoodKeys

        val keysToCertify = mutableListOf<KeyInfo>()
        val readerCertifications: List<X509CertChain>
        try {
            repeat(numKeysNeeded) {
                keysToCertify.add(secureArea.createKey(null, createKeySettings))
            }
            readerCertifications = certify(keysToCertify.map { it.attestation })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // If creation or certification fails, clean up any keys created in SecureArea
            for (keyInfo in keysToCertify) {
                try {
                    secureArea.deleteKey(keyInfo.alias)
                } catch (deleteEx: Exception) {
                    if (deleteEx is CancellationException) throw deleteEx
                    Logger.w(TAG, "Error deleting key after failed certification", deleteEx)
                }
            }
            throw e
        }

        check(readerCertifications.size == keysToCertify.size) {
            "Certification result count (${readerCertifications.size}) does not match " +
                "requested count (${keysToCertify.size})"
        }
        Logger.i(TAG, "Retrieved ${readerCertifications.size} new certified keys")

        var n = 0
        for (readerCertification in readerCertifications) {
            require(readerCertification.certificates.isNotEmpty()) {
                "Certificate chain must not be empty"
            }
            // Refresh a key once it's past the configured lifetime fraction.
            val validFrom = readerCertification.certificates[0].validityNotBefore
            val validUntil = readerCertification.certificates[0].validityNotAfter
            val validFor = validUntil - validFrom
            val refreshAt = validFrom + validFor * refreshThresholdFraction
            val keyInfo = keysToCertify[n++]
            val certifiedKey = CertifiedKeyRecord(
                alias = keyInfo.alias,
                certification = readerCertification,
                validFrom = validFrom,
                validUntil = validUntil,
                refreshAt = refreshAt
            )
            val id = certifiedKeysTable.insert(
                key = null,
                data = ByteString(certifiedKey.toCbor())
            )
            keys[id] = certifiedKey
        }

        toDelete.forEach {
            try {
                secureArea.deleteKey(it.second.alias)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Error deleting key from SecureArea", e)
            }
            certifiedKeysTable.delete(it.first)
            keys.remove(it.first)
        }
        return readerCertifications.size
    }

    /**
     * Gets a certified key.
     *
     * This may involve replenishing the key pool if the number of fresh keys falls below the 50% threshold.
     *
     * When the key has been used, call [markKeyAsUsed] to ensure it won't be used again.
     *
     * It is allowable to call this function to just prime the pool (i.e. the result isn't immediately used) so
     * network I/O is reduced for future calls.
     *
     * @param atTime the current time, to take into consideration for evicting expired keys.
     * @return the [CertifiedKey] containing the [KeyInfo] and its [X509CertChain].
     * @throws IllegalStateException if no valid keys are available.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IllegalStateException::class, CancellationException::class)
    suspend fun getKey(
        atTime: Instant = Clock.System.now()
    ): CertifiedKey {
        lock.withLock {
            ensureCertifiedKeys()
            try {
                ensureReplenished(atTime = atTime)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Ignoring error replenishing keys", e)
            }

            // Return the oldest valid certificate whose key exists in SecureArea
            val sortedCertifiedKeys = certifiedKeys!!.values
                .filter { it.validFrom < atTime && atTime < it.validUntil }
                .sortedBy { it.validFrom }
            val certifiedKeysTable = storage.getTable(tableSpec)
            for (certifiedKey in sortedCertifiedKeys) {
                val keyInfo = try {
                    secureArea.getKeyInfo(certifiedKey.alias)
                } catch (e: IllegalArgumentException) {
                    Logger.w(TAG, "Key ${certifiedKey.alias} missing from SecureArea, removing from certifiedKeys", e)
                    val entry = certifiedKeys!!.entries.find { it.value.alias == certifiedKey.alias }
                    if (entry != null) {
                        certifiedKeysTable.delete(entry.key)
                        certifiedKeys!!.remove(entry.key)
                    }
                    null
                }
                if (keyInfo != null) {
                    return CertifiedKey(keyInfo, certifiedKey.certification)
                }
            }
            throw IllegalStateException("No currently valid keys available")
        }
    }

    /**
     * Marks a key retrieved with [getKey] as used.
     *
     * Note that this function may perform network I/O to replenish the key pool if the number of available
     * fresh keys drops below the threshold.
     *
     * @param keyInfo the [KeyInfo] returned from the [getKey] call.
     * @param atTime the current time, to take into consideration for evicting expired keys.
     * @throws IllegalArgumentException if no such certified key exists.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun markKeyAsUsed(
        keyInfo: KeyInfo,
        atTime: Instant = Clock.System.now()
    ) {
        markKeyAsUsed(keyInfo.alias, atTime)
    }

    /**
     * Marks a key retrieved with [getKey] as used.
     *
     * @param certifiedKey the [CertifiedKey] returned from the [getKey] call.
     * @param atTime the current time, to take into consideration for evicting expired keys.
     * @throws IllegalArgumentException if no such certified key exists.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun markKeyAsUsed(
        certifiedKey: CertifiedKey,
        atTime: Instant = Clock.System.now()
    ) {
        markKeyAsUsed(certifiedKey.alias, atTime)
    }

    /**
     * Marks a key retrieved with [getKey] as used by its alias.
     *
     * @param alias the alias of the key to mark as used.
     * @param atTime the current time, to take into consideration for evicting expired keys.
     * @throws IllegalArgumentException if no such certified key exists.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun markKeyAsUsed(
        alias: String,
        atTime: Instant = Clock.System.now()
    ) {
        withContext(NonCancellable) {
            lock.withLock {
                ensureCertifiedKeys()
                val entry = certifiedKeys!!.entries.find { (_, certifiedKey) ->
                    certifiedKey.alias == alias
                } ?: throw IllegalArgumentException("No such certified key to mark as used")

                // If this was the last key, replenish immediately. If that fails (e.g. no Internet connectivity)
                // leave the key around but mark that it's already been used
                if (certifiedKeys!!.size == 1) {
                    try {
                        ensureReplenished(atTime = atTime)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.w(TAG, "Ignoring error replenishing keys so keeping around last key", e)
                        return@withLock
                    }
                }

                val certifiedKeysTable = storage.getTable(tableSpec)
                try {
                    secureArea.deleteKey(entry.value.alias)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Error deleting key from SecureArea", e)
                }
                certifiedKeysTable.delete(key = entry.key)
                certifiedKeys!!.remove(entry.key)
            }
        }
    }

    /**
     * Clears all managed keys from [SecureArea] and storage.
     *
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(CancellationException::class)
    suspend fun clearKeys() {
        if (numKeys == 0) {
            return
        }
        lock.withLock {
            clearKeysLocked()
        }
    }

    /**
     * Refreshes certified keys.
     *
     * @param atTime the current time.
     * @return the number of certified keys that were newly certified / replenished.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(CancellationException::class)
    suspend fun refreshKeys(atTime: Instant = Clock.System.now()): Int {
        if (numKeys == 0) {
            return 0
        }
        return lock.withLock {
            ensureReplenished(atTime = atTime)
        }
    }

    companion object {
        /**
         * Creates a [CertifiedKeyManager] using a suspend function that certifies a single [KeyAttestation] at a time.
         *
         * @param secureArea the [SecureArea] to generate and hold private keys.
         * @param storage the [Storage] used to persist certification metadata.
         * @param numKeys the target number of keys to maintain in the pool, defaults to 10.
         * @param createKeySettings settings passed when creating keys in the [SecureArea].
         * @param tableName the storage table name for persisting certified keys.
         * @param certifySingleKey a suspend function that certifies a single [KeyAttestation].
         * @return a new [CertifiedKeyManager] instance.
         */
        fun createWithSingleKeyCertifier(
            secureArea: SecureArea,
            storage: Storage = EphemeralStorage(),
            numKeys: Int = 10,
            createKeySettings: CreateKeySettings = CreateKeySettings(),
            tableName: String = "CertifiedKeys",
            replenishThresholdFraction: Double = 0.5,
            refreshThresholdFraction: Double = 2.0 / 3.0,
            certifySingleKey: suspend (key: KeyAttestation) -> X509CertChain
        ): CertifiedKeyManager = CertifiedKeyManager(
            secureArea = secureArea,
            storage = storage,
            numKeys = numKeys,
            createKeySettings = createKeySettings,
            tableName = tableName,
            replenishThresholdFraction = replenishThresholdFraction,
            refreshThresholdFraction = refreshThresholdFraction,
            certify = { keys -> keys.map { certifySingleKey(it) } }
        )
    }
}
