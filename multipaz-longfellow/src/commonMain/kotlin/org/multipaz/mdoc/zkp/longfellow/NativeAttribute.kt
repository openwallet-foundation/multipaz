package org.multipaz.mdoc.zkp.longfellow

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem

/** Statements for the zk proof. */
internal data class NativeAttribute(
    val key: String,
    val namespace: String,
    val value: ByteArray
)