package org.multipaz.verification

/**
 * Details about query for a particular IETF SD-JWT credential in DCQL query.
 *
 * @property vct list of vct values that identify the requested credential type(s).
 * @property requireCryptographicHolderBinding whether cryptographic holder binding is required.
 */
data class SdJwtQueryData(
    override val id: String,
    override val multiple: Boolean,
    val vct: List<String>,
    val requireCryptographicHolderBinding: Boolean = true,
): QueryData() {
    companion object
}