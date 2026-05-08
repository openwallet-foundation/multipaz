package org.multipaz.verification

/**
 * Details about query for a particular IETF SD-JWT credential in DCQL query.
 *
 * @param vct list of vct values that identify the requested credential type(s).
 */
data class SdJwtQueryData(
    override val id: String,
    override val multiple: Boolean,
    val vct: List<String>,
): QueryData() {
    companion object
}