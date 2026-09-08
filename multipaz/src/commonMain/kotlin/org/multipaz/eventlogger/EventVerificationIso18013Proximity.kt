package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.mdoc.engagement.EngagementType
import org.multipaz.mdoc.transport.NfcHybridTransportStats
import org.multipaz.verification.PresentmentRecord
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * An event representing credential verification via ISO/IEC 18013-5 proximity presentment.
 *
 * @property identifier a unique identifier for the event.
 * @property timestamp the timestamp when the event was recorded.
 * @property appData additional application-specific data.
 * @property presentmentRecord a [PresentmentRecord] with the result of the presentation.
 * @property engagementType the engagement channel type used.
 * @property durationNfcTapToEngagement time elapsed from initial NFC tap until device engagement was received, if available.
 * @property durationEngagementReceivedToRequestSent time elapsed from receiving device engagement until the request was sent, if available.
 * @property durationRequestSentToResponseReceived time elapsed from sending the device request until receiving the response, if available.
 * @property durationScanningTime time spent scanning for holder transport connections, if applicable.
 * @property nfcHybridTransportStats statistics and diagnostics for NFC hybrid transport, if used.
 */
@CborSerializable
data class EventVerificationIso18013Proximity(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    override val presentmentRecord: PresentmentRecord,
    val engagementType: EngagementType,
    val durationNfcTapToEngagement: Duration? = null,
    val durationEngagementReceivedToRequestSent: Duration? = null,
    override val durationRequestSentToResponseReceived: Duration? = null,
    val durationScanningTime: Duration? = null,
    val nfcHybridTransportStats: NfcHybridTransportStats? = null,
): EventVerification(identifier, timestamp, appData, presentmentRecord, durationRequestSentToResponseReceived) {
    override fun copy(identifier: String, timestamp: Instant, appData: Map<String, DataItem>): Event = copy(
        identifier = identifier,
        timestamp = timestamp,
        appData = appData,
        presentmentRecord = this.presentmentRecord,
        engagementType = this.engagementType,
        durationNfcTapToEngagement = this.durationNfcTapToEngagement,
        durationEngagementReceivedToRequestSent = this.durationEngagementReceivedToRequestSent,
        durationRequestSentToResponseReceived = this.durationRequestSentToResponseReceived,
        durationScanningTime = this.durationScanningTime,
        nfcHybridTransportStats = this.nfcHybridTransportStats,
    )
}
