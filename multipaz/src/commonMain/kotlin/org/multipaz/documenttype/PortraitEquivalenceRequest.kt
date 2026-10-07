package org.multipaz.documenttype

/**
 * Type used for requesting portrait equivalence data elements.
 *
 * @property includePortrait if true, also requests the portrait data element in addition to portrait equivalence.
 * @property intentToRetain the intent-to-retain value.
 */
data class PortraitEquivalenceRequest(
    val includePortrait: Boolean = false,
    val intentToRetain: Boolean = false,
)