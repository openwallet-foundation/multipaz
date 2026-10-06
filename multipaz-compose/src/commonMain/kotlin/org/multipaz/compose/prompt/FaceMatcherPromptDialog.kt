package org.multipaz.compose.prompt

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.camera.Camera
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.camera.toPromptCameraFrame
import org.multipaz.compose.toImageBitmap
import org.multipaz.facematch.OverlayFrame
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_cancel
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_default_subtitle
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_default_title
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_grant_permission
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_permission_required
import org.multipaz.facematch.FaceMatcherPromptState
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.prompt.ConvertToHumanReadableFn
import org.multipaz.prompt.FaceMatcherPromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.Reason

/**
 * Composable dialog that prompts the user to perform face matching using the front camera.
 *
 * @param model the dialog model managing the face matcher request.
 * @param toHumanReadable function to convert reason to localized human-readable text.
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

        FaceMatcherBottomSheet(
            sheetState = sheetState,
            faceMatcherSession = dialogParameters.faceMatcherSession,
            title = humanReadableReason?.title,
            subtitle = humanReadableReason?.subtitle,
            onMatched = {
                coroutineScope.launch {
                    dialogStateValue.resultChannel.send(true)
                }
            },
            onDismissed = {
                coroutineScope.launch {
                    dialogParameters.faceMatcherSession.cancel()
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
    faceMatcherSession: FaceMatcherSession,
    title: String?,
    subtitle: String?,
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

    LaunchedEffect(cameraPermissionState.isGranted) {
        if (!cameraPermissionState.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(promptState.status) {
        if (promptState.status == FaceMatcherPromptState.Status.SUCCESS) {
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
                .padding(top = 16.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title ?: stringResource(Res.string.face_matcher_prompt_default_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .padding(horizontal = 48.dp)
                )
                IconButton(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onClick = {
                        if (!isDismissed) {
                            isDismissed = true
                            coroutineScope.launch { sheetState.hide() }
                            onDismissed()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.face_matcher_prompt_cancel)
                    )
                }
            }

            val resolvedSubtitle = subtitle ?: stringResource(Res.string.face_matcher_prompt_default_subtitle)
            if (resolvedSubtitle.isNotEmpty()) {
                Text(
                    text = resolvedSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!cameraPermissionState.isGranted) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(Res.string.face_matcher_prompt_permission_required),
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
                        Text(stringResource(Res.string.face_matcher_prompt_grant_permission))
                    }
                }
            } else {
                val isSuccess = promptState.status == FaceMatcherPromptState.Status.SUCCESS

                val cornerRadius = 36.dp
                // Camera feed clipped inside vertical rectangle with rounded corners
                Box(
                    modifier = Modifier
                        .size(width = 220.dp, height = 284.dp)
                        .clip(RoundedCornerShape(cornerRadius))
                        .pointerInput(faceMatcherSession) {
                            detectTapGestures { offset ->
                                faceMatcherSession.onTouchEvent(
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

                    val overlay = promptState.overlay
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

                val messageText = promptState.message.orEmpty()
                if (messageText.isNotEmpty()) {
                    Text(
                        text = messageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (promptState.status == FaceMatcherPromptState.Status.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                    )
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

