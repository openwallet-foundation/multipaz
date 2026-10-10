// Reference: references/openid4vp.md
// Pinned upstream sample anchor (not assumed local):
// samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/TestAppUriSchemePresentmentActivity.kt

import org.multipaz.compose.presentment.UriSchemePresentmentActivity

class YourUriSchemePresentmentActivity : UriSchemePresentmentActivity() {
    override suspend fun getSettings(): Settings {
        return Settings(
            source = appPresentmentSource(),
            httpClientEngineFactory = appHttpClientEngineFactory()
        )
    }
}
