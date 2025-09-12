package org.multipaz.models.presentment

import kotlinx.serialization.json.JsonObject
import org.multipaz.models.digitalcredentials.DigitalCredentials
import org.multipaz.document.Document

/**
 * A [PresentmentMechanism] to use with [PresentmentModel] when using presentations
 * via [DigitalCredentials] and the
 * [W3C Digital Credentials API](https://w3c-fedid.github.io/digital-credentials/).
 *
 * @property appId the id of the application making the request.
 * @property origin the origin of the requester.
 * @property protocol the `protocol` field in the `DigitalCredentialGetRequest` dictionary in to the W3C DC API.
 * @property data the `data` field in the `DigitalCredentialGetRequest` dictionary in the W3C DC API.
 * @property preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 */
abstract class DigitalCredentialsPresentmentMechanism(
    val appId: String,
    val origin: String,
    val protocol: String,
    val data: JsonObject,
    val preselectedDocuments: List<Document>
): PresentmentMechanism {

    /**
     * Sends the response back to the caller.
     *
     * @param protocol the `protocol` field in the `DigitalCredential` interface in the W3C DC API.
     * @param data the `data` field in the `DigitalCredential` interface in the W3C DC API.
     */
    abstract fun sendResponse(protocol: String, data: JsonObject)
}