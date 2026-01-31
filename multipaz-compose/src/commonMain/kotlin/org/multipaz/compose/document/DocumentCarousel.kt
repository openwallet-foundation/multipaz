package org.multipaz.compose.document

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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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

// MARK: - Internal Helper Models

private data class CarouselModel(
    val id: String,
    val name: String,
    val image: ImageBitmap
)

// MARK: - DocumentCarousel

/**
 * A horizontal carousel composable that displays a collection of documents.
 *
 * [DocumentCarousel] provides a highly interactive way to browse, select, and reorder documents.
 * It features a "cover flow" style layout where the center item is elevated, and side items are scaled down.
 *
 * ## Features
 * - **Snap Scrolling**: Automatically snaps to the nearest card after dragging.
 * - **Reordering**: Long-press and drag to reorder items (optional).
 * - **Focus Reporting**: Reports which document is currently centered.
 * - **Custom Overlays**: Supports custom views for selected item information and empty states.
 *
 * @param modifier a [Modifier].
 * @param documentModel a [DocumentModel] with the documents to show a carousel for.
 * @param initialDocumentId the document to initially select.
 * @param allowReordering if `true` allow the user to reorder documents by long pressing.
 * @param onDocumentClicked action to perform when a document is tapped.
 * @param onDocumentFocused called when a new document is focused.
 * @param onDocumentReordered called when the user has reordered a document, to update the underlying [DocumentStore].
 * @param selectedDocumentInfo a composable to draw text underneath the focused document.
 * @param emptyDocumentContent a composable to draw text when there are no documents. This will be rendered in
 *   the center of a dashed outline of a grey card .
 */
