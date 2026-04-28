package org.multipaz.compose.cards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A horizontal carousel composable that displays a collection of cards.
 *
 * [CardCarousel] provides a highly interactive way to browse, select, and reorder cards.
 * It features a "cover flow" style layout where the center item is elevated, and side items are scaled down.
 *
 * ## Features
 * - **Snap Scrolling**: Automatically snaps to the nearest card after dragging.
 * - **Reordering**: Long-press and drag to reorder items (optional).
 * - **Focus Reporting**: Reports which card is currently centered.
 * - **Custom Overlays**: Supports custom views for selected item information and empty states.
 *
 * @param modifier a [Modifier].
 * @param cardInfos the cards to show in the carousel.
 * @param initialCardInfo the card to initially select.
 * @param allowReordering if `true` allow the user to reorder cards by long pressing.
 * @param onCardClicked action to perform when a card is tapped.
 * @param onCardFocused called when a new card is focused.
 * @param onCardReordered called when the user has reordered a card.
 * @param selectedCardInfo a composable to draw text underneath the focused card.
 * @param emptyCardContent a composable to draw text when there are no cards. This will be rendered in
 *   the center of a dashed outline of a grey card .
 */
@Composable
fun CardCarousel(
    modifier: Modifier = Modifier,
    cardInfos: List<CardInfo>,
    initialCardInfo: CardInfo? = null,
    allowReordering: Boolean = true,
    onCardClicked: (CardInfo) -> Unit = {},
    onCardFocused: (CardInfo) -> Unit = {},
    onCardReordered: (cardInfo: CardInfo, oldIndex: Int, newIndex: Int) -> Unit = { _, _, _ -> },
    selectedCardInfo: @Composable (cardInfo: CardInfo?, index: Int, total: Int) -> Unit = { _, _, _ -> },
    emptyCardContent: @Composable () -> Unit = { }
) {
    // Local state
    var items by remember(cardInfos) { mutableStateOf(cardInfos) }
    val cardIndex = remember { Animatable(0f) }
    var hasInitialized by remember { mutableStateOf(false) }
    var lastReportedFocusCard by remember { mutableStateOf<CardInfo?>(null) }
    var isReordering by remember { mutableStateOf(false) }

    // Initial Index Logic
    LaunchedEffect(items) {
        if (!hasInitialized && items.isNotEmpty()) {
            val targetIndex = if (initialCardInfo != null) {
                items.indexOfFirst { it.identifier == initialCardInfo.identifier }.takeIf { it >= 0 } ?: 0
            } else {
                0
            }
            cardIndex.snapTo(targetIndex.toFloat())
            hasInitialized = true
        }

        // Clamp index if items reduced
        if (items.isNotEmpty()) {
            val maxIndex = (items.size - 1).toFloat()
            if (cardIndex.value > maxIndex) {
                cardIndex.snapTo(maxIndex)
            }
        }
    }

    // Report Focus Changes
    LaunchedEffect(items, isReordering) {
        snapshotFlow { cardIndex.value }
            .distinctUntilChanged()
            .collect { currentIndex ->
                if (items.isEmpty() || isReordering) return@collect

                val index = currentIndex.roundToInt().coerceIn(0, items.size - 1)
                val focusedCard = items[index]

                if (focusedCard != lastReportedFocusCard) {
                    lastReportedFocusCard = focusedCard
                    onCardFocused(focusedCard)
                }
            }
    }

    if (items.isEmpty()) {
        EmptyStateView(modifier, emptyCardContent)
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy((-10).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CardCarousel(
                items = items,
                cardIndexAnimatable = cardIndex,
                isReordering = isReordering,
                onIsReorderingChange = { isReordering = it },
                allowReordering = allowReordering,
                onCarouselItemClick = { cardInfo ->
                    onCardClicked(cardInfo)
                },
                onReorder = { oldIndex, newIndex ->
                    if (newIndex in items.indices) {
                        val movedCard = items[newIndex]
                        onCardReordered(movedCard, oldIndex, newIndex)
                    }
                },
                onItemsChanged = { newItems -> items = newItems }
            )

            InfoOverlayView(
                items = items,
                cardIndex = cardIndex.value,
                isReordering = isReordering,
                selectedCardInfo = selectedCardInfo
            )
        }
    }
}

