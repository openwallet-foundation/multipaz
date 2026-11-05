package org.multipaz.compose.digitalcredentials

import android.content.Intent
import android.os.Bundle
import androidx.credentials.CreateDigitalCredentialRequest
import androidx.credentials.CreateDigitalCredentialResponse
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.context.initializeApplication
import org.multipaz.digitalcredentials.CredmanCredentialOffer
import org.multipaz.digitalcredentials.getAppOrigin
import org.multipaz.util.Logger

/**
 * Base class for the activity used for provisioning credentials offered through Android Credential
 * Manager, i.e. by websites calling `navigator.credentials.create()` of the W3C Digital Credentials API
 * with an OpenID4VCI credential offer.
 *
 * Applications should subclass this, register for credential offers with
 * [org.multipaz.digitalcredentials.registerForCredmanCredentialOffers], and declare the activity in their
 * manifest with an intent filter for the `androidx.credentials.registry.provider.action.CREATE_CREDENTIAL`
 * action.
 */
abstract class CredentialManagerProvisioningActivity : FragmentActivity() {
    companion object {
        private const val TAG = "CredentialManagerProvisioningActivity"

        // Where the request JSON is if Credential Manager doesn't hand over a `CreateDigitalCredentialRequest`.
        private const val BUNDLE_KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
    }

    /**
     * The outcome of handling a [CredmanCredentialOffer] in [handleCredentialOffer].
     */
    sealed class Result {
        /**
         * Provisioning succeeded and a response should be returned to the calling application.
         */
        data object Success : Result()

        /**
         * Provisioning was canceled by the user.
         *
         * @property message optional cancellation message.
         */
        data class Canceled(
            val message: String? = null,
        ) : Result()

        /**
         * Provisioning failed with an error.
         *
         * @property message optional error message.
         */
        data class Error(
            val message: String? = null,
        ) : Result()
    }

    /**
     * Must be implemented by the application to provide the JSON allow-list of privileged browsers
     * that the application trusts to provide the correct origin, see
     * [CredentialManagerPresentmentActivity.Settings.privilegedAllowList].
     *
     * @return a string containing the privileged allow-list JSON.
     */
    abstract suspend fun getPrivilegedAllowList(): String

    /**
     * Must be implemented by the application to handle a [CredmanCredentialOffer] from [origin].
     *
     * Implementations should run the provisioning flow and suspend until provisioning (including
     * any completion animations) has finished.
     *
     * @param origin the origin of the calling application or website.
     * @param offer the credential offer extracted from the request.
     * @return the [Result] of handling the credential offer.
     */
    abstract suspend fun handleCredentialOffer(
        origin: String,
        offer: CredmanCredentialOffer,
    ): Result

    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplication(this.applicationContext)

        lifecycleScope.launch {
            processRequest()
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private suspend fun processRequest() {
        try {
            val privilegedAllowList = getPrivilegedAllowList()
            val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
                ?: throw IllegalStateException("No Credential Manager request in intent")
            val callingAppInfo = request.callingAppInfo
            val origin = callingAppInfo.getOrigin(privilegedAllowList)
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
            when (val result = handleCredentialOffer(origin, offer)) {
                is Result.Success -> finishWithResponse(offer)
                is Result.Canceled -> finishWithException(
                    CreateCredentialCancellationException(result.message)
                )
                is Result.Error -> finishWithException(
                    CreateCredentialUnknownException(result.message)
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "Error processing request", e)
            finishWithException(CreateCredentialUnknownException(e.message))
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    private fun finishWithResponse(offer: CredmanCredentialOffer) {
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
