package org.multipaz.nfc

/**
 * NDEF records representing a single alternative carrier in NFC Forum Connection Handover.
 *
 * @property carrierRecord the carrier configuration [NdefRecord] referenced by [alternativeCarrierRecord].
 * @property alternativeCarrierRecord the [AlternativeCarrierRecord] to embed in a
 *   [HandoverRequestRecord] or [HandoverSelectRecord].
 * @property auxiliaryRecords any carrier-specific auxiliary data [NdefRecord]s referenced by
 *   [alternativeCarrierRecord].
 */
data class HandoverCarrierRecords(
    val carrierRecord: NdefRecord,
    val alternativeCarrierRecord: AlternativeCarrierRecord,
    val auxiliaryRecords: List<NdefRecord> = emptyList(),
)
