package org.multipaz.openid

import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Algorithm
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OpenID4VPTransactionHashAlgorithmTest {
    private fun item(algs: List<Algorithm>?) = TransactionData(
        type = PaymentTransaction,
        payload = PaymentTransaction.sampleData.payload,
        protocol = TransactionProtocol.OPENID4VP,
        rawBytes = ByteString(byteArrayOf(1, 2, 3)),
        hashAlgorithms = algs
    )

    @Test
    fun noLists_impliesDefault() {
        assertNull(OpenID4VP.selectTransactionHashAlgorithm(listOf(item(null), item(null))))
    }

    @Test
    fun sameList() {
        assertEquals(
            Algorithm.SHA384,
            OpenID4VP.selectTransactionHashAlgorithm(
                listOf(item(listOf(Algorithm.SHA384)), item(listOf(Algorithm.SHA384)))
            )
        )
    }

    @Test
    fun differentFirstEntries_overlapIsAccepted() {
        assertEquals(
            Algorithm.SHA256,
            OpenID4VP.selectTransactionHashAlgorithm(
                listOf(
                    item(listOf(Algorithm.SHA384, Algorithm.SHA256)),
                    item(listOf(Algorithm.SHA256))
                )
            )
        )
    }

    @Test
    fun itemWithoutList_acceptsOnlySha256() {
        assertEquals(
            Algorithm.SHA256,
            OpenID4VP.selectTransactionHashAlgorithm(
                listOf(item(null), item(listOf(Algorithm.SHA384, Algorithm.SHA256)))
            )
        )
    }

    @Test
    fun noCommonAlgorithm_throws() {
        val e = assertFailsWith<IllegalArgumentException> {
            OpenID4VP.selectTransactionHashAlgorithm(
                listOf(item(listOf(Algorithm.SHA384)), item(listOf(Algorithm.SHA256)))
            )
        }
        assertEquals(true, e.message!!.contains("transaction_data_hashes_alg"))
    }

    @Test
    fun sha384WithItemLackingList_throws() {
        assertFailsWith<IllegalArgumentException> {
            OpenID4VP.selectTransactionHashAlgorithm(
                listOf(item(listOf(Algorithm.SHA384)), item(null))
            )
        }
    }
}
