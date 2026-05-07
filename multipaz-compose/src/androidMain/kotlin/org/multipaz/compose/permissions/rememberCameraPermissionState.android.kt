package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable

import android.Manifest
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState as AccompanistPermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
private class AccompanistCameraPermissionState(
    val permissionsState: AccompanistPermissionState
) : PermissionState {

    override val isGranted: Boolean
        get() = permissionsState.status == PermissionStatus.Granted

    override val isPermanentlyDenied: Boolean
        get() {
            val status = permissionsState.status
            return status is PermissionStatus.Denied && !status.shouldShowRationale
        }

    override suspend fun launchPermissionRequest() {
        permissionsState.launchPermissionRequest()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
actual fun rememberCameraPermissionState(): PermissionState {
    return AccompanistCameraPermissionState(
        rememberPermissionState(
            permission = Manifest.permission.CAMERA,
        )
    )
}
