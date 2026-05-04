package org.multipaz.compose.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.yield
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * State object for [VerticalCardList].
 *
 * Use [rememberVerticalCardListState] to create an instance.
 *
 * @param scrollState the scroll state for the list.
 */
@Stable
class VerticalCardListState(
    val scrollState: ScrollState
) {
    /**
     * The current display order of the cards, tracked by identifier.
     */
    var displayOrderIdentifiers by mutableStateOf<List<String>>(emptyList())
        internal set

    /**
     * The identifier of the card currently being dragged, if any.
     */
    var draggedCardIdentifier by mutableStateOf<String?>(null)
        internal set

    /**
     * The current Y position of the dragged card.
     */
    var dragCurrentY by mutableFloatStateOf(0f)
        internal set

    /**
     * Whether a drag operation just ended.
     */
    var dragJustEnded by mutableStateOf(false)
        internal set

    /**
     * The identifier of the last focused card, used to preserve animations across navigation.
     */
    var lastFocusedCardIdentifier by mutableStateOf<String?>(null)
        internal set

    /**
     * Whether to animate spatial transitions (like sliding cards) when entering this screen.
     * This should typically be set to true only when navigating directly between two list states.
     */
    var animateListTransitions by mutableStateOf(false)
}

/**
 * Creates and remembers a [VerticalCardListState].
 *
 * @param scrollState the scroll state for the list.
 * @return a [VerticalCardListState] instance.
 */
@Composable
fun rememberVerticalCardListState(
    scrollState: ScrollState = rememberScrollState()
): VerticalCardListState {
    return remember(scrollState) {
        VerticalCardListState(scrollState)
    }
}

/**
 * A vertically scrolling list of cards that mimics a physical wallet experience.
 *
 * In its default state, cards are displayed as a vertical list. The amount of
 * overlap between cards is configurable. Users can long-press a card to drag and drop it into
 * a new position.
 *
 * When a user taps a card, it enters a "focused" state. The focused card elevates and animates
 * to the top of the viewport. A dynamic content section ([showCardInfo]) fades in immediately
 * below it. By default, the remaining unfocused cards animate into a 3D overlapping stack at the
 * bottom of the screen.
 *
 * This composable uses [VerticalCardListState] to store state and this state object uses
 * [CardInfo.identifier] as the identifiers, meaning it's allowable to pass ephemeral [CardInfo]
 * instances into this composable as long as they keep using the same stable identifiers. This
 * in turn means that it works directly with [DocumentModel] in the following way:
 *
 * ```
 * val cardInfos by documentModel.documentInfos.collectAsState()
 * VerticalCardList(
 *   cardInfos = cardInfos,
 *   [...]
 * )
 * ```
 *
 * @param modifier The modifier to be applied to the list container.
 * @param cardInfos The list of [CardInfo] objects to display.
 * @param focusedCard The currently focused card. When null, the component operates in
 * standard list mode. When set to a [CardInfo], that card is brought to the top and
 * detailed information is displayed.
 * @param unfocusedVisiblePercent Determines how much of each card is visible when not focused. A
 * value of `100` displays cards with standard spacing (no overlap). Lower values cause cards to
 * overlap, allowing more cards to fit on screen. Must be between 0 and 100.
 * @param allowCardReordering If true, users can long-press and drag cards to reorder them
 * when in standard list mode. Defaults to true.
 * @param showStackWhileFocused If true, unfocused cards will collapse into a 3D stack at the bottom
 * of the screen when a card is focused. If false, unfocused cards fade away entirely to maximize
 * screen real estate for the detail view. Defaults to true.
 * @param cardMaxHeight An optional max height constraint for the cards. Useful for foldables and wide screens.
 * @param state The state object to be used to control or observe the list's state.
 * @param showCardInfo A composable slot that renders the detailed content below the focused card.
 * It is horizontally centered by default.
 * @param emptyContent A composable slot displayed inside a dashed placeholder card when the
 * [cardInfos] list is empty.
 * @param onCardReordered Callback invoked when a drag-and-drop reordering operation completes.
 * Provides the [CardInfo] of the moved card and its new index position in the list.
 * @param onCardFocused Callback invoked when a card is tapped to be focused.
 * @param onCardFocusedTapped Callback invoked when the currently focused card is tapped.
 * @param onCardFocusedStackTapped Callback invoked when the unfocused card stack is tapped while another card is in focus.
 *
 * @throws IllegalArgumentException if [unfocusedVisiblePercent] is not between 0 and 100.
 */
