package org.multipaz.cards

import kotlin.math.abs

/**
 * Pure, deterministic layout and visual state calculator for card lists.
 *
 * This calculator is completely stateless and side-effect free. It translates layout configuration
 * parameters ([CardListLayoutParameters]), viewport constraints, and active focus/drag identifiers
 * into exact geometric coordinates, dimensions, and visual properties ([CardVisualState]) for every
 * card in a list.
 *
 * ### Responsibilities
 * - **Dimension & Aspect Ratio Calculation**: Computes aspect-ratio preserved card dimensions
 *   and horizontal centering offsets based on viewport constraints ([computeCardDimensions]).
 * - **Total Height & Offsets**: Computes total scrollable heights, top content offsets, and list
 *   margins ([computeLayout]).
 * - **Visual State Derivation**: Computes the exact `y`, `scale`, `elevation`, `alpha`, and `zIndex`
 *   for any card at any point in time ([computeCardVisualState]).
 * - **Drag Target Resolution**: Determines target index insertion points during active drag
 *   gestures based on vertical overlap thresholds ([findTargetIndexForDragY]).
 *
 * Used in conjunction with [VerticalCardListModel] across both Compose Multiplatform and SwiftUI.
 */
object CardListLayoutCalculator {

    /**
     * Computes the rendered dimensions and horizontal centering offset for a card.
     *
     * @param viewportWidth available viewport width.
     * @param cardMaxHeight optional maximum height constraint.
     * @param paddingHorizontal horizontal padding.
     * @param aspectRatio width-to-height aspect ratio of the card.
     * @return [CardDimensions] containing width, height, and horizontal offset.
     */
    fun computeCardDimensions(
        viewportWidth: Double,
        cardMaxHeight: Double?,
        paddingHorizontal: Double,
        aspectRatio: Double
    ): CardDimensions {
        val availableWidth = (viewportWidth - 2.0 * paddingHorizontal).coerceAtLeast(0.0)
        val effectiveAspectRatio = if (aspectRatio > 0.0) aspectRatio else CardLayoutItem.DEFAULT_ASPECT_RATIO
        var cardWidth = availableWidth
        var cardHeight = if (effectiveAspectRatio > 0.0) cardWidth / effectiveAspectRatio else 0.0

        if (cardMaxHeight != null && cardMaxHeight > 0.0 && cardHeight > cardMaxHeight) {
            cardHeight = cardMaxHeight
            cardWidth = cardHeight * effectiveAspectRatio
        }

        val cardXOffset = ((viewportWidth - cardWidth) / 2.0).coerceAtLeast(0.0)
        return CardDimensions(
            width = cardWidth,
            height = cardHeight,
            xOffset = cardXOffset
        )
    }

    /**
     * Computes the vertical step distance allocated to a card in standard list mode.
     *
     * @param cardHeight rendered height of the card.
     * @param unfocusedVisiblePercent percentage of each card visible when unfocused (0 to 100).
     * @param spacing vertical spacing applied when visible percent is 100.
     * @return vertical distance in dp/points to the next card header.
     */
    fun computeCardStep(
        cardHeight: Double,
        unfocusedVisiblePercent: Int,
        spacing: Double
    ): Double {
        return if (unfocusedVisiblePercent == 100) {
            cardHeight + spacing
        } else {
            cardHeight * (unfocusedVisiblePercent / 100.0)
        }
    }

