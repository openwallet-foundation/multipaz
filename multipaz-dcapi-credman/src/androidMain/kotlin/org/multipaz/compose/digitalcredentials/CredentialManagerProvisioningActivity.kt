package org.multipaz.compose.digitalcredentials

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.credentials.CreateDigitalCredentialRequest
import androidx.credentials.CreateDigitalCredentialResponse
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.compose.provisioning.ProvisioningBottomSheet
import org.multipaz.context.initializeApplication
import org.multipaz.digitalcredentials.CredmanCredentialOffer
import org.multipaz.digitalcredentials.getAppOrigin
import org.multipaz.document.Document
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptModel
import org.multipaz.provisioning.ProvisioningModel
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.util.Logger

/**
 * Base class for the activity used for provisioning credentials offered through Android Credential
 * Manager, i.e. by websites calling `navigator.credentials.create()` of the W3C Digital Credentials API
 * with an OpenID4VCI credential offer.
 *
 * Applications should subclass this, register for credential offers with
 * [org.multipaz.digitalcredentials.registerForCredmanCredentialOffers], and declare the activity in their
 * manifest with an intent filter for the `androidx.credentials.registry.provider.action.CREATE_CREDENTIAL`
 * action, typically with a translucent theme since the activity only shows a bottom sheet.
 *
 * The activity runs the OpenID4VCI flow for the offer using [ProvisioningBottomSheet] and returns a
 * response to the website once a credential has been provisioned, or an error if provisioning failed
 * or was canceled by the user.
 */
abstract class CredentialManagerProvisioningActivity: FragmentActivity() {
    companion object {
        private const val TAG = "CredentialManagerProvisioningActivity"

        // Where the request JSON is if Credential Manager doesn't hand over a `CreateDigitalCredentialRequest`.
        private const val BUNDLE_KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
    }

    /**
     * Settings provided by the application for provisioning.
     *
     * @property provisioningModel the [ProvisioningModel] to use, created with the [PromptModel] passed to
     *   [getSettings]. It should not be used for other provisioning sessions while this activity runs.
     * @property clientPreferences the [OpenID4VCIClientPreferences] to use.
     * @property backend the [OpenID4VCIBackend] to use.
     * @property waitForRedirectLinkInvocation see [ProvisioningBottomSheet].
     * @property privilegedAllowList a string containing JSON with an allow-list of privileged browsers
     *   that the application trusts to provide the correct origin, see
     *   [CredentialManagerPresentmentActivity.Settings.privilegedAllowList].
     */
    class Settings(
        val provisioningModel: ProvisioningModel,
        val clientPreferences: OpenID4VCIClientPreferences,
        val backend: OpenID4VCIBackend,
        val waitForRedirectLinkInvocation: suspend (state: String) -> String?,
        val privilegedAllowList: String,
    )

    /**
     * Must be implemented by the application to specify how to provision.
     *
     * @param promptModel the [PromptModel] for the UI of this activity, to be used for the
     *   [Settings.provisioningModel].
     * @return a [Settings] object.
     */
    abstract suspend fun getSettings(promptModel: PromptModel): Settings

    private val promptModel = AndroidPromptModel.Builder().apply { addCommonDialogs() }.build()

    private var offer: CredmanCredentialOffer? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplication(this.applicationContext)
        enableEdgeToEdge()

        window.isNavigationBarContrastEnforced = false

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

                val settings = remember { mutableStateOf<Settings?>(null) }
                LaunchedEffect(Unit) {
                    settings.value = startProvisioning()
                }
                settings.value?.let {
                    ProvisioningBottomSheet(
                        provisioningModel = it.provisioningModel,
                        waitForRedirectLinkInvocation = { state ->
                            it.waitForRedirectLinkInvocation(state).also { moveTaskToFront() }
                        },
                        clientPreferences = CompletableDeferred(it.clientPreferences),
                        backend = CompletableDeferred(it.backend),
                        onFinishedProvisioning = { document, _ -> onFinishedProvisioning(document) }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private suspend fun startProvisioning(): Settings? {
        try {
            val settings = getSettings(promptModel)
            val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
                ?: throw IllegalStateException("No Credential Manager request in intent")
            val callingAppInfo = request.callingAppInfo
            val origin = callingAppInfo.getOrigin(settings.privilegedAllowList)
                ?: getAppOrigin(callingAppInfo.signingInfoCompat.signingCertificateHistory[0].toByteArray())
            Logger.i(TAG, "Credential offer from $origin (${callingAppInfo.packageName})")
            val callingRequest = request.callingRequest
            val requestJson = (callingRequest as? CreateDigitalCredentialRequest)?.requestJson
                ?: callingRequest.credentialData.getString(BUNDLE_KEY_REQUEST_JSON)
                ?: throw IllegalStateException("Unexpected request of type ${callingRequest.type}")
            val json = Json.parseToJsonElement(requestJson).jsonObject
            Logger.dJson(TAG, "Request Json", json)
            val offer = CredmanCredentialOffer.fromRequest(json)
                ?: throw IllegalArgumentException("No supported credential offer in request")
            this.offer = offer
            settings.provisioningModel.launchOpenID4VCIProvisioning(
                offerUri = offer.offerUri,
                clientPreferences = settings.clientPreferences,
                backend = settings.backend
            )
            return settings
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error processing request", e)
            finishWithException(CreateCredentialUnknownException(e.message))
            return null
        }
    }

    // The redirect link from the authorization server is delivered to the application's
    // activity handling it, in another task, and the browser used for authorization may
    // also be in a task of its own. Bring back the task of this activity, which is the
    // task of the calling application, so the user returns there when provisioning is done.
    private fun moveTaskToFront() {
        getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0)
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private fun onFinishedProvisioning(document: Document?) {
        val offer = this.offer
        if (document == null || offer == null) {
            finishWithException(CreateCredentialCancellationException("Credential not provisioned"))
            return
        }
        Logger.i(TAG, "Provisioned document ${document.identifier}")
        if (finished) {
            return
        }
        finished = true
        val resultData = Intent()
        PendingIntentHandler.setCreateCredentialResponse(
            resultData,
            CreateDigitalCredentialResponse(Json.encodeToString(offer.buildResponse()))
        )
        setResult(RESULT_OK, resultData)
        finish()
    }

    private fun finishWithException(exception: CreateCredentialException) {
        if (finished) {
            return
        }
        finished = true
        val resultData = Intent()
        PendingIntentHandler.setCreateCredentialException(resultData, exception)
        setResult(RESULT_OK, resultData)
        finish()
    }
}
