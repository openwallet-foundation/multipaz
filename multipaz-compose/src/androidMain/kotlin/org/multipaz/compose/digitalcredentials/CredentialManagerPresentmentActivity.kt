package org.multipaz.compose.digitalcredentials

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.core.graphics.drawable.toDrawable
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.credentials.registry.provider.selectedEntryId
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.multipaz.compose.presentment.Presentment
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.context.initializeApplication
import org.multipaz.digitalcredentials.getAppOrigin
import org.multipaz.digitalcredentials.lookupForCredmanId
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.presentment.model.DigitalCredentialsPresentmentMechanism
import org.multipaz.presentment.model.PresentmentModel
import org.multipaz.presentment.model.PresentmentSource
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger
import java.lang.IllegalStateException

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
                settings.presentmentSource.documentStore.lookupForCredmanId(it)
                    ?: throw Error("No registered document for document ID $it")
            }
            // Find request matching the protocol for the selected entry...
            val requestForSelectedEntry = json["requests"]!!.jsonArray.find {
                (it as JsonObject)["protocol"]!!.jsonPrimitive.content == selectionInfo.protocol
            }!!.jsonObject
            val mechanism = object : DigitalCredentialsPresentmentMechanism(
                appId = callingPackageName,
                origin = origin,
                protocol = requestForSelectedEntry["protocol"]!!.jsonPrimitive.content,
                data = requestForSelectedEntry["data"]!!.jsonObject,
                preselectedDocuments = documents
            ) {
                override fun sendResponse(
                    protocol: String,
                    data: JsonObject
                ) {
                    val resultData = Intent()
                    val json = Json.encodeToString(
                        buildJsonObject {
                            put("protocol", protocol)
                            put("data", data)
                        }
                    )
                    Logger.i(TAG, "Size of JSON response for protocol $protocol: ${json.length} bytes")
                    val response = GetCredentialResponse(DigitalCredential(json))
                    PendingIntentHandler.setGetCredentialResponse(
                        resultData,
                        response
                    )
                    setResult(RESULT_OK, resultData)
                }

                override fun close() {
                    Logger.i(TAG, "close")
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
