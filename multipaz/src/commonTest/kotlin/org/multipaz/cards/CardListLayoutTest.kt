package org.multipaz.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardListLayoutTest {

    @Test
    fun computeCardDimensions_standardCreditCardRatio() {
        val dims = CardListLayoutCalculator.computeCardDimensions(
            viewportWidth = 400.0,
            cardMaxHeight = null,
            paddingHorizontal = 16.0,
            aspectRatio = 1.586
        )

        assertEquals(368.0, dims.width, 0.001)
        assertEquals(368.0 / 1.586, dims.height, 0.001)
        assertEquals(16.0, dims.xOffset, 0.001)
    }

    @Test
    fun computeCardDimensions_squareRatio() {
        val dims = CardListLayoutCalculator.computeCardDimensions(
            viewportWidth = 400.0,
            cardMaxHeight = null,
            paddingHorizontal = 16.0,
            aspectRatio = 1.0
        )

        assertEquals(368.0, dims.width, 0.001)
        assertEquals(368.0, dims.height, 0.001)
        assertEquals(16.0, dims.xOffset, 0.001)
    }

    @Test
    fun computeCardDimensions_maxHeightConstraintApplied() {
        val dims = CardListLayoutCalculator.computeCardDimensions(
            viewportWidth = 400.0,
            cardMaxHeight = 150.0,
            paddingHorizontal = 16.0,
            aspectRatio = 1.586
        )

        assertEquals(150.0, dims.height, 0.001)
        assertEquals(150.0 * 1.586, dims.width, 0.001)
        assertEquals((400.0 - (150.0 * 1.586)) / 2.0, dims.xOffset, 0.001)
    }

    @Test
    fun computeCardDimensions_squareWithMaxHeightClamping() {
        val dims = CardListLayoutCalculator.computeCardDimensions(
            viewportWidth = 400.0,
            cardMaxHeight = 200.0,
            paddingHorizontal = 16.0,
            aspectRatio = 1.0
        )

        assertEquals(200.0, dims.height, 0.001)
        assertEquals(200.0, dims.width, 0.001)
        assertEquals(100.0, dims.xOffset, 0.001)
    }

    @Test
    fun computeCardStep_unfocusedPercent() {
        val height = 200.0
        val step100 = CardListLayoutCalculator.computeCardStep(height, 100, spacing = 16.0)
        assertEquals(216.0, step100, 0.001)

        val step25 = CardListLayoutCalculator.computeCardStep(height, 25, spacing = 16.0)
        assertEquals(50.0, step25, 0.001)

        val step50 = CardListLayoutCalculator.computeCardStep(height, 50, spacing = 16.0)
        assertEquals(100.0, step50, 0.001)

        val step0 = CardListLayoutCalculator.computeCardStep(height, 0, spacing = 16.0)
        assertEquals(0.0, step0, 0.001)
    }

    @Test
    fun parametersValidation() {
        assertFailsWith<IllegalArgumentException> {
            CardListLayoutParameters(
                viewportWidth = 400.0,
                viewportHeight = 800.0,
                unfocusedVisiblePercent = -1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CardListLayoutParameters(
                viewportWidth = 400.0,
                viewportHeight = 800.0,
                unfocusedVisiblePercent = 101
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CardListLayoutParameters(
                viewportWidth = 400.0,
                viewportHeight = 800.0,
                topContentProgress = -0.1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CardListLayoutParameters(
                viewportWidth = 400.0,
                viewportHeight = 800.0,
                topContentProgress = 1.1
            )
        }
    }

    @Test
    fun computeLayout_topContentOffsets() {
        val cards = listOf(CardLayoutItem("c1"), CardLayoutItem("c2"))

        // Top content visible
        val paramsVisible = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0,
            topContentHeight = 60.0,
            isTopContentVisible = true,
            topContentProgress = 1.0,
            spacing = 16.0
        )
        val layoutVisible = CardListLayoutCalculator.computeLayout(paramsVisible, cards, isAnyFocused = false)
        assertTrue(layoutVisible.isTopContentEffectivelyVisible)
        assertEquals(76.0, layoutVisible.effectiveTopContentHeight, 0.001)
        assertEquals(92.0, layoutVisible.listTopOffset, 0.001)

        // Top content half animated
        val paramsHalf = paramsVisible.copy(topContentProgress = 0.5)
        val layoutHalf = CardListLayoutCalculator.computeLayout(paramsHalf, cards, isAnyFocused = false)
        assertEquals(38.0, layoutHalf.effectiveTopContentHeight, 0.001)
        assertEquals(54.0, layoutHalf.listTopOffset, 0.001)

        // Top content disabled when focused
        val layoutFocused = CardListLayoutCalculator.computeLayout(paramsVisible, cards, isAnyFocused = true)
        assertFalse(layoutFocused.isTopContentEffectivelyVisible)
        assertEquals(0.0, layoutFocused.effectiveTopContentHeight, 0.001)
        assertEquals(16.0, layoutFocused.listTopOffset, 0.001)
    }

    @Test
    fun computeLayout_totalHeightCalculations() {
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0,
            paddingBottom = 16.0,
            unfocusedVisiblePercent = 25,
            spacing = 16.0
        )

        // Empty list
        val layoutEmpty = CardListLayoutCalculator.computeLayout(params, emptyList(), isAnyFocused = false)
        assertEquals(0.0, layoutEmpty.totalHeight, 0.001)

        // 1 card
        val card1 = CardLayoutItem("c1", aspectRatio = 2.0) // width = 368, height = 184
        val layout1 = CardListLayoutCalculator.computeLayout(params, listOf(card1), isAnyFocused = false)
        // totalHeight = paddingTop (16) + height (184) + paddingBottom (16) = 216
        assertEquals(216.0, layout1.totalHeight, 0.001)

        // 3 cards of uniform height 184.0
        // step = 184.0 * 0.25 = 46.0
        // totalHeight = 16.0 + (2 * 46.0) + 184.0 + 16.0 = 308.0
        val cards3 = listOf(card1, CardLayoutItem("c2", 2.0), CardLayoutItem("c3", 2.0))
        val layout3 = CardListLayoutCalculator.computeLayout(params, cards3, isAnyFocused = false)
        assertEquals(308.0, layout3.totalHeight, 0.001)
    }

    @Test
    fun computeLayout_heterogeneousAspectRatios() {
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0,
            paddingBottom = 16.0,
            unfocusedVisiblePercent = 50,
            spacing = 16.0
        )

        val card1 = CardLayoutItem("c1", aspectRatio = 2.0) // height = 184, step = 92
        val card2 = CardLayoutItem("c2", aspectRatio = 1.0) // height = 368, step = 184
        val card3 = CardLayoutItem("c3", aspectRatio = 4.0) // height = 92
        val cards = listOf(card1, card2, card3)

        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = false)
        // totalHeight = 16 (paddingTop) + 92 (c1 step) + 184 (c2 step) + 92 (c3 height) + 16 (paddingBottom) = 400
        assertEquals(400.0, layout.totalHeight, 0.001)
        assertEquals(184.0, layout.getDimensions("c1").height, 0.001)
        assertEquals(368.0, layout.getDimensions("c2").height, 0.001)
        assertEquals(92.0, layout.getDimensions("c3").height, 0.001)

        // When focused while scrolled down (e.g. scrollOffset = 500, viewportHeight = 800)
        val paramsScrolled = params.copy(scrollOffset = 500.0)
        val layoutFocusedScrolled = CardListLayoutCalculator.computeLayout(paramsScrolled, cards, isAnyFocused = true)
        // totalHeight must be at least 500 + 800 = 1300 to prevent scroll clamping
        assertEquals(1300.0, layoutFocusedScrolled.totalHeight, 0.001)
    }

    @Test
    fun computeCardVisualState_listMode() {
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0,
            unfocusedVisiblePercent = 25,
            spacing = 16.0
        )
        val cards = listOf(
            CardLayoutItem("c1", aspectRatio = 2.0), // h = 184, step = 46
            CardLayoutItem("c2", aspectRatio = 2.0),
            CardLayoutItem("c3", aspectRatio = 2.0)
        )
        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = false)

        val state0 = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c1",
            index = 0,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = null,
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(16.0, state0.y, 0.001)
        assertEquals(1.0, state0.scale, 0.001)
        assertEquals(12.0, state0.elevation, 0.001)
        assertEquals(0.0, state0.zIndex, 0.001)
        assertEquals(1.0, state0.alpha, 0.001)

        val state1 = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c2",
            index = 1,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = null,
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(62.0, state1.y, 0.001) // 16 + 46
        assertEquals(1.0, state1.scale, 0.001)
        assertEquals(1.0, state1.zIndex, 0.001)

        // Dragged item
        val stateDragged = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c2",
            index = 1,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = null,
            draggedCardIdentifier = "c2",
            dragCurrentY = 150.0
        )
        assertEquals(150.0, stateDragged.y, 0.001)
        assertEquals(1.05, stateDragged.scale, 0.001)
        assertEquals(24.0, stateDragged.elevation, 0.001)
        assertEquals(100.0, stateDragged.zIndex, 0.001)
    }

    @Test
    fun computeCardVisualState_focusedModeAndStack() {
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0,
            scrollOffset = 50.0,
            showStackWhileFocused = true,
            stackOffset = 14.0
        )
        val cards = (0..6).map { CardLayoutItem("c$it", aspectRatio = 2.0) } // 7 cards, height = 184
        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = true)

        // Focused card (c0)
        val stateFocused = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c0",
            index = 0,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = "c0",
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(66.0, stateFocused.y, 0.001) // scrollOffset(50) + paddingTop(16)
        assertEquals(1.025, stateFocused.scale, 0.001)
        assertEquals(24.0, stateFocused.elevation, 0.001)
        assertEquals(100.0, stateFocused.zIndex, 0.001)
        assertEquals(1.0, stateFocused.alpha, 0.001)

        // maxStackIndex = 7 - 2 = 5
        // Front-most card in stack is c6 (stackIndex = 5, distanceToFront = 0)
        val stateFront = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c6",
            index = 6,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = "c0",
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        val frontCardVisibleHeight = 184.0 * 0.25 // 46.0
        val frontCardY = 50.0 + (800.0 - 46.0) // 804.0
        assertEquals(frontCardY, stateFront.y, 0.001)
        assertEquals(0.95, stateFront.scale, 0.001)
        assertEquals(1.0, stateFront.alpha, 0.001)

        // Card c5 (stackIndex = 4, distanceToFront = 1)
        val stateC5 = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c5",
            index = 5,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = "c0",
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(frontCardY - 14.0, stateC5.y, 0.001)
        assertEquals(0.925, stateC5.scale, 0.001)
        assertEquals(1.0, stateC5.alpha, 0.001)

        // Card c1 (stackIndex = 0, distanceToFront = 5 >= maxVisibleCardsInStack(5)) -> alpha must be 0.0
        val stateC1 = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c1",
            index = 1,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = "c0",
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(0.0, stateC1.alpha, 0.001)
    }

    @Test
    fun computeCardVisualState_focusedModeStackDisabled() {
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0,
            showStackWhileFocused = false
        )
        val cards = listOf(CardLayoutItem("c0"), CardLayoutItem("c1"))
        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = true)

        val stateUnfocused = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c1",
            index = 1,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = "c0",
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(0.0, stateUnfocused.alpha, 0.001)
    }

    @Test
    fun findTargetIndexForDragY_uniformAndVariableHeights() {
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0,
            unfocusedVisiblePercent = 50,
            spacing = 16.0
        )
        val cards = listOf(
            CardLayoutItem("c0", 2.0), // h=184, step=92, slot 0: [16..108], center=62
            CardLayoutItem("c1", 2.0), // h=184, step=92, slot 1: [108..200], center=154
            CardLayoutItem("c2", 2.0)  // h=184, step=92, slot 2: [200..292], center=246
        )
        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = false)

        assertEquals(0, CardListLayoutCalculator.findTargetIndexForDragY(-50.0, layout, cards, params))
        assertEquals(0, CardListLayoutCalculator.findTargetIndexForDragY(70.0, layout, cards, params))
        assertEquals(1, CardListLayoutCalculator.findTargetIndexForDragY(150.0, layout, cards, params))
        assertEquals(2, CardListLayoutCalculator.findTargetIndexForDragY(260.0, layout, cards, params))
        assertEquals(2, CardListLayoutCalculator.findTargetIndexForDragY(500.0, layout, cards, params))
    }

    @Test
    fun computeDetailTopOffset_calculation() {
        val offset = CardListLayoutCalculator.computeDetailTopOffset(
            paddingTop = 16.0,
            focusedCardHeight = 200.0,
            spacing = 24.0
        )
        // 16.0 + 200.0 * 1.025 + 24.0 = 245.0
        assertEquals(245.0, offset, 0.001)
    }

    @Test
    fun computeCardVisualState_focusedCardInMiddleOfList() {
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 16.0,
            scrollOffset = 0.0,
            showStackWhileFocused = true,
            stackOffset = 14.0
        )
        val cards = (0..4).map { CardLayoutItem("c$it", aspectRatio = 2.0) } // 5 cards, height = 184
        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = true)

        // Focus middle card (c2, index 2)
        val stateC2 = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c2",
            index = 2,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = "c2",
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(16.0, stateC2.y, 0.001)
        assertEquals(1.025, stateC2.scale, 0.001)
        assertEquals(100.0, stateC2.zIndex, 0.001)

        // In focused mode, all non-focused cards form the bottom stack ordered by index:
        // maxStackIndex = 5 - 2 = 3. frontCardY = 800 - 46 = 754.0
        // c4 (index 4): stackIndex = 3, distanceToFront = 0 -> y = 754.0, zIndex = 3.0
        // c3 (index 3): stackIndex = 2, distanceToFront = 1 -> y = 740.0, zIndex = 2.0
        // c1 (index 1): stackIndex = 1, distanceToFront = 2 -> y = 726.0, zIndex = 1.0
        // c0 (index 0): stackIndex = 0, distanceToFront = 3 -> y = 712.0, zIndex = 0.0
        val frontCardY = 800.0 - (184.0 * 0.25) // 754.0

        val stateC4 = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c4",
            index = 4,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = "c2",
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(frontCardY, stateC4.y, 0.001)
        assertEquals(0.95, stateC4.scale, 0.001)
        assertEquals(3.0, stateC4.zIndex, 0.001)
        assertEquals(1.0, stateC4.alpha, 0.001)

        val stateC3 = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c3",
            index = 3,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = "c2",
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(frontCardY - 14.0, stateC3.y, 0.001)
        assertEquals(0.925, stateC3.scale, 0.001)
        assertEquals(2.0, stateC3.zIndex, 0.001)
        assertEquals(1.0, stateC3.alpha, 0.001)

        val stateC1 = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c1",
            index = 1,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = "c2",
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(frontCardY - 28.0, stateC1.y, 0.001)
        assertEquals(0.90, stateC1.scale, 0.001)
        assertEquals(1.0, stateC1.zIndex, 0.001)
        assertEquals(1.0, stateC1.alpha, 0.001)

        val stateC0 = CardListLayoutCalculator.computeCardVisualState(
            cardIdentifier = "c0",
            index = 0,
            cards = cards,
            layout = layout,
            params = params,
            focusedCardIdentifier = "c2",
            draggedCardIdentifier = null,
            dragCurrentY = 0.0
        )
        assertEquals(frontCardY - 42.0, stateC0.y, 0.001)
        assertEquals(0.875, stateC0.scale, 0.001)
        assertEquals(0.0, stateC0.zIndex, 0.001)
        assertEquals(1.0, stateC0.alpha, 0.001)
    }

    @Test
    fun computeLayout_unfocusedPercent100FullSpacing() {
        val params = CardListLayoutParameters(
            viewportWidth = 400.0,
            viewportHeight = 800.0,
            paddingTop = 20.0,
            paddingBottom = 20.0,
            unfocusedVisiblePercent = 100,
            spacing = 15.0
        )
        val cards = listOf(
            CardLayoutItem("c1", 2.0), // h = 184
            CardLayoutItem("c2", 2.0)  // h = 184
        )
        val layout = CardListLayoutCalculator.computeLayout(params, cards, isAnyFocused = false)
        // step = 184 + 15 = 199.0
        // totalHeight = paddingTop (20) + step (199) + height (184) + paddingBottom (20) = 423.0
        assertEquals(423.0, layout.totalHeight, 0.001)
    }
}
