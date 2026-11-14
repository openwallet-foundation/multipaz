package org.multipaz.mdoc.rical

import org.multipaz.cbor.DataItem

/**
 * A RICAL trust constraint according to ISO/IEC 18013-5 Second Edition Annex F.3.2.4.
 *
 * @property extensions application-specific extensions (`"extensions"`).
 * @property otherInfo additional RFU fields present in the `TrustConstraint` map (`* tstr => RFU`).
 */
data class RicalTrustConstraint(
    val extensions: Map<String, DataItem> = emptyMap(),
    val otherInfo: Map<String, DataItem> = emptyMap(),
)