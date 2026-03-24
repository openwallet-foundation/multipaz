package org.multipaz.testapp

import org.multipaz.compose.digitalcredentials.CredentialManagerPresentmentActivity

class TestAppCredentialManagerPresentmentActivity: CredentialManagerPresentmentActivity() {
    override suspend fun getSettings(): Settings {
        val app = App.getInstance()
        app.initialize()

        val stream = assets.open("privilegedUserAgents.json")
        val data = ByteArray(stream.available())
        stream.read(data)
        stream.close()
        val privilegedAllowList = data.decodeToString()

        return Settings(
            source = app.getPresentmentSource(),
            privilegedAllowList = privilegedAllowList,
        )
    }
}
