package org.multipaz.trustmanagement

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.rical.SignedRical
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.toHex

private data class LocalTrustEntry(
    val id: String,
    val ski: String,
    val timeAdded: Instant,
    val entry: TrustEntry
) {
    fun toDataItem(): DataItem {
        return buildCborMap {
            put("id", id)
            put("ski", ski)
            put("timeAddedSec", timeAdded.epochSeconds)
            put("timeAddedNSec", timeAdded.nanosecondsOfSecond)
            put("entry", entry.toDataItem())
        }
    }

    companion object {
        fun fromDataItem(dataItem: DataItem): LocalTrustEntry {
            val id = dataItem["id"].asTstr
            val ski = dataItem["ski"].asTstr
            val timeAddedSeconds = dataItem["timeAddedSec"].asNumber
            val timeAddedNanoSeconds = dataItem["timeAddedNSec"].asNumber
            val timeAdded = Instant.fromEpochSeconds(timeAddedSeconds, timeAddedNanoSeconds)
            val entry = TrustEntry.fromDataItem(dataItem["entry"])
            return LocalTrustEntry(id, ski, timeAdded, entry)
        }
    }
}

/**
 * A robust, thread-safe implementation of [TrustManagerInterface] that securely manages and
 * persists trust points (X.509 Certificates, VICALs, and RICALs).
 *
 * This manager is backed by a persistent [Storage] instance and uses an internal [Mutex]
 * to guarantee memory consistency across highly concurrent read and write operations.
 * It also exposes an [eventFlow] that external components can observe to reactively
 * update their state whenever trust entries are added, modified, or deleted.
 *
 * @param storage the [Storage] interface used for persistent storage.
 * @param identifier an identifier for the [TrustManagerInterface].
 * @param partitionId an identifier used to namespace data if multiple [TrustManager] instances share the same [storage].
 */
