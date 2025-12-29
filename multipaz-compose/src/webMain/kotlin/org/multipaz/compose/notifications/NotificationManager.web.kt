package org.multipaz.compose.notifications

internal actual suspend fun defaultNotify(
    notification: Notification,
    notificationId: NotificationId?,
): NotificationId {
    TODO("NotificationManager not yet implemented on web")
}

internal actual suspend fun defaultCancel(
    notificationId: NotificationId
) {
    TODO("NotificationManager not yet implemented on web")
}

internal actual suspend fun defaultCancelAll() {
    TODO("NotificationManager not yet implemented on web")
}
