package org.multipaz.testapp

import android.content.Intent
import org.multipaz.compose.digitalcredentials.CredentialManagerProvisioningActivity
import org.multipaz.digitalcredentials.CredmanCredentialOffer

class TestAppCredentialManagerProvisioningActivity : CredentialManagerProvisioningActivity() {
    override suspend fun getPrivilegedAllowList(): String {
        return assets.open("privilegedUserAgents.json").use { it.readBytes() }.decodeToString()
    }

    override suspend fun handleCredentialOffer(
        origin: String,
        offer: CredmanCredentialOffer,
    ): Result {
        val app = App.getInstance()
        app.initialize()
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(mainIntent)
        val document = app.handleCredentialOffer(offer.offerUri)
            ?: return Result.Canceled("Credential not provisioned")
        return Result.Success(returnToCallingApp = true)
    }
}
