package org.multipaz.document

private const val TAG_IOS_MDOC_DOCTYPES = "org.multipaz.documentStore.ios.MdocDoctypes"
private const val TAG_IOS_SD_JWT_VC_TYPES = "org.multipaz.documentStore.ios.SdJwtVcTypes"

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

/**
 * Gets the list of IETF SD-JWT VC verifiable credential types (`vct`) that the application has registered
 * for in its iOS manifest.
 *
 * @return the list of registered verifiable credential types, or `null` if not configured.
 */
suspend fun DocumentStore.getIosSdJwtVcTypes(): List<String>? {
    return getTags().getStringList(TAG_IOS_SD_JWT_VC_TYPES)
}

/**
 * Sets the list of IETF SD-JWT VC verifiable credential types (`vct`) that the application has registered
 * for in its iOS manifest.
 *
 * @param value the list of registered verifiable credential types, or `null` to clear.
 */
suspend fun DocumentStore.setIosSdJwtVcTypes(value: List<String>?) {
    getTags().edit {
        if (value != null) {
            setStringList(TAG_IOS_SD_JWT_VC_TYPES, value)
        } else {
            remove(TAG_IOS_SD_JWT_VC_TYPES)
        }
    }
}

