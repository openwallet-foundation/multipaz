package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.camera.Camera
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.decodeImage
import org.multipaz.compose.encodeImageToPng
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.compose.pickers.rememberImagePicker
import org.multipaz.documenttype.knowntypes.SampleData
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherRepository
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.Reason
import org.multipaz.prompt.showFaceMatcherPrompt
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.testapp.TestAppConfiguration
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url

private const val TAG = "FaceMatcherPromptScreen"

val FACE_MATCHER_STORAGE_TABLE_SPEC = StorageTableSpec(
    name = "FaceMatcherPromptData",
    supportPartitions = false,
    supportExpiration = false
)

const val FACE_MATCHER_KEY_REFERENCE_PORTRAIT = "referencePortrait"

/**
 * Retrieves the stored reference portrait from persistent storage if available.
 */
suspend fun getFaceMatcherReferencePortrait(storage: Storage = TestAppConfiguration.storage): ByteString? {
    return try {
        val table = storage.getTable(FACE_MATCHER_STORAGE_TABLE_SPEC)
        table.get(FACE_MATCHER_KEY_REFERENCE_PORTRAIT)
    } catch (e: Throwable) {
        null
    }
}

/**
 * Screen demonstrating the Face Matcher Prompt.
 *
 * Allows capturing, selecting, or clearing a reference portrait stored in persistent storage,
 * and invoking [PromptModel.showFaceMatcherPrompt] against that reference portrait.
 */
