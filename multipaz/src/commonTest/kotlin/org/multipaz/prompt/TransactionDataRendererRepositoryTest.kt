package org.multipaz.prompt

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class TransactionDataRendererRepositoryTest {

    private class TestRenderer : TransactionDataRenderer()

    @Test
    fun returnsRendererForIdentifier() {
        val payment = TestRenderer()
        val ping = TestRenderer()
        val repository = TransactionDataRendererRepository().apply {
            addRenderer("urn:eudi:sca:payment:1", payment)
            addRenderer("org.multipaz.transaction.ping", ping)
        }
        assertSame(payment, repository.getRenderer("urn:eudi:sca:payment:1"))
        assertSame(ping, repository.getRenderer("org.multipaz.transaction.ping"))
    }

    @Test
    fun returnsNullForUnknownIdentifier() {
        val repository = TransactionDataRendererRepository().apply {
            addRenderer("delegate", TestRenderer())
        }
        assertNull(repository.getRenderer("org.multipaz.transaction.ping"))
        assertNull(TransactionDataRendererRepository().getRenderer("delegate"))
    }

    @Test
    fun addingSameIdentifierReplacesRenderer() {
        val first = TestRenderer()
        val second = TestRenderer()
        val repository = TransactionDataRendererRepository().apply {
            addRenderer("delegate", first)
            addRenderer("delegate", second)
        }
        assertSame(second, repository.getRenderer("delegate"))
    }
}
