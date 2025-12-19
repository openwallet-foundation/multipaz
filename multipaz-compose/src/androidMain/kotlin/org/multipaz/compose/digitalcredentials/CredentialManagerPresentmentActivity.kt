package org.multipaz.compose.digitalcredentials

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.core.graphics.drawable.toDrawable
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.credentials.registry.provider.selectedEntryId
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.multipaz.compose.presentment.Presentment
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.context.initializeApplication
import org.multipaz.digitalcredentials.setPresentmentModelMechanism
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.presentment.model.PresentmentModel
import org.multipaz.presentment.model.PresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger

/**
 * Base class for activity used for Android Credential Manager presentments using the W3C Digital Credentials API.
 *
 * Applications should subclass this and include the appropriate stanzas in its manifest
 *
 * See the [MpzCmpWallet](https://github.com/davidz25/MpzCmpWallet) sample for an example.
 */
abstract class CredentialManagerPresentmentActivity: FragmentActivity() {
    companion object {
        private const val TAG = "CredentialManagerPresentmentActivity"
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
     * @property imageLoader the [ImageLoader] to use.
     * @property privilegedAllowList a string containing JSON with an allow-list of privileged browsers/apps
     *   that the applications trusts to provide the correct origin. For the format of the JSON see
     *   [CallingAppInfo.getOrigin()](https://developer.android.com/reference/androidx/credentials/provider/CallingAppInfo#getOrigin(kotlin.String))
     *   in the Android Credential Manager APIs. For an example, see the
     *   [public list of browsers trusted by Google Password Manager](https://gstatic.com/gpm-passkeys-privileged-apps/apps.json).
     */
    data class Settings(
        val appName: String,
        val appIcon: DrawableResource,
        val promptModel: PromptModel,
        val applicationTheme: @Composable (content: @Composable () -> Unit) -> Unit,
        val documentTypeRepository: DocumentTypeRepository,
        val presentmentSource: PresentmentSource,
        val imageLoader: ImageLoader,
        val privilegedAllowList: String
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

        CoroutineScope(Dispatchers.Main).launch {
            startPresentment(getSettings())
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private suspend fun startPresentment(settings: Settings) {
        presentmentModel.setPromptModel(settings.promptModel)

        try {
            // Extracting credential request here as consumer apps would want to look for different types of intents
            val credentialRequest =
                PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)!!

            setPresentmentModelMechanism(
                credentialRequest,
                ProviderGetCredentialRequest::selectedEntryId,
                settings.privilegedAllowList,
                settings.presentmentSource,
                presentmentModel
            ) { resultCode, data ->
                setResult(resultCode, data)
            }

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
                    appIconPainter = painterResource(settings.appIcon),
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
