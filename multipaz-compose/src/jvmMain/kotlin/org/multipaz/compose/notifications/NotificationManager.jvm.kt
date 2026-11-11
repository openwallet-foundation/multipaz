package org.multipaz.compose.notifications

import org.multipaz.util.Logger
import org.multipaz.util.UUID

private const val TAG = "NotificationManager.jvm"

internal actual suspend fun defaultNotify(
    notification: Notification,
    notificationId: NotificationId?,
): NotificationId {
    val id = notificationId ?: UUID.randomUUID().toString()
    Logger.i(TAG, "Notification [$id]: ${notification.title} - ${notification.body}")
    return id
}

internal actual suspend fun defaultCancel(
    notificationId: NotificationId
) {
    Logger.i(TAG, "Cancel notification [$notificationId]")
}

internal actual suspend fun defaultCancelAll() {
    Logger.i(TAG, "Cancel all notifications")
}
