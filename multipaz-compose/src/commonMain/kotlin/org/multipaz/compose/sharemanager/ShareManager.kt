package org.multipaz.compose.sharemanager

/**
 * A platform-neutral interface for invoking system share sheets.
 *
 * This abstraction allows the shared Compose UI to export documents or media to other apps
 * without leaking platform-specific contexts or file system implementations.
 */
expect class ShareManager() {
    /**
     * Presents the system share sheet for the given document payload.
     *
     * This will trigger external components for the user to interact with so make sure to launch
     * this from a coroutine which is properly bound to the UI, see [org.multipaz.context.UiContext]
     * for details.
     *
     * @param content The raw byte payload of the file to be shared.
     * @param filename The name of the file, including its extension (e.g., "identity_doc.json").
     * **Note:** iOS heavily relies on the file extension to determine the
     * file's Uniform Type Identifier (UTI) and proper target apps.
     * @param mimeType The MIME type of the content (e.g., "application/json").
     * **Note:** This is required for Android to populate the share sheet
     * with capable target apps. iOS ignores this parameter entirely.
     * @param title An optional title to display on the share sheet UI, or to use as a
     * subject line if the user selects an email client as the target app.
     */
    suspend fun shareDocument(
        content: ByteArray,
        filename: String,
        mimeType: String,
        title: String? = null
    )
}
