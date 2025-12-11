package org.multipaz.compose.carousels

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            name = documentInfo.document.metadata.displayName.orEmpty(),
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
    val maxIndex = (carouselItems.size - 1).toFloat()

    if (carouselItems.isEmpty()) {
        emptyItemContent()
        return
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(carouselItems.size) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            val delta = dragAmount / 450f
                            val newValue = (cardIndex.value - delta)
                                .coerceIn(0f, maxIndex)
                            cardIndex.snapTo(newValue)
                        }
                    },
                    onDragEnd = {
                        val target = cardIndex.value.roundToInt().coerceIn(0, maxIndex.toInt())
                        scope.launch {
                            cardIndex.animateTo(
                                target.toFloat(),
                                animationSpec = tween(
                                    durationMillis = 300,
                                    easing = FastOutSlowInEasing
                                )
                            )
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val screenWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val cardWidthPx = screenWidthPx * 0.75f
        val cardHeightPx = cardWidthPx / 1.6f
        val horizontalPeekPx = cardWidthPx * 0.12f
        val verticalOffsetPx = cardHeightPx * 0.02f
        val initialFocus = cardIndex.value < 0.05f
        val lastFocus = cardIndex.value > maxIndex - 0.5f

        val focusAlignmentOffset = when {
            initialFocus -> horizontalPeekPx / 3f
            lastFocus -> -horizontalPeekPx / 3f
            else -> 0f
        }

        carouselItems.indices.forEach { index ->
            val offset = index - cardIndex.value
            val absOffset = abs(offset)
            val visibleFactor = (1f - (absOffset - 1f).coerceAtLeast(0f)).coerceIn(0f, 1f)

            if (visibleFactor <= 0f) return@forEach

            val isFocused = absOffset < 0.5f
            val scale = 1f - 0.08f * absOffset.coerceIn(0f, 1.5f)
            val translationX =
                horizontalPeekPx * offset.coerceIn(-1f, 1f) - focusAlignmentOffset
            val translationY = if (isFocused) 0f else verticalOffsetPx
            val alpha = if (isFocused) 1f else 1f - 0.55f * absOffset.coerceIn(0f, 1.5f)

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
                    .zIndex(if (isFocused) 1f else -absOffset)
                    .fillMaxWidth(0.8f)
                    .aspectRatio(if (isFocused) 1.6f else 1.9f)
                    .clickable(enabled = isFocused) {
                        onCarouselItemClick(carouselItems[index])
                    },
                contentAlignment = Alignment.Center
            ) {
                CarouselItem(item = carouselItems[index])
            }
        }
    }
}


@Composable
private fun CarouselItem(item: CarouselModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
    ) {
        item.image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
            )
        }
    }
}

private data class CarouselModel(
    val id: String,
    val name: String,
    val image: ImageBitmap?
)