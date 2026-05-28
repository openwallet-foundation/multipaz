package org.multipaz.utopia.organizations.bankofutopia.server

import org.multipaz.cbor.DataItem
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.util.Logger
import org.multipaz.utopia.issuer.IssuerAssistant

/**
 * Bank of Utopia's audit hook. Real deployments would forward the event to an audit log
 * or risk/fraud pipeline; here we just log so the wiring is observable.
 */
class BankOfUtopiaIssuerAssistant : IssuerAssistant {
    override suspend fun onIssuance(
        systemOfRecordData: DataItem,
        credentialId: CredentialId,
        format: CredentialFormat,
    ) {
        Logger.i(TAG, "Issued credential id=$credentialId format=${format.formatId}")
    }

    companion object {
        private const val TAG = "BankOfUtopiaIssuerAssistant"
    }
}
