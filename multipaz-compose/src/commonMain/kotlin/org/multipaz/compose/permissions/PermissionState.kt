package org.multipaz.compose.permissions

/**
 * An interface for querying and requesting a permission.
 */
interface PermissionState {

    /**
     * Whether the permission has been granted.
     */
    val isGranted: Boolean

    /**
     * Whether the permission has been permanently denied by the user.
     *
     * When `true`, calling [launchPermissionRequest] will not show a system dialog.
     * The user must be directed to the app settings screen to grant the permission manually.
     *
     * On Android this means the user has denied the permission and the system will no longer
     * offer the permission dialog (e.g. the user selected "Don't ask again").
     * On iOS this means the system authorization status is denied or restricted.
     */
    val isPermanentlyDenied: Boolean

    /**
     * Requests the permission.
     */
    suspend fun launchPermissionRequest()
}
