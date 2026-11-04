package org.multipaz.provisioning.openid4vci

import org.multipaz.cbor.annotation.CborSerializable

/**
 * Data structure that holds OpenID4VCI session authorization data.
 *
 * This data enables the client to fetch more credentials from the issuing server. This object
 * is serialized and written to the disk, so be careful changing its schema!
 */
@CborSerializable(schemaHash = "tSadM4JcWvdA3nqneHBRndJAVEbWdmRFofbJYGqORLA")
internal class OpenID4VCIAuthorizationData(
    val issuerUri: String,
    var configurationId: String,
    val secureAreaId: String,
    val authorizationServer: String?,
    var refreshToken: String? = null,
    var dpopKeyAlias: String? = null,
    var walletAttestationKeyAlias: String? = null,
    var walletAttestation: String? = null,
    var type: String = "openid4vci"
) {
    companion object
}