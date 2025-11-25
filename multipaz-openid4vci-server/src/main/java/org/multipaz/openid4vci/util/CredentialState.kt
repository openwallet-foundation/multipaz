package org.multipaz.openid4vci.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.decodeToString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X509Cert
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Data about an issued and not yet expired credential.
 *
 * Credentials are identified by their index, which is assigned randomly but aims to be
 * relatively small. This index is used in the status list to support credential revocation.
 *
 * Note that credential status is stored separately to facilitate quick enumeration through
 * all non-zero status codes (to facilitate status list creation).
 *
 * @param issuanceStateId identifies [IssuanceState] to which this credential belongs
 * @param keyId identifies the key to which the credential is bound, assigned by the client
 *    (equal to the credential id in Multipaz client implementation) or created by hashing
 *    the public key if client key id was not supplied; must be null for non-key-bound credentials
 * @param creation credential creation time
 * @param expiration credential expiration time
 */
@CborSerializable
class CredentialState(
    val issuanceStateId: String,
    val keyId: String?,
    val format: CredentialFormat,
    var creation: Instant,
    var expiration: Instant
) {

    /**
     * Credential status.
     *
     * Follows https://datatracker.ietf.org/doc/draft-ietf-oauth-status-list/
     */
    enum class Status(val encoded: Int, val jsonName: String) {
        /**
         * The status of the Referenced Token is valid, correct or legal
         */
        VALID(0, "valid"),

        /**
         * The status of the Referenced Token is revoked, annulled, taken back, recalled or
         * cancelled.
         */
        INVALID(1, "invalid"),

        /**
         * The status of the Referenced Token is temporarily invalid, hanging, debarred from
         * privilege.  This state is usually temporary.
         */
        SUSPENDED(2, "suspended");

        companion object {
            fun decode(byte: Byte) = when (byte.toInt()) {
                0 -> VALID
                1 -> INVALID
                2 -> SUSPENDED
                else -> throw IllegalArgumentException("Invalid encoded status")
            }

            fun decode(value: String) = when (value) {
                "valid" -> VALID
                "invalid" -> INVALID
                "suspended" -> SUSPENDED
                else -> throw IllegalArgumentException("Invalid encoded status")
            }
        }
    }

    @CborSerializable
    data class BucketInfo(val formatId: String, val key: EcPublicKey) {
        companion object Companion
    }

    companion object {
        private const val TAG = "CredentialState"
        private val lock = Mutex()

        @Volatile
        private var keySpaceSizeLog = 4  // shared across buckets


        /** Holds [CredentialState] */
        private val dataTableSpec = StorageTableSpec(
            name = "OID4VCICredData",
            supportPartitions = true,
            supportExpiration = true
        )

        private val bucketTableSpec = StorageTableSpec(
            name = "OID4VCICredBucket",
            supportPartitions = false,
            supportExpiration = true
        )

        /**
         * Holds [Status], but only for values other than [Status.VALID], so that this table is
         * sparse.
         */
        private val statusTableSpec = StorageTableSpec(
            name = "OID4VCICredRevocation",
            supportPartitions = true,
            supportExpiration = true
        )

        /**
         * Creates a new unique credential identifier.
         *
         * @param format credential format
         * @param cert certificate for the key used to sign this new credential
         * @return new credential identifier (bucket + index), see [CredentialId]
         */
        suspend fun createCredentialId(format: CredentialFormat, cert: X509Cert): CredentialId {
            val bucketInfo = BucketInfo(format.formatId, cert.ecPublicKey).toCbor().toBase64Url()
            val bucketTable = BackendEnvironment.getTable(bucketTableSpec)
            val dataTable = BackendEnvironment.getTable(dataTableSpec)
            val bucketId = bucketTable.get(key = bucketInfo)?.decodeToString()
                ?: lock.withLock {
                    // Other thread might have inserted it already
                    bucketTable.get(key = bucketInfo)?.decodeToString()
                        ?: run {
                            var bucketId: String
                            do {
                                // We want short bucket id, 24 bits should be plenty, find an unused bucket
                                bucketId = Random.nextBytes(3).toBase64Url()
                            } while (dataTable.enumerate(bucketId, limit = 1).isNotEmpty())
                            // Bucket only exists until the certificate for the key is valid
                            bucketTable.insert(
                                key = bucketInfo,
                                data = ByteString(bucketId.encodeToByteArray()),
                                expiration = cert.validityNotAfter
                            )
                            bucketId
                        }
                }
            val now = Clock.System.now()
            val expiration = now + 20.minutes
            val blankState = CredentialState(
                issuanceStateId = "",
                keyId = null,
                creation = now,
                format = format,
                expiration = expiration
            )
            // We want to keep the key space fairly compact yet random to generate revocation list
            // efficiently. Try to find an unused key. If after a number of tries we still could
            // not, grow the key space.
            while (true) {
                val currentKeySpaceSizeLog = keySpaceSizeLog
                val currentKeySpaceSize = 1 shl currentKeySpaceSizeLog
                repeat(currentKeySpaceSizeLog) {
                    try {
                        val index = Random.nextInt(currentKeySpaceSize)
                        dataTable.insert(
                            partitionId = bucketId,
                            key = encodeIndexToKey(index),
                            data = ByteString(blankState.toCbor()),
                            expiration = expiration
                        )
                        return CredentialId(bucketId, index)
                    } catch (_: KeyExistsStorageException) {
                        // try again
                    }
                }
                // key space is too small
                if (keySpaceSizeLog == currentKeySpaceSizeLog && currentKeySpaceSizeLog < 20) {
                    val newKeySpaceSizeLog = keySpaceSizeLog + 1
                    keySpaceSizeLog = newKeySpaceSizeLog
                    Logger.i(TAG, "Grew key space to ${1 shl newKeySpaceSizeLog} items")
                }
            }
        }

        /**
         * Updates the existing credential record.
         *
         * @param credentialId credential identifier
         * @param state new credential state
         */
        suspend fun updateCredential(credentialId: CredentialId, state: CredentialState) {
            val table = BackendEnvironment.getTable(dataTableSpec)
            val data = ByteString(state.toCbor())
            // Keep the record around a bit after the actual expiration
            val expiration = state.expiration + 10.seconds
            table.update(
                partitionId = credentialId.bucket,
                key = encodeIndexToKey(credentialId.index),
                data = data,
                expiration = expiration
            )
        }

        /**
         * Queries the credential state
         *
         * @param credentialId credential identifier
         * @return credential's state or null if it is not found or expired
         */
        suspend fun getCredentialState(credentialId: CredentialId): CredentialState? =
            BackendEnvironment.getTable(dataTableSpec)
                .get(
                    partitionId = credentialId.bucket,
                    key = encodeIndexToKey(credentialId.index)
                )?.let { bytes ->
                    fromCbor(bytes.toByteArray())
                }

        /**
         * List credentials with status codes that are not zero (zero represents a valid
         * credential).
         *
         * @param after only return credentials that have index greater than `after`
         * @param limit return at most this many statuses
         * @return pairs of credential index and its [Status]
         */
        suspend fun listNonValidCredentials(
            bucketId: String,
            after: Int? = null,
            limit: Int = Int.MAX_VALUE
        ): List<Pair<Int, Status>> =
            BackendEnvironment.getTable(statusTableSpec)
                .enumerateWithData(
                    partitionId = bucketId,
                    afterKey = after?.let { encodeIndexToKey(it) },
                    limit = limit
                ).map { (key, data) ->
                    Pair(decodeKeyToIndex(key), Status.decode(data[0]))
                }

        fun indexToIdentifier(index: Int): ByteString {
            require(index >= 0)
            return buildByteString {
                if (index > 0xFFFFFF) {
                    append((index shr 12).toByte())
                }
                if (index > 0xFFFF) {
                    append((index shr 8).toByte())
                }
                if (index > 0xFF) {
                    append((index shr 4).toByte())
                }
                append(index.toByte())
            }
        }

        /**
         * Sets the credential status.
         *
         * Initial credential status is always [Status.VALID] (which is coded as zero)
         */
        suspend fun setCredentialStatus(
            credentialId: CredentialId,
            status: Status,
            expiration: Instant
        ) {
            val credentialKey = encodeIndexToKey(credentialId.index)
            val table = BackendEnvironment.getTable(statusTableSpec)
            if (status == Status.VALID) {
                table.delete(partitionId = credentialId.bucket, key = credentialKey)
            } else {
                val encoded = buildByteString { append(status.encoded.toByte()) }
                try {
                    table.insert(
                        partitionId = credentialId.bucket,
                        key = credentialKey,
                        data = encoded,
                        expiration = expiration
                    )
                } catch (_: KeyExistsStorageException) {
                    table.update(
                        partitionId = credentialId.bucket,
                        key = credentialKey,
                        data = encoded,
                        expiration = expiration
                    )
                }
            }
        }

        /**
         * Queries the credential status
         *
         * @param credentialId credential identifier
         * @return credential's status
         */
        suspend fun getCredentialStatus(credentialId: CredentialId): Status =
            BackendEnvironment.getTable(statusTableSpec)
                .get(
                    partitionId = credentialId.bucket,
                    key = encodeIndexToKey(credentialId.index)
                )?.let { Status.decode(it[0]) } ?: Status.VALID


        /**
         * Encodes up to 31-bit non-negative integer into a string so that larger integers
         * always come alphabetically later than the smaller integers
         */
        private fun encodeIndexToKey(index: Int): String {
            val b36 = index.toString(36)  // length is 1 to 6
            return b36.length.toString() + b36
        }

        /** Inverse of [encodeIndexToKey] */
        private fun decodeKeyToIndex(key: String): Int {
            check(key.substring(0, 1).toInt() + 1 == key.length)
            return key.substring(1).toInt(36)
        }
    }
}