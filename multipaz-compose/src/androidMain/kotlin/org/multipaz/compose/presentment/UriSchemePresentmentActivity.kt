package org.multipaz.compose.presentment

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.collectAsState
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.context.initializeApplication
import org.multipaz.presentment.PresentmentSource
import org.multipaz.util.Logger
import java.net.URL
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import org.multipaz.compose.branding.Branding
import org.multipaz.presentment.uriSchemePresentment
import org.multipaz.prompt.AndroidPromptModel

/**
 * Base class for activity used for credential presentments using URI schemes.
 *
 * Applications should subclass this and include the appropriate stanzas in its manifest
 *
 * See `ComposeWallet` in [Multipaz Samples](https://github.com/openwallet-foundation/multipaz-samples)
 * for an example.
 */
abstract class UriSchemePresentmentActivity: FragmentActivity() {
    companion object {
        private const val TAG = "UriSchemePresentmentActivity"
    }

    /**
     * Settings provided by the application for specifying what to present.
     *
     * @property source the [PresentmentSource] to use as the source of truth for what to present.
     * @property httpClientEngineFactory the factory for creating the Ktor HTTP client engine (e.g. CIO).
     */
    data class Settings(
        val source: PresentmentSource,
        val httpClientEngineFactory: HttpClientEngineFactory<*>,
    )

    /**
     * Must be implemented by the application to specify what to present.
     *
     * @return a [Settings] object.
     */
    abstract suspend fun getSettings(): Settings

    private val promptModel = AndroidPromptModel.Builder().apply { addCommonDialogs() }.build()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplication(this.applicationContext)
        enableEdgeToEdge()

        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setTranslucent(true)
        }

        if (intent.action == Intent.ACTION_VIEW) {
            val url = intent.dataString
            // This may or may not be set. For example in Chrome it only works
            // if the website is using Referrer-Policy: unsafe-url
            //
            // Reference: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Referrer-Policy
            //
            @Suppress("DEPRECATION")
            var referrerUrl: String? = intent.extras?.get(Intent.EXTRA_REFERRER).toString()
            if (referrerUrl == "null") {
                referrerUrl = null
            }
            if (url != null) {
                CoroutineScope(Dispatchers.Main + promptModel).launch {
                    startPresentment(url, referrerUrl, getSettings())
                }
            }
        }
    }

    private suspend fun startPresentment(
        url: String,
        referrerUrl: String?,
        settings: Settings
    ) {
        val imageLoader = ImageLoader.Builder(applicationContext).components {
            add(KtorNetworkFetcherFactory(HttpClient(Android.create())))
        }.build()

        setContent {
            val currentBranding = Branding.Current.collectAsState().value
            currentBranding.theme {
                PromptDialogs(
                    promptModel = promptModel,
                    imageLoader = imageLoader,
                )
            }
        }

        val origin = referrerUrl?.let {
            val url = URL(it)
            "${url.protocol}://${url.host}${if (url.port != -1) ":${url.port}" else ""}"
        }
        try {
            val redirectUri = uriSchemePresentment(
                source = settings.source,
                uri = url,
                origin = origin,
                httpClientEngineFactory = settings.httpClientEngineFactory,
            )
            // Open the redirect URI in a browser...
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    redirectUri.toUri()
                )
            )
        } catch (e: Throwable) {
            Logger.i(TAG, "Error processing request", e)
        } finally {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
