package org.multipaz.compose.prompt

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.FullScreenDialogEffect
import org.multipaz.compose.ScreenBrightnessEffect
import org.multipaz.compose.camera.Camera
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.camera.toPromptCameraFrame
import org.multipaz.compose.fullScreenDialogProperties
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.compose.toComposeColor
import org.multipaz.compose.toImageBitmap
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcherLivenessPromptState
import org.multipaz.facematch.FaceMatcherPromptState
import org.multipaz.facematch.FacePose
import org.multipaz.facematch.OverlayFrame
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.face_matcher_liveness_prompt_default_subtitle
import org.multipaz.multipaz_compose.generated.resources.face_matcher_liveness_prompt_default_title
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_cancel
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_confirm
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_default_subtitle
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_default_title
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_grant_permission
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_permission_required
import org.multipaz.prompt.ConvertToHumanReadableFn
import org.multipaz.prompt.FaceMatcherLivenessPromptDialogModel
import org.multipaz.prompt.FaceMatcherPromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.Reason

/**
 * Full-screen composable dialog that prompts the user to perform face matching using the front camera.
 *
 * @param model the dialog model managing the face matcher request.
 * @param toHumanReadable function to convert reason to localized human-readable text.
 */
@Composable
fun FaceMatcherPromptDialog(
    model: PromptDialogModel<FaceMatcherPromptDialogModel.FaceMatcherRequest, Boolean>,
    toHumanReadable: ConvertToHumanReadableFn
) {
    val dialogState = model.dialogState.collectAsState(PromptDialogModel.NoDialogState())
    val coroutineScope = rememberCoroutineScope()
    val dialogStateValue = dialogState.value

    var humanReadableReason by remember(dialogStateValue) { mutableStateOf<Reason.HumanReadable?>(null) }
    LaunchedEffect(dialogStateValue) {
        if (dialogStateValue is PromptDialogModel.DialogShownState) {
            humanReadableReason = toHumanReadable(dialogStateValue.parameters.reason, null)
        } else {
            humanReadableReason = null
        }
    }

    if (dialogStateValue is PromptDialogModel.DialogShownState) {
        val dialogParameters = dialogStateValue.parameters
        val session = dialogParameters.faceMatcherSession
        val promptState by session.state.collectAsState()

        if (dialogParameters.usesCustomUserInterface) {
            DisposableEffect(session) {
                onDispose {
                    session.cancel()
                }
            }
            LaunchedEffect(session, promptState.status) {
                when (promptState.status) {
                    FaceMatcherPromptState.Status.SUCCESS -> {
                        dialogStateValue.resultChannel.send(true)
                    }
                    FaceMatcherPromptState.Status.FAILED -> {
                        session.cancel()
                        dialogStateValue.resultChannel.close(PromptDismissedException())
                    }
                    FaceMatcherPromptState.Status.IN_PROGRESS -> {}
                }
            }
        } else {
            FaceMatcherFullScreenDialog(
                sessionKey = session,
                title = humanReadableReason?.title ?: stringResource(Res.string.face_matcher_prompt_default_title),
                subtitle = humanReadableReason?.subtitle ?: stringResource(Res.string.face_matcher_prompt_default_subtitle),
                message = promptState.message,
                isSuccess = promptState.status == FaceMatcherPromptState.Status.SUCCESS,
                isFailed = promptState.status == FaceMatcherPromptState.Status.FAILED,
                requireConfirmation = dialogParameters.requireConfirmation || (humanReadableReason?.requireConfirmation == true),
                overlay = promptState.overlay,
                backgroundColor = promptState.backgroundColor,
                onFeedFrame = { frame -> session.feedFrame(frame) },
                onTouchEvent = { x, y -> session.onTouchEvent(x, y) },
                onCancelSession = { session.cancel() },
                onSuccess = {
                    coroutineScope.launch {
                        dialogStateValue.resultChannel.send(true)
                    }
                },
                onDismissed = {
                    coroutineScope.launch {
                        session.cancel()
                        dialogStateValue.resultChannel.close(PromptDismissedException())
                    }
                }
            )
        }
    }
}

/**
 * Full-screen composable dialog that prompts the user to perform face liveness verification and portrait
 * capture using the front camera.
 *
 * @param model the dialog model managing the face liveness request.
 * @param toHumanReadable function to convert reason to localized human-readable text.
 */