    /**
     * Computes overall layout metadata for the card list container.
     *
     * @param params layout configuration parameters.
     * @param cards list of cards in current display order.
     * @param isAnyFocused whether any card in the list is currently focused.
     * @return [CardListLayout] containing computed offsets, total height, and stack metrics.
     */
    fun computeLayout(
        params: CardListLayoutParameters,
        cards: List<CardLayoutItem>,
        isAnyFocused: Boolean
    ): CardListLayout {
        val dimensionsMap = cards.associate { card ->
            card.identifier to computeCardDimensions(
                viewportWidth = params.viewportWidth,
                cardMaxHeight = params.cardMaxHeight,
                paddingHorizontal = params.paddingHorizontal,
                aspectRatio = card.aspectRatio
            )
        }

        val defaultDimensions = computeCardDimensions(
            viewportWidth = params.viewportWidth,
            cardMaxHeight = params.cardMaxHeight,
            paddingHorizontal = params.paddingHorizontal,
            aspectRatio = CardLayoutItem.DEFAULT_ASPECT_RATIO
        )

        val isTopContentEffectivelyVisible = params.isTopContentVisible && !isAnyFocused
        val topContentSpacing = if (params.topContentHeight > 0.0) params.spacing else 0.0
        val effectiveTopContentHeight = if (isTopContentEffectivelyVisible) {
            (params.topContentHeight + topContentSpacing) * params.topContentProgress
        } else {
            0.0
        }
        val listTopOffset = params.paddingTop + effectiveTopContentHeight

        val totalListHeight: Double = when {
            cards.isEmpty() -> 0.0
            cards.size == 1 -> {
                val h0 = dimensionsMap[cards[0].identifier]?.height ?: defaultDimensions.height
                listTopOffset + h0 + params.paddingBottom
            }
            else -> {
                var sumSteps = 0.0
                for (i in 0 until cards.size - 1) {
                    val h = dimensionsMap[cards[i].identifier]?.height ?: defaultDimensions.height
                    sumSteps += computeCardStep(h, params.unfocusedVisiblePercent, params.spacing)
                }
                val lastH = dimensionsMap[cards.last().identifier]?.height ?: defaultDimensions.height
                listTopOffset + sumSteps + lastH + params.paddingBottom
            }
        }

        val totalHeight = if (isAnyFocused) {
            val minFocusedContentHeight = params.scrollOffset.coerceAtLeast(0.0) + params.viewportHeight
            maxOf(totalListHeight, minFocusedContentHeight)
        } else {
            totalListHeight
        }

        val maxStackIndex = (cards.size - 2).coerceAtLeast(0)
        val maxVisibleStackOffsets = minOf(maxStackIndex, params.maxVisibleCardsInStack - 1)

        val frontCardHeight = if (cards.isNotEmpty()) {
            dimensionsMap[cards.last().identifier]?.height ?: defaultDimensions.height
        } else {
            defaultDimensions.height
        }
        val frontCardVisibleHeight = frontCardHeight * params.frontCardVisibleHeightFraction

        val detailBottomPadding = if (params.showStackWhileFocused && cards.size > 1) {
            frontCardVisibleHeight + (maxVisibleStackOffsets * params.stackOffset) + params.spacing
        } else {
            params.spacing
        }

        return CardListLayout(
            cardDimensionsMap = dimensionsMap,
            defaultCardDimensions = defaultDimensions,
            listTopOffset = listTopOffset,
            totalHeight = totalHeight,
            maxStackIndex = maxStackIndex,
            maxVisibleStackOffsets = maxVisibleStackOffsets,
            detailBottomPadding = detailBottomPadding,
            isTopContentEffectivelyVisible = isTopContentEffectivelyVisible,
            effectiveTopContentHeight = effectiveTopContentHeight
        )
    }

