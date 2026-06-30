package org.multipaz.document

import kotlinx.io.bytestring.ByteString

private const val TAG_ANDROID_CREDMAN_TITLE = "org.multipaz.document.androidCredman.Title"
private const val TAG_ANDROID_CREDMAN_SUBTITLE = "org.multipaz.document.androidCredman.Subtitle"
private const val TAG_ANDROID_CREDMAN_BITMAP = "org.multipaz.document.androidCredman.Bitmap"
private const val TAG_ANDROID_CREDMAN_EXCHANGE_PROTOCOLS = "org.multipaz.document.androidCredman.ExchangeProtocols"

/**
 * The title for a document in Android Credential Manager or `null` if not configured.
 */
val Document.androidCredmanTitle: String?
    get() = tags.getString(TAG_ANDROID_CREDMAN_TITLE)

/**
 * Sets the title for a document in Android Credential Manager.
 *
 * @param value the value or `null` to unconfigure.
 */
suspend fun Document.setAndroidCredmanTitle(value: String?) {
    edit {
        value?.let {
            tags.setString(TAG_ANDROID_CREDMAN_TITLE, value)
        } ?: tags.remove(TAG_ANDROID_CREDMAN_TITLE)
    }
}

/**
 * The subtitle for a document in Android Credential Manager or `null` if not configured.
 */
val Document.androidCredmanSubtitle: String?
    get() = tags.getString(TAG_ANDROID_CREDMAN_SUBTITLE)

/**
 * Sets the subtitle for a document in Android Credential Manager.
 *
 * @param value the value or `null` to unconfigure.
 */
suspend fun Document.setAndroidCredmanSubtitle(value: String?) {
    edit {
        value?.let {
            tags.setString(TAG_ANDROID_CREDMAN_SUBTITLE, value)
        } ?: tags.remove(TAG_ANDROID_CREDMAN_SUBTITLE)
    }
}

/**
 * The bitmap for a document in Android Credential Manager or `null` if not configured.
 */
val Document.androidCredmanBitmap: ByteString?
    get() = tags.getByteString(TAG_ANDROID_CREDMAN_BITMAP)

/**
 * Sets bitmap for a document in Android Credential Manager.
 *
 * @param value the value or `null` to unconfigure.
 */
suspend fun Document.setAndroidCredmanBitmap(value: ByteString?) {
    edit {
        value?.let {
            tags.setByteString(TAG_ANDROID_CREDMAN_BITMAP, value)
        } ?: tags.remove(TAG_ANDROID_CREDMAN_BITMAP)
    }
}

/**
 * The exchange protocols for a document in Android Credential Manager or `null` if not configured.
 */
val Document.androidCredmanExchangeProtocols: List<String>?
    get() = tags.getList<String>(TAG_ANDROID_CREDMAN_EXCHANGE_PROTOCOLS)

/**
 * Sets exchange protocols for a document in Android Credential Manager.
 *
 * @param value the value or `null` to unconfigure.
 */
suspend fun Document.setAndroidCredmanExchangeProtocols(value: List<String>?) {
    edit {
        value?.let {
            tags.setList(TAG_ANDROID_CREDMAN_EXCHANGE_PROTOCOLS, value)
        } ?: tags.remove(TAG_ANDROID_CREDMAN_EXCHANGE_PROTOCOLS)
    }
}
