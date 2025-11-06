package org.multipaz.jwt

import kotlinx.io.bytestring.buildByteString
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.StorageTableSpec
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Helper object to generate and validate short single-use unique values with expiration that are
 * suitable for use as JWT nonce/challenge.
 *
 * This implementation is not a very scalable, but it is the simplest one that is persistent and
 * would reliably detect nonce/challenge replay.
 */
object Challenge {
    /**
     * Generates a nonce/challenge string.
     *
     * @param expiration time when this nonce becomes invalid (10 minutes from now by default)
     * @return new unique nonce
     */
    suspend fun create(
        expiration: Instant = Clock.System.now() + 10.minutes
    ): String =
        BackendEnvironment.getTable(challengeTableSpec).insert(
            key = null,
            data = buildByteString {},
            expiration = expiration
        )

    /**
     * Validates a nonce/challenge and atomically marks it as used, so another attempt to validate
     * it will fail.
     *
     * @param challenge challenge to validate
     * @throws ChallengeInvalidException when the given challenge is expired or invalid
     */
    suspend fun validateAndConsume(challenge: String) {
        val table = BackendEnvironment.getTable(challengeTableSpec)
        if (!table.delete(challenge)) {
            throw ChallengeInvalidException()
        }
    }

    private val challengeTableSpec = StorageTableSpec(
        name = "ActiveChallenges",
        supportExpiration = true,
        supportPartitions = false
    )
}