// MARK: - Subviews

@Composable
private fun EmptyStateView(modifier: Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.5f),
        contentAlignment = Alignment.Center
    ) {
        val width = maxWidth
        val cardWidth = width * 0.85f
        val borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

        Box(
            modifier = Modifier
                .width(cardWidth)
                .fillMaxHeight(0.85f)
                .drawBehind {
                    // Create dashed border effect
                    val stroke = Stroke(
                        width = 4.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 20f), 0f)
                    )

                    drawRoundRect(
                        color = borderColor,
                        style = stroke,
                        cornerRadius = CornerRadius(24.dp.toPx())
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun InfoOverlayView(
    items: List<CardInfo>,
    cardIndex: Float,
    isReordering: Boolean,
    selectedCardInfo: @Composable (CardInfo?, Int, Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(10f),
        contentAlignment = Alignment.Center
    ) {
        val totalCount = items.size

        items.forEachIndexed { index, cardInfo ->
            val dist = index.toFloat() - cardIndex
            val absDist = abs(dist)

            if (absDist < 0.5f) {
                val opacity = max(0f, 1.0f - (absDist * 2.0f))
                val xOffset = dist * 30

                Box(
                    modifier = Modifier
                        .offset { IntOffset(xOffset.dp.roundToPx(), 0) }
                        .graphicsLayer { alpha = opacity }
                ) {
                    if (isReordering) {
                        selectedCardInfo(null, index, totalCount)
                    } else {
                        selectedCardInfo(cardInfo, index, totalCount)
                    }
                }
            }
        }
    }
}

// MARK: - Internal Implementation

@Composable
private fun CardCarousel(
    items: List<CardInfo>,
    cardIndexAnimatable: Animatable<Float, AnimationVector1D>,
    isReordering: Boolean,
    onIsReorderingChange: (Boolean) -> Unit,
    allowReordering: Boolean,
    onCarouselItemClick: (CardInfo) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onItemsChanged: (List<CardInfo>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    // Scroll State
    var dragStartIndex by remember { mutableStateOf<Float?>(null) }
    var currentDragTotal by remember { mutableFloatStateOf(0f) }
    var lastHapticIndex by remember { mutableIntStateOf(0) }

    // Reorder State
    // We track WHICH item is being dragged by reference to survive list swaps
    var draggingItem by remember { mutableStateOf<CardInfo?>(null) }
    var originalReorderIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }

    // We keep a reference to the latest list to avoid stale closures in gesture detection
    val currentItems by rememberUpdatedState(items)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .draggable(
                enabled = !isReordering, // Disable main scroll when reordering
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    // Standard Scroll Logic
                    if (dragStartIndex == null) return@rememberDraggableState

                    val dragSensitivity = 650f
                    currentDragTotal += delta
                    val dragOffset = currentDragTotal / dragSensitivity

                    val start = dragStartIndex!!
                    val maxIndex = (items.size - 1).toFloat()
                    val rawNewValue = start - dragOffset
                    val minBound = max(0f, start - 1f)
                    val maxBound = min(maxIndex, start + 1f)

                    val clampedVal = rawNewValue.coerceIn(minBound, maxBound)
                    scope.launch { cardIndexAnimatable.snapTo(clampedVal) }

                    val currentSnapIndex = clampedVal.roundToInt()
                    if (currentSnapIndex != lastHapticIndex) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        lastHapticIndex = currentSnapIndex
                    }
                },
                onDragStarted = {
                    dragStartIndex = cardIndexAnimatable.value
                    lastHapticIndex = cardIndexAnimatable.value.roundToInt()
                    currentDragTotal = 0f
                },
                onDragStopped = { velocity ->
                    // Standard Fling Logic
                    val currentIndex = cardIndexAnimatable.value
                    val nearestIndex = currentIndex.roundToInt().toFloat()

                    val isSwipeLeft = velocity < -1000f
                    val isSwipeRight = velocity > 1000f

                    val rawTarget = when {
                        isSwipeLeft -> min(items.size - 1, nearestIndex.toInt() + 1)
                        isSwipeRight -> max(0, nearestIndex.toInt() - 1)
                        else -> nearestIndex.toInt().coerceIn(0, items.size - 1)
                    }

                    dragStartIndex = null

                    // Haptic check for the final resting spot
                    if (rawTarget != lastHapticIndex) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        lastHapticIndex = rawTarget
                    }

                    scope.launch {
                        cardIndexAnimatable.animateTo(
                            rawTarget.toFloat(),
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)
                        )
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val cardWidthPx = screenWidthPx * 0.85f
        val maxCardHeightPx = cardWidthPx / 1.586f

        val horizontalPeekPx = cardWidthPx * 0.12f
        val verticalOffsetPx = maxCardHeightPx * 0.02f
        val swapThreshold = horizontalPeekPx * 0.85f

        val cardIndex = cardIndexAnimatable.value
        val fractionalPart = cardIndex - floor(cardIndex)

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            items.forEachIndexed { index, cardInfo ->
                // key(cardInfo) is crucial for correct state retention during swaps
                key(cardInfo) {
                    val i = index.toFloat()
                    val offset = i - cardIndex
                    val absOffset = abs(offset)
                    val isCurrentCard = index == cardIndex.roundToInt()

                    // --- Visual Calculations ---
                    val scaleBase = 1.0f - 0.08f * min(1.5f, absOffset)
                    val interpolation = min(1.0f, absOffset)
                    val targetRatio = 1.586f + (0.3f * interpolation)
                    val targetHeight = cardWidthPx / targetRatio

                    val clampedOffset = min(1.0f, max(-1.0f, offset))
                    val baseTranslationX = horizontalPeekPx * clampedOffset

                    // Motion Factor (opening gap between cards)
                    val motionFactor = 2.0f * min(fractionalPart, 1.0f - fractionalPart)
                    val maxSlideDistance = (screenWidthPx - cardWidthPx) / 2.0f - 8.0f

                    val floorIndex = floor(cardIndex).toInt()
                    val isNextCard = index == floorIndex + 1
                    val isNextNextCard = index == floorIndex + 2

                    val motionExtra = if (isCurrentCard || isNextCard || isNextNextCard) {
                        val direction = if (offset > 0) 1 else -1

                        // Move the "next" card 40% "slower" relative to the spread force.
                        // Since spread force pushes cards AWAY from center, reducing it makes
                        // the card move TOWARD the center (swipe direction) effectively "faster".
                        // This increases the gap between Next and NextNext.
                        val speedMultiplier = if (isNextCard) 0.6f else 1.0f

                        (maxSlideDistance * 2.0f - horizontalPeekPx) * motionFactor * direction * speedMultiplier
                    } else { 0.0f }

                    val standardTranslationX = baseTranslationX + motionExtra
                    val translationY = verticalOffsetPx * interpolation

                    // --- Reorder State Calculations ---
                    val isBeingDragged = (cardInfo == draggingItem)

                    // If dragged, use the cumulative drag offset + where it "should" be
                    val finalTranslationX = if (isBeingDragged) standardTranslationX + dragOffsetX else standardTranslationX
                    val finalScale = if (isBeingDragged) 1.05f else scaleBase
                    val finalZIndex = if (isBeingDragged) 100f else if (isCurrentCard) 2f else if (isNextCard) 1f else -absOffset
                    val finalShadow = if (isBeingDragged) 24.dp else if (isCurrentCard) 16.dp else 5.dp

                    Box(
                        modifier = Modifier
                            // 1. GESTURES FIRST: So they don't get lost when zIndex changes
                            .pointerInput(cardInfo) {
                                detectTapGestures {
                                    if (!isReordering && absOffset < 0.5f) {
                                        onCarouselItemClick(cardInfo)
                                    }
                                }
                            }
                            .pointerInput(cardInfo) {
                                if (allowReordering) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            // Only allow reordering the currently centered card
                                            if (!isReordering && isCurrentCard) {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onIsReorderingChange(true)
                                                draggingItem = cardInfo

                                                val currentIndex = currentItems.indexOfFirst { it.identifier == cardInfo.identifier }
                                                originalReorderIndex = currentIndex
                                                dragOffsetX = 0f
                                            }
                                        },
                                        onDragEnd = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)

                                            onIsReorderingChange(false)
                                            draggingItem = null
                                            dragOffsetX = 0f

                                            // Notify listener of final move
                                            val finalIdx = currentItems.indexOfFirst { it.identifier == cardInfo.identifier }
                                            val origIdx = originalReorderIndex
                                            if (origIdx != null && finalIdx != -1 && origIdx != finalIdx) {
                                                onReorder(origIdx, finalIdx)
                                            }
                                            originalReorderIndex = null
                                        },
                                        onDragCancel = {
                                            onIsReorderingChange(false)
                                            draggingItem = null
                                            dragOffsetX = 0f
                                            originalReorderIndex = null
                                        },
                                        onDrag = { change, dragAmount ->
                                            if (draggingItem != cardInfo) return@detectDragGesturesAfterLongPress
                                            change.consume()

                                            // Accumulate drag
                                            dragOffsetX += dragAmount.x

                                            // Logic to check for swaps
                                            val currentList = currentItems
                                            val currentIndex = currentList.indexOfFirst { it.identifier == cardInfo.identifier }
                                            if (currentIndex == -1) return@detectDragGesturesAfterLongPress

                                            // Check Right Swap
                                            if (dragOffsetX > swapThreshold && currentIndex < currentList.size - 1) {
                                                val targetIndex = currentIndex + 1

                                                // Let's do the data swap
                                                val mutableItems = currentList.toMutableList()
                                                val movedItem = mutableItems.removeAt(currentIndex)
                                                mutableItems.add(targetIndex, movedItem)

                                                // Update Data
                                                onItemsChanged(mutableItems)
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                                                // COMPENSATE OFFSET
                                                dragOffsetX -= horizontalPeekPx

                                                // Also snap the carousel index to follow so the camera follows the card
                                                scope.launch { cardIndexAnimatable.snapTo(targetIndex.toFloat()) }

                                            } else if (dragOffsetX < -swapThreshold && currentIndex > 0) {
                                                val targetIndex = currentIndex - 1

                                                val mutableItems = currentList.toMutableList()
                                                val movedItem = mutableItems.removeAt(currentIndex)
                                                mutableItems.add(targetIndex, movedItem)

                                                onItemsChanged(mutableItems)
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                                                // COMPENSATE OFFSET
                                                dragOffsetX += horizontalPeekPx

                                                scope.launch { cardIndexAnimatable.snapTo(targetIndex.toFloat()) }
                                            }
                                        }
                                    )
                                }
                            }
                            // 2. LAYOUT PROPERTIES
                            .zIndex(finalZIndex)
                            .width(with(LocalDensity.current) { cardWidthPx.toDp() })
                            .height(with(LocalDensity.current) { targetHeight.toDp() })
                            .graphicsLayer {
                                this.scaleX = finalScale
                                this.scaleY = finalScale
                                this.translationX = finalTranslationX
                                this.translationY = translationY
                                shadowElevation = finalShadow.toPx()
                                shape = RoundedCornerShape(24.dp)
                                clip = false
                            }
                    ) {
                        CarouselItem(
                            cardInfo = cardInfo,
                            overlayAlpha = calculateOverlay(
                                isReordering = isReordering,
                                isCurrentCard = isCurrentCard,
                                isNextCard = isNextCard,
                                motionFactor = motionFactor,
                                fraction = fractionalPart
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun calculateOverlay(
    isReordering: Boolean,
    isCurrentCard: Boolean,
    isNextCard: Boolean,
    motionFactor: Float,
    fraction: Float
): Float {
    if (isReordering) return 0.0f
    if (isCurrentCard) return 0.35f * motionFactor
    if (isNextCard) {
        return if (fraction >= 0.5f) 0.0f else 0.35f * motionFactor
    }
    return 0.0f
}

@Composable
private fun CarouselItem(
    cardInfo: CardInfo,
    overlayAlpha: Float
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    shape = RoundedCornerShape(24.dp)
                    clip = true
                }
                .background(Color.White)
        ) {
            Image(
                bitmap = cardInfo.cardArt,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )

            if (overlayAlpha > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = overlayAlpha))
                )
            }
        }

        CardBadges(
            badges = cardInfo.badges,
            elevation = 8.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(100f)
        )
    }
}