class TrustManager(
    private val storage: Storage,
    override val identifier: String = "default",
    private val partitionId: String = "default_$identifier"
): TrustManagerInterface {
    private lateinit var storageTable: StorageTable

    /**
     * An internal buffered flow used to broadcast changes to the trust store.
     * Drops the oldest events if the buffer overflows, preventing slow subscribers from
     * bottlenecking rapid, concurrent database writes. Emits [Unit] to signal a generic
     * state invalidation.
     */
    private val _eventFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * A reactive stream of events emitted whenever the underlying trust data changes.
     * Observers can collect this flow to know when to refresh their cached UI states.
     */
    val eventFlow
        get() = _eventFlow.asSharedFlow()

    // Lock to protect in-memory collections from concurrent modifications
    private val stateLock = Mutex()
    private val skiToTrustPoint = mutableMapOf<String, TrustPoint>()
    private val vicalTrustManagers = mutableMapOf<String, VicalTrustManager>()
    private val ricalTrustManagers = mutableMapOf<String, RicalTrustManager>()
    private val localEntries = mutableListOf<LocalTrustEntry>()

    private val initializationLock = Mutex()
    private var initializationComplete = false

    /**
     * Lazily initializes the database table and loads all existing trust entries
     * into the memory cache. Uses [initializationLock] to ensure it only runs once.
     */
    private suspend fun ensureInitialized() {
        initializationLock.withLock {
            if (initializationComplete) {
                return
            }
            storageTable = storage.getTable(tableSpec)

            val loadedSkiToTrustPoint = mutableMapOf<String, TrustPoint>()
            val loadedVicalTrustManagers = mutableMapOf<String, VicalTrustManager>()
            val loadedRicalTrustManagers = mutableMapOf<String, RicalTrustManager>()
            val loadedEntries = mutableListOf<LocalTrustEntry>()

            for ((key, encodedData) in storageTable.enumerateWithData(partitionId = partitionId)) {
                val localEntry = LocalTrustEntry.fromDataItem(Cbor.decode(encodedData.toByteArray()))
                when (localEntry.entry) {
                    is TrustEntryX509Cert -> {
                        loadedSkiToTrustPoint[localEntry.ski] = TrustPoint(
                            certificate = localEntry.entry.certificate,
                            metadata = localEntry.entry.metadata,
                            trustManager = this
                        )
                    }
                    is TrustEntryVical -> {
                        val signedVical = SignedVical.parse(
                            encodedSignedVical = localEntry.entry.encodedSignedVical.toByteArray(),
                            disableSignatureVerification = true
                        )
                        loadedVicalTrustManagers[key] = VicalTrustManager(signedVical)
                    }
                    is TrustEntryRical -> {
                        val signedRical = SignedRical.parse(
                            encodedSignedRical = localEntry.entry.encodedSignedRical.toByteArray(),
                            disableSignatureVerification = true
                        )
                        loadedRicalTrustManagers[key] = RicalTrustManager(signedRical)
                    }
                }
                loadedEntries.add(localEntry)
            }

            stateLock.withLock {
                skiToTrustPoint.putAll(loadedSkiToTrustPoint)
                vicalTrustManagers.putAll(loadedVicalTrustManagers)
                ricalTrustManagers.putAll(loadedRicalTrustManagers)
                localEntries.addAll(loadedEntries)
            }
            initializationComplete = true
        }
    }

    /**
     * Retrieves a flattened list of all [TrustPoint]s currently managed.
     * This includes standalone X.509 certificates as well as individual certificates
     * embedded within managed VICALs and RICALs.
     *
     * @return A list of actively trusted [TrustPoint]s.
     */
    override suspend fun getTrustPoints(): List<TrustPoint> {
        ensureInitialized()
        return stateLock.withLock {
            val ret = mutableListOf<TrustPoint>()
            ret.addAll(skiToTrustPoint.values)
            vicalTrustManagers.forEach { ret.addAll(it.value.getTrustPoints()) }
            ricalTrustManagers.forEach { ret.addAll(it.value.getTrustPoints()) }
            ret
        }
    }

    /**
     * Retrieves all high-level [TrustEntry] items currently managed, sorted
     * chronologically by the time they were added.
     *
     * @return A list of [TrustEntry] objects (e.g., [TrustEntryX509Cert], [TrustEntryVical]).
     */
    suspend fun getEntries(): List<TrustEntry> {
        ensureInitialized()
        return stateLock.withLock {
            localEntries.sortedBy { it.timeAdded }.map { it.entry }
        }
    }

    /**
     * Adds a standalone X.509 certificate to the trust manager.
     *
     * @param certificate The [X509Cert] to be trusted. Must contain a Subject Key Identifier (SKI).
     * @param metadata Associated metadata (like a display name or icon) for the certificate.
     * @return The newly created and persisted [TrustEntryX509Cert].
     * @throws IllegalArgumentException if the certificate lacks an SKI.
     * @throws TrustEntryAlreadyExistsException if a certificate with the same SKI is already managed.
     */
    suspend fun addX509Cert(
        certificate: X509Cert,
        metadata: TrustMetadata,
    ): TrustEntryX509Cert {
        ensureInitialized()
        val trustPoint = TrustPoint(
            certificate = certificate,
            metadata = metadata,
            trustManager = this
        )
        val ski = trustPoint.certificate.subjectKeyIdentifier?.toHex()
        require(ski != null) { "SubjectKeyIdentifier must be set in certificate for TrustPoint" }

        stateLock.withLock {
            if (skiToTrustPoint.containsKey(ski)) {
                throw TrustEntryAlreadyExistsException("TrustPoint with given SubjectKeyIdentifier already exists")
            }
        }

        val key = storageTable.insert(
            key = null,
            data = ByteString(),
            partitionId = partitionId,
        )
        val localEntry = LocalTrustEntry(
            id = key,
            ski = ski,
            timeAdded = Clock.System.now(),
            entry = TrustEntryX509Cert(
                identifier = key,
                certificate = certificate,
                metadata = metadata
            )
        )
        storageTable.update(
            key = key,
            data = ByteString(Cbor.encode(localEntry.toDataItem())),
            partitionId = partitionId,
        )

        stateLock.withLock {
            skiToTrustPoint[ski] = trustPoint
            localEntries.add(localEntry)
        }
        _eventFlow.tryEmit(Unit)
        return localEntry.entry as TrustEntryX509Cert
    }

    /**
     * Adds a signed VICAL (Verified Issuer Certificate Authority List) to the trust manager.
     *
     * @param encodedSignedVical The raw CBOR/ASN.1 encoded bytes of the signed VICAL.
     * @param metadata Associated metadata for the VICAL.
     * @return The newly created and persisted [TrustEntryVical].
     */
    suspend fun addVical(
        encodedSignedVical: ByteString,
        metadata: TrustMetadata,
    ): TrustEntryVical {
        ensureInitialized()
        val signedVical = SignedVical.parse(
            encodedSignedVical = encodedSignedVical.toByteArray(),
            disableSignatureVerification = true
        )
        val key = storageTable.insert(
            key = null,
            data = ByteString(),
            partitionId = partitionId,
        )
        val localEntry = LocalTrustEntry(
            id = key,
            ski = "",
            timeAdded = Clock.System.now(),
            entry = TrustEntryVical(
                identifier = key,
                encodedSignedVical = encodedSignedVical,
                metadata = metadata
            )
        )
        storageTable.update(
            key = key,
            data = ByteString(Cbor.encode(localEntry.toDataItem())),
            partitionId = partitionId,
        )

        stateLock.withLock {
            vicalTrustManagers[key] = VicalTrustManager(signedVical)
            localEntries.add(localEntry)
        }
        _eventFlow.tryEmit(Unit)
        return localEntry.entry as TrustEntryVical
    }

    /**
     * Adds a signed RICAL (Reader Issuer Certificate Authority List) to the trust manager.
     *
     * @param encodedSignedRical The raw encoded bytes of the signed RICAL.
     * @param metadata Associated metadata for the RICAL.
     * @return The newly created and persisted [TrustEntryRical].
     */
    suspend fun addRical(
        encodedSignedRical: ByteString,
        metadata: TrustMetadata,
    ): TrustEntryRical {
        ensureInitialized()
        val signedRical = SignedRical.parse(
            encodedSignedRical = encodedSignedRical.toByteArray(),
            disableSignatureVerification = true
        )
        val key = storageTable.insert(
            key = null,
            data = ByteString(),
            partitionId = partitionId,
        )
        val localEntry = LocalTrustEntry(
            id = key,
            ski = "",
            timeAdded = Clock.System.now(),
            entry = TrustEntryRical(
                identifier = key,
                encodedSignedRical = encodedSignedRical,
                metadata = metadata
            )
        )
        storageTable.update(
            key = key,
            data = ByteString(Cbor.encode(localEntry.toDataItem())),
            partitionId = partitionId,
        )

        stateLock.withLock {
            ricalTrustManagers[key] = RicalTrustManager(signedRical)
            localEntries.add(localEntry)
        }
        _eventFlow.tryEmit(Unit)
        return localEntry.entry as TrustEntryRical
    }

    /**
     * Deletes a specific [TrustEntry] from both memory and persistent storage.
     *
     * @param entry The entry to remove.
     * @return `true` if the entry was found and successfully deleted, `false` otherwise.
     */
    suspend fun deleteEntry(entry: TrustEntry): Boolean {
        ensureInitialized()
        val localEntry = stateLock.withLock {
            localEntries.find { it.entry == entry }
        } ?: return false

        storageTable.delete(
            key = localEntry.id,
            partitionId = partitionId,
        )

        val removed = stateLock.withLock {
            localEntries.remove(localEntry)
            when (localEntry.entry) {
                is TrustEntryX509Cert -> skiToTrustPoint.remove(localEntry.ski) != null
                is TrustEntryVical -> vicalTrustManagers.remove(localEntry.id) != null
                is TrustEntryRical -> ricalTrustManagers.remove(localEntry.id) != null
            }
        }

        if (removed) {
            _eventFlow.tryEmit(Unit)
        }
        return removed
    }

    /**
     * Purges all managed trust entries from this partition and clears the memory cache.
     * Warning: This operation is irreversible.
     */
    suspend fun deleteAll() {
        ensureInitialized()
        storageTable.deletePartition(partitionId = partitionId)
        stateLock.withLock {
            localEntries.clear()
            skiToTrustPoint.clear()
            vicalTrustManagers.clear()
            ricalTrustManagers.clear()
        }
        _eventFlow.tryEmit(Unit)
    }

    /**
     * Updates the mutable [TrustMetadata] associated with an existing [TrustEntry].
     *
     * @param entry The existing entry whose metadata should be updated.
     * @param metadata The new metadata object to apply.
     * @return A new instance of the [TrustEntry] containing the updated metadata.
     * @throws IllegalStateException if the specified entry is not found in the manager.
     */
    suspend fun updateMetadata(
        entry: TrustEntry,
        metadata: TrustMetadata
    ): TrustEntry {
        ensureInitialized()
        val localEntry = stateLock.withLock {
            localEntries.find { it.entry == entry }
        } ?: throw IllegalStateException("Trust Manager does not contain entry")

        val newEntry = when (localEntry.entry) {
            is TrustEntryX509Cert -> {
                TrustEntryX509Cert(
                    identifier = localEntry.id,
                    metadata = metadata,
                    certificate = localEntry.entry.certificate
                )
            }
            is TrustEntryVical -> {
                TrustEntryVical(
                    identifier = localEntry.id,
                    metadata = metadata,
                    encodedSignedVical = localEntry.entry.encodedSignedVical
                )
            }
            is TrustEntryRical -> {
                TrustEntryRical(
                    identifier = localEntry.id,
                    metadata = metadata,
                    encodedSignedRical = localEntry.entry.encodedSignedRical
                )
            }
        }

        val newLocalEntry = LocalTrustEntry(
            id = localEntry.id,
            timeAdded = localEntry.timeAdded,
            ski = localEntry.ski,
            entry = newEntry
        )

        storageTable.update(
            key = localEntry.id,
            data = ByteString(Cbor.encode(newLocalEntry.toDataItem())),
            partitionId = partitionId,
        )

        stateLock.withLock {
            localEntries.remove(localEntry)
            localEntries.add(newLocalEntry)
            if (newEntry is TrustEntryX509Cert) {
                skiToTrustPoint[localEntry.ski] = TrustPoint(
                    certificate = newEntry.certificate,
                    metadata = newEntry.metadata,
                    trustManager = this@TrustManager
                )
            }
        }

        _eventFlow.tryEmit(Unit)
        return newLocalEntry.entry
    }

    /**
     * Updates the underlying byte data of an existing VICAL entry while preserving its metadata.
     *
     * Signature verification is bypassed during parsing.
     *
     * @param entry The existing [TrustEntryVical] to update.
     * @param encodedSignedVical The new CBOR/ASN.1 encoded bytes of the signed VICAL.
     * @return A new instance of [TrustEntryVical] containing the updated bytes.
     * @throws IllegalStateException if the specified entry is not found in the manager.
     */
    suspend fun updateVical(
        entry: TrustEntryVical,
        encodedSignedVical: ByteString
    ): TrustEntryVical {
        ensureInitialized()
        val signedVical = SignedVical.parse(
            encodedSignedVical = encodedSignedVical.toByteArray(),
            disableSignatureVerification = true
        )

        val localEntry = stateLock.withLock {
            localEntries.find { it.entry == entry }
        } ?: throw IllegalStateException("Trust Manager does not contain entry")

        val newEntry = TrustEntryVical(
            identifier = localEntry.id,
            metadata = localEntry.entry.metadata,
            encodedSignedVical = encodedSignedVical
        )

        val newLocalEntry = LocalTrustEntry(
            id = localEntry.id,
            timeAdded = localEntry.timeAdded,
            ski = localEntry.ski,
            entry = newEntry
        )

        storageTable.update(
            key = localEntry.id,
            data = ByteString(Cbor.encode(newLocalEntry.toDataItem())),
            partitionId = partitionId,
        )

        stateLock.withLock {
            localEntries.remove(localEntry)
            localEntries.add(newLocalEntry)
            vicalTrustManagers[localEntry.id] = VicalTrustManager(signedVical)
        }

        _eventFlow.tryEmit(Unit)
        return newEntry
    }

    /**
     * Updates the underlying byte data of an existing RICAL entry while preserving its metadata.
     *
     * Signature verification is bypassed during parsing.
     *
     * @param entry The existing [TrustEntryRical] to update.
     * @param encodedSignedRical The new encoded bytes of the signed RICAL.
     * @return A new instance of [TrustEntryRical] containing the updated bytes.
     * @throws IllegalStateException if the specified entry is not found in the manager.
     */
    suspend fun updateRical(
        entry: TrustEntryRical,
        encodedSignedRical: ByteString
    ): TrustEntryRical {
        ensureInitialized()
        val signedRical = SignedRical.parse(
            encodedSignedRical = encodedSignedRical.toByteArray(),
            disableSignatureVerification = true
        )

        val localEntry = stateLock.withLock {
            localEntries.find { it.entry == entry }
        } ?: throw IllegalStateException("Trust Manager does not contain entry")

        val newEntry = TrustEntryRical(
            identifier = localEntry.id,
            metadata = localEntry.entry.metadata,
            encodedSignedRical = encodedSignedRical
        )

        val newLocalEntry = LocalTrustEntry(
            id = localEntry.id,
            timeAdded = localEntry.timeAdded,
            ski = localEntry.ski,
            entry = newEntry
        )

        storageTable.update(
            key = localEntry.id,
            data = ByteString(Cbor.encode(newLocalEntry.toDataItem())),
            partitionId = partitionId,
        )

        stateLock.withLock {
            localEntries.remove(localEntry)
            localEntries.add(newLocalEntry)
            ricalTrustManagers[localEntry.id] = RicalTrustManager(signedRical)
        }

        _eventFlow.tryEmit(Unit)
        return newEntry
    }

    /**
     * Evaluates a given X.509 certificate chain against all managed trust points to
     * determine if it is trusted.
     *
     * This evaluation happens in three phases:
     * 1. Checks against certificates provided by managed VICALs.
     * 2. Checks against certificates provided by managed RICALs.
     * 3. Checks against individually managed standalone X.509 certificates.
     *
     * @param chain The certificate chain to verify.
     * @param atTime The exact time to use for validity and expiration checks.
     * @return A [TrustResult] containing the verification status, the resolved chain,
     * and any associated error if verification failed.
     */
    override suspend fun verify(
        chain: List<X509Cert>,
        atTime: Instant,
    ): TrustResult {
        ensureInitialized()
        return stateLock.withLock {
            // VICAL trust managers get first dibs...
            vicalTrustManagers.forEach { (_, trustManager) ->
                val ret = trustManager.verify(chain, atTime)
                if (ret.isTrusted) {
                    return@withLock ret
                }
            }
            // Then RICAL..
            ricalTrustManagers.forEach { (_, trustManager) ->
                val ret = trustManager.verify(chain, atTime)
                if (ret.isTrusted) {
                    return@withLock ret
                }
            }
            // Finally certificates...
            TrustManagerUtil.verifyX509TrustChain(chain, atTime, skiToTrustPoint)
        }
    }

    companion object {
        private const val TAG = "TrustManager"

        private val tableSpec = StorageTableSpec(
            name = "TrustManager",
            supportPartitions = true,
            supportExpiration = false,
            schemaVersion = 0L
        )
    }
}