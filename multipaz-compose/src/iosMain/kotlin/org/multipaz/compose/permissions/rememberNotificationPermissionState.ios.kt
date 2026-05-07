package org.multipaz.compose.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound

import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import kotlin.coroutines.resume

private class IosNotificationPermissionState(
    val center: UNUserNotificationCenter,
    val hasPermission: Boolean,
    val authorizationStatus: Long,
    val recomposeCounter: MutableIntState
): PermissionState {
    companion object {
        private val NOTIFICATION_PERMISSIONS =
            UNAuthorizationOptionAlert or
                    UNAuthorizationOptionSound or
                    UNAuthorizationOptionBadge
    }

    override val isGranted: Boolean
        get() = hasPermission

    override val isPermanentlyDenied: Boolean
        get() = authorizationStatus == UNAuthorizationStatusDenied

    override suspend fun launchPermissionRequest() {
        center.requestAuthorizationWithOptions(NOTIFICATION_PERMISSIONS) { isGranted, _ ->
            recomposeCounter.value = recomposeCounter.value + 1
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
actual fun rememberNotificationPermissionState(): PermissionState {
    val center = UNUserNotificationCenter.currentNotificationCenter()

    val recomposeCounter = remember { mutableIntStateOf(0) }
    LaunchedEffect(recomposeCounter.value) {}

    val (hasPermission, authorizationStatus) = runBlocking {
        suspendCancellableCoroutine<Pair<Boolean, Long>> { continuation ->
            center.getNotificationSettingsWithCompletionHandler { settings ->
                val status = settings?.authorizationStatus ?: -1L
                continuation.resume(Pair(status == UNAuthorizationStatusAuthorized, status))
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            recomposeCounter.value = recomposeCounter.value + 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return IosNotificationPermissionState(center, hasPermission, authorizationStatus, recomposeCounter)
}
