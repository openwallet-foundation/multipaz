package org.multipaz.testapp

import org.multipaz.compose.presentment.UriSchemePresentmentActivity

class TestAppUriSchemePresentmentActivity: UriSchemePresentmentActivity() {
    override suspend fun getSettings(): Settings {
        val app = App.getInstance()
        app.initialize()
        return Settings(
            source = app.getPresentmentSource(),
            httpClientEngineFactory = TestAppConfiguration.httpClientEngineFactory,
        )
    }
}
