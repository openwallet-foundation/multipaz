package org.multipaz.presentment

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.X509Cert
import org.multipaz.request.OpenID4VPRequesterIdentity
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.toHex

/**
 * An implementation of [RequesterHistory] backed by [Storage].
 *
 * @param storage the underlying persistent [Storage].
 * @param partitionId logical partition identifier in [storage]. Defaults to `"default"`.
 * @param clock the [Clock] used to timestamp interactions. Defaults to [Clock.System].
 */
class SimpleRequesterHistory(
    private val storage: Storage,
    private val partitionId: String = "default",
    private val clock: Clock = Clock.System,
) : RequesterHistory {
    private lateinit var storageTable: StorageTable
    private val lock = Mutex()

    @Volatile
    private var initializationComplete = false

    private suspend fun ensureInitialized() {
        if (initializationComplete) return
        lock.withLock {
            if (initializationComplete) return
            storageTable = storage.getTable(tableSpec)
            initializationComplete = true
        }
    }

    override suspend fun getEntry(
        requester: Requester,
        trustedRequesterIdentity: TrustedRequesterIdentity?,
    ): RequesterHistoryEntry? {
        val key = deriveStorageKey(requester, trustedRequesterIdentity) ?: return null
        ensureInitialized()
        return lock.withLock {
            storageTable.get(key = key, partitionId = partitionId)?.let { encoded ->
                RequesterHistoryEntry.fromCbor(encoded.toByteArray())
            }
        }
    }

    override suspend fun recordShare(
        requester: Requester,
        trustedRequesterIdentity: TrustedRequesterIdentity?,
    ) {
        val key = deriveStorageKey(requester, trustedRequesterIdentity) ?: return
        ensureInitialized()
        val now = clock.now()
        lock.withLock {
            val existing = storageTable.get(key = key, partitionId = partitionId)?.let { encoded ->
                RequesterHistoryEntry.fromCbor(encoded.toByteArray())
            }
            if (existing == null) {
                val entry = RequesterHistoryEntry(
                    shareCount = 1,
                    firstSharedAt = now,
                    lastSharedAt = now,
                )
                storageTable.insert(
                    key = key,
                    data = ByteString(entry.toCbor()),
                    partitionId = partitionId,
                )
            } else {
                val updated = existing.copy(
                    shareCount = existing.shareCount + 1,
                    lastSharedAt = now,
                )
                storageTable.update(
                    key = key,
                    data = ByteString(updated.toCbor()),
                    partitionId = partitionId,
                )
            }
        }
    }

    override suspend fun deleteAll() {
        ensureInitialized()
        lock.withLock {
            storageTable.deletePartition(partitionId = partitionId)
        }
    }

    companion object {
        private val tableSpec = StorageTableSpec(
            name = "SimpleRequesterHistory",
            supportPartitions = true,
            supportExpiration = false,
            schemaVersion = 0L,
        )

        internal suspend fun deriveStorageKey(
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
        ): String? {
            val canonicalId = deriveCanonicalIdentifier(requester, trustedRequesterIdentity)
                ?: return null
            return Crypto.digest(
                algorithm = Algorithm.SHA256,
                message = canonicalId.encodeToByteArray(),
            ).toHex()
        }

        internal suspend fun deriveCanonicalIdentifier(
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
        ): String? {
            if (trustedRequesterIdentity != null) {
                val trustId = trustedRequesterIdentity.trustMetadata.identifier
                if (trustId != null) {
                    return "trusted:$trustId"
                }
                val anchorCert = trustedRequesterIdentity.identity.certChain.certificates.last()
                val certId = anchorCert.stableCertIdentifier()
                val displayName = trustedRequesterIdentity.trustMetadata.displayName.orEmpty()
                return "trusted_cert:$certId:$displayName"
            }

            if (requester.isWebOrigin) {
                val origin = requester.origin
                if (!origin.isNullOrEmpty()) {
                    return "web:$origin"
                }
            }

            if (requester.appId != null) {
                return "app:${requester.appId}:${requester.origin.orEmpty()}"
            }

            val firstIdentity = requester.requesterIdentities.firstOrNull()
            if (firstIdentity != null) {
                val leafCertId = firstIdentity.certChain.certificates.first().stableCertIdentifier()
                return when (firstIdentity) {
                    is OpenID4VPRequesterIdentity ->
                        "untrusted_oid4vp:${firstIdentity.clientId}:$leafCertId"
                    else ->
                        "untrusted_cert:$leafCertId"
                }
            }

            return null
        }

        private suspend fun X509Cert.stableCertIdentifier(): String =
            subjectKeyIdentifier?.toHex()
                ?: Crypto.digest(Algorithm.SHA256, encoded.toByteArray()).toHex()
    }
}
