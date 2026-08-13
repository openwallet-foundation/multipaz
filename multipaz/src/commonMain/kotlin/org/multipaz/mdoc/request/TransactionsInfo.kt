package org.multipaz.mdoc.request

import org.multipaz.cbor.DataItem

/**
 * Data structure that describes transactions in a ISO/IEC 18013 request (`transactions` array
 * in `requestInfo`).
 *
 * @property data maps transaction type to its data
 */
data class TransactionsInfo(
    val data: Map<String, DataItem>
)