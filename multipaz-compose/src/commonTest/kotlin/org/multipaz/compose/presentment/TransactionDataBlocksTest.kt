package org.multipaz.compose.presentment

import kotlinx.io.bytestring.ByteString
import org.multipaz.documenttype.TransactionType
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import org.multipaz.prompt.TransactionDataRenderer
import org.multipaz.prompt.TransactionDataRendererRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionDataBlocksTest {

    private object TypeA : TransactionType<String>(displayName = "A", identifier = "com.example.a")
    private object TypeB : TransactionType<String>(displayName = "B", identifier = "com.example.b")

    private fun item(type: TransactionType<String>, payload: String) = TransactionData(
        type = type,
        payload = payload,
        protocol = TransactionProtocol.OPENID4VP,
        rawBytes = ByteString(payload.encodeToByteArray()),
    )

    private val rendererA = TransactionDataRendererCompose(summary = { })

    private val repositoryA = TransactionDataRendererRepository().apply {
        addRenderer(TypeA.identifier, rendererA)
    }

    @Test
    fun withoutRepositoryEveryItemIsBuiltInInRequestOrder() {
        val items = listOf(item(TypeA, "a1"), item(TypeB, "b1"), item(TypeA, "a2"))

        val blocks = transactionDataBlocks(items, transactionDataRendererRepository = null)

        assertEquals(items.map { TransactionDataBlock.BuiltIn(it) }, blocks)
    }

    @Test
    fun typeWithoutRendererIsBuiltIn() {
        val items = listOf(item(TypeB, "b1"), item(TypeB, "b2"))

        val blocks = transactionDataBlocks(items, transactionDataRendererRepository = repositoryA)

        assertEquals(items.map { TransactionDataBlock.BuiltIn(it) }, blocks)
    }

    @Test
    fun registeredTypeIsDrawnOnceWithAllItsItems() {
        val a1 = item(TypeA, "a1")
        val a2 = item(TypeA, "a2")

        val blocks = transactionDataBlocks(listOf(a1, a2), transactionDataRendererRepository = repositoryA)

        assertEquals(listOf(TransactionDataBlock.Wallet(TypeA.identifier, listOf(a1, a2), rendererA)), blocks)
    }

    @Test
    fun walletGroupTakesThePlaceOfItsFirstItem() {
        val b1 = item(TypeB, "b1")
        val a1 = item(TypeA, "a1")
        val b2 = item(TypeB, "b2")
        val a2 = item(TypeA, "a2")

        val blocks = transactionDataBlocks(listOf(b1, a1, b2, a2), transactionDataRendererRepository = repositoryA)

        assertEquals(
            listOf(
                TransactionDataBlock.BuiltIn(b1),
                TransactionDataBlock.Wallet(TypeA.identifier, listOf(a1, a2), rendererA),
                TransactionDataBlock.BuiltIn(b2),
            ),
            blocks
        )
    }

    @Test
    fun rendererForAnotherToolkitKeepsBuiltInRendering() {
        val swiftUiLike = object : TransactionDataRenderer() {}
        val repository = TransactionDataRendererRepository().apply {
            addRenderer(TypeA.identifier, swiftUiLike)
        }
        val a1 = item(TypeA, "a1")

        val blocks = transactionDataBlocks(listOf(a1), transactionDataRendererRepository = repository)

        assertEquals(listOf(TransactionDataBlock.BuiltIn(a1)), blocks)
    }

    @Test
    fun repositoryCanChooseRendererPerIdentifier() {
        val rendererB = TransactionDataRendererCompose(summary = { })
        val looked = mutableListOf<String>()
        val repository = object : TransactionDataRendererRepository() {
            override fun getRenderer(identifier: String): TransactionDataRenderer? {
                looked.add(identifier)
                return if (identifier == TypeB.identifier) rendererB else null
            }
        }
        val a1 = item(TypeA, "a1")
        val b1 = item(TypeB, "b1")

        val blocks = transactionDataBlocks(listOf(a1, b1), transactionDataRendererRepository = repository)

        assertEquals(
            listOf(
                TransactionDataBlock.BuiltIn(a1),
                TransactionDataBlock.Wallet(TypeB.identifier, listOf(b1), rendererB),
            ),
            blocks
        )
        assertEquals(listOf(TypeA.identifier, TypeB.identifier), looked)
    }
}
