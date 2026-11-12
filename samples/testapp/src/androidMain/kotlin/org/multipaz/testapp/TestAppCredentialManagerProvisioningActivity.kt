package org.multipaz.testapp

import android.app.ActivityManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CompletableDeferred
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.digitalcredentials.CredentialManagerProvisioningActivity
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.compose.provisioning.ProvisioningBottomSheet
import org.multipaz.digitalcredentials.CredmanCredentialOffer
import org.multipaz.prompt.AndroidPromptModel

/**
 * The credential offer as an `openid-credential-offer://` URI, as accepted by
 * [org.multipaz.provisioning.ProvisioningModel.launchOpenID4VCIProvisioning].
 */
val CredmanCredentialOffer.offerUri: String
    get() = "openid-credential-offer://?credential_offer=" + offer.toString().encodeURLParameter()

class TestAppCredentialManagerProvisioningActivity : CredentialManagerProvisioningActivity() {
    private val promptModel = AndroidPromptModel.Builder().apply { addCommonDialogs() }.build()

    override suspend fun getPrivilegedAllowList(): String {
        return assets.open("privilegedUserAgents.json").use { it.readBytes() }.decodeToString()
    }

    override suspend fun handleCredentialOffer(
        origin: String,
        offer: CredmanCredentialOffer,
    ): Result {
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false

        val app = App.getInstance()
        app.initialize()
        val provisioningModel = app.createProvisioningModel(promptModel)
        val clientPreferences = app.provisioningSupport.getOpenID4VCIClientPreferences()
        val backend = app.provisioningSupport.getOpenID4VCIBackend()
        provisioningModel.launchOpenID4VCIProvisioning(
            offerUri = offer.offerUri,
            clientPreferences = clientPreferences,
            backend = backend,
        )

        val imageLoader = ImageLoader.Builder(applicationContext).components {
            add(KtorNetworkFetcherFactory(HttpClient(Android.create())))
        }.build()

        val result = CompletableDeferred<Result>()
        setContent {
            val currentBranding = Branding.Current.collectAsState().value
            currentBranding.theme {
                PromptDialogs(
                    promptModel = promptModel,
                    imageLoader = imageLoader,
                )
                ProvisioningBottomSheet(
                    provisioningModel = provisioningModel,
                    waitForRedirectLinkInvocation = { state ->
                        app.provisioningSupport.waitForAppLinkInvocation(state).also {
                            getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0)
                        }
                    },
                    clientPreferences = CompletableDeferred(clientPreferences),
                    backend = CompletableDeferred(backend),
                    onFinishedProvisioning = { document, _ ->
                        if (document != null) {
                            result.complete(Result.Success)
                        } else {
                            result.complete(Result.Canceled("Credential not provisioned"))
                        }
                    }
                )
            }
        }
        return result.await()
    }
}
