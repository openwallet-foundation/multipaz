package org.multipaz.compose.prompt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.multipaz.compose.camera.Camera
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.camera.toPromptCameraFrame
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_cancel
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_default_subtitle
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_default_title
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_grant_permission
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_permission_required
import org.multipaz.facematch.FaceMatcherGraphic
import org.multipaz.facematch.FaceMatcherGraphics
import org.multipaz.facematch.FaceMatcherPromptState
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.facematch.RingSegment
import org.multipaz.facematch.SimulatedFaceMatcher
import org.multipaz.prompt.ConvertToHumanReadableFn
import org.multipaz.prompt.FaceMatcherPromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptDismissedException

/**
 * Composable dialog that prompts the user to perform face matching using the front camera.
 *
 * @param model the dialog model managing the face matcher request.
 * @param toHumanReadable function to convert reasons to human-readable strings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceMatcherPromptDialog(
    model: PromptDialogModel<FaceMatcherPromptDialogModel.FaceMatcherRequest, Boolean>,
    toHumanReadable: ConvertToHumanReadableFn
) {
    val dialogState = model.dialogState.collectAsState(PromptDialogModel.NoDialogState())
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val dialogStateValue = dialogState.value
    var humanReadableTitle by remember { mutableStateOf<String?>(null) }
    var humanReadableSubtitle by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dialogStateValue) {
        if (dialogStateValue is PromptDialogModel.DialogShownState) {
            val parameters = dialogStateValue.parameters
            try {
                val hr = toHumanReadable(parameters.reason, null)
                humanReadableTitle = hr.title
                humanReadableSubtitle = hr.subtitle
            } catch (e: Exception) {
                humanReadableTitle = null
                humanReadableSubtitle = null
            }
        } else {
            humanReadableTitle = null
            humanReadableSubtitle = null
        }
    }

    if (dialogStateValue is PromptDialogModel.DialogShownState) {
        val dialogParameters = dialogStateValue.parameters
        val matcher = dialogParameters.matcher
            ?: (model as? FaceMatcherPromptDialogModel)?.defaultMatcher
            ?: remember { SimulatedFaceMatcher() }

        val faceMatcherSession = remember(dialogParameters) {
            dialogParameters.faceMatcherSession ?: matcher.createSession(dialogParameters.referencePortrait)
        }

        val title = humanReadableTitle?.ifEmpty { null }
        val subtitle = humanReadableSubtitle?.ifEmpty { null }

        FaceMatcherBottomSheet(
            sheetState = sheetState,
            title = title,
            subtitle = subtitle,
            faceMatcherSession = faceMatcherSession,
            onMatched = {
                coroutineScope.launch {
                    dialogStateValue.resultChannel.send(true)
                }
            },
            onDismissed = {
                coroutineScope.launch {
                    faceMatcherSession.cancel()
                    dialogStateValue.resultChannel.close(PromptDismissedException())
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FaceMatcherBottomSheet(
    sheetState: SheetState,
    title: String?,
    subtitle: String?,
    faceMatcherSession: FaceMatcherSession,
    onMatched: () -> Unit,
    onDismissed: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val cameraPermissionState = rememberCameraPermissionState()
    val promptState by faceMatcherSession.state.collectAsState()

    var isProcessingFrame by remember { mutableStateOf(false) }
    var isDismissed by remember { mutableStateOf(false) }

    DisposableEffect(faceMatcherSession) {
        onDispose {
            faceMatcherSession.cancel()
        }
    }

    var resolvedTitle by remember { mutableStateOf(title ?: "") }
    var resolvedSubtitle by remember { mutableStateOf(subtitle ?: "") }

    LaunchedEffect(title, subtitle) {
        if (title == null) {
            resolvedTitle = getString(Res.string.face_matcher_prompt_default_title)
        } else {
            resolvedTitle = title
        }
        if (subtitle == null) {
            resolvedSubtitle = getString(Res.string.face_matcher_prompt_default_subtitle)
        } else {
            resolvedSubtitle = subtitle
        }
    }

    LaunchedEffect(cameraPermissionState.isGranted) {
        if (!cameraPermissionState.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(promptState.outcome) {
        if (promptState.outcome == FaceMatcherPromptState.Outcome.SUCCESS) {
            delay(1200)
            onMatched()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isDismissed) {
                isDismissed = true
                onDismissed()
            }
        },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val titleText = promptState.messageAbove ?: resolvedTitle
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (!cameraPermissionState.isGranted) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    var permissionRequiredText by remember { mutableStateOf("") }
                    var grantPermissionText by remember { mutableStateOf("") }
                    LaunchedEffect(Unit) {
                        permissionRequiredText = getString(Res.string.face_matcher_prompt_permission_required)
                        grantPermissionText = getString(Res.string.face_matcher_prompt_grant_permission)
                    }
                    Text(
                        text = permissionRequiredText,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                cameraPermissionState.launchPermissionRequest()
                            }
                        }
                    ) {
                        Text(grantPermissionText)
                    }
                }
            } else {
                val isSuccess = promptState.outcome == FaceMatcherPromptState.Outcome.SUCCESS

                val cornerRadius = 36.dp
                Box(
                    modifier = Modifier
                        .size(width = 232.dp, height = 296.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Camera feed clipped inside vertical rectangle with rounded corners
                    Box(
                        modifier = Modifier
                            .size(width = 220.dp, height = 284.dp)
                            .clip(RoundedCornerShape(cornerRadius)),
                        contentAlignment = Alignment.Center
                    ) {
                        Camera(
                            modifier = Modifier.fillMaxSize(),
                            cameraSelection = CameraSelection.DEFAULT_FRONT_CAMERA,
                            captureResolution = CameraCaptureResolution.MEDIUM,
                            showCameraPreview = true,
                            onFrameCaptured = { frame ->
                                if (isDismissed || isSuccess || isProcessingFrame) return@Camera
                                isProcessingFrame = true
                                try {
                                    val promptFrame = frame.toPromptCameraFrame()
                                    faceMatcherSession.feedFrame(promptFrame)
                                } finally {
                                    isProcessingFrame = false
                                }
                            }
                        )

                        val overlay = promptState.graphicsOverlay
                        if (overlay != null && overlay.items.isNotEmpty()) {
                            FaceMatcherOverlay(
                                overlay = overlay,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        if (isSuccess) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2E7D32)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 18-segment ring rendered on Canvas over solid black border
                    val segments = promptState.ringSegments
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val baseStroke = 5.dp.toPx()
                        val blackBorderWidth = 10.dp.toPx()
                        val numSegments = FaceMatcherPromptState.NUM_RING_SEGMENTS
                        val pad = 6.dp.toPx()
                        val cornerRadiusPx = cornerRadius.toPx()
                        val rect = Rect(pad, pad, size.width - pad, size.height - pad)
                        val fullPath = createRoundedRectPath(rect, cornerRadiusPx)

                        // 1. Solid black border around the vertical rounded rectangle
                        drawPath(
                            path = fullPath,
                            color = Color.Black,
                            style = Stroke(
                                width = blackBorderWidth,
                                cap = StrokeCap.Round
                            )
                        )

                        // 2. Draw 18 segments on top of the black border
                        val pathMeasure = PathMeasure()
                        pathMeasure.setPath(fullPath, forceClosed = true)
                        val totalLength = pathMeasure.length
                        val slotLength = totalLength / numSegments.toFloat()
                        val segLength = slotLength * 0.72f

                        for (i in 0 until numSegments) {
                            val segment = segments.getOrElse(i) { RingSegment() }
                            val centerDist = (i.toFloat() / numSegments.toFloat()) * totalLength
                            val rawStart = centerDist - segLength / 2f
                            val rawEnd = centerDist + segLength / 2f

                            val segPath = Path()
                            if (rawStart < 0f) {
                                pathMeasure.getSegment(totalLength + rawStart, totalLength, segPath, startWithMoveTo = true)
                                pathMeasure.getSegment(0f, rawEnd, segPath, startWithMoveTo = false)
                            } else if (rawEnd > totalLength) {
                                pathMeasure.getSegment(rawStart, totalLength, segPath, startWithMoveTo = true)
                                pathMeasure.getSegment(0f, rawEnd - totalLength, segPath, startWithMoveTo = false)
                            } else {
                                pathMeasure.getSegment(rawStart, rawEnd, segPath, startWithMoveTo = true)
                            }

                            val strokeWidth = baseStroke * segment.scale
                            drawPath(
                                path = segPath,
                                color = Color(segment.color.argb),
                                style = Stroke(
                                    width = strokeWidth,
                                    cap = StrokeCap.Round
                                )
                            )
                        }
                    }
                }

                val statusText = promptState.messageBelow ?: resolvedSubtitle
                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (promptState.outcome == FaceMatcherPromptState.Outcome.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }

            var cancelText by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                cancelText = getString(Res.string.face_matcher_prompt_cancel)
            }

            TextButton(
                onClick = {
                    if (!isDismissed) {
                        isDismissed = true
                        coroutineScope.launch { sheetState.hide() }
                        onDismissed()
                    }
                }
            ) {
                Text(cancelText)
            }
        }
    }
}

private fun createRoundedRectPath(
    rect: Rect,
    cornerRadius: Float
): Path {
    val path = Path()
    val r = cornerRadius.coerceAtMost(minOf(rect.width, rect.height) / 2f)
    val centerX = rect.center.x
    val left = rect.left
    val top = rect.top
    val right = rect.right
    val bottom = rect.bottom

    path.moveTo(centerX, top)
    path.lineTo(right - r, top)
    path.arcTo(
        rect = Rect(right - 2f * r, top, right, top + 2f * r),
        startAngleDegrees = -90f,
        sweepAngleDegrees = 90f,
        forceMoveTo = false
    )
    path.lineTo(right, bottom - r)
    path.arcTo(
        rect = Rect(right - 2f * r, bottom - 2f * r, right, bottom),
        startAngleDegrees = 0f,
        sweepAngleDegrees = 90f,
        forceMoveTo = false
    )
    path.lineTo(left + r, bottom)
    path.arcTo(
        rect = Rect(left, bottom - 2f * r, left + 2f * r, bottom),
        startAngleDegrees = 90f,
        sweepAngleDegrees = 90f,
        forceMoveTo = false
    )
    path.lineTo(left, top + r)
    path.arcTo(
        rect = Rect(left, top, left + 2f * r, top + 2f * r),
        startAngleDegrees = 180f,
        sweepAngleDegrees = 90f,
        forceMoveTo = false
    )
    path.close()
    return path
}

@Composable
private fun FaceMatcherOverlay(
    overlay: FaceMatcherGraphics,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val viewW = size.width
        val viewH = size.height
        val frameW = overlay.frameWidth.toFloat()
        val frameH = overlay.frameHeight.toFloat()
        if (frameW <= 0f || frameH <= 0f) return@Canvas

        val scale = maxOf(viewW / frameW, viewH / frameH)
        val offsetX = (viewW - frameW * scale) / 2f
        val offsetY = (viewH - frameH * scale) / 2f

        fun mapX(x: Float): Float {
            val mappedX = if (overlay.isMirrored) (frameW - x) else x
            return mappedX * scale + offsetX
        }

        fun mapY(y: Float): Float = y * scale + offsetY

        for (item in overlay.items) {
            when (item) {
                is FaceMatcherGraphic.Point -> {
                    val cx = mapX(item.x)
                    val cy = mapY(item.y)
                    drawCircle(
                        color = Color(item.color.argb),
                        radius = item.radius.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
                is FaceMatcherGraphic.Line -> {
                    val x1 = mapX(item.startX)
                    val y1 = mapY(item.startY)
                    val x2 = mapX(item.endX)
                    val y2 = mapY(item.endY)
                    drawLine(
                        color = Color(item.color.argb),
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = item.strokeWidth.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                is FaceMatcherGraphic.Rect -> {
                    val l = mapX(item.left)
                    val r = mapX(item.right)
                    val t = mapY(item.top)
                    val b = mapY(item.bottom)
                    val minX = minOf(l, r)
                    val maxX = maxOf(l, r)
                    drawRect(
                        color = Color(item.color.argb),
                        topLeft = Offset(minX, t),
                        size = Size(maxX - minX, b - t),
                        style = Stroke(width = item.strokeWidth.dp.toPx())
                    )
                }
                is FaceMatcherGraphic.Text -> {
                    val cx = mapX(item.x)
                    val cy = mapY(item.y)
                    val layoutResult = textMeasurer.measure(
                        text = item.text,
                        style = TextStyle(
                            fontSize = item.fontSize.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(item.color.argb)
                        )
                    )
                    val padH = 6.dp.toPx()
                    val padV = 2.dp.toPx()
                    val textW = layoutResult.size.width.toFloat()
                    val textH = layoutResult.size.height.toFloat()
                    val topLeftX = cx - textW / 2f
                    val topLeftY = cy - textH / 2f

                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.65f),
                        topLeft = Offset(topLeftX - padH, topLeftY - padV),
                        size = Size(textW + padH * 2f, textH + padV * 2f),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                    drawText(
                        textLayoutResult = layoutResult,
                        topLeft = Offset(topLeftX, topLeftY)
                    )
                }
            }
        }
    }
}
