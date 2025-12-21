package org.multipaz.testapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.imageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import org.multipaz.compose.digitalcredentials.CredentialManagerPresentmentActivity
import org.multipaz.testapp.ui.AppTheme

class TestAppCredentialManagerPresentmentActivity: CredentialManagerPresentmentActivity() {
    override suspend fun getSettings(): Settings {
        val app = App.Companion.getInstance()
        app.init()
        val imageLoader = ImageLoader.Builder(applicationContext).components {
            add(KtorNetworkFetcherFactory(HttpClient(platformHttpClientEngineFactory().create())))
        }.build()

        val stream = assets.open("privilegedUserAgents.json")
        val data = ByteArray(stream.available())
        stream.read(data)
        stream.close()
        val privilegedAllowList = data.decodeToString()

        return Settings(
            appName = platformAppName,
            appIcon = platformAppIcon,
            promptModel = app.promptModel,
            applicationTheme = @Composable { AppTheme(it) },
            documentTypeRepository = app.documentTypeRepository,
            presentmentSource = app.getPresentmentSource(),
            imageLoader = imageLoader,
            privilegedAllowList = privilegedAllowList,
        )
    }
}
