package org.multipaz.verification

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.presentment.TransactionData
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
     * NB: nonce is only used for certain request types, specifically DC API and OpenID4VP requests.
     *
     * @param nonce the expected nonce (e.g. the one returned by
     *     [PaymentProcessor.createTransaction]).
     * @throws org.multipaz.rpc.handler.InvalidRequestException if the nonce does not match.
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
        atTime: Instant = Clock.System.now(),
        documentTypeRepository: DocumentTypeRepository? = null,
        zkSystemRepository: ZkSystemRepository? = null
    ): List<VerifiedPresentation>

    /**
     * Parses and returns transaction data entries using the given [documentTypeRepository]
     * to resolve transaction types.
     *
     * @param documentTypeRepository repository used to look up transaction type definitions, it
     *  must be non-null if any transactions are present
     * @return map from credential ID to parsed transaction data entries.
     */
    abstract fun getTransactionData(
        documentTypeRepository: DocumentTypeRepository
    ): Map<String, List<TransactionData>>

    companion object
}