@Composable
fun FaceMatcherLivenessPromptDialog(
    model: PromptDialogModel<FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest, Map<FacePose, ByteString>>,
    toHumanReadable: ConvertToHumanReadableFn
) {
    val dialogState = model.dialogState.collectAsState(PromptDialogModel.NoDialogState())
    val coroutineScope = rememberCoroutineScope()
    val dialogStateValue = dialogState.value

    var humanReadableReason by remember(dialogStateValue) { mutableStateOf<Reason.HumanReadable?>(null) }
    LaunchedEffect(dialogStateValue) {
        if (dialogStateValue is PromptDialogModel.DialogShownState) {
            humanReadableReason = toHumanReadable(dialogStateValue.parameters.reason, null)
        } else {
            humanReadableReason = null
        }
    }

    if (dialogStateValue is PromptDialogModel.DialogShownState) {
        val dialogParameters = dialogStateValue.parameters
        val session = dialogParameters.faceMatcherLivenessSession
        val promptState by session.state.collectAsState()

        if (dialogParameters.usesCustomUserInterface) {
            DisposableEffect(session) {
                onDispose {
                    session.cancel()
                }
            }
            LaunchedEffect(session, promptState.status) {
                when (promptState.status) {
                    FaceMatcherLivenessPromptState.Status.SUCCESS -> {
                        dialogStateValue.resultChannel.send(promptState.capturedImages)
                    }
                    FaceMatcherLivenessPromptState.Status.FAILED -> {
                        session.cancel()
                        dialogStateValue.resultChannel.close(PromptDismissedException())
                    }
                    FaceMatcherLivenessPromptState.Status.IN_PROGRESS -> {}
                }
            }
        } else {
            FaceMatcherFullScreenDialog(
                sessionKey = session,
                title = humanReadableReason?.title ?: stringResource(Res.string.face_matcher_liveness_prompt_default_title),
                subtitle = humanReadableReason?.subtitle ?: stringResource(Res.string.face_matcher_liveness_prompt_default_subtitle),
                message = promptState.message,
                isSuccess = promptState.status == FaceMatcherLivenessPromptState.Status.SUCCESS,
                isFailed = promptState.status == FaceMatcherLivenessPromptState.Status.FAILED,
                requireConfirmation = dialogParameters.requireConfirmation || (humanReadableReason?.requireConfirmation == true),
                overlay = promptState.overlay,
                backgroundColor = promptState.backgroundColor,
                onFeedFrame = { frame -> session.feedFrame(frame) },
                onTouchEvent = { x, y -> session.onTouchEvent(x, y) },
                onCancelSession = { session.cancel() },
                onSuccess = {
                    val capturedImages = promptState.capturedImages
                    coroutineScope.launch {
                        dialogStateValue.resultChannel.send(capturedImages)
                    }
                },
                onDismissed = {
                    coroutineScope.launch {
                        session.cancel()
                        dialogStateValue.resultChannel.close(PromptDismissedException())
                    }
                }
            )
        }
    }
}

