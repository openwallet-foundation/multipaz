package org.multipaz.verifier.session

import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Algorithm

/**
 * Transaction data hashes in the credential verification response for a particular credential.
 */
data class TransactionDataHashes(
    val hashAlgorithm: Algorithm,
    val hashes: List<ByteString>
)