package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.multipaz.datetime.formatLocalized
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.driving_license_card_art
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.multipaz.compose.decodeImage
import org.multipaz.compose.notifications.Notification
import org.multipaz.compose.notifications.NotificationId
import org.multipaz.compose.notifications.NotificationManager
import org.multipaz.compose.permissions.rememberNotificationPermissionState

@Composable
fun NotificationsScreen(
    showToast: (message: String) -> Unit,
) {
    val notificationPermissionState = rememberNotificationPermissionState()

    val coroutineScope = rememberCoroutineScope()

    if (!notificationPermissionState.isGranted) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        notificationPermissionState.launchPermissionRequest()
                    }
                }
            ) {
                Text("Request Notification permission")
            }
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            LazyColumn(
                modifier = Modifier.padding(8.dp)
            ) {
                item {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            postNotification(true)
                        }
                    }) {
                        Text("Post notification with image")
                    }
                }
                item {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            postNotification(false)
                        }
                    }) {
                        Text("Post notification without image")
                    }
                }
                item {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            coroutineScope.launch {
                                updateNotification(showToast)
                            }
                        }
                    }) {
                        Text("Update last notification")
                    }
                }
                item {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            coroutineScope.launch {
                                cancelNotification(showToast)
                            }
                        }
                    }) {
                        Text("Cancel last notification")
                    }
                }
                item {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            coroutineScope.launch {
                                cancelAllNotifications()
                            }
                        }
                    }) {
                        Text("Cancel all notifications")
                    }
                }
            }
        }
    }
}

private var notificationCount = 1
private var lastNotificationId: NotificationId? = null
private var lastNotficationIncludedImage = false

@OptIn(ExperimentalResourceApi::class)
private suspend fun postNotification(includeImage: Boolean) {
    val nowStr = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).formatLocalized()
    val image = if (includeImage) {
        decodeImage(
            getDrawableResourceBytes(
                getSystemResourceEnvironment(),
                Res.drawable.driving_license_card_art
            )
        )
    } else {
        null
    }

    lastNotificationId = NotificationManager.Default.notify(
        Notification(
            title = "Test Notification #${notificationCount++}",
            body = "Notification sent at $nowStr",
            image = image,
        )
    )
    lastNotficationIncludedImage = includeImage
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun updateNotification(
    showToast: (String) -> Unit
) {
    if (lastNotificationId == null) {
        showToast("No notification to update")
        return
    }

    val nowStr = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).formatLocalized()
    val image = if (lastNotficationIncludedImage) {
        decodeImage(
            getDrawableResourceBytes(
                getSystemResourceEnvironment(),
                Res.drawable.driving_license_card_art
            )
        )
    } else {
        null
    }

    NotificationManager.Default.notify(
        notification = Notification(
            title = "Test Notification #${notificationCount - 1} update",
            body = "Updated notification at $nowStr",
            image =  image
        ),
        notificationId = lastNotificationId
    )
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun cancelNotification(
    showToast: (String) -> Unit
) {
    if (lastNotificationId == null) {
        showToast("No notification to cancel")
        return
    }
    NotificationManager.Default.cancel(lastNotificationId!!)
    lastNotificationId = null
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun cancelAllNotifications() {
    NotificationManager.Default.cancelAll()
    lastNotificationId = null
}
