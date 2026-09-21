package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
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
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherRepository
import org.multipaz.facenet.FaceNetFaceMatcher
import org.multipaz.facenet.testdata.FaceSamplePortrait
import org.multipaz.facenet.testdata.FaceTestData
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.Reason
import org.multipaz.prompt.showFaceLivenessPrompt
import org.multipaz.prompt.showFaceMatcherPrompt
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.testapp.TestAppConfiguration
import org.multipaz.util.Logger

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
        Logger.w(TAG, "Could not load reference portrait from storage", e)
        null
    }
}

/**
 * Screen demonstrating the Face Matcher Prompt.
 *
 * Allows capturing, selecting, or clearing a reference portrait stored in persistent storage,
 * and invoking [PromptModel.showFaceMatcherPrompt] against that reference portrait.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    var showSamplePortraitPicker by remember { mutableStateOf(false) }
    val matchers = faceMatcherRepository?.all ?: emptyList()
    var selectedMatcher by remember { mutableStateOf<FaceMatcher?>(faceMatcherRepository?.defaultMatcher) }
    var detectedFaceCrop by remember { mutableStateOf<ByteString?>(null) }
    var isExtractingCrop by remember { mutableStateOf(false) }
    var cropError by remember { mutableStateOf<String?>(null) }
    var capturedPortraitDialogBytes by remember { mutableStateOf<ByteString?>(null) }

    val faceNetMatcher = remember(selectedMatcher, matchers) {
        (selectedMatcher as? FaceNetFaceMatcher)
            ?: matchers.filterIsInstance<FaceNetFaceMatcher>().firstOrNull()
    }

    LaunchedEffect(referencePortrait, faceNetMatcher) {
        val portrait = referencePortrait
        if (portrait != null && faceNetMatcher != null && faceNetMatcher.isSupported) {
            isExtractingCrop = true
            cropError = null
            try {
                detectedFaceCrop = faceNetMatcher.extractFaceCrop(portrait)
            } catch (e: Throwable) {
                detectedFaceCrop = null
                cropError = e.message ?: "No face detected"
            } finally {
                isExtractingCrop = false
            }
        } else {
            detectedFaceCrop = null
            cropError = null
            isExtractingCrop = false
        }
    }

    val imagePicker = rememberImagePicker(
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                coroutineScope.launch {
                    try {
                        val raw = files.first()
                        val normalized = normalizePortrait(raw)
                        savePortrait(storage, normalized)
                        referencePortrait = normalized
                        showToast("Reference portrait selected from gallery")
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Failed to save selected portrait", e)
                        showToast("Failed to process image: ${e.message}")
                    }
                }
            }
        }
    )

    LaunchedEffect(Unit) {
        try {
            val table = storage.getTable(FACE_MATCHER_STORAGE_TABLE_SPEC)
            referencePortrait = try {
                table.get(FACE_MATCHER_KEY_REFERENCE_PORTRAIT)
            } catch (e: Throwable) {
                Logger.e(TAG, "Failed to load reference portrait from storage, clearing corrupt entry", e)
                try {
                    table.delete(FACE_MATCHER_KEY_REFERENCE_PORTRAIT)
                } catch (_: Throwable) {}
                null
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to access storage table", e)
        } finally {
            isLoading = false
        }
    }

    if (showCameraCaptureDialog) {
        CapturePortraitDialog(
            onDismiss = { showCameraCaptureDialog = false },
            onPortraitCaptured = { capturedBytes ->
                coroutineScope.launch {
                    try {
                        val normalized = normalizePortrait(capturedBytes)
                        savePortrait(storage, normalized)
                        referencePortrait = normalized
                        showCameraCaptureDialog = false
                        showToast("Reference portrait captured from camera")
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Failed to save captured portrait", e)
                        showToast("Failed to process image: ${e.message}")
                    }
                }
            }
        )
    }

    if (showSamplePortraitPicker) {
        SamplePortraitPickerDialog(
            onDismiss = { showSamplePortraitPicker = false },
            onPortraitSelected = { selected ->
                coroutineScope.launch {
                    try {
                        savePortrait(storage, selected.data)
                        referencePortrait = selected.data
                        showSamplePortraitPicker = false
                        showToast("Selected ${selected.displayName}")
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Failed to save sample portrait", e)
                        showToast("Failed to save portrait: ${e.message}")
                    }
                }
            }
        )
    }
 
    if (capturedPortraitDialogBytes != null) {
        val capturedBytes = capturedPortraitDialogBytes!!
        CapturedPortraitDialog(
            portraitBytes = capturedBytes,
            onDismiss = { capturedPortraitDialogBytes = null },
            onUseAsReference = {
                coroutineScope.launch {
                    try {
                        val normalized = normalizePortrait(capturedBytes)
                        savePortrait(storage, normalized)
                        referencePortrait = normalized
                        capturedPortraitDialogBytes = null
                        showToast("Reference portrait updated from captured photo")
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Failed to save captured portrait", e)
                        showToast("Failed to save portrait: ${e.message}")
                    }
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

                        if (faceNetMatcher != null && faceNetMatcher.isSupported) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Left: Reference Portrait
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = "Reference Portrait",
                                            modifier = Modifier
                                                .size(130.dp)
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
                                                .size(130.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("Invalid Image", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Text(
                                        text = "Reference Portrait",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val resInfo = if (bitmap != null) "${bitmap.width}×${bitmap.height} • " else ""
                                    Text(
                                        text = "$resInfo${portraitBytes.size / 1024} KB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Right: FaceNet detected face
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val cropBytes = detectedFaceCrop
                                    val cropBitmap = remember(cropBytes) {
                                        cropBytes?.let {
                                            try {
                                                decodeImage(it.toByteArray())
                                            } catch (e: Throwable) {
                                                null
                                            }
                                        }
                                    }

                                    if (isExtractingCrop) {
                                        Box(
                                            modifier = Modifier
                                                .size(130.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                        }
                                    } else if (cropBitmap != null) {
                                        Image(
                                            bitmap = cropBitmap,
                                            contentDescription = "FaceNet Face Detected",
                                            modifier = Modifier
                                                .size(130.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .border(
                                                    width = 2.dp,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(130.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = cropError ?: "No face detected",
                                                style = MaterialTheme.typography.bodySmall,
                                                textAlign = TextAlign.Center,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(8.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = "FaceNet face detected",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = if (cropBytes != null) "${cropBytes.size} bytes (112×112)" else "BlazeFace alignment",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
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

                            val resInfo = if (bitmap != null) "${bitmap.width}×${bitmap.height} • " else ""
                            Text(
                                text = "Size: $resInfo${portraitBytes.size / 1024} KB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showCameraCaptureDialog = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Retake", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { imagePicker.launch() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Pick Image", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { showSamplePortraitPicker = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Sample", maxLines = 1)
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
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Rotate 90°", maxLines = 1)
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
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Clear Portrait", maxLines = 1)
                            }
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
                                onClick = { showSamplePortraitPicker = true }
                            ) {
                                Text("Pick from Sample portraits")
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

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    val matcherToUse = selectedMatcher ?: faceMatcherRepository?.defaultMatcher
                                    val captured = promptModel.showFaceLivenessPrompt(
                                        matcher = matcherToUse,
                                        reason = Reason.HumanReadable(
                                            title = "Check Liveness",
                                            subtitle = "Follow the prompts and hold still to capture your portrait",
                                            requireConfirmation = false
                                        )
                                    )
                                    if (captured != null) {
                                        capturedPortraitDialogBytes = captured
                                        showToast("Liveness confirmed! Portrait captured.")
                                    } else {
                                        showToast("Liveness check failed or dismissed")
                                    }
                                } catch (e: PromptDismissedException) {
                                    showToast("Liveness check cancelled")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    showToast("Error: ${e.message}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check liveness and capture portrait image")
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
    val normalized = normalizePortrait(bytes)
    val table: StorageTable = storage.getTable(FACE_MATCHER_STORAGE_TABLE_SPEC)
    try {
        table.delete(FACE_MATCHER_KEY_REFERENCE_PORTRAIT)
    } catch (_: Throwable) {}
    table.insert(key = FACE_MATCHER_KEY_REFERENCE_PORTRAIT, data = normalized)
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
                            captureResolution = CameraCaptureResolution.HIGH,
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

@Composable
private fun SamplePortraitPickerDialog(
    onDismiss: () -> Unit,
    onPortraitSelected: (FaceSamplePortrait) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Pick Sample Portrait",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(FaceTestData.allPortraits.size) { index ->
                    val sample = FaceTestData.allPortraits[index]
                    val bitmap = remember(sample.id) {
                        try {
                            decodeImage(sample.data.toByteArray())
                        } catch (e: Throwable) {
                            null
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPortraitSelected(sample) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = sample.displayName,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = sample.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = sample.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
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

private fun scaleImageBitmap(bitmap: ImageBitmap, targetWidth: Int, targetHeight: Int): ImageBitmap {
    val newBitmap = ImageBitmap(targetWidth, targetHeight)
    val canvas = Canvas(newBitmap)
    val scaleX = targetWidth.toFloat() / bitmap.width.toFloat()
    val scaleY = targetHeight.toFloat() / bitmap.height.toFloat()
    canvas.save()
    canvas.scale(scaleX, scaleY)
    canvas.drawImage(
        bitmap,
        Offset.Zero,
        Paint()
    )
    canvas.restore()
    return newBitmap
}

private fun normalizePortrait(rawBytes: ByteString): ByteString {
    return try {
        val bitmap = decodeImage(rawBytes.toByteArray())
        val maxDim = 1920
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim && rawBytes.size <= 2_000_000) {
            rawBytes
        } else {
            val scale = maxDim.toFloat() / maxOf(width, height)
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            val scaledBitmap = scaleImageBitmap(bitmap, targetWidth, targetHeight)
            encodeImageToPng(scaledBitmap)
        }
    } catch (e: Throwable) {
        Logger.w(TAG, "Failed to normalize portrait image, using original", e)
        rawBytes
    }
}

@Composable
private fun CapturedPortraitDialog(
    portraitBytes: ByteString,
    onDismiss: () -> Unit,
    onUseAsReference: () -> Unit
) {
    val bitmap = remember(portraitBytes) {
        try {
            decodeImage(portraitBytes.toByteArray())
        } catch (e: Throwable) {
            null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Captured Portrait") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Captured Portrait",
                        modifier = Modifier
                            .size(200.dp)
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
                            .size(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Invalid Image", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val resolutionText = if (bitmap != null) "${bitmap.width} × ${bitmap.height}" else "Unknown"
                Text(
                    text = "Resolution: $resolutionText (${portraitBytes.size / 1024} KB)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Liveness check passed! High-resolution photo captured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(onClick = onUseAsReference) {
                Text("Use as Reference Portrait")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

