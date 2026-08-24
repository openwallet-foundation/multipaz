package org.multipaz.compose.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.multipaz.cards.CardDimensions
import org.multipaz.cards.CardLayoutItem
import org.multipaz.cards.CardListLayout
import org.multipaz.cards.CardListLayoutCalculator
import org.multipaz.cards.CardListLayoutParameters
import org.multipaz.cards.CardTapAction
import org.multipaz.cards.CardVisualState
import org.multipaz.cards.VerticalCardListModel
import kotlin.math.roundToInt

/**
 * State object for [VerticalCardList].
 *
 * Use [rememberVerticalCardListState] to create an instance.
 *
 * @param scrollState the scroll state for the list.
 * @param model underlying platform-agnostic [VerticalCardListModel].
 */
@Stable
class VerticalCardListState(
    val scrollState: ScrollState,
    val model: VerticalCardListModel = VerticalCardListModel()
) {
    /**
     * The current display order of the cards, tracked by identifier.
     */
    var displayOrderIdentifiers by mutableStateOf(model.displayOrderIdentifiers)
        internal set

    /**
     * The identifier of the card currently being dragged, if any.
     */
    var draggedCardIdentifier by mutableStateOf(model.draggedCardIdentifier)
        internal set

    /**
     * The current Y position of the dragged card.
     */
    var dragCurrentY by mutableFloatStateOf(model.dragCurrentY.toFloat())
        internal set

    /**
     * Whether a drag operation just ended.
     */
    var dragJustEnded by mutableStateOf(model.dragJustEnded)
        internal set

    /**
     * The identifier of the last focused card, used to preserve animations across navigation.
     */
    var lastFocusedCardIdentifier by mutableStateOf(model.lastFocusedCardIdentifier)
        internal set

    /**
     * Trigger counter for requesting an in-place unfocus animation.
     */
    var unfocusTrigger by mutableIntStateOf(0)
        internal set

    /**
     * Unfocuses the currently focused card with an animation.
     *
     * Suspends until the 400ms transition animation completes.
     */
    suspend fun unfocus() {
        unfocusTrigger++
        lastFocusedCardIdentifier = null
        model.lastFocusedCardIdentifier = null
        delay(400)
    }

    /**
     * Whether to animate spatial transitions (like sliding cards) when entering this screen.
     */
    var animateListTransitions by mutableStateOf(model.animateListTransitions)

    /**
     * Whether the top content composable should be shown when no card is focused.
     */
    var showTopContent by mutableStateOf(model.showTopContent)

    /**
     * Whether to show a dashed placeholder card when the card list is empty.
     */
    var showPlaceholderWhenEmpty by mutableStateOf(model.showPlaceholderWhenEmpty)

    /**
     * The measured height of the top content in pixels.
     */
    var topContentHeightPx by mutableFloatStateOf(model.topContentHeight.toFloat())
        internal set
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
 * of the screen when a card is focused. If false, unfocused cards fade away entirely. Defaults to true.
 * @param cardMaxHeight An optional max height constraint for the cards. Useful for foldables and wide screens.
 * @param paddingTop The top padding for the card list. Defaults to 16.dp.
 * @param paddingBottom The bottom padding for the card list. Defaults to 16.dp.
 * @param state The state object to be used to control or observe the list's state.
 * @param showTopContent Whether the [topContent] composable should be shown when no card is focused.
 * @param showPlaceholderWhenEmpty Whether to show a dashed placeholder card when the card list is empty.
 * @param topContent A composable slot displayed at the top of the list when no card is focused.
 * @param showCardInfo A composable slot that renders the detailed content below the focused card.
 * @param emptyContent A composable slot displayed inside a dashed placeholder card when empty.
 * @param onCardReordered Callback invoked when a drag-and-drop reordering operation completes.
 * @param onCardFocused Callback invoked when a card is tapped to be focused.
 * @param onCardFocusedTapped Callback invoked when the currently focused card is tapped.
 * @param onCardFocusedStackTapped Callback invoked when the unfocused card stack is tapped while another card is in focus.
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
    paddingTop: Dp = 16.dp,
    paddingBottom: Dp = 16.dp,
    animateListTransitions: Boolean = false,
    state: VerticalCardListState = rememberVerticalCardListState(),
    showTopContent: Boolean = state.showTopContent,
    showPlaceholderWhenEmpty: Boolean = state.showPlaceholderWhenEmpty,
    topContent: @Composable () -> Unit = {},
    showCardInfo: @Composable (CardInfo) -> Unit = {},
    emptyContent: @Composable () -> Unit = {},
    onCardReordered: (cardInfo: CardInfo, newPosition: Int) -> Unit = { _, _ -> },
    onCardFocused: (cardInfo: CardInfo) -> Unit = {},
    onCardFocusedTapped: (cardInfo: CardInfo) -> Unit = {},
    onCardFocusedStackTapped: (cardInfo: CardInfo) -> Unit = {}
) {
    val currentCardIdentifiers = cardInfos.map { it.identifier }
    if (state.draggedCardIdentifier == null && state.displayOrderIdentifiers != currentCardIdentifiers) {
        state.model.syncCards(currentCardIdentifiers)
        state.displayOrderIdentifiers = state.model.displayOrderIdentifiers
    }

    val displayOrder = remember(state.displayOrderIdentifiers, cardInfos) {
        state.displayOrderIdentifiers.mapNotNull { id -> cardInfos.find { it.identifier == id } }
    }

    var internalFocusedCardIdentifier by remember(focusedCard?.identifier) {
        mutableStateOf(if (animateListTransitions) state.lastFocusedCardIdentifier else focusedCard?.identifier)
    }

    LaunchedEffect(focusedCard?.identifier) {
        if (animateListTransitions && state.lastFocusedCardIdentifier != focusedCard?.identifier) {
            internalFocusedCardIdentifier = state.lastFocusedCardIdentifier
            yield()
            state.lastFocusedCardIdentifier = focusedCard?.identifier
            state.model.lastFocusedCardIdentifier = focusedCard?.identifier
            internalFocusedCardIdentifier = focusedCard?.identifier
        } else {
            internalFocusedCardIdentifier = focusedCard?.identifier
            state.lastFocusedCardIdentifier = focusedCard?.identifier
            state.model.lastFocusedCardIdentifier = focusedCard?.identifier
        }
    }

    var lastHandledUnfocusTrigger by remember { mutableIntStateOf(state.unfocusTrigger) }

    LaunchedEffect(state.unfocusTrigger) {
        if (state.unfocusTrigger != lastHandledUnfocusTrigger) {
            lastHandledUnfocusTrigger = state.unfocusTrigger
            internalFocusedCardIdentifier = null
        }
    }

    val internalFocusedCard = cardInfos.find { it.identifier == internalFocusedCardIdentifier }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(state.dragJustEnded) {
        if (state.dragJustEnded) {
            delay(300)
            state.dragJustEnded = false
            state.model.clearDragJustEnded()
        }
    }

    val isAnyFocused = internalFocusedCardIdentifier != null

    SubcomposeLayout(modifier = modifier.fillMaxSize()) { constraints ->
        val density = this
        val maxWidthPx = constraints.maxWidth.toDouble()
        val maxHeightPx = constraints.maxHeight.toDouble()

        val paddingHorizontalPx = with(density) { 16.dp.toPx().toDouble() }
        val paddingTopPx = with(density) { paddingTop.toPx().toDouble() }
        val paddingBottomPx = with(density) { paddingBottom.toPx().toDouble() }
        val spacingPx = with(density) { 16.dp.toPx().toDouble() }
        val cardMaxHeightPx = if (cardMaxHeight.isSpecified) with(density) { cardMaxHeight.toPx().toDouble() } else null

        // Measure topContent synchronously before laying out cards
        val topContentPlaceables = subcompose("topContentMeasurement") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                topContent()
            }
        }.map { it.measure(constraints.copy(minHeight = 0)) }

        val measuredTopContentHeight = topContentPlaceables.maxOfOrNull { it.height }?.toFloat() ?: 0f
        if (measuredTopContentHeight > 0f && state.topContentHeightPx != measuredTopContentHeight) {
            state.topContentHeightPx = measuredTopContentHeight
            state.model.topContentHeight = measuredTopContentHeight.toDouble()
        }

        val mainPlaceables = subcompose("mainContent") {
            if (cardInfos.isEmpty()) {
                val defaultDims = CardListLayoutCalculator.computeCardDimensions(
                    viewportWidth = maxWidthPx,
                    cardMaxHeight = cardMaxHeightPx,
                    paddingHorizontal = paddingHorizontalPx,
                    aspectRatio = CardLayoutItem.DEFAULT_ASPECT_RATIO
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = paddingTop, start = 16.dp, end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AnimatedVisibility(
                        visible = showTopContent,
                        enter = fadeIn(tween(400)),
                        exit = fadeOut(tween(400))
                    ) {
                        topContent()
                    }

                    if (showPlaceholderWhenEmpty) {
                        Box(
                            modifier = Modifier
                                .width(with(density) { defaultDims.width.toFloat().toDp() })
                                .height(with(density) { defaultDims.height.toFloat().toDp() })
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
                }
            } else {
                val scrollOffsetPx = state.scrollState.value.toDouble()
                val isTopContentEffectivelyVisible = showTopContent && !isAnyFocused
                val topContentProgress by animateFloatAsState(
                    targetValue = if (isTopContentEffectivelyVisible) 1f else 0f,
                    animationSpec = tween(400),
                    label = "topContentProgress"
                )

                val cardLayoutItems = displayOrder.map {
                    val aspectRatio = if (it.cardArt.height > 0) {
                        it.cardArt.width.toDouble() / it.cardArt.height.toDouble()
                    } else {
                        CardLayoutItem.DEFAULT_ASPECT_RATIO
                    }
                    CardLayoutItem(it.identifier, aspectRatio)
                }

                val layoutParams = CardListLayoutParameters(
                    viewportWidth = maxWidthPx,
                    viewportHeight = maxHeightPx,
                    cardMaxHeight = cardMaxHeightPx,
                    paddingTop = paddingTopPx,
                    paddingBottom = paddingBottomPx,
                    paddingHorizontal = paddingHorizontalPx,
                    unfocusedVisiblePercent = unfocusedVisiblePercent,
                    showStackWhileFocused = showStackWhileFocused,
                    topContentHeight = state.topContentHeightPx.toDouble(),
                    isTopContentVisible = showTopContent,
                    topContentProgress = if (isTopContentEffectivelyVisible) 1.0 else 0.0,
                    scrollOffset = scrollOffsetPx,
                    stackOffset = with(density) { 14.dp.toPx().toDouble() },
                    spacing = spacingPx
                )

                val layout = CardListLayoutCalculator.computeLayout(
                    params = layoutParams,
                    cards = cardLayoutItems,
                    isAnyFocused = isAnyFocused
                )

                val totalHeightDp = with(density) { layout.totalHeight.toFloat().toDp() }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(state.scrollState, enabled = !isAnyFocused)
                ) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(totalHeightDp)
                    )

                    // Top Content
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (paddingTopPx - (1f - topContentProgress) * (state.topContentHeightPx + spacingPx)).roundToInt()
                                )
                            }
                            .graphicsLayer {
                                alpha = topContentProgress
                            }
                    ) {
                        if (topContentProgress > 0f || state.topContentHeightPx == 0f) {
                            topContent()
                        }
                    }

                    // Focused Detail View
                    AnimatedVisibility(
                        visible = isAnyFocused,
                        enter = fadeIn(tween(400)),
                        exit = fadeOut(tween(400)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { maxHeightPx.toFloat().toDp() })
                            .offset { IntOffset(0, state.scrollState.value) }
                            .zIndex(50f)
                    ) {
                        val focusedCardDim = internalFocusedCardIdentifier?.let { layout.getDimensions(it) } ?: layout.defaultCardDimensions
                        val topOffsetDp = with(density) {
                            CardListLayoutCalculator.computeDetailTopOffset(
                                paddingTop = paddingTopPx,
                                focusedCardHeight = focusedCardDim.height,
                                spacing = with(density) { 24.dp.toPx().toDouble() }
                            ).toFloat().toDp()
                        }
                        val detailBottomPaddingDp = with(density) { layout.detailBottomPadding.toFloat().toDp() }

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

                    // Cards
                    displayOrder.forEachIndexed { index, cardInfo ->
                        key(cardInfo.identifier) {
                            val cardDim = layout.getDimensions(cardInfo.identifier)
                            val cardVisualState = CardListLayoutCalculator.computeCardVisualState(
                                cardIdentifier = cardInfo.identifier,
                                index = index,
                                cards = cardLayoutItems,
                                layout = layout,
                                params = layoutParams,
                                focusedCardIdentifier = internalFocusedCardIdentifier,
                                draggedCardIdentifier = state.draggedCardIdentifier,
                                dragCurrentY = state.dragCurrentY.toDouble()
                            )

                            VerticalCardListItem(
                                cardInfo = cardInfo,
                                index = index,
                                density = density,
                                cardDim = cardDim,
                                cardVisualState = cardVisualState,
                                layout = layout,
                                cardLayoutItems = cardLayoutItems,
                                layoutParams = layoutParams,
                                isAnyFocused = isAnyFocused,
                                focusedCardIdentifier = internalFocusedCardIdentifier,
                                focusedCard = internalFocusedCard,
                                allowCardReordering = allowCardReordering,
                                state = state,
                                haptic = haptic,
                                onCardFocused = onCardFocused,
                                onCardFocusedTapped = onCardFocusedTapped,
                                onCardFocusedStackTapped = onCardFocusedStackTapped,
                                onCardReordered = onCardReordered
                            )
                        }
                    }
                }
            }
        }.map { it.measure(constraints) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            mainPlaceables.forEach { it.place(0, 0) }
        }
    }
}

