package org.multipaz.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerticalCardListModelTest {

    @Test
    fun syncCards_basicAndReordering() {
        val model = VerticalCardListModel()

        // Initial sync
        model.syncCards(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), model.displayOrderIdentifiers)

        // Reordered externally when not dragging updates displayOrderIdentifiers
        model.syncCards(listOf("b", "a", "c", "d"))
        assertEquals(listOf("b", "a", "c", "d"), model.displayOrderIdentifiers)

        // Item removed
        model.syncCards(listOf("b", "c", "d"))
        assertEquals(listOf("b", "c", "d"), model.displayOrderIdentifiers)

        // Sync during drag is ignored
        model.draggedCardIdentifier = "c"
        model.syncCards(listOf("x", "y"))
        assertEquals(listOf("b", "c", "d"), model.displayOrderIdentifiers)
    }

    @Test
    fun resolveDisplayOrder_matchesDisplayOrderIdentifiers() {
        val model = VerticalCardListModel(
            displayOrderIdentifiers = listOf("c2", "c1", "c3")
        )
        val available = listOf(
            CardLayoutItem("c1", 1.586),
            CardLayoutItem("c2", 1.0),
            CardLayoutItem("c3", 2.0)
        )

        val resolved = model.resolveDisplayOrder(available)
        assertEquals(listOf("c2", "c1", "c3"), resolved.map { it.identifier })
        assertEquals(1.0, resolved[0].aspectRatio)
        assertEquals(1.586, resolved[1].aspectRatio)
        assertEquals(2.0, resolved[2].aspectRatio)
    }

    @Test
    fun dragAndDrop_fullLifecycle() {
        val model = VerticalCardListModel(
            displayOrderIdentifiers = listOf("c0", "c1", "c2", "c3")
        )
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0,
            unfocusedVisiblePercent = 50,
            spacing = 16.0
        )
        val cards = listOf(
            CardLayoutItem("c0", 2.0), // h=184, step=92
            CardLayoutItem("c1", 2.0),
            CardLayoutItem("c2", 2.0),
            CardLayoutItem("c3", 2.0)
        )
        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = false)

        // Start drag on c1 (at index 1, initial y = 16 + 92 = 108.0)
        val started = model.startDrag("c1", layout, cards, params)
        assertTrue(started)
        assertEquals("c1", model.draggedCardIdentifier)
        assertEquals(108.0, model.dragCurrentY, 0.001)
        assertFalse(model.dragJustEnded)

        // Small drag within slot 1
        val resSmall = model.updateDrag(10.0, layout, cards, params)
        assertFalse(resSmall.reordered)
        assertEquals(listOf("c0", "c1", "c2", "c3"), model.displayOrderIdentifiers)

        // Drag down past slot 2 threshold (delta +120 -> dragCurrentY = 238)
        val resDown = model.updateDrag(120.0, layout, cards, params)
        assertTrue(resDown.reordered)
        assertEquals(1, resDown.fromIndex)
        assertEquals(2, resDown.toIndex)
        assertEquals(listOf("c0", "c2", "c1", "c3"), model.displayOrderIdentifiers)

        // Drag all the way to bottom
        val resBottom = model.updateDrag(300.0, layout, cards, params)
        assertTrue(resBottom.reordered)
        assertEquals(2, resBottom.fromIndex)
        assertEquals(3, resBottom.toIndex)
        assertEquals(listOf("c0", "c2", "c3", "c1"), model.displayOrderIdentifiers)

        // End drag
        val endResult = model.endDrag()
        assertNotNull(endResult)
        assertEquals("c1", endResult.cardIdentifier)
        assertEquals(3, endResult.newIndex)
        assertNull(model.draggedCardIdentifier)
        assertTrue(model.dragJustEnded)

        // Tap during cooldown is ignored
        val tapAction = model.handleTap("c0", isAnyFocused = false, focusedCardIdentifier = null)
        assertEquals(CardTapAction.Ignore, tapAction)

        // Clear cooldown
        model.clearDragJustEnded()
        assertFalse(model.dragJustEnded)
    }

    @Test
    fun updateDragPosition_absolutePositioning() {
        val model = VerticalCardListModel(
            displayOrderIdentifiers = listOf("c0", "c1", "c2")
        )
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0,
            unfocusedVisiblePercent = 50,
            spacing = 16.0
        )
        val cards = listOf(
            CardLayoutItem("c0", 2.0), // h=184, step=92
            CardLayoutItem("c1", 2.0),
            CardLayoutItem("c2", 2.0)
        )
        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = false)

        model.startDrag("c0", layout, cards, params)
        assertEquals(16.0, model.dragCurrentY, 0.001)

        // Drag absolute position to slot 1 center (y ≈ 154)
        val res = model.updateDragPosition(154.0, layout, cards, params)
        assertTrue(res.reordered)
        assertEquals(0, res.fromIndex)
        assertEquals(1, res.toIndex)
        assertEquals(listOf("c1", "c0", "c2"), model.displayOrderIdentifiers)
        assertEquals(154.0, model.dragCurrentY, 0.001)
    }

    @Test
    fun cancelDrag_resetsState() {
        val model = VerticalCardListModel(
            displayOrderIdentifiers = listOf("c0", "c1")
        )
        model.draggedCardIdentifier = "c0"
        model.cancelDrag()
        assertNull(model.draggedCardIdentifier)
        assertTrue(model.dragJustEnded)
    }

    @Test
    fun handleTap_allScenarios() {
        val model = VerticalCardListModel(
            displayOrderIdentifiers = listOf("c0", "c1", "c2")
        )

        // List mode: tap card
        val tapList = model.handleTap("c1", isAnyFocused = false, focusedCardIdentifier = null)
        assertEquals(CardTapAction.Focus("c1"), tapList)

        // Focused mode: tap focused card
        val tapFocused = model.handleTap("c1", isAnyFocused = true, focusedCardIdentifier = "c1")
        assertEquals(CardTapAction.FocusedCardTapped("c1"), tapFocused)

        // Focused mode: tap stack card
        val tapStack = model.handleTap("c2", isAnyFocused = true, focusedCardIdentifier = "c1")
        assertEquals(CardTapAction.FocusedStackTapped("c2"), tapStack)

        // Dragging active: ignore
        model.draggedCardIdentifier = "c0"
        val tapDragging = model.handleTap("c0", isAnyFocused = false, focusedCardIdentifier = null)
        assertEquals(CardTapAction.Ignore, tapDragging)
    }

    @Test
    fun startDrag_cardNotInList_returnsFalse() {
        val model = VerticalCardListModel(
            displayOrderIdentifiers = listOf("c0", "c1")
        )
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0
        )
        val cards = listOf(CardLayoutItem("c0"), CardLayoutItem("c1"))
        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = false)

        val result = model.startDrag("non_existent", layout, cards, params)
        assertFalse(result)
        assertNull(model.draggedCardIdentifier)
    }

    @Test
    fun startDrag_singleCardList_handledGracefully() {
        val model = VerticalCardListModel(
            displayOrderIdentifiers = listOf("c0")
        )
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0
        )
        val cards = listOf(CardLayoutItem("c0", 2.0))
        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = false)

        assertTrue(model.startDrag("c0", layout, cards, params))
        assertEquals("c0", model.draggedCardIdentifier)
        assertEquals(16.0, model.dragCurrentY, 0.001)

        val update = model.updateDrag(100.0, layout, cards, params)
        assertFalse(update.reordered)
        assertEquals(listOf("c0"), model.displayOrderIdentifiers)

        val end = model.endDrag()
        assertNotNull(end)
        assertEquals("c0", end.cardIdentifier)
        assertEquals(0, end.newIndex)
    }

    @Test
    fun syncCards_emptyAndDuplicateHandling() {
        val model = VerticalCardListModel()

        // Sync with empty list
        model.syncCards(emptyList())
        assertTrue(model.displayOrderIdentifiers.isEmpty())

        // Sync with duplicate input IDs
        model.syncCards(listOf("c1", "c2", "c1"))
        assertEquals(listOf("c1", "c2", "c1"), model.displayOrderIdentifiers)

        // Sync removes items not present in incoming list
        model.syncCards(listOf("c2"))
        assertEquals(listOf("c2"), model.displayOrderIdentifiers)
    }

    @Test
    fun model_constructorsAndPropertyDefaults() {
        val defaultModel = VerticalCardListModel()
        assertTrue(defaultModel.displayOrderIdentifiers.isEmpty())
        assertNull(defaultModel.draggedCardIdentifier)
        assertEquals(0.0, defaultModel.dragCurrentY, 0.001)
        assertFalse(defaultModel.dragJustEnded)
        assertNull(defaultModel.lastFocusedCardIdentifier)
        assertFalse(defaultModel.animateListTransitions)
        assertTrue(defaultModel.showTopContent)
        assertTrue(defaultModel.showPlaceholderWhenEmpty)
        assertEquals(0.0, defaultModel.topContentHeight, 0.001)
        assertEquals(0.0, defaultModel.scrollOffset, 0.001)
    }
}
