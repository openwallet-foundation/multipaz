package org.multipaz.compose.document

import org.multipaz.credential.Credential
import org.multipaz.securearea.KeyInfo

/**
 * Information about a single [Credential] inside a [DocumentInfo].
 *
 * @property credential the [Credential] instance of the [org.multipaz.document.Document].
 * @property keyInfo information about the key backing this credential.
 * @property keyInvalidated returns the validity of the secure-area key.
 */
data class CredentialInfo(
    val credential: Credential,
    val keyInfo: KeyInfo?,
    val keyInvalidated: Boolean
)