    /**
     * Computes the visual state for a card given its position and interaction state.
     *
     * @param cardIdentifier identifier of the card.
     * @param index index of the card in the current display order.
     * @param cards list of cards in current display order.
     * @param layout computed [CardListLayout].
     * @param params layout configuration parameters.
     * @param focusedCardIdentifier identifier of the currently focused card, if any.
     * @param draggedCardIdentifier identifier of the card being dragged, if any.
     * @param dragCurrentY current vertical position of the dragged card.
     * @return [CardVisualState] containing y, scale, elevation, zIndex, and alpha.
     */
    fun computeCardVisualState(
        cardIdentifier: String,
        index: Int,
        cards: List<CardLayoutItem>,
        layout: CardListLayout,
        params: CardListLayoutParameters,
        focusedCardIdentifier: String?,
        draggedCardIdentifier: String?,
        dragCurrentY: Double
    ): CardVisualState {
        val isAnyFocused = focusedCardIdentifier != null
        val isFocused = isAnyFocused && cardIdentifier == focusedCardIdentifier
        val isDragged = cardIdentifier == draggedCardIdentifier

        return if (isAnyFocused) {
            if (isFocused) {
                CardVisualState(
                    y = params.scrollOffset + params.paddingTop,
                    scale = 1.025,
                    elevation = 24.0,
                    zIndex = 100.0,
                    alpha = 1.0
                )
            } else {
                val focusedIndex = cards.indexOfFirst { it.identifier == focusedCardIdentifier }.coerceAtLeast(0)
                val stackIndex = if (index < focusedIndex) index else index - 1
                val distanceToFront = layout.maxStackIndex - stackIndex
                val clampedDistanceToFront = minOf(distanceToFront, params.maxVisibleCardsInStack - 1)

                val frontCardHeight = if (cards.isNotEmpty()) {
                    layout.getDimensions(cards.last().identifier).height
                } else {
                    layout.defaultCardDimensions.height
                }
                val frontCardVisibleHeight = frontCardHeight * params.frontCardVisibleHeightFraction
                val frontCardY = params.scrollOffset + (params.viewportHeight - frontCardVisibleHeight).coerceAtLeast(0.0)
                val targetY = frontCardY - (clampedDistanceToFront * params.stackOffset)
                val targetScale = (0.95 - (clampedDistanceToFront * 0.025)).coerceAtLeast(0.6)
                val targetElevation = 12.0
                val targetZIndex = stackIndex.toDouble()
                val targetAlpha = if (!params.showStackWhileFocused || distanceToFront >= params.maxVisibleCardsInStack) 0.0 else 1.0

                CardVisualState(
                    y = targetY,
                    scale = targetScale,
                    elevation = targetElevation,
                    zIndex = targetZIndex,
                    alpha = targetAlpha
                )
            }
        } else {
            if (isDragged) {
                CardVisualState(
                    y = dragCurrentY,
                    scale = 1.05,
                    elevation = 24.0,
                    zIndex = 100.0,
                    alpha = 1.0
                )
            } else {
                var yAcc = layout.listTopOffset
                for (i in 0 until index) {
                    val h = layout.getDimensions(cards[i].identifier).height
                    yAcc += computeCardStep(h, params.unfocusedVisiblePercent, params.spacing)
                }
                CardVisualState(
                    y = yAcc,
                    scale = 1.0,
                    elevation = 12.0,
                    zIndex = index.toDouble(),
                    alpha = 1.0
                )
            }
        }
    }

    /**
     * Computes the top offset for the detail view below a focused card.
     *
     * @param paddingTop top padding of the list.
     * @param focusedCardHeight rendered height of the focused card.
     * @param spacing vertical spacing below the focused card. Defaults to 24.0.
     * @return top offset in dp/points for the detail view.
     */
    fun computeDetailTopOffset(
        paddingTop: Double,
        focusedCardHeight: Double,
        spacing: Double = 24.0
    ): Double {
        return paddingTop + focusedCardHeight * 1.025 + spacing
    }

    /**
     * Finds the target index for a dragged card given its current Y position.
     *
     * @param dragCurrentY current vertical position of the dragged card.
     * @param layout computed [CardListLayout].
     * @param cards list of cards in current display order.
     * @param params layout configuration parameters.
     * @return target index position in the list clamped to `[0, cards.size - 1]`.
     */
    fun findTargetIndexForDragY(
        dragCurrentY: Double,
        layout: CardListLayout,
        cards: List<CardLayoutItem>,
        params: CardListLayoutParameters
    ): Int {
        if (cards.isEmpty()) return 0
        if (cards.size == 1) return 0

        var ySlot = layout.listTopOffset
        var closestIndex = 0
        var minDistance = Double.MAX_VALUE

        for (k in cards.indices) {
            val h = layout.getDimensions(cards[k].identifier).height
            val step = computeCardStep(h, params.unfocusedVisiblePercent, params.spacing)
            val slotCenter = ySlot + step / 2.0
            val dist = abs(dragCurrentY - slotCenter)
            if (dist < minDistance) {
                minDistance = dist
                closestIndex = k
            }
            ySlot += step
        }

        return closestIndex.coerceIn(0, cards.size - 1)
    }
}