@Composable
fun DocumentCarousel(
    modifier: Modifier = Modifier,
    documentModel: DocumentModel,
    initialDocumentId: String? = null,
    allowReordering: Boolean = true,
    onDocumentClicked: (DocumentInfo) -> Unit = {},
    onDocumentFocused: (DocumentInfo) -> Unit = {},
    onDocumentReordered: (document: DocumentInfo, oldIndex: Int, newIndex: Int) -> Unit = { _, _, _ -> },
    selectedDocumentInfo: @Composable (docInfo: DocumentInfo?, index: Int, total: Int) -> Unit = { _, _, _ -> },
    emptyDocumentContent: @Composable () -> Unit = { }
) {
    val docInfos by documentModel.documentInfos.collectAsState()

    // Local state
    var items by remember { mutableStateOf(emptyList<CarouselModel>()) }
    val cardIndex = remember { Animatable(0f) }
    var hasInitialized by remember { mutableStateOf(false) }
    var lastReportedFocusId by remember { mutableStateOf<String?>(null) }
    var isReordering by remember { mutableStateOf(false) }

    // Sync Items from Model
    LaunchedEffect(docInfos) {
        val newItems = docInfos.map { info ->
            CarouselModel(
                id = info.document.identifier,
                name = info.document.displayName ?: "",
                image = info.cardArt
            )
        }
        items = newItems

        // Clamp index if items reduced
        if (items.isNotEmpty()) {
            val maxIndex = (items.size - 1).toFloat()
            if (cardIndex.value > maxIndex) {
                cardIndex.snapTo(maxIndex)
            }
        }

        // Initial Index Logic
        if (!hasInitialized && items.isNotEmpty()) {
            val targetIndex = if (initialDocumentId != null) {
                items.indexOfFirst { it.id == initialDocumentId }.takeIf { it >= 0 } ?: 0
            } else {
                0
            }
            cardIndex.snapTo(targetIndex.toFloat())
            hasInitialized = true
        }
    }

    // Report Focus Changes
    LaunchedEffect(items, isReordering) {
        snapshotFlow { cardIndex.value }
            .distinctUntilChanged()
            .collect { currentIndex ->
                if (items.isEmpty() || isReordering) return@collect

                val index = currentIndex.roundToInt().coerceIn(0, items.size - 1)
                val focusedItem = items[index]

                if (focusedItem.id != lastReportedFocusId) {
                    val realDoc = docInfos.firstOrNull { it.document.identifier == focusedItem.id }
                    if (realDoc != null) {
                        lastReportedFocusId = focusedItem.id
                        onDocumentFocused(realDoc)
                    }
                }
            }
    }

    if (items.isEmpty() && docInfos.isEmpty()) {
        EmptyStateView(modifier, emptyDocumentContent)
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
                onCarouselItemClick = { item ->
                    val info = docInfos.firstOrNull { it.document.identifier == item.id }
                    if (info != null) onDocumentClicked(info)
                },
                onReorder = { oldIndex, newIndex ->
                    if (newIndex in items.indices) {
                        val movedItem = items[newIndex]
                        // Parent has access to docInfos, so we do the lookup here
                        val realDoc = docInfos.firstOrNull { it.document.identifier == movedItem.id }
                        if (realDoc != null) {
                            onDocumentReordered(realDoc, oldIndex, newIndex)
                        }
                    }
                },
                onItemsChanged = { newItems -> items = newItems }
            )

            InfoOverlayView(
                items = items,
                cardIndex = cardIndex.value,
                isReordering = isReordering,
                documentInfos = docInfos,
                selectedDocumentInfo = selectedDocumentInfo
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
    items: List<CarouselModel>,
    cardIndex: Float,
    isReordering: Boolean,
    documentInfos: List<DocumentInfo>,
    selectedDocumentInfo: @Composable (DocumentInfo?, Int, Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(10f),
        contentAlignment = Alignment.Center
    ) {
        val totalCount = items.size

        items.forEachIndexed { index, item ->
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
                        selectedDocumentInfo(null, index, totalCount)
                    } else {
                        val realDoc = documentInfos.firstOrNull { it.document.identifier == item.id }
                        if (realDoc != null) {
                            selectedDocumentInfo(realDoc, index, totalCount)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Internal Implementation

@Composable
private fun CardCarousel(
    items: List<CarouselModel>,
    cardIndexAnimatable: Animatable<Float, AnimationVector1D>,
    isReordering: Boolean,
    onIsReorderingChange: (Boolean) -> Unit,
    allowReordering: Boolean,
    onCarouselItemClick: (CarouselModel) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onItemsChanged: (List<CarouselModel>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    // Scroll State
    var dragStartIndex by remember { mutableStateOf<Float?>(null) }
    var currentDragTotal by remember { mutableFloatStateOf(0f) }
    var lastHapticIndex by remember { mutableIntStateOf(0) }

    // Reorder State
    // We track WHICH item is being dragged by ID to survive list swaps
    var draggingItemId by remember { mutableStateOf<String?>(null) }
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
            items.forEachIndexed { index, item ->
                // key(item.id) is crucial for correct state retention during swaps
                key(item.id) {
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
                    val isBeingDragged = (item.id == draggingItemId)

                    // If dragged, use the cumulative drag offset + where it "should" be
                    val finalTranslationX = if (isBeingDragged) standardTranslationX + dragOffsetX else standardTranslationX
                    val finalScale = if (isBeingDragged) 1.05f else scaleBase
                    val finalZIndex = if (isBeingDragged) 100f else if (isCurrentCard) 2f else if (isNextCard) 1f else -absOffset
                    val finalShadow = if (isBeingDragged) 24.dp else if (isCurrentCard) 16.dp else 5.dp

                    Box(
                        modifier = Modifier
                            // 1. GESTURES FIRST: So they don't get lost when zIndex changes
                            .pointerInput(item.id) {
                                detectTapGestures {
                                    if (!isReordering && absOffset < 0.5f) {
                                        onCarouselItemClick(item)
                                    }
                                }
                            }
                            .pointerInput(item.id) {
                                if (allowReordering) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            // Only allow reordering the currently centered card
                                            if (!isReordering && isCurrentCard) {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onIsReorderingChange(true)
                                                draggingItemId = item.id

                                                val currentIndex = currentItems.indexOfFirst { it.id == item.id }
                                                originalReorderIndex = currentIndex
                                                dragOffsetX = 0f
                                            }
                                        },
                                        onDragEnd = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)

                                            onIsReorderingChange(false)
                                            draggingItemId = null
                                            dragOffsetX = 0f

                                            // Notify listener of final move
                                            val finalIdx = currentItems.indexOfFirst { it.id == item.id }
                                            val origIdx = originalReorderIndex
                                            if (origIdx != null && finalIdx != -1 && origIdx != finalIdx) {
                                                onReorder(origIdx, finalIdx)
                                            }
                                            originalReorderIndex = null
                                        },
                                        onDragCancel = {
                                            onIsReorderingChange(false)
                                            draggingItemId = null
                                            dragOffsetX = 0f
                                            originalReorderIndex = null
                                        },
                                        onDrag = { change, dragAmount ->
                                            if (draggingItemId != item.id) return@detectDragGesturesAfterLongPress
                                            change.consume()

                                            // Accumulate drag
                                            dragOffsetX += dragAmount.x

                                            // Logic to check for swaps
                                            val currentList = currentItems
                                            val currentIndex = currentList.indexOfFirst { it.id == item.id }
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
                                clip = true
                            }
                    ) {
                        CarouselItem(
                            item = item,
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
    item: CarouselModel,
    overlayAlpha: Float
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Image(
            bitmap = item.image,
            contentDescription = item.name,
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
}