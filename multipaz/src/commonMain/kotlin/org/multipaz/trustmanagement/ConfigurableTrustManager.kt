package org.multipaz.trustmanagement

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.rical.SignedRical
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.time.Instant

/**
 * A [TrustManagerInterface] implementation backed by a configurable list of [TrustEntry] items.
 *
 * The entries may be updated later using [setEntries].
 *
 * This is typically used when receiving a lists of [TrustEntry] items from e.g. a backend server.
 *
 * @param identifier an identifier for the [TrustManagerInterface] instance.
 * @param entries A list of [TrustEntry] objects (e.g., [TrustEntryX509Cert], [TrustEntryVical]).
 */
class ConfigurableTrustManager(
    override val identifier: String,
    entries: List<TrustEntry>
): TrustEntryBasedTrustManager {

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

    override val eventFlow
        get() = _eventFlow.asSharedFlow()

    private var currentEntries = entries

    // Lock to protect in-memory collections from concurrent modifications
    private val stateLock = Mutex()
    private val skiToTrustPoint = mutableMapOf<String, TrustPoint>()
    private val vicalTrustManagers = mutableListOf<VicalTrustManager>()
    private val ricalTrustManagers = mutableListOf<RicalTrustManager>()

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

            val loadedSkiToTrustPoint = mutableMapOf<String, TrustPoint>()
            val loadedVicalTrustManagers = mutableListOf<VicalTrustManager>()
            val loadedRicalTrustManagers = mutableListOf<RicalTrustManager>()

            currentEntries.forEach { entry ->
                when (entry) {
                    is TrustEntryX509Cert -> {
                        val ski = entry.certificate.subjectKeyIdentifier?.toHex()
                        if (ski == null) {
                            Logger.w(TAG, "Skipping certificate without SKI")
                        } else {
                            loadedSkiToTrustPoint[ski] = TrustPoint(
                                certificate = entry.certificate,
                                metadata = entry.metadata,
                                trustManager = this
                            )
                        }
                    }
                    is TrustEntryVical -> {
                        val signedVical = SignedVical.parse(
                            encodedSignedVical = entry.encodedSignedVical.toByteArray(),
                            disableSignatureVerification = true
                        )
                        loadedVicalTrustManagers.add(VicalTrustManager(signedVical))
                    }
                    is TrustEntryRical -> {
                        val signedRical = SignedRical.parse(
                            encodedSignedRical = entry.encodedSignedRical.toByteArray(),
                            disableSignatureVerification = true
                        )
                        loadedRicalTrustManagers.add(RicalTrustManager(signedRical))
                    }
                }
            }

            stateLock.withLock {
                skiToTrustPoint.putAll(loadedSkiToTrustPoint)
                vicalTrustManagers.addAll(loadedVicalTrustManagers)
                ricalTrustManagers.addAll(loadedRicalTrustManagers)
            }
            initializationComplete = true
        }
    }

    override suspend fun getTrustPoints(): List<TrustPoint> {
        ensureInitialized()
        return stateLock.withLock {
            val ret = mutableListOf<TrustPoint>()
            ret.addAll(skiToTrustPoint.values)
            vicalTrustManagers.forEach { ret.addAll(it.getTrustPoints()) }
            ricalTrustManagers.forEach { ret.addAll(it.getTrustPoints()) }
            ret
        }
    }

    override suspend fun verify(
        chain: List<X509Cert>,
        atTime: Instant,
        validateCaValidity: Boolean
    ): TrustResult {
        ensureInitialized()
        return stateLock.withLock {
            // VICAL trust managers get first dibs...
            vicalTrustManagers.forEach { trustManager ->
                val ret = trustManager.verify(chain, atTime, validateCaValidity)
                if (ret.isTrusted) {
                    return@withLock ret
                }
            }
            // Then RICAL..
            ricalTrustManagers.forEach { trustManager ->
                val ret = trustManager.verify(chain, atTime, validateCaValidity)
                if (ret.isTrusted) {
                    return@withLock ret
                }
            }
            // Finally certificates...
            TrustManagerUtil.verifyX509TrustChain(chain, atTime, skiToTrustPoint, validateCaValidity)
        }
    }

    override suspend fun getEntries(): List<TrustEntry> {
        return currentEntries
    }

    /**
     * Sets which [TrustEntry] items to use.
     *
     * @param entries A list of [TrustEntry] objects (e.g., [TrustEntryX509Cert], [TrustEntryVical]).
     */
    suspend fun setEntries(
        entries: List<TrustEntry>
    ) {
        stateLock.withLock {
            currentEntries = entries
            initializationComplete = false
        }
        _eventFlow.tryEmit(Unit)
    }

    companion object {
        private const val TAG = "ConfigurableTrustManager"
    }
}