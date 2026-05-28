package org.multipaz.utopia.issuer

import org.multipaz.cbor.DataItem
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat

/**
 * Optional post-issuance hook. Most orgs register [NoOp].
 *
 * Parallels the verifier-side `VerifierAssistant`. Modelled as an interface (not a lambda)
 * because audit requirements tend to grow sibling methods (e.g. `onAuthorize`,
 * `onTokenRefresh`) over time.
 */
interface IssuerAssistant {
    /**
     * Invoked after a credential is minted. The wallet has already received the credential
     * by the time this fires; failures are logged, not surfaced to the wallet.
     *
     * @param systemOfRecordData the identity data the credential was minted from
     * @param credentialId the issuer-side identifier of the minted credential
     * @param format the format of the minted credential
     */
    suspend fun onIssuance(
        systemOfRecordData: DataItem,
        credentialId: CredentialId,
        format: CredentialFormat,
    )

    object NoOp : IssuerAssistant {
        override suspend fun onIssuance(
            systemOfRecordData: DataItem,
            credentialId: CredentialId,
            format: CredentialFormat,
        ) {}
    }
}
