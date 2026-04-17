package org.multipaz.server.presentment

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Data that describes the self-contained result of presentation in a way that can be stored
 * and verified (immediately or at a later time).
 */
@CborSerializable
sealed class PresentmentRecord() {
    /**
     * Verifies that the presentation was bound to the expected nonce.
     *
     * @param nonce the expected nonce (e.g. the one returned by
     *     [PaymentProcessor.createTransaction]).
     * @throws InvalidRequestException if the nonce does not match.
     */
    abstract suspend fun verifyNonce(nonce: ByteString)

    /**
     * Verifies the cryptographic validity of the presentment and the issuer trust chain.
     * This includes verifying that the credential holder approved all transactions that apply to
     * the presented credentials, even if no data was sent as a response for a given transaction.
     *
     * @param atTime the time at which to evaluate validity.
     * @return a list of verification results, one per document/credential in the presentation.
     */
    abstract suspend fun verify(
        atTime: Instant = Clock.System.now()
    ): List<PresentmentResult>

    companion object
}