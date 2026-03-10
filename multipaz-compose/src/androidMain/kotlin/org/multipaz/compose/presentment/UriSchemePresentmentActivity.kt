package org.multipaz.compose.presentment

import kotlinx.coroutines.CancellationException
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.prompt.PresentmentActivityContent
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.context.initializeApplication
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.uriSchemePresentment
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.util.Logger
import java.net.URL

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

    val presentmentModel = PresentmentModel()

    var openRedirectUri: String? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplication(this.applicationContext)
        enableEdgeToEdge()

        window.isNavigationBarContrastEnforced = false

        val imageLoader = ImageLoader.Builder(applicationContext).components {
            add(KtorNetworkFetcherFactory(HttpClient(Android.create())))
        }.build()

        val startChannel = Channel<Unit>()
        setContent {
            val currentBranding = Branding.Current.collectAsState().value
            currentBranding.theme {
                val coroutineScope = rememberCoroutineScope()

                PromptDialogs(
                    promptModel = promptModel,
                    imageLoader = imageLoader,
                )

                // Only start presentation once we're fully faded in
                PresentmentActivityContent(
                    window = window,
                    getPresentmentModel = {
                        val settings = getSettings()
                        presentmentModel.reset(
                            source = settings.source,
                            preselectedDocuments = emptyList(),
                            showDocumentChooser = null
                        )

                        CoroutineScope(Dispatchers.IO + promptModel).launch {
                            // wait until we're faded in
                            startChannel.receive()

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

                            val appId = if (referrer?.scheme == "android-app") {
                                referrer?.host
                            } else {
                                null
                            }
                            if (url != null) {
                                startPresentment(
                                    url = url,
                                    appId = appId,
                                    referrerUrl = referrerUrl,
                                    settings = settings
                                )
                            }
                        }

                        presentmentModel
                    },
                    onFadedIn = { coroutineScope.launch { startChannel.send(Unit) } },
                    onFinish = { _ ->
                        // Open the redirect URI in a browser...
                        openRedirectUri?.let {
                            startActivity(Intent(Intent.ACTION_VIEW, it.toUri()))
                        }
                        finish()
                    }
                )
            }
        }
    }

    private suspend fun startPresentment(
        url: String,
        appId: String?,
        referrerUrl: String?,
        settings: Settings
    ) {
        val origin = referrerUrl?.let {
            val url = URL(it)
            "${url.protocol}://${url.host}${if (url.port != -1) ":${url.port}" else ""}"
        }
        try {
            presentmentModel.setWaitingForUserInput()
            openRedirectUri = uriSchemePresentment(
                source = settings.source,
                uri = url,
                appId = appId,
                origin = origin,
                httpClientEngineFactory = settings.httpClientEngineFactory,
                onDocumentsInFocus = { documents ->
                    presentmentModel.setDocumentsSelected(documents)
                }
            )
            presentmentModel.setCompleted(error = null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.i(TAG, "Error processing request", e)
            presentmentModel.setCompleted(error = e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
