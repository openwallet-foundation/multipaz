package org.multipaz.compose.carousels

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.multipaz.compose.document.DocumentInfo
import org.multipaz.compose.document.DocumentModel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Displays a horizontally swipeable carousel of documents.
 *
 * @param modifier to allow control over visual attributes
 * @param documentModel the [DocumentModel] to observe.
 * @param onDocumentClicked callback invoked when the currently focused card is tapped. Provides the
 *   unique document identifier associated with the click item.
 * @param emptyDocumentContent fallback content for when there are
 *   no [org.multipaz.document.Document]'s available.
 */
@Composable
fun DocumentCarousel(
    modifier: Modifier = Modifier,
    documentModel: DocumentModel,
    onDocumentClicked: (DocumentInfo) -> Unit = {},
    emptyDocumentContent: @Composable () -> Unit = {},
) {
    val documentInfos = documentModel.documentInfos.collectAsState().value
    val carouselItems = documentInfos.values.map { documentInfo ->
        CarouselModel(
            id = documentInfo.document.identifier,
            name = documentInfo.document.displayName.orEmpty(),
            image = documentInfo.cardArt
        )
    }

    CardCarousel(
        modifier = modifier,
        carouselItems = carouselItems,
        onCarouselItemClick = { cardItem ->
            documentInfos[cardItem.id]?.let { info ->
                onDocumentClicked(info)
            }

        },
        emptyItemContent = emptyDocumentContent
    )
}