@Composable
fun VerticalCardList(
    modifier: Modifier = Modifier,
    cardInfos: List<CardInfo>,
    focusedCard: CardInfo?,
    unfocusedVisiblePercent: Int = 25,
    allowCardReordering: Boolean = true,
    showStackWhileFocused: Boolean = true,
    cardMaxHeight: Dp = Dp.Unspecified,
    state: VerticalCardListState = rememberVerticalCardListState(),
    showCardInfo: @Composable (CardInfo) -> Unit = {},
    emptyContent: @Composable () -> Unit = { },
    onCardReordered: (cardInfo: CardInfo, newPosition: Int) -> Unit = { _, _ -> },
    onCardFocused: (cardInfo: CardInfo) -> Unit = {},
    onCardFocusedTapped: (cardInfo: CardInfo) -> Unit = {},
    onCardFocusedStackTapped: (cardInfo: CardInfo) -> Unit = {}
) {
    if (unfocusedVisiblePercent !in 0..100) {
        throw IllegalArgumentException("unfocusedVisiblePercent must be between 0 and 100")
    }

    // Sync cardInfos with state.displayOrderIdentifiers
    val currentCardIdentifiers = cardInfos.map { it.identifier }
    if (state.draggedCardIdentifier == null && state.displayOrderIdentifiers != currentCardIdentifiers) {
        state.displayOrderIdentifiers = currentCardIdentifiers
    }

    // Resolve CardInfo objects based on the current display order
    val displayOrder = remember(state.displayOrderIdentifiers, cardInfos) {
        state.displayOrderIdentifiers.mapNotNull { id -> cardInfos.find { it.identifier == id } }
    }

    // To preserve animations across navigation, we use an internal focused card identifier state
    // initialized from the state's last focused card identifier ONLY if we are animating a list transition.
    var internalFocusedCardIdentifier by remember(state) {
        mutableStateOf(if (state.animateListTransitions) state.lastFocusedCardIdentifier else focusedCard?.identifier)
    }

    // We use LaunchedEffect to detect when this screen enters the composition or focusedCard changes.
    LaunchedEffect(focusedCard?.identifier) {
        if (state.animateListTransitions && state.lastFocusedCardIdentifier != focusedCard?.identifier) {
            // We are transitioning from another list state, and the focus changed.
            // Snap to the global state so we can animate from it.
            internalFocusedCardIdentifier = state.lastFocusedCardIdentifier
            // Yield to allow Compose to render the snapshot before we change it
            yield()
            // The next frame will animate to our intended state
            state.lastFocusedCardIdentifier = focusedCard?.identifier
            internalFocusedCardIdentifier = focusedCard?.identifier
        } else if (internalFocusedCardIdentifier != focusedCard?.identifier || state.lastFocusedCardIdentifier != focusedCard?.identifier) {
            // Normal forward navigation without transition animation, or local state change
            internalFocusedCardIdentifier = focusedCard?.identifier
            state.lastFocusedCardIdentifier = focusedCard?.identifier
        }
    }

    val internalFocusedCard = cardInfos.find { it.identifier == internalFocusedCardIdentifier }

    val haptic = LocalHapticFeedback.current

    // Automatically reset the block on clicks after a short delay
    LaunchedEffect(state.dragJustEnded) {
        if (state.dragJustEnded) {
            delay(300)
            state.dragJustEnded = false
        }
    }

    val isAnyFocused = internalFocusedCardIdentifier != null
    val focusedIndex = state.displayOrderIdentifiers.indexOf(internalFocusedCardIdentifier).coerceAtLeast(0)

    // A nested scroll connection to intercept and consume the overscroll effect cleanly
    val overscrollConsumer = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = available // Silently consume all leftover scroll at the edges

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity = available
        }
    }

    if (cardInfos.isEmpty()) {
        BoxWithConstraints(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            val density = LocalDensity.current
            val maxWidthPx = constraints.maxWidth.toFloat()
            val paddingHorizontalPx = with(density) { 16.dp.toPx() }

            var cardWidthPx = maxWidthPx - 2 * paddingHorizontalPx
            var cardHeightPx = cardWidthPx / 1.586f

            // Apply height limitation and scale down width if needed to keep ratio
            if (cardMaxHeight.isSpecified) {
                val maxAllowedCardHeightPx = with(density) { cardMaxHeight.toPx() }
                if (cardHeightPx > maxAllowedCardHeightPx) {
                    cardHeightPx = maxAllowedCardHeightPx
                    cardWidthPx = cardHeightPx * 1.586f
                }
            }

            Box(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .width(with(density) { cardWidthPx.toDp() })
                    .height(with(density) { cardHeightPx.toDp() })
                    .drawBehind {
                        drawRoundRect(
                            color = Color.Gray,
                            style = Stroke(
                                width = 3.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 30f), 0f)
                            ),
                            cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx())
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                emptyContent()
            }
        }
        return
    }

    // OUTER BoxWithConstraints: Grabs the exact screen dimensions before any scrolling alters them.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()

        val paddingHorizontalPx = with(density) { 16.dp.toPx() }
        val paddingTopPx = with(density) { 24.dp.toPx() }
        val spacingPx = with(density) { 16.dp.toPx() }

        var cardWidthPx = maxWidthPx - 2 * paddingHorizontalPx
        var cardHeightPx = cardWidthPx / 1.586f

        // Apply height limitation and scale down width if needed to keep ratio
        if (cardMaxHeight.isSpecified) {
            val maxAllowedCardHeightPx = with(density) { cardMaxHeight.toPx() }
            if (cardHeightPx > maxAllowedCardHeightPx) {
                cardHeightPx = maxAllowedCardHeightPx
                cardWidthPx = cardHeightPx * 1.586f
            }
        }

        // Determine X offset to keep the card centered if its width shrank due to max height
        val cardXOffsetPx = (maxWidthPx - cardWidthPx) / 2f

        // --- List Math ---
        val listStepPx = if (unfocusedVisiblePercent == 100) {
            cardHeightPx + spacingPx
        } else {
            cardHeightPx * (unfocusedVisiblePercent / 100f)
        }

        val totalHeightPx = paddingTopPx + (max(0, state.displayOrderIdentifiers.size - 1) * listStepPx) + cardHeightPx + paddingTopPx
        val totalHeightDp = with(density) { totalHeightPx.toDp() }

        // --- Stack Math ---
        val maxStackIndex = max(0, state.displayOrderIdentifiers.size - 2)
        val maxVisibleCardsInStack = 5
        val maxVisibleStackOffsets = min(maxStackIndex, maxVisibleCardsInStack - 1)

        val stackOffsetPx = with(density) { 14.dp.toPx() }
        val frontCardVisibleHeightPx = cardHeightPx * 0.25f

        val detailBottomPaddingDp = if (showStackWhileFocused) {
            with(density) {
                (frontCardVisibleHeightPx + (maxVisibleStackOffsets * stackOffsetPx)).toDp()
            } + 16.dp
        } else {
            16.dp
        }

        // SCROLLING CONTAINER
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(overscrollConsumer)
                .verticalScroll(state.scrollState, enabled = !isAnyFocused)
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeightDp)
            )

            AnimatedVisibility(
                visible = isAnyFocused,
                enter = fadeIn(tween(400)),
                exit = fadeOut(tween(400)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { maxHeightPx.toDp() })
                    .offset { IntOffset(0, state.scrollState.value) }
                    .zIndex(50f)
            ) {
                val topOffsetDp = with(density) { (paddingTopPx + cardHeightPx * 1.05f + 24.dp.toPx()).toDp() }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topOffsetDp, bottom = detailBottomPaddingDp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (internalFocusedCard != null) {
                        showCardInfo(internalFocusedCard)
                    }
                }
            }

            // Iterate over displayOrder so dragged positions instantly update visually
            displayOrder.forEachIndexed { index, cardInfo ->
                // key block prevents the layout from destroying gesture state when cards swap indices
                key(cardInfo.identifier) {
                    val isFocused = cardInfo.identifier == internalFocusedCardIdentifier
                    val isDragged = cardInfo.identifier == state.draggedCardIdentifier
                    val viewportTop = state.scrollState.value.toFloat()

                    val targetY: Float
                    val targetScale: Float
                    val targetElevation: Float
                    val targetZIndex: Float
                    val targetAlpha: Float

                    if (isAnyFocused) {
                        if (isFocused) {
                            targetY = viewportTop + paddingTopPx
                            targetScale = 1.025f
                            targetElevation = 24f
                            targetZIndex = 100f
                            targetAlpha = 1f
                        } else {
                            val stackIndex = if (index < focusedIndex) index else index - 1
                            val distanceToFront = maxStackIndex - stackIndex
                            val clampedDistanceToFront = min(distanceToFront, maxVisibleCardsInStack - 1)

                            val frontCardY = viewportTop + maxHeightPx - frontCardVisibleHeightPx

                            targetY = frontCardY - (clampedDistanceToFront * stackOffsetPx)
                            targetScale = max(0.6f, 0.95f - (clampedDistanceToFront * 0.025f))
                            targetElevation = 12f
                            targetZIndex = stackIndex.toFloat()

                            targetAlpha = if (!showStackWhileFocused) 0f else if (distanceToFront >= maxVisibleCardsInStack) 0f else 1f
                        }
                    } else {
                        // In list mode, the dragged card directly tracks the finger ignoring layout positioning
                        targetY = if (isDragged) state.dragCurrentY else paddingTopPx + index * listStepPx
                        targetScale = if (isDragged) 1.05f else 1f
                        targetElevation = if (isDragged) 24f else 12f
                        targetZIndex = if (isDragged) 100f else index.toFloat()
                        targetAlpha = 1f
                    }

                    // We skip animation for the dragged item so it tracks 1:1 with the finger without lag
                    val animatedY by animateFloatAsState(targetY, tween(if (isDragged) 0 else 400), label = "y")
                    val animatedScale by animateFloatAsState(targetScale, tween(400), label = "scale")
                    val animatedElevation by animateFloatAsState(targetElevation, tween(400), label = "elevation")
                    val animatedAlpha by animateFloatAsState(targetAlpha, tween(400), label = "alpha")

                    Box(
                        modifier = Modifier
                            .width(with(density) { cardWidthPx.toDp() })
                            .height(with(density) { cardHeightPx.toDp() })
                            .zIndex(if (isDragged) 100f else targetZIndex)
                            .offset {
                                IntOffset(
                                    x = cardXOffsetPx.roundToInt(),
                                    y = animatedY.roundToInt()
                                )
                            }
                            .graphicsLayer {
                                scaleX = animatedScale
                                scaleY = animatedScale
                                shadowElevation = animatedElevation.dp.toPx()
                                alpha = animatedAlpha
                                shape = RoundedCornerShape(24.dp)
                                clip = false
                            }
                            .pointerInput(isAnyFocused, allowCardReordering) {
                                if (!isAnyFocused && allowCardReordering) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { _ ->
                                            state.draggedCardIdentifier = cardInfo.identifier
                                            val currentIndex = state.displayOrderIdentifiers.indexOf(cardInfo.identifier)
                                            state.dragCurrentY = paddingTopPx + currentIndex * listStepPx
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            state.dragCurrentY += dragAmount.y

                                            // Calculate what index the card *should* be at based on physical height
                                            val newIndex = ((state.dragCurrentY - paddingTopPx) / listStepPx)
                                                .roundToInt()
                                                .coerceIn(0, state.displayOrderIdentifiers.lastIndex)

                                            val currentIndex = state.displayOrderIdentifiers.indexOf(cardInfo.identifier)

                                            if (currentIndex != -1 && newIndex != currentIndex) {
                                                // Swap the items visually in the local state
                                                val newOrder = state.displayOrderIdentifiers.toMutableList()
                                                val item = newOrder.removeAt(currentIndex)
                                                newOrder.add(newIndex, item)
                                                state.displayOrderIdentifiers = newOrder

                                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                            }
                                        },
                                        onDragEnd = {
                                            state.dragJustEnded = true
                                            val draggedCardIdentifier = state.draggedCardIdentifier
                                            if (draggedCardIdentifier != null) {
                                                val finalIndex = state.displayOrderIdentifiers.indexOf(draggedCardIdentifier)
                                                val finalCard = cardInfos.find { it.identifier == draggedCardIdentifier }

                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                state.draggedCardIdentifier = null

                                                if (finalCard != null) {
                                                    onCardReordered(finalCard, finalIndex)
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            state.dragJustEnded = true
                                            state.draggedCardIdentifier = null
                                        }
                                    )
                                }
                            }
                            .clickable {
                                // Block clicks if a drag is occurring, or ended in the last 300ms
                                if (state.dragJustEnded || state.draggedCardIdentifier != null) return@clickable

                                if (isAnyFocused) {
                                    internalFocusedCard?.let {
                                        if (isFocused) {
                                            // The user tapped the card that is currently focused
                                            onCardFocusedTapped(it)
                                        } else {
                                            // The user tapped a card in the unfocused stack
                                            onCardFocusedStackTapped(it)
                                        }
                                    }
                                } else {
                                    onCardFocused(cardInfo)
                                }
                            }
                    ) {
                        Image(
                            bitmap = cardInfo.cardArt,
                            contentDescription = "Card Image",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    shape = RoundedCornerShape(24.dp)
                                    clip = true
                                }
                        )

                        CardBadges(
                            badges = cardInfo.badges,
                            elevation = 8.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .zIndex(100f)
                        )
                    }
                }
            }
        }
    }
}
