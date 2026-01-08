package org.multipaz.compose.presentment

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.context.initializeApplication
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.presentment.model.PresentmentModel
import org.multipaz.presentment.model.PresentmentSource
import org.multipaz.presentment.model.UriSchemePresentmentMechanism
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger
import java.net.URL
import androidx.core.net.toUri
import coil3.ImageLoader

/**
 * Base class for activity used for credential presentments using URI schemes.
 *
 * Applications should subclass this and include the appropriate stanzas in its manifest
 *
 * See the [MpzCmpWallet](https://github.com/davidz25/MpzCmpWallet) sample for an example.
 */
abstract class UriSchemePresentmentActivity: FragmentActivity() {
    companion object {
        private const val TAG = "UriSchemePresentmentActivity"
    }

    /**
     * Settings provided by the application for specifying what to present.
     *
     * @property appName the application name.
     * @property appIcon the application icon.
     * @property promptModel the [PromptModel] to use.
     * @property applicationTheme the theme to use.
     * @property documentTypeRepository a [DocumentTypeRepository]
     * @property presentmentSource the [PresentmentSource] to use as the source of truth for what to present.
     * @property httpClientEngineFactory the factory for creating the Ktor HTTP client engine (e.g. CIO).
     * @property imageLoader the [ImageLoader] to use.
     */
    data class Settings(
        val appName: String,
        val appIcon: @Composable () -> Painter,
        val promptModel: PromptModel,
        val applicationTheme: @Composable (content: @Composable () -> Unit) -> Unit,
        val documentTypeRepository: DocumentTypeRepository,
        val presentmentSource: PresentmentSource,
        val httpClientEngineFactory: HttpClientEngineFactory<*>,
        val imageLoader: ImageLoader
    )

    /**
     * Must be implemented by the application to specify what to present.
     *
     * @return a [Settings] object.
     */
    abstract suspend fun getSettings(): Settings

    private val presentmentModel = PresentmentModel()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplication(this.applicationContext)
        enableEdgeToEdge()

        window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
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
                CoroutineScope(Dispatchers.Main).launch {
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
        val origin = referrerUrl?.let {
            val url = URL(it)
            "${url.protocol}://${url.host}${if (url.port != -1) ":${url.port}" else ""}"
        }
        presentmentModel.setPromptModel(settings.promptModel)
        try {
            val mechanism = object : UriSchemePresentmentMechanism(
                uri = url,
                origin = origin,
                httpClientEngineFactory = settings.httpClientEngineFactory
            ) {
                override fun openUriInBrowser(uri: String) {
                    // TODO: maybe defer this until the presentment is over...
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            uri.toUri()
                        )
                    )
                }

                override fun close() {
                    Logger.i(TAG, "closing..")
                    finish()
                }
            }
            presentmentModel.reset()
            presentmentModel.setConnecting()
            presentmentModel.setMechanism(mechanism)
        } catch (e: Throwable) {
            Logger.i(TAG, "Error processing request", e)
            e.printStackTrace()
            finish()
            return
        }

        setContent {
            settings.applicationTheme {
                PromptDialogs(settings.promptModel)
                Presentment(
                    appName = settings.appName,
                    appIconPainter = settings.appIcon(),
                    presentmentModel = presentmentModel,
                    presentmentSource = settings.presentmentSource,
                    documentTypeRepository = settings.documentTypeRepository,
                    imageLoader = settings.imageLoader,
                    onPresentmentComplete = { finish() },
                    onlyShowConsentPrompt = true,
                    showCancelAsBack = true
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