@Composable
fun FaceMatcherPromptScreen(
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
    faceMatcherRepository: FaceMatcherRepository? = null,
    storage: Storage = TestAppConfiguration.storage
) {
    val coroutineScope = rememberCoroutineScope()
    var referencePortrait by remember { mutableStateOf<ByteString?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showCameraCaptureDialog by remember { mutableStateOf(false) }
    val matchers = faceMatcherRepository?.all ?: emptyList()
    var selectedMatcher by remember { mutableStateOf<FaceMatcher?>(faceMatcherRepository?.defaultMatcher) }

    val imagePicker = rememberImagePicker(
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                coroutineScope.launch {
                    savePortrait(storage, files.first())
                    referencePortrait = files.first()
                    showToast("Reference portrait selected from gallery")
                }
            }
        }
    )

    LaunchedEffect(Unit) {
        try {
            val table = storage.getTable(FACE_MATCHER_STORAGE_TABLE_SPEC)
            referencePortrait = table.get(FACE_MATCHER_KEY_REFERENCE_PORTRAIT)
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to load reference portrait from storage", e)
        } finally {
            isLoading = false
        }
    }

    if (showCameraCaptureDialog) {
        CapturePortraitDialog(
            onDismiss = { showCameraCaptureDialog = false },
            onPortraitCaptured = { capturedBytes ->
                coroutineScope.launch {
                    savePortrait(storage, capturedBytes)
                    referencePortrait = capturedBytes
                    showCameraCaptureDialog = false
                    showToast("Reference portrait captured from camera")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Face Matcher Prompt",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Store a reference portrait (persisted across restarts) to match against. " +
                                "When a portrait is present, you can invoke the Face Matcher Prompt to verify " +
                                "the user's identity via the front camera.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Reference Portrait",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                    } else if (referencePortrait != null) {
                        val portraitBytes = referencePortrait!!
                        val bitmap = remember(portraitBytes) {
                            try {
                                decodeImage(portraitBytes.toByteArray())
                            } catch (e: Throwable) {
                                null
                            }
                        }

                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Reference Portrait",
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Invalid Image", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Text(
                            text = "Size: ${portraitBytes.size} bytes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            OutlinedButton(
                                onClick = { showCameraCaptureDialog = true }
                            ) {
                                Text("Retake")
                            }
                            OutlinedButton(
                                onClick = { imagePicker.launch() }
                            ) {
                                Text("Pick Image")
                            }
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        try {
                                            val currentBitmap = decodeImage(portraitBytes.toByteArray())
                                            val rotated = rotateImageBitmap(currentBitmap, 90f)
                                            val newBytes = encodeImageToPng(rotated)
                                            savePortrait(storage, newBytes)
                                            referencePortrait = newBytes
                                            showToast("Portrait rotated 90° clockwise")
                                        } catch (e: Throwable) {
                                            Logger.e(TAG, "Failed to rotate portrait", e)
                                            showToast("Failed to rotate: ${e.message}")
                                        }
                                    }
                                }
                            ) {
                                Text("Rotate 90°")
                            }
                        }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        val table = storage.getTable(FACE_MATCHER_STORAGE_TABLE_SPEC)
                                        table.delete(FACE_MATCHER_KEY_REFERENCE_PORTRAIT)
                                        referencePortrait = null
                                        showToast("Reference portrait cleared")
                                    } catch (e: Throwable) {
                                        Logger.e(TAG, "Failed to clear portrait", e)
                                        showToast("Failed to clear portrait: ${e.message}")
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Clear Portrait")
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(width = 200.dp, height = 130.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountBox,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "No portrait stored",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Text(
                            text = "Capture a photo with the camera, select an image from your gallery, or load a sample portrait.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(
                                onClick = { showCameraCaptureDialog = true },
                                modifier = Modifier.fillMaxWidth(0.8f)
                            ) {
                                Text("Capture with Camera")
                            }
                            OutlinedButton(
                                onClick = { imagePicker.launch() },
                                modifier = Modifier.fillMaxWidth(0.8f)
                            ) {
                                Text("Pick from Gallery")
                            }
                            TextButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val sampleBytes = ByteString(SampleData.PORTRAIT_BASE64URL.fromBase64Url())
                                        savePortrait(storage, sampleBytes)
                                        referencePortrait = sampleBytes
                                        showToast("Sample portrait loaded")
                                    }
                                }
                            ) {
                                Text("Use Sample Portrait")
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Face Verification",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Invokes the Face Matcher Prompt dialog to match the user's face in the camera stream against the stored reference portrait.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (matchers.size > 1) {
                        SettingMultipleChoice(
                            title = "Face matcher implementation",
                            choices = matchers.map { it.displayName },
                            initialChoice = (selectedMatcher ?: faceMatcherRepository?.defaultMatcher)?.displayName ?: "",
                            onChoiceSelected = { choice ->
                                selectedMatcher = matchers.find { it.displayName == choice }
                            }
                        )
                    }

                    Button(
                        onClick = {
                            val portrait = referencePortrait ?: return@Button
                            coroutineScope.launch {
                                try {
                                    val matcherToUse = selectedMatcher ?: faceMatcherRepository?.defaultMatcher
                                    val matched = promptModel.showFaceMatcherPrompt(
                                        referencePortrait = portrait,
                                        matcher = matcherToUse,
                                        reason = Reason.HumanReadable(
                                            title = "Verify Identity",
                                            subtitle = "Please look at the camera to match your face",
                                            requireConfirmation = false
                                        )
                                    )
                                    if (matched) {
                                        showToast("Face matched successfully!")
                                    } else {
                                        showToast("Face verification failed or dismissed")
                                    }
                                } catch (e: PromptDismissedException) {
                                    showToast("Face verification cancelled")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    showToast("Error: ${e.message}")
                                }
                            }
                        },
                        enabled = (referencePortrait != null),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Verify Face (Show Face Matcher Prompt)")
                    }

                    if (referencePortrait == null) {
                        Text(
                            text = "A reference portrait is required to enable face verification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

private suspend fun savePortrait(storage: Storage, bytes: ByteString) {
    val table: StorageTable = storage.getTable(FACE_MATCHER_STORAGE_TABLE_SPEC)
    if (table.get(FACE_MATCHER_KEY_REFERENCE_PORTRAIT) == null) {
        table.insert(key = FACE_MATCHER_KEY_REFERENCE_PORTRAIT, data = bytes)
    } else {
        table.update(key = FACE_MATCHER_KEY_REFERENCE_PORTRAIT, data = bytes)
    }
}

@Composable
private fun CapturePortraitDialog(
    onDismiss: () -> Unit,
    onPortraitCaptured: (ByteString) -> Unit
) {
    val cameraPermissionState = rememberCameraPermissionState()
    val coroutineScope = rememberCoroutineScope()
    var shouldCapture by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var cameraSelection by remember { mutableStateOf(CameraSelection.DEFAULT_FRONT_CAMERA) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture Reference Portrait") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!cameraPermissionState.isGranted) {
                    Text(
                        text = "Camera permission is required to capture a portrait.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                cameraPermissionState.launchPermissionRequest()
                            }
                        }
                    ) {
                        Text("Grant Camera Permission")
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(width = 240.dp, height = 280.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Camera(
                            modifier = Modifier.fillMaxSize(),
                            cameraSelection = cameraSelection,
                            captureResolution = CameraCaptureResolution.MEDIUM,
                            showCameraPreview = true,
                            onFrameCaptured = { frame ->
                                if (shouldCapture && !isCapturing) {
                                    isCapturing = true
                                    try {
                                        val bitmap = frame.cameraImage.toImageBitmap()
                                        val pngBytes = encodeImageToPng(bitmap)
                                        onPortraitCaptured(pngBytes)
                                    } catch (e: Throwable) {
                                        Logger.e(TAG, "Failed to capture portrait frame", e)
                                    } finally {
                                        shouldCapture = false
                                        isCapturing = false
                                    }
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                cameraSelection = if (cameraSelection == CameraSelection.DEFAULT_FRONT_CAMERA) {
                                    CameraSelection.DEFAULT_BACK_CAMERA
                                } else {
                                    CameraSelection.DEFAULT_FRONT_CAMERA
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cameraswitch,
                                contentDescription = "Switch Camera"
                            )
                        }
                        Text(
                            text = if (cameraSelection == CameraSelection.DEFAULT_FRONT_CAMERA) {
                                "Front Camera"
                            } else {
                                "Rear Camera"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (cameraPermissionState.isGranted) {
                Button(
                    onClick = {
                        shouldCapture = true
                    },
                    enabled = !isCapturing
                ) {
                    Text(if (isCapturing) "Capturing..." else "Capture Photo")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun rotateImageBitmap(bitmap: ImageBitmap, degrees: Float): ImageBitmap {
    val newWidth = if (degrees == 90f || degrees == 270f) bitmap.height else bitmap.width
    val newHeight = if (degrees == 90f || degrees == 270f) bitmap.width else bitmap.height
    val newBitmap = ImageBitmap(newWidth, newHeight)
    val canvas = Canvas(newBitmap)
    canvas.save()
    canvas.translate(newWidth.toFloat() / 2f, newHeight.toFloat() / 2f)
    canvas.rotate(degrees)
    canvas.translate(-bitmap.width.toFloat() / 2f, -bitmap.height.toFloat() / 2f)
    canvas.drawImage(
        bitmap,
        Offset.Zero,
        Paint()
    )
    canvas.restore()
    return newBitmap
}