@Composable
private fun CardCarousel(
    modifier: Modifier = Modifier,
    carouselItems: List<CarouselModel>,
    onCarouselItemClick: (CarouselModel) -> Unit = {},
    emptyItemContent: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val cardIndex = remember { Animatable(0f) }
    val maxIndex = (carouselItems.size - 1).coerceAtLeast(0).toFloat()

    val lastDragAmount = remember { mutableFloatStateOf(0f) }

    if (carouselItems.isEmpty()) {
        emptyItemContent()
        return
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .pointerInput(carouselItems.size) {
                var dragStartValue = -1f
                var totalDragPx = 0f

                detectHorizontalDragGestures(
                    onDragStart = { _ ->
                        scope.launch { cardIndex.stop() }
                        dragStartValue = -1f
                        totalDragPx = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        lastDragAmount.floatValue = dragAmount

                        if (dragStartValue < 0f) {
                            dragStartValue = cardIndex.value.roundToInt().toFloat()
                        }

                        totalDragPx += dragAmount

                        val dragSensitivity = size.width * 0.8f
                        val dragOffset = totalDragPx / dragSensitivity

                        val minBound = (dragStartValue - 1f).coerceAtLeast(0f)
                        val maxBound = (dragStartValue + 1f).coerceAtMost(maxIndex)
                        val newValue = (dragStartValue - dragOffset).coerceIn(minBound, maxBound)

                        scope.launch {
                            cardIndex.snapTo(newValue)
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            val velocityThreshold = 5f
                            val currentIndex = cardIndex.value
                            val nearestIndex = currentIndex.roundToInt()
                            val fraction = currentIndex - nearestIndex

                            val rawTarget = when {
                                lastDragAmount.floatValue < -velocityThreshold ->
                                    (nearestIndex + 1).coerceAtMost(maxIndex.toInt())
                                lastDragAmount.floatValue > velocityThreshold ->
                                    (nearestIndex - 1).coerceAtLeast(0)
                                fraction > 0.35f ->
                                    (nearestIndex + 1).coerceAtMost(maxIndex.toInt())
                                fraction < -0.35f ->
                                    (nearestIndex - 1).coerceAtLeast(0)
                                else -> nearestIndex.coerceIn(0, maxIndex.toInt())
                            }

                            val startIdx = if (dragStartValue >= 0f) dragStartValue.toInt() else nearestIndex
                            val target = rawTarget.coerceIn(
                                (startIdx - 1).coerceAtLeast(0),
                                (startIdx + 1).coerceAtMost(maxIndex.toInt())
                            )

                            cardIndex.animateTo(
                                target.toFloat(),
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val edgePaddingPx = with(density) { 8.dp.toPx() }

        val cardWidthPx = screenWidthPx * 0.75f
        val cardHeightPx = cardWidthPx / 1.6f
        val horizontalPeekPx = cardWidthPx * 0.12f
        val verticalOffsetPx = cardHeightPx * 0.02f
        val initialFocus = cardIndex.value < 0.05f
        val lastFocus = cardIndex.value > maxIndex - 0.5f

        val maxSlideDistance = (screenWidthPx - cardWidthPx) / 2f - edgePaddingPx
        val motionSlidePx = maxSlideDistance * 2f

        val focusAlignmentOffset = when {
            initialFocus -> horizontalPeekPx / 3f
            lastFocus -> -horizontalPeekPx / 3f
            else -> 0f
        }

        val currentWhole = cardIndex.value.toInt()
        val fractionalPart = cardIndex.value - currentWhole
        val motionFactor = 2f * minOf(fractionalPart, 1f - fractionalPart)

        carouselItems.indices.forEach { index ->
            val offset = index - cardIndex.value
            val absOffset = abs(offset)
            val visibleFactor = (1f - (absOffset - 1f).coerceAtLeast(0f)).coerceIn(0f, 1f)

            if (visibleFactor > 0f) {
                val isFocused = absOffset < 0.5f
                val scale = 1f - 0.08f * absOffset.coerceIn(0f, 1.5f)

                val baseTranslationX = horizontalPeekPx * offset.coerceIn(-1f, 1f) - focusAlignmentOffset

                val isCurrentCard = index == currentWhole
                val isNextCard = index == currentWhole + 1
                val motionExtra = if (isCurrentCard || isNextCard) {
                    (motionSlidePx - horizontalPeekPx) * motionFactor * offset.coerceIn(-1f, 1f)
                } else {
                    0f
                }

                val translationX = baseTranslationX + motionExtra
                val translationY = if (isFocused) 0f else verticalOffsetPx
                val alpha = if (isFocused) 1f else 1f - 0.55f * absOffset.coerceIn(0f, 1.5f)

                val overlayAlpha = when {
                    isCurrentCard -> 0.35f * motionFactor
                    isNextCard -> if (fractionalPart >= 0.5f) 0f else 0.35f * motionFactor
                    else -> 0f
                }

                val zIndexValue = when {
                    fractionalPart < 0.5f && isCurrentCard -> 2f
                    fractionalPart < 0.5f && isNextCard -> 1f
                    fractionalPart >= 0.5f && isNextCard -> 2f
                    fractionalPart >= 0.5f && isCurrentCard -> 1f
                    isFocused -> 1f
                    else -> -absOffset
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            this.scaleX = scale
                            this.scaleY = scale
                            this.translationX = translationX
                            this.translationY = translationY
                            this.alpha = alpha
                            this.shape = RoundedCornerShape(24.dp)
                            this.clip = true
                        }
                        .zIndex(zIndexValue)
                        .fillMaxWidth(0.8f)
                        .aspectRatio(if (isFocused) 1.6f else 1.9f)
                        .clickable(enabled = isFocused) {
                            onCarouselItemClick(carouselItems[index])
                        },
                    contentAlignment = Alignment.Center
                ) {
                    CarouselItem(item = carouselItems[index], overlayAlpha = overlayAlpha)
                }
            }
        }
    }
}


@Composable
private fun CarouselItem(item: CarouselModel, overlayAlpha: Float = 0f) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
    ) {
        item.image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
            )
        }

        if (overlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = overlayAlpha))
            )
        }
    }
}

private data class CarouselModel(
    val id: String,
    val name: String,
    val image: ImageBitmap?
)