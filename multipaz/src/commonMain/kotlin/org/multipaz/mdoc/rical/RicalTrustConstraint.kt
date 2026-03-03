package org.multipaz.mdoc.rical

import org.multipaz.cbor.DataItem

/**
 * A RICAL trust constraint.
 *
 * @property extensions proprietary extensions.
 */
data class RicalTrustConstraint(
    val extensions: Map<String, DataItem> = emptyMap(),
)