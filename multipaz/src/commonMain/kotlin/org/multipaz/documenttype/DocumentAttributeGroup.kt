package org.multipaz.documenttype

/**
 * Metadata for a logical group of [DocumentAttribute]s that can be collapsed into a single
 * pseudo-claim in the consent prompt when at least [minClaimsToGroup] attributes from the
 * group are requested together.
 *
 * @property identifier unique identifier for the group within a document type (e.g. `"name"`).
 * @property displayName localized name suitable for display in the UI (e.g. `"Name"`).
 * @property description localized description of the group.
 * @property icon icon to display for the grouped pseudo-claim, if available.
 * @property sensitivity sensitivity of the grouped pseudo-claim, defaults to [DocumentAttributeSensitivity.PII].
 * @property minClaimsToGroup minimum number of requested claims in this group required before collapsing into
 *   the pseudo-claim (e.g. `2` for composite fields like `"Name"`, `1` for `"Passport data"`).
 */
data class DocumentAttributeGroup(
    val identifier: String,
    val displayName: String,
    val description: String = "",
    val icon: Icon? = null,
    val sensitivity: DocumentAttributeSensitivity = DocumentAttributeSensitivity.PII,
    val minClaimsToGroup: Int = 2,
)
