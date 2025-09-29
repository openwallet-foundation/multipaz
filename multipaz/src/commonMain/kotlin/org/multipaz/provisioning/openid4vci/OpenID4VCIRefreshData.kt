package org.multipaz.provisioning.openid4vci

import org.multipaz.cbor.annotation.CborSerializable
import kotlin.time.Instant

@CborSerializable
internal data class OpenID4VCIRefreshData(
    val walletAttestation: String,
    val secureAreaId: String,
    val walletAttestationKeyAlias: String,
    val refreshToken: String,
    val expiration: Instant
)