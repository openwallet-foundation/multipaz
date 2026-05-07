package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType

private class IosCameraPermissionState(
    val hasPermission: Boolean,
    val authorizationStatus: Long,
    val recomposeCounter: MutableIntState
): PermissionState {
    override val isGranted: Boolean
        get() = hasPermission

    override val isPermanentlyDenied: Boolean
        get() = authorizationStatus == AVAuthorizationStatusDenied ||
            authorizationStatus == AVAuthorizationStatusRestricted

    override suspend fun launchPermissionRequest() {
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
            recomposeCounter.value += 1
        }
    }
}

@Composable
actual fun rememberCameraPermissionState(): PermissionState {
    val recomposeCounter = remember { mutableIntStateOf(0) }
    LaunchedEffect(recomposeCounter.value) {}

    val authzStatus = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
    val hasPermission = (authzStatus == AVAuthorizationStatusAuthorized)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            recomposeCounter.value += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return IosCameraPermissionState(hasPermission, authzStatus, recomposeCounter)
}