@Composable
private fun VerticalCardListItem(
    cardInfo: CardInfo,
    index: Int,
    density: Density,
    cardDim: CardDimensions,
    cardVisualState: CardVisualState,
    layout: CardListLayout,
    cardLayoutItems: List<CardLayoutItem>,
    layoutParams: CardListLayoutParameters,
    isAnyFocused: Boolean,
    focusedCardIdentifier: String?,
    focusedCard: CardInfo?,
    allowCardReordering: Boolean,
    state: VerticalCardListState,
    haptic: HapticFeedback,
    onCardFocused: (CardInfo) -> Unit,
    onCardFocusedTapped: (CardInfo) -> Unit,
    onCardFocusedStackTapped: (CardInfo) -> Unit,
    onCardReordered: (CardInfo, Int) -> Unit
) {
    val isDragged = cardInfo.identifier == state.draggedCardIdentifier
    val targetY = cardVisualState.y.toFloat()

    val animatedY by animateFloatAsState(targetY, tween(if (isDragged) 0 else 400), label = "y")
    val animatedScale by animateFloatAsState(cardVisualState.scale.toFloat(), tween(400), label = "scale")
    val animatedElevation by animateFloatAsState(cardVisualState.elevation.toFloat(), tween(400), label = "elevation")
    val animatedAlpha by animateFloatAsState(cardVisualState.alpha.toFloat(), tween(400), label = "alpha")

    Box(
        modifier = Modifier
            .width(with(density) { cardDim.width.toFloat().toDp() })
            .height(with(density) { cardDim.height.toFloat().toDp() })
            .zIndex(if (isDragged) 100f else cardVisualState.zIndex.toFloat())
            .offset {
                IntOffset(
                    x = cardDim.xOffset.roundToInt(),
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
                            state.model.displayOrderIdentifiers = state.displayOrderIdentifiers
                            if (state.model.startDrag(cardInfo.identifier, layout, cardLayoutItems, layoutParams)) {
                                state.draggedCardIdentifier = state.model.draggedCardIdentifier
                                state.dragCurrentY = state.model.dragCurrentY.toFloat()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val updateResult = state.model.updateDrag(
                                deltaY = dragAmount.y.toDouble(),
                                layout = layout,
                                cards = cardLayoutItems,
                                params = layoutParams
                            )
                            state.dragCurrentY = state.model.dragCurrentY.toFloat()
                            if (updateResult.reordered) {
                                state.displayOrderIdentifiers = state.model.displayOrderIdentifiers
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        },
                        onDragEnd = {
                            val endResult = state.model.endDrag()
                            state.dragJustEnded = true
                            state.draggedCardIdentifier = null
                            if (endResult != null) {
                                val finalCard = cardLayoutItems.find { it.identifier == endResult.cardIdentifier }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (finalCard != null) {
                                    onCardReordered(cardInfo, endResult.newIndex)
                                }
                            }
                        },
                        onDragCancel = {
                            state.model.cancelDrag()
                            state.dragJustEnded = true
                            state.draggedCardIdentifier = null
                        }
                    )
                }
            }
            .clickable {
                val action = state.model.handleTap(
                    cardIdentifier = cardInfo.identifier,
                    isAnyFocused = isAnyFocused,
                    focusedCardIdentifier = focusedCardIdentifier
                )
                when (action) {
                    is CardTapAction.Ignore -> {}
                    is CardTapAction.Focus -> onCardFocused(cardInfo)
                    is CardTapAction.FocusedCardTapped -> {
                        focusedCard?.let { onCardFocusedTapped(it) }
                    }
                    is CardTapAction.FocusedStackTapped -> {
                        focusedCard?.let { onCardFocusedStackTapped(it) }
                    }
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
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
        )
    }
}
