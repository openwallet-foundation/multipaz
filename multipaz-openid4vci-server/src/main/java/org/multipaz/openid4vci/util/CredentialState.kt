package org.multipaz.openid4vci.util

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import kotlin.random.Random
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

    companion object {
        private const val TAG = "CredentialState"

        @Volatile
        private var keySpaceSizeLog = 4

        /** Holds [CredentialState] */
        private val dataTableSpec = StorageTableSpec(
            name = "Openid4VciCredential",
            supportPartitions = false,
            supportExpiration = true
        )

        /**
         * Holds [Status], but only for values other than [Status.VALID], so that this table is
         * sparse.
         */
        private val statusTableSpec = StorageTableSpec(
            name = "Openid4VciCredStatus",
            supportPartitions = false,
            supportExpiration = true
        )

        /**
         * Creates a new credential record.
         *
         * @param state initial credential state, typically a placeholder as the credential itself
         *   was not yet created (as it requires the credential index).
         * @return credential index
         */
        suspend fun recordNewCredential(state: CredentialState): Int {
            val table = BackendEnvironment.getTable(dataTableSpec)
            val data = ByteString(state.toCbor())
            // Keep the record around a bit after the actual expiration
            val expiration = state.expiration + 10.seconds
            // We want to keep the key space fairly compact yet random to generate revocation list
            // efficiently. Try to find an unused key. If after 16 tries we still could not,
            // grow the key space.
            while (true) {
                val currentKeySpaceSizeLog = keySpaceSizeLog
                val currentKeySpaceSize = 1 shl currentKeySpaceSizeLog
                repeat(currentKeySpaceSizeLog) {
                    try {
                        val index = Random.nextInt(currentKeySpaceSize)
                        table.insert(
                            key = encodeIndexToKey(index),
                            data = data,
                            expiration = expiration
                        )
                        return index
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
         * @param credentialIndex credential index
         * @param state new credential state
         */
        suspend fun updateCredential(credentialIndex: Int, state: CredentialState) {
            val table = BackendEnvironment.getTable(dataTableSpec)
            val data = ByteString(state.toCbor())
            // Keep the record around a bit after the actual expiration
            val expiration = state.expiration + 10.seconds
            table.update(
                key = encodeIndexToKey(credentialIndex),
                data = data,
                expiration = expiration
            )
        }

        /**
         * Queries the credential state
         *
         * @param credentialIndex credential index
         * @return credential's state or null if it is not found or expired
         */
        suspend fun getCredentialState(credentialIndex: Int): CredentialState? =
            BackendEnvironment.getTable(dataTableSpec)
                .get(encodeIndexToKey(credentialIndex))?.let { bytes ->
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
            after: Int? = null,
            limit: Int = Int.MAX_VALUE
        ): List<Pair<Int, Status>> =
            BackendEnvironment.getTable(statusTableSpec)
                .enumerateWithData(
                    afterKey = after?.let { encodeIndexToKey(it) },
                    limit = limit
                ).map { (key, data) ->
                    Pair(decodeKeyToIndex(key), Status.decode(data[0]))
                }

        /**
         * Sets the credential status.
         *
         * Initial credential status is always [Status.VALID] (which is coded as zero)
         */
        suspend fun setCredentialStatus(
            credentialIndex: Int,
            status: Status,
            expiration: Instant
        ) {
            val credentialStateId = encodeIndexToKey(credentialIndex)
            val table = BackendEnvironment.getTable(statusTableSpec)
            if (status == Status.VALID) {
                table.delete(credentialStateId)
            } else {
                val encoded = buildByteString { append(status.encoded.toByte()) }
                try {
                    table.insert(credentialStateId, encoded, expiration = expiration)
                } catch (_: KeyExistsStorageException) {
                    table.update(credentialStateId, encoded, expiration = expiration)
                }
            }
        }

        /**
         * Queries the credential status
         *
         * @param credentialIndex index of the credential
         * @return credential's status
         */
        suspend fun getCredentialStatus(credentialIndex: Int): Status =
            BackendEnvironment.getTable(statusTableSpec)
                .get(encodeIndexToKey(credentialIndex))?.let { Status.decode(it[0]) } ?: Status.VALID


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