package org.multipaz.documenttype

/**
 * Namespace defined by ISO/IEC 23220-5 for Portrait Image Equivalence.
 */
const val ISO_23220_5_CHV_1_NAMESPACE = "org.iso.23220.5.1"

/**
 * Data element defined by ISO/IEC 23220-5 for Portrait Image Equivalence.
 */
const val ISO_23220_5_CHV_1_DATA_ELEMENT = "CHV_1"

/**
 * Information about portrait image equivalence support for an ISO mdoc document type.
 *
 * @property namespace the namespace containing the portrait data element.
 * @property dataElementName the name of the data element containing the portrait.
 */
data class MdocPortraitEquivalenceData(
    val namespace: String,
    val dataElementName: String,
)
