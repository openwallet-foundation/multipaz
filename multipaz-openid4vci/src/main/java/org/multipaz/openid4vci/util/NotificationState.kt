package org.multipaz.openid4vci.util

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.Crypto
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.time.Instant

@CborSerializable
internal data class NotificationState(
    val issuanceStateId: String,
    val expiration: Instant
) {
    companion object {
        private val notificationTable = StorageTableSpec(
            name = "OID4VCINotification",
            supportPartitions = false,
            supportExpiration = true
        )
        private val outcomeTable = StorageTableSpec(
            name = "OID4VCINotificationOutcome",
            supportPartitions = false,
            supportExpiration = true
        )

        suspend fun create(
            issuanceStateId: String,
            expiration: Instant,
            random: Random = Crypto.secureRandom
        ): String {
            val notificationId = random.nextBytes(16).toBase64Url()
            val state = NotificationState(issuanceStateId, expiration)
            BackendEnvironment.getTable(notificationTable).insert(
                key = notificationId,
                data = ByteString(state.toCbor()),
                expiration = expiration
            )
            return notificationId
        }

        suspend fun recordEvent(notificationId: String, issuanceStateId: String, event: String): Result {
            if (notificationId.length != 22 || notificationId.any {
                    it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_'
                }
            ) {
                return Result.INVALID_ID
            }
            val data = BackendEnvironment.getTable(notificationTable).get(notificationId)
                ?: return Result.INVALID_ID
            val state = fromCbor(data.toByteArray())
            if (state.issuanceStateId != issuanceStateId) {
                return Result.INVALID_ID
            }
            val table = BackendEnvironment.getTable(outcomeTable)
            return try {
                table.insert(
                    key = notificationId,
                    data = event.encodeToByteString(),
                    expiration = state.expiration
                )
                Result.RECORDED
            } catch (_: KeyExistsStorageException) {
                when (table.get(notificationId)?.decodeToString()) {
                    event -> Result.RETRY
                    null -> Result.INVALID_ID
                    else -> Result.CONFLICT
                }
            }
        }
    }

    enum class Result {
        RECORDED,
        RETRY,
        CONFLICT,
        INVALID_ID
    }
}
