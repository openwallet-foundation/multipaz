package org.multipaz.prompt

import kotlinx.io.bytestring.ByteString
import org.multipaz.documenttype.TransactionType
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransactionDataRendererRequirementTest {

    private object Plain : TransactionType<String>(
        displayName = "Plain",
        identifier = "com.example.plain",
    )

    private object Needed : TransactionType<String>(
        displayName = "Needed",
        identifier = "com.example.needed",
        requiresRenderer = true,
    )

    private class ToolkitA : TransactionDataRenderer()
    private class ToolkitB : TransactionDataRenderer()

    private fun <T : Any> item(type: TransactionType<T>, payload: T) =
        TransactionData(type, payload, TransactionProtocol.OPENID4VP, ByteString())

    private val anyRenderer: (TransactionDataRenderer) -> Boolean = { true }

    private fun repositoryWith(identifier: String, renderer: TransactionDataRenderer) =
        TransactionDataRendererRepository().apply { addRenderer(identifier, renderer) }

    @Test
    fun aTypeThatDoesNotRequireOneNeverNeedsARenderer() {
        assertNull(missingTransactionDataRenderer(listOf(item(Plain, "p")), repository = null, isUsable = anyRenderer))
        assertNull(missingTransactionDataRenderer(listOf(item(Plain, "p")), TransactionDataRendererRepository(), anyRenderer))
    }

    // The three ways a renderer can be missing, each of which used to fall back to the built-in
    // rendering and let the person approve without seeing the data.
    @Test
    fun noRendererRegisteredForTheType() {
        assertEquals(
            Needed.identifier,
            missingTransactionDataRenderer(listOf(item(Needed, "n")), TransactionDataRendererRepository(), anyRenderer)
        )
        // One registered for another type does not count.
        assertEquals(
            Needed.identifier,
            missingTransactionDataRenderer(listOf(item(Needed, "n")), repositoryWith(Plain.identifier, ToolkitA()), anyRenderer)
        )
    }

    @Test
    fun noRepositoryWasPassedToThePrompt() {
        assertEquals(Needed.identifier, missingTransactionDataRenderer(listOf(item(Needed, "n")), repository = null, isUsable = anyRenderer))
    }

    @Test
    fun aRendererMadeForAnotherToolkitCountsAsNone() {
        val repository = repositoryWith(Needed.identifier, ToolkitB())
        val usableHere: (TransactionDataRenderer) -> Boolean = { it is ToolkitA }
        assertEquals(Needed.identifier, missingTransactionDataRenderer(listOf(item(Needed, "n")), repository, usableHere))
        // The same renderer is fine for the toolkit it was made for.
        assertNull(missingTransactionDataRenderer(listOf(item(Needed, "n")), repository) { it is ToolkitB })
    }

    @Test
    fun aRegisteredRendererLetsThePromptThrough() {
        val repository = repositoryWith(Needed.identifier, ToolkitA())
        assertNull(missingTransactionDataRenderer(listOf(item(Plain, "p"), item(Needed, "n")), repository, anyRenderer))
    }

    // The first type, in request order, without one is the one named.
    @Test
    fun theFirstTypeWithoutOneIsNamed() {
        val other = object : TransactionType<String>(
            displayName = "Other", identifier = "com.example.other", requiresRenderer = true,
        ) {}
        val repository = repositoryWith(Needed.identifier, ToolkitA())
        assertEquals(other.identifier, missingTransactionDataRenderer(listOf(item(Needed, "n"), item(other, "o")), repository, anyRenderer))
    }
}
