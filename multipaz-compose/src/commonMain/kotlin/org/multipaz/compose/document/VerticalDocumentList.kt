package org.multipaz.compose.document

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A vertically scrolling list of documents that mimics a physical wallet experience.
 *
 * In its default state, documents are displayed as a vertical list of cards. The amount of
 * overlap between cards is configurable. Users can long-press a card to drag and drop it into
 * a new position.
 *
 * When a user taps a card, it enters a "focused" state. The focused card elevates and animates
 * to the top of the viewport. A dynamic content section ([showDocumentInfo]) fades in immediately
 * below it. By default, the remaining unfocused cards animate into a 3D overlapping stack at the
 * bottom of the screen.
 *
 * @param modifier The modifier to be applied to the list container.
 * @param documentModel The [DocumentModel] providing the reactive flow of documents to display.
 * @param focusedDocument The currently focused document. When null, the component operates in
 * standard list mode. When set to a [DocumentInfo], that document is brought to the top and
 * detailed information is displayed.
 * @param unfocusedVisiblePercent Determines how much of each card is visible when not focused. A
 * value of `100` displays cards with standard spacing (no overlap). Lower values cause cards to
 * overlap, allowing more cards to fit on screen. Must be between 0 and 100.
 * @param allowDocumentReordering If true, users can long-press and drag cards to reorder them
 * when in standard list mode. Defaults to true.
 * @param showStackWhileFocused If true, unfocused cards will collapse into a 3D stack at the bottom
 * of the screen when a document is focused. If false, unfocused cards fade away entirely to maximize
 * screen real estate for the detail view. Defaults to true.
 * @param showDocumentInfo A composable slot that renders the detailed content below the focused card.
 * It is horizontally centered by default.
 * @param emptyDocumentContent A composable slot displayed inside a dashed placeholder card when the
 * [documentModel] is empty.
 * @param onDocumentReordered Callback invoked when a drag-and-drop reordering operation completes.
 * Provides the [DocumentInfo] of the moved card and its new index position in the list.
 * @param onDocumentFocused Callback invoked when a document is tapped to be focused.
 * @param onDocumentFocusedTapped Callback invoked when the currently focused document is tapped.
 * @param onDocumentFocusedStackTapped Callback invoked when the unfocused document stack is tapped while another document is in focus.
 *
 * @throws IllegalArgumentException if [unfocusedVisiblePercent] is not between 0 and 100.
 */
