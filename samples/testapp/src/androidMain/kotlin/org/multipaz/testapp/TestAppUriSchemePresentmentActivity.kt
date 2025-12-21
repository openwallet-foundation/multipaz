package org.multipaz.testapp

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import org.multipaz.compose.presentment.UriSchemePresentmentActivity
import org.multipaz.testapp.ui.AppTheme

class TestAppUriSchemePresentmentActivity: UriSchemePresentmentActivity() {
    override suspend fun getSettings(): Settings {
        val app = App.Companion.getInstance()
        app.init()
        val imageLoader = ImageLoader.Builder(applicationContext).components {
            add(KtorNetworkFetcherFactory(HttpClient(platformHttpClientEngineFactory().create())))
        }.build()
        return Settings(
            appName = platformAppName,
            appIcon = platformAppIcon,
            promptModel = app.promptModel,
            applicationTheme = @Composable { AppTheme(it) },
            documentTypeRepository = app.documentTypeRepository,
            presentmentSource = app.getPresentmentSource(),
            httpClientEngineFactory = platformHttpClientEngineFactory(),
            imageLoader = imageLoader
        )
    }
}
