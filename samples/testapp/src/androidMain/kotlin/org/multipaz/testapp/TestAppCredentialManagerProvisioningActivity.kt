package org.multipaz.testapp

import org.multipaz.compose.digitalcredentials.CredentialManagerProvisioningActivity
import org.multipaz.prompt.PromptModel

class TestAppCredentialManagerProvisioningActivity: CredentialManagerProvisioningActivity() {
    override suspend fun getSettings(promptModel: PromptModel): Settings {
        val app = App.getInstance()
        app.initialize()

        val privilegedAllowList = assets.open("privilegedUserAgents.json").use { it.readBytes() }.decodeToString()

        return Settings(
            provisioningModel = app.createProvisioningModel(promptModel),
            clientPreferences = app.provisioningSupport.getOpenID4VCIClientPreferences(),
            backend = app.provisioningSupport.getOpenID4VCIBackend(),
            waitForRedirectLinkInvocation = { state ->
                app.provisioningSupport.waitForAppLinkInvocation(state)
            },
            privilegedAllowList = privilegedAllowList,
        )
    }
}