@Composable
fun VerticalDocumentList(
    modifier: Modifier = Modifier,
    documentModel: DocumentModel,
    focusedDocument: DocumentInfo?,
    unfocusedVisiblePercent: Int = 25,
    allowDocumentReordering: Boolean = true,
    showStackWhileFocused: Boolean = true,
    showDocumentInfo: @Composable (DocumentInfo) -> Unit = {},
    emptyDocumentContent: @Composable () -> Unit = { },
    onDocumentReordered: (documentInfo: DocumentInfo, newPosition: Int) -> Unit = { _, _ -> },
    onDocumentFocused: (documentInfo: DocumentInfo) -> Unit = {},
    onDocumentFocusedTapped: (documentInfo: DocumentInfo) -> Unit = {},
    onDocumentFocusedStackTapped: (documentInfo: DocumentInfo) -> Unit = {}
) {
    if (unfocusedVisiblePercent !in 0..100) {
        throw IllegalArgumentException("unfocusedVisiblePercent must be between 0 and 100")
    }

    val docInfos by documentModel.documentInfos.collectAsState()
    val scrollState = rememberScrollState()
    val haptic = LocalHapticFeedback.current

    // Local state to handle visual reordering without waiting for DB updates
    var displayOrder by remember(docInfos) { mutableStateOf(docInfos) }

    // Drag tracking state
    var draggedDocId by remember { mutableStateOf<String?>(null) }
    var dragCurrentY by remember { mutableFloatStateOf(0f) }
    var dragJustEnded by remember { mutableStateOf(false) }

    // Automatically reset the block on clicks after a short delay
    LaunchedEffect(dragJustEnded) {
        if (dragJustEnded) {
            delay(300)
            dragJustEnded = false
        }
    }

    val isAnyFocused = focusedDocument != null
    val focusedId = focusedDocument?.document?.identifier
    val focusedIndex = displayOrder.indexOfFirst { it.document.identifier == focusedId }.coerceAtLeast(0)

    if (docInfos.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth()
                    .aspectRatio(1.586f)
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
                emptyDocumentContent()
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

        val cardWidthPx = maxWidthPx - 2 * paddingHorizontalPx
        val cardHeightPx = cardWidthPx / 1.586f

        // --- List Math ---
        val listStepPx = if (unfocusedVisiblePercent == 100) {
            cardHeightPx + spacingPx
        } else {
            cardHeightPx * (unfocusedVisiblePercent / 100f)
        }

        val totalHeightPx = paddingTopPx + (max(0, displayOrder.size - 1) * listStepPx) + cardHeightPx + paddingTopPx
        val totalHeightDp = with(density) { totalHeightPx.toDp() }

        // --- Stack Math ---
        val maxStackIndex = max(0, displayOrder.size - 2)
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
                .verticalScroll(scrollState, enabled = !isAnyFocused)
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
                    .graphicsLayer { translationY = scrollState.value.toFloat() }
                    .zIndex(50f)
            ) {
                val topOffsetDp = with(density) { (paddingTopPx + cardHeightPx * 1.05f + 24.dp.toPx()).toDp() }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topOffsetDp, bottom = detailBottomPaddingDp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (focusedDocument != null) {
                        showDocumentInfo(focusedDocument)
                    }
                }
            }

            // Iterate over displayOrder so dragged positions instantly update visually
            displayOrder.forEachIndexed { index, docInfo ->
                val identifier = docInfo.document.identifier

                // key block prevents the layout from destroying gesture state when cards swap indices
                key(identifier) {
                    val isFocused = identifier == focusedId
                    val isDragged = identifier == draggedDocId
                    val viewportTop = scrollState.value.toFloat()

                    val targetY: Float
                    val targetScale: Float
                    val targetElevation: Float
                    val targetZIndex: Float
                    val targetAlpha: Float

                    if (isAnyFocused) {
                        if (isFocused) {
                            targetY = viewportTop + paddingTopPx
                            targetScale = 1.05f
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

                            targetAlpha = if (!showStackWhileFocused || distanceToFront >= maxVisibleCardsInStack) 0f else 1f
                        }
                    } else {
                        // In list mode, the dragged card directly tracks the finger ignoring layout positioning
                        targetY = if (isDragged) dragCurrentY else paddingTopPx + index * listStepPx
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
                            .graphicsLayer {
                                translationX = paddingHorizontalPx
                                translationY = animatedY
                                scaleX = animatedScale
                                scaleY = animatedScale
                                shadowElevation = animatedElevation.dp.toPx()
                                alpha = animatedAlpha
                                shape = RoundedCornerShape(24.dp)
                                clip = true
                            }
                            .pointerInput(isAnyFocused, allowDocumentReordering) {
                                if (!isAnyFocused && allowDocumentReordering) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { _ ->
                                            draggedDocId = identifier
                                            val currentIndex = displayOrder.indexOfFirst { it.document.identifier == identifier }
                                            dragCurrentY = paddingTopPx + currentIndex * listStepPx
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragCurrentY += dragAmount.y

                                            // Calculate what index the card *should* be at based on physical height
                                            val newIndex = ((dragCurrentY - paddingTopPx) / listStepPx)
                                                .roundToInt()
                                                .coerceIn(0, displayOrder.lastIndex)

                                            val currentIndex = displayOrder.indexOfFirst { it.document.identifier == identifier }

                                            if (currentIndex != -1 && newIndex != currentIndex) {
                                                // Swap the items visually in the local state
                                                val newOrder = displayOrder.toMutableList()
                                                val item = newOrder.removeAt(currentIndex)
                                                newOrder.add(newIndex, item)
                                                displayOrder = newOrder

                                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                            }
                                        },
                                        onDragEnd = {
                                            dragJustEnded = true
                                            if (draggedDocId != null) {
                                                val finalIndex = displayOrder.indexOfFirst { it.document.identifier == draggedDocId }
                                                val finalDoc = displayOrder.getOrNull(finalIndex)

                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                draggedDocId = null

                                                if (finalDoc != null) {
                                                    onDocumentReordered(finalDoc, finalIndex)
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            dragJustEnded = true
                                            draggedDocId = null
                                        }
                                    )
                                }
                            }
                            .clickable {
                                // Block clicks if a drag is occurring, or ended in the last 300ms
                                if (dragJustEnded || draggedDocId != null) return@clickable

                                if (isAnyFocused) {
                                    focusedDocument?.let {
                                        if (isFocused) {
                                            // The user tapped the card that is currently focused
                                            onDocumentFocusedTapped(it)
                                        } else {
                                            // The user tapped a card in the unfocused stack
                                            onDocumentFocusedStackTapped(it)
                                        }
                                    }
                                } else {
                                    onDocumentFocused(docInfo)
                                }
                            }
                    ) {
                        Image(
                            bitmap = docInfo.cardArt,
                            contentDescription = docInfo.document.displayName ?: "Document Card",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}