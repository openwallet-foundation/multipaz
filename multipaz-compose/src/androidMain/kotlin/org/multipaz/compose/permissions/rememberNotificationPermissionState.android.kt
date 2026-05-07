package org.multipaz.compose.permissions

import android.Manifest
import android.os.Build
import androidx.compose.runtime.Composable
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState as AccompanistPermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
private class AccompanistNotificationPermissionState(
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
actual fun rememberNotificationPermissionState(): PermissionState {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return AccompanistNotificationPermissionState(
            rememberPermissionState(
                permission = Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    } else {
        // On Android API 32 and earlier (Android 12 and earlier), no permission is needed
        // to post notifications.
        return object: PermissionState {
            override val isGranted: Boolean
                get() = true

            override val isPermanentlyDenied: Boolean
                get() = false

            override suspend fun launchPermissionRequest() {
                throw IllegalStateException("Permission is already granted")
            }
        }
    }
}
