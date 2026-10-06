package org.multipaz.compose.prompt

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.jetbrains.compose.resources.getString
import org.multipaz.compose.camera.Camera
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.camera.toPromptCameraFrame
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.facematch.FaceMatcherLivenessPromptState
import org.multipaz.facematch.FaceMatcherLivenessSession
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.face_matcher_liveness_prompt_default_subtitle
import org.multipaz.multipaz_compose.generated.resources.face_matcher_liveness_prompt_default_title
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_cancel
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_grant_permission
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_permission_required
import org.multipaz.prompt.ConvertToHumanReadableFn
import org.multipaz.prompt.FaceMatcherLivenessPromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptDismissedException

/**
 * Composable dialog that prompts the user to perform active liveness verification and capture a portrait photo.
 *
 * @param model the dialog model managing the liveness request.
 * @param toHumanReadable function to convert reasons to human-readable strings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceMatcherLivenessPromptDialog(
    model: PromptDialogModel<FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest, ByteString?>,
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

        val faceMatcherLivenessSession = remember(dialogParameters) {
            dialogParameters.faceMatcherLivenessSession ?: run {
                val matcher = dialogParameters.matcher
                    ?: (model as? FaceMatcherLivenessPromptDialogModel)?.defaultMatcher
                    ?: throw IllegalStateException("No FaceMatcher available")
                matcher.createLivenessSession()
            }
        }

        val title = humanReadableTitle?.ifEmpty { null }
        val subtitle = humanReadableSubtitle?.ifEmpty { null }

        FaceMatcherLivenessBottomSheet(
            sheetState = sheetState,
            title = title,
            subtitle = subtitle,
            faceMatcherLivenessSession = faceMatcherLivenessSession,
            onSuccess = { capturedImage ->
                coroutineScope.launch {
                    dialogStateValue.resultChannel.send(capturedImage)
                }
            },
            onDismissed = {
                coroutineScope.launch {
                    faceMatcherLivenessSession.cancel()
                    dialogStateValue.resultChannel.close(PromptDismissedException())
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FaceMatcherLivenessBottomSheet(
    sheetState: SheetState,
    title: String?,
    subtitle: String?,
    faceMatcherLivenessSession: FaceMatcherLivenessSession,
    onSuccess: (ByteString?) -> Unit,
    onDismissed: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val cameraPermissionState = rememberCameraPermissionState()
    val promptState by faceMatcherLivenessSession.state.collectAsState()

    var isProcessingFrame by remember { mutableStateOf(false) }
    var isDismissed by remember { mutableStateOf(false) }

    DisposableEffect(faceMatcherLivenessSession) {
        onDispose {
            faceMatcherLivenessSession.cancel()
        }
    }

    var resolvedTitle by remember { mutableStateOf(title ?: "") }
    var resolvedSubtitle by remember { mutableStateOf(subtitle ?: "") }

    LaunchedEffect(title, subtitle) {
        if (title == null) {
            resolvedTitle = getString(Res.string.face_matcher_liveness_prompt_default_title)
        } else {
            resolvedTitle = title
        }
        if (subtitle == null) {
            resolvedSubtitle = getString(Res.string.face_matcher_liveness_prompt_default_subtitle)
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
        if (promptState.outcome == FaceMatcherLivenessPromptState.Outcome.SUCCESS) {
            delay(1200)
            onSuccess(promptState.capturedImage)
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
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
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
                val isSuccess = promptState.outcome == FaceMatcherLivenessPromptState.Outcome.SUCCESS

                val cornerRadius = 36.dp
                Box(
                    modifier = Modifier
                        .size(width = 220.dp, height = 284.dp)
                        .clip(RoundedCornerShape(cornerRadius)),
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
                                faceMatcherLivenessSession.feedFrame(promptFrame)
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

                val statusText = promptState.messageBelow ?: resolvedSubtitle
                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (promptState.outcome == FaceMatcherLivenessPromptState.Outcome.FAILED) {
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
