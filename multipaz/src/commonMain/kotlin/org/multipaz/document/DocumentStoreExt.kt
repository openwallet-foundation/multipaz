package org.multipaz.document

private const val TAG_IOS_MDOC_DOCTYPES = "org.multipaz.documentStore.ios.MdocDoctypes"

/**
 * Gets the list of ISO mdoc document types that the application has registered for in its iOS manifest.
 *
 * @return the list of registered document types, or `null` if not configured.
 */
suspend fun DocumentStore.getIosMdocDoctypes(): List<String>? {
    return getTags().getStringList(TAG_IOS_MDOC_DOCTYPES)
}

/**
 * Sets the list of ISO mdoc document types that the application has registered for in its iOS manifest.
 *
 * @param value the list of registered document types, or `null` to clear.
 */
suspend fun DocumentStore.setIosMdocDoctypes(value: List<String>?) {
    getTags().edit {
        if (value != null) {
            setStringList(TAG_IOS_MDOC_DOCTYPES, value)
        } else {
            remove(TAG_IOS_MDOC_DOCTYPES)
        }
    }
}
