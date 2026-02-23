package org.multipaz.mdoc.mso

import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged

internal object MsoPayloadDecoder {
    /**
     * Accepts both spec-compliant payloads (#6.24(bstr .cbor MSO)) and legacy/raw CBOR MSO payloads.
     */
    fun decode(payload: ByteArray): DataItem {
        return decodeDataItem(Cbor.decode(payload))
    }

    private fun decodeDataItem(item: DataItem): DataItem {
        val unwrapped = when {
            item is Tagged && item.tagNumber == Tagged.ENCODED_CBOR && item.taggedItem is Bstr ->
                Cbor.decode(item.taggedItem.asBstr)
            item is Bstr -> Cbor.decode(item.asBstr)
            else -> item
        }
        return if (
            unwrapped is Tagged &&
            unwrapped.tagNumber == Tagged.ENCODED_CBOR &&
            unwrapped.taggedItem is Bstr
        ) {
            Cbor.decode(unwrapped.taggedItem.asBstr)
        } else {
            unwrapped
        }
    }
}
