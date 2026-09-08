package org.multipaz.mdoc.engagement

import org.multipaz.mdoc.nfc.MdocHandoverType

/**
 * ISO/IEC 18013 engagement types.
 */
enum class EngagementType {
    /** QR code engagement according to ISO/IEC 18013-5:2021 section 8.2. */
    QR_CODE,

    /** Static NFC handover according to ISO/IEC 18013-5:2021 section 8.3.3.1.1. */
    NFC_STATIC_HANDOVER,

    /** Negotiated NFC handover according to ISO/IEC 18013-5:2021 section 8.3.3.1.2. */
    NFC_NEGOTIATED_HANDOVER,

    /** NFC Concurrent Channel Engagement (CCE), formerly known as NFC handover v2. */
    NFC_CONCURRENT_CHANNEL_ENGAGEMENT;

    companion object
}

/**
 * Converts a [MdocHandoverType] to an [EngagementType].
 */
fun MdocHandoverType.toEngagementType(): EngagementType = when (this) {
    MdocHandoverType.STATIC_HANDOVER -> EngagementType.NFC_STATIC_HANDOVER
    MdocHandoverType.NEGOTIATED_HANDOVER -> EngagementType.NFC_NEGOTIATED_HANDOVER
    MdocHandoverType.V2_HANDOVER -> EngagementType.NFC_CONCURRENT_CHANNEL_ENGAGEMENT
}
