package org.multipaz.openid.dcql

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import org.multipaz.request.RequestedClaim

/**
 * DCQL Credential Query.
 *
 * Reference: OpenID4VP 1.0 Section 6.1.
 *
 * @property id the assigned identifier for the Credential Query.
 * @property format the requested format of the credential e.g. `mso_mdoc` or `dc+sd-jwt`.
 * @property mdocDocType the ISO mdoc doctype or `null` if format isn't `mso_mdoc`.
 * @property vctValues the array of Verifiable Credential Types or `null` if format isn't `dc+sd-jwt`.
 * @property issuerIdentifiers the list of Authority Key Identifiers from `trusted_authorities` or empty.
 * @property claims a list of claims being requested.
 * @property claimSets a list of claim sets.
 */
data class DcqlCredentialQuery(
    val id: String,
    val format: String,
    val meta: JsonObject,

    // from meta
    val mdocDocType: String? = null,
    val vctValues: List<String>? = null,

    // from trusted_authorities
    val issuerIdentifiers: List<ByteString> = emptyList(),

    val claims: List<RequestedClaim>,
    val claimSets: List<DcqlClaimSet>,

    internal val claimIdToClaim: Map<String, RequestedClaim>
)