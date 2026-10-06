package org.multipaz.compose.facematch

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import org.multipaz.compose.prompt.FaceMatcherOverlay
import org.multipaz.facematch.FaceMatcherLivenessPromptState
import org.multipaz.facematch.FaceMatcherLivenessSession
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_grant_permission
import org.multipaz.multipaz_compose.generated.resources.face_matcher_prompt_permission_required

/**
 * Composable for interactive face liveness verification and portrait photo capture.
 *
 * This composable can be directly embedded into wallet application screens, such as
 * credential provisioning and identity verification workflows. It manages the front camera
 * preview, visual challenge ring and facial landmark overlays, and feeds captured frames
 * into the provided [FaceMatcherLivenessSession].
 *
 * @param session the [FaceMatcherLivenessSession] driving liveness verification and capture.
 * @param modifier the [Modifier] to be applied to this composable layout.
 * @param onSuccess invoked with the captured portrait photo bytes upon successful liveness verification.
 * @param onFailed optional callback invoked if liveness verification fails or times out.
 */
@Composable
fun FaceMatcherLivenessCapture(
    session: FaceMatcherLivenessSession,
    modifier: Modifier = Modifier,
    onSuccess: (ByteString) -> Unit,
    onFailed: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val cameraPermissionState = rememberCameraPermissionState()
    val promptState by session.state.collectAsState()

    var isProcessingFrame by remember { mutableStateOf(false) }

    DisposableEffect(session) {
        onDispose {
            session.cancel()
        }
    }

    LaunchedEffect(cameraPermissionState.isGranted) {
        if (!cameraPermissionState.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(promptState.status) {
        when (promptState.status) {
            FaceMatcherLivenessPromptState.Status.SUCCESS -> {
                val capturedImage = promptState.capturedImage
                if (capturedImage != null) {
                    delay(1200)
                    onSuccess(capturedImage)
                }
            }
            FaceMatcherLivenessPromptState.Status.FAILED -> {
                onFailed?.invoke()
            }
            else -> {}
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
            val isSuccess = promptState.status == FaceMatcherLivenessPromptState.Status.SUCCESS
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
                        if (isSuccess || isProcessingFrame) return@Camera
                        isProcessingFrame = true
                        try {
                            val promptFrame = frame.toPromptCameraFrame()
                            session.feedFrame(promptFrame)
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

            val statusText = promptState.message ?: ""
            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (promptState.status == FaceMatcherLivenessPromptState.Status.FAILED) {
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
