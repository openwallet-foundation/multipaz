package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import org.multipaz.provisioning.Display
import kotlin.time.Instant

/**
 * An event recorded when a document is provisioned.
 *
 * @property identifier the event identifier.
 * @property timestamp the time the event was recorded.
 * @property appData application-specific data.
 * @property issuerData information about the issuer.
 * @property initialProvisioning whether this was the first time the document was provisioned.
 * @property documentId the unique identifier of the document.
 * @property documentName the name of the document, if available.
 * @property display display information for the document.
 * @property credentialsFetched the credentials fetched, keyed by credential configuration ID.
 */
data class EventProvisioning(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    val issuerData: EventProvisioningIssuerData,
    val initialProvisioning: Boolean,
    val documentId: String,
    val documentName: String?,
    val display: Display?,
    val credentialsFetched: Map<String, List<EventProvisioningCredentialData>>
): Event(identifier, timestamp, appData) {

    override fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event = copy(
        identifier = identifier,
        timestamp = timestamp,
        appData = appData,
        issuerData = this.issuerData,
        initialProvisioning = this.initialProvisioning,
        documentId = this.documentId,
        documentName = this.documentName,
        display = this.display,
        credentialsFetched = this.credentialsFetched,
    )
}
