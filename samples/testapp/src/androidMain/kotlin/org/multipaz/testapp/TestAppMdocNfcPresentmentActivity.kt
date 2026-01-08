package org.multipaz.testapp

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import org.jetbrains.compose.resources.painterResource
import org.multipaz.compose.mdoc.MdocNfcPresentmentActivity
import org.multipaz.testapp.ui.AppTheme

class TestAppMdocNfcPresentmentActivity : MdocNfcPresentmentActivity() {
    override suspend fun getSettings(): Settings {
        val app = App.getInstance()
        app.initialize()
        val imageLoader = ImageLoader.Builder(applicationContext).components {
            add(KtorNetworkFetcherFactory(HttpClient(TestAppConfiguration.httpClientEngineFactory.create())))
        }.build()
        return Settings(
            appName = TestAppConfiguration.appName,
            appIcon = @Composable { painterResource(TestAppConfiguration.appIcon) },
            promptModel = app.promptModel,
            applicationTheme = @Composable { AppTheme(it) },
            documentTypeRepository = app.documentTypeRepository,
            presentmentSource = app.getPresentmentSource(),
            imageLoader = imageLoader
        )
    }
}