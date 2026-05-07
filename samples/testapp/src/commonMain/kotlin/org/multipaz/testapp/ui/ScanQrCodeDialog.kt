package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.permissions.rememberCameraPermissionState
import org.multipaz.compose.qrcode.QrCodeScanner

/**
 * Shows a dialog for scanning QR codes.
 *
 * If the application doesn't have the necessary permission, the user is prompted to grant it.
 *
 * @param title The title of the dialog.
 * @param text The description to include in the dialog, displayed above the QR scanner window.
 * @param additionalContent Content which is displayed below the QR scanner window.
 * @param dismissButton The text for the dismiss button.
 * @param onCodeScanned called when a QR code is scanned, the parameter is the parsed data. Should
 *   return `true` to stop scanning, `false` to continue scanning.
 * @param onDismiss called when the dismiss button is pressed.
 * @param modifier A [Modifier] or `null`.
 */
@Composable
fun ScanQrCodeDialog(
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    additionalContent: (@Composable () -> Unit)? = null,
    dismissButton: String,
    onCodeScanned: (data: String) -> Boolean,
    onDismiss: () -> Unit,
    onNoCodeDetected: () -> Unit = {},
    modifier: Modifier? = null
) {
    val cameraPermissionState = rememberCameraPermissionState()
    val coroutineScope = rememberCoroutineScope()
    var lastQrCode by remember { mutableStateOf<String?>(null )}

    AlertDialog(
        modifier = modifier ?: Modifier,
        title = title,
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                text?.invoke()

                if (!cameraPermissionState.isGranted) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (cameraPermissionState.isPermanentlyDenied) {
                            Text("Camera permission has been permanently denied. Please enable it in your device settings.")
                        } else {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        cameraPermissionState.launchPermissionRequest()
                                    }
                                }
                            ) {
                                Text("Request Camera permission")
                            }
                        }
                    }
                } else {
                    QrCodeScanner(
                        modifier = Modifier.clipToBounds(),
                        cameraSelection = CameraSelection.DEFAULT_BACK_CAMERA,
                        captureResolution = CameraCaptureResolution.HIGH,
                        showCameraPreview = true,
                        onCodeScanned = { qrCode ->
                            lastQrCode = qrCode
                            if (qrCode != null) {
                                val shouldDismiss = onCodeScanned(qrCode)
                                if (shouldDismiss) {
                                    onDismiss()
                                }
                            } else {
                                onNoCodeDetected()
                            }
                        }
                    )
                }
                additionalContent?.invoke()
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(dismissButton)
            }
        }
    )
}