@Composable
private fun FaceMatcherFullScreenDialog(
    sessionKey: Any,
    title: String,
    subtitle: String,
    message: String?,
    isSuccess: Boolean,
    isFailed: Boolean,
    requireConfirmation: Boolean,
    overlay: OverlayFrame?,
    backgroundColor: org.multipaz.facematch.Color?,
    onFeedFrame: suspend (CameraFrame) -> Unit,
    onTouchEvent: (Float, Float) -> Unit,
    onCancelSession: () -> Unit,
    onSuccess: () -> Unit,
    onDismissed: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val cameraPermissionState = rememberCameraPermissionState()

    var isProcessingFrame by remember { mutableStateOf(false) }
    var isDismissed by remember { mutableStateOf(false) }
    val dialogAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        dialogAlpha.animateTo(1f, animationSpec = tween(durationMillis = 200))
    }

    DisposableEffect(sessionKey) {
        onDispose {
            onCancelSession()
        }
    }

    LaunchedEffect(cameraPermissionState.isGranted) {
        if (!cameraPermissionState.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(isSuccess, requireConfirmation) {
        if (isSuccess && !requireConfirmation) {
            delay(1200.milliseconds)
            if (!isDismissed) {
                isDismissed = true
                dialogAlpha.animateTo(0f, animationSpec = tween(durationMillis = 200))
                onSuccess()
            }
        }
    }

    LaunchedEffect(isFailed) {
        if (isFailed) {
            delay(2.seconds)
            if (!isDismissed) {
                isDismissed = true
                dialogAlpha.animateTo(0f, animationSpec = tween(durationMillis = 200))
                onDismissed()
            }
        }
    }

    fun dismissWithFade() {
        if (!isDismissed) {
            isDismissed = true
            coroutineScope.launch {
                dialogAlpha.animateTo(0f, animationSpec = tween(durationMillis = 200))
                onDismissed()
            }
        }
    }

    fun confirmWithFade() {
        if (!isDismissed) {
            isDismissed = true
            coroutineScope.launch {
                dialogAlpha.animateTo(0f, animationSpec = tween(durationMillis = 200))
                onSuccess()
            }
        }
    }

    val defaultBackgroundColor = Color.White
    val customBackgroundColor = backgroundColor?.toComposeColor()
    val resolvedBackgroundColor = customBackgroundColor?.compositeOver(defaultBackgroundColor)
        ?: defaultBackgroundColor

    val isResolvedBgLight = resolvedBackgroundColor.luminance() > 0.5f
    val primaryContentColor = if (isResolvedBgLight) Color.Black else Color.White
    val secondaryContentColor = if (isResolvedBgLight) {
        Color.Black.copy(alpha = 0.7f)
    } else {
        Color.White.copy(alpha = 0.7f)
    }

    Dialog(
        onDismissRequest = { dismissWithFade() },
        properties = fullScreenDialogProperties()
    ) {
        FullScreenDialogEffect()
        ScreenBrightnessEffect()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dialogAlpha.value }
                .background(resolvedBackgroundColor)
                .safeDrawingPadding()
        ) {
            IconButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
                onClick = { dismissWithFade() }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    tint = primaryContentColor,
                    contentDescription = stringResource(Res.string.face_matcher_prompt_cancel)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Spacer(modifier = Modifier.weight(0.7f))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = primaryContentColor,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = secondaryContentColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (!cameraPermissionState.isGranted) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(355.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(Res.string.face_matcher_prompt_permission_required),
                            style = MaterialTheme.typography.bodyLarge,
                            color = primaryContentColor,
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
                            Text(stringResource(Res.string.face_matcher_prompt_grant_permission))
                        }
                    }
                } else {
                    val cornerRadius = 45.dp
                    // Camera feed clipped inside vertical rectangle with rounded corners
                    Box(
                        modifier = Modifier
                            .size(width = 275.dp, height = 355.dp)
                            .clip(RoundedCornerShape(cornerRadius))
                            .pointerInput(sessionKey) {
                                detectTapGestures { offset ->
                                    onTouchEvent(
                                        (offset.x / size.width.toFloat()).coerceIn(0f, 1f),
                                        (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Camera(
                            modifier = Modifier.fillMaxSize(),
                            cameraSelection = CameraSelection.DEFAULT_FRONT_CAMERA,
                            captureResolution = CameraCaptureResolution.HIGH,
                            showCameraPreview = true,
                            onFrameCaptured = { frame ->
                                if (isDismissed || isSuccess || isFailed || isProcessingFrame) return@Camera
                                isProcessingFrame = true
                                try {
                                    val promptFrame = frame.toPromptCameraFrame()
                                    onFeedFrame(promptFrame)
                                } finally {
                                    isProcessingFrame = false
                                }
                            }
                        )

                        if (overlay != null) {
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
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2E7D32)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                            }
                        }
                    }

                    val messageText = message.orEmpty()
                    Text(
                        text = messageText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            primaryContentColor
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            if (requireConfirmation && cameraPermissionState.isGranted) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp, start = 24.dp, end = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { dismissWithFade() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = primaryContentColor
                        )
                    ) {
                        Text(stringResource(Res.string.face_matcher_prompt_cancel))
                    }

                    Button(
                        onClick = { confirmWithFade() },
                        enabled = isSuccess,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = primaryContentColor.copy(alpha = 0.12f),
                            disabledContentColor = primaryContentColor.copy(alpha = 0.38f)
                        )
                    ) {
                        Text(stringResource(Res.string.face_matcher_prompt_confirm))
                    }
                }
            }
        }
    }
}


@Composable
internal fun FaceMatcherOverlay(
    overlay: OverlayFrame,
    modifier: Modifier = Modifier
) {
    val imageBitmap = remember(overlay) { overlay.toImageBitmap() }
    Image(
        bitmap = imageBitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}
