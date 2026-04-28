package org.multipaz.compose.digitalcredentials

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.credentials.registry.provider.selectedEntryId
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.prompt.PresentmentActivityContent
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.context.initializeApplication
import org.multipaz.digitalcredentials.getAppOrigin
import org.multipaz.digitalcredentials.lookupForCredmanId
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.digitalCredentialsPresentment
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.util.Logger

/**
 * Base class for activity used for Android Credential Manager presentments using the W3C Digital Credentials API.
 *
 * Applications should subclass this and include the appropriate stanzas in its manifest
 *
 * See `ComposeWallet` in [Multipaz Samples](https://github.com/openwallet-foundation/multipaz-samples)
 * for an example.
 */
abstract class CredentialManagerPresentmentActivity: FragmentActivity() {
    companion object {
        private const val TAG = "CredentialManagerPresentmentActivity"
    }

    /**
     * Settings provided by the application for specifying what to present.
     *
     * @property source the [PresentmentSource] to use as the source of truth for what to present.
     * @property privilegedAllowList a string containing JSON with an allow-list of privileged browsers/apps
     *   that the applications trusts to provide the correct origin. For the format of the JSON see
     *   [CallingAppInfo.getOrigin()](https://developer.android.com/reference/androidx/credentials/provider/CallingAppInfo#getOrigin(kotlin.String))
     *   in the Android Credential Manager APIs. For an example, see the
     *   [public list of browsers trusted by Google Password Manager](https://gstatic.com/gpm-passkeys-privileged-apps/apps.json).
     */
    class Settings(
        val source: PresentmentSource,
        val privilegedAllowList: String,
    )

    /**
     * Must be implemented by the application to specify what to present.
     *
     * @return a [Settings] object.
     */
    abstract suspend fun getSettings(): Settings

    private val promptModel = AndroidPromptModel.Builder().apply { addCommonDialogs() }.build()

    val presentmentModel = PresentmentModel()

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
                            startPresentment(settings = settings)
                        }

                        presentmentModel
                    },
                    onFadedIn = { coroutineScope.launch { startChannel.send(Unit) } },
                    onFinish = { _ -> finish() }
                )
            }
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private suspend fun startPresentment(settings: Settings) {
        try {
            val credentialRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)!!

            val callingAppInfo = credentialRequest.callingAppInfo
            val callingPackageName = callingAppInfo.packageName
            val origin = callingAppInfo.getOrigin(settings.privilegedAllowList)
                ?: getAppOrigin(callingAppInfo.signingInfoCompat.signingCertificateHistory[0].toByteArray())
            val option = credentialRequest.credentialOptions[0] as GetDigitalCredentialOption
            val json = Json.parseToJsonElement(option.requestJson).jsonObject
            Logger.iJson(TAG, "Request Json", json)
            val selectionInfo = getSetSelection(credentialRequest)
                ?: getSelection(credentialRequest)
                ?:  throw IllegalStateException("Unable to get credman selection")
            Logger.i(TAG, "SelectionInfo: $selectionInfo")

            val documents = selectionInfo.documentIds.map {
                settings.source.documentStore.lookupForCredmanId(it)
                    ?: throw Error("No registered document for document ID $it")
            }
            // Find request matching the protocol for the selected entry...
            val requestForSelectedEntry = json["requests"]!!.jsonArray.find {
                (it as JsonObject)["protocol"]!!.jsonPrimitive.content == selectionInfo.protocol
            }!!.jsonObject
            presentmentModel.setWaitingForUserInput()
            val response = digitalCredentialsPresentment(
                protocol = requestForSelectedEntry["protocol"]!!.jsonPrimitive.content,
                data = requestForSelectedEntry["data"]!!.jsonObject,
                appId = callingPackageName,
                origin = origin,
                preselectedDocuments = documents,
                source = settings.source,
                onDocumentsInFocus = { documents ->
                    presentmentModel.setDocumentsSelected(documents)
                }
            )
            val jsonString = Json.encodeToString(response)
            Logger.i(TAG, "Size of JSON response: ${jsonString.length} bytes")
            val resultData = Intent()
            val credentialManagerResponse = GetCredentialResponse(DigitalCredential(jsonString))
            PendingIntentHandler.setGetCredentialResponse(resultData, credentialManagerResponse)
            setResult(RESULT_OK, resultData)
            presentmentModel.setCompleted(error = null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.i(TAG, "Error processing request", e)
            val resultData = Intent()
            val credentialManagerException = GetCredentialCustomException(
                type = "org.multipaz.Error",
                errorMessage = e.message
            )
            PendingIntentHandler.setGetCredentialException(resultData, credentialManagerException)
            setResult(RESULT_OK, resultData)
            presentmentModel.setCompleted(error = e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

private data class SelectionInfo(
    val protocol: String,
    val documentIds: List<String>
)

private fun getSetSelection(request: ProviderGetCredentialRequest): SelectionInfo? {
    // TODO: replace sourceBundle peeking when we upgrade to a new Credman Jetpack..
    val setId = request.sourceBundle!!.getString("androidx.credentials.registry.provider.extra.CREDENTIAL_SET_ID")
        ?: return null
    val setElementLength = request.sourceBundle!!.getInt(
        "androidx.credentials.registry.provider.extra.CREDENTIAL_SET_ELEMENT_LENGTH", 0
    )
    val credIds = mutableListOf<String>()
    for (n in 0 until setElementLength) {
        val credId = request.sourceBundle!!.getString(
            "androidx.credentials.registry.provider.extra.CREDENTIAL_SET_ELEMENT_ID_$n"
        ) ?: return null
        val splits = credId.split(" ")
        require(splits.size == 3) { "Expected CredId $n to have three parts, got ${splits.size}" }
        credIds.add(splits[2])
    }
    val splits = setId.split(" ")
    require(splits.size == 2) { "Expected SetId to have two parts, got ${splits.size}" }
    return SelectionInfo(
        protocol = splits[1],
        documentIds = credIds
    )
}

private fun getSelection(request: ProviderGetCredentialRequest): SelectionInfo? {
    val selectedEntryId = request.selectedEntryId
        ?: throw IllegalStateException("selectedEntryId is null")
    val splits = selectedEntryId.split(" ")
    require(splits.size == 3) { "Expected CredId to have three parts, got ${splits.size}" }
    return SelectionInfo(
        protocol = splits[1],
        documentIds = listOf(splits[2])
    )
}
