package org.multipaz.digitalcredentials

import android.content.Context
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import com.google.android.gms.identitycredentials.IdentityCredentialManager
import com.google.android.gms.identitycredentials.RegisterCreationOptionsRequest
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CredmanCredentialOffers"

private const val ISSUANCE_MATCHER_ASSET = "issuancematcher.wasm"

private const val CREATION_OPTIONS_ID = "openid4vci"

/**
 * A credential offer received through the W3C Digital Credentials API, i.e. from a website calling
 * `navigator.credentials.create()`.
 *
 * @property protocol the protocol identifier of the request which contained the offer, e.g. `openid4vci-v1`.
 * @property offer the OpenID4VCI credential offer.
 */
data class CredmanCredentialOffer(
    val protocol: String,
    val offer: JsonObject
) {
    /**
     * The credential offer as an `openid-credential-offer://` URI, as accepted by
     * [org.multipaz.provisioning.ProvisioningModel.launchOpenID4VCIProvisioning].
     */
    val offerUri: String
        get() = "openid-credential-offer://?credential_offer=" + offer.toString().encodeURLParameter()

    /**
     * Builds the response to return to the website once the credential has been provisioned.
     *
     * @return the response JSON for a `DigitalCredential` returned by `navigator.credentials.create()`.
     */
    fun buildResponse(): JsonObject = buildJsonObject {
        put("protocol", protocol)
        put("data", JsonObject(emptyMap()))
    }

    companion object {
        /**
         * The protocol identifiers for OpenID4VCI which are accepted in requests.
         *
         * The identifier is not settled yet, so several variants in use are accepted.
         */
        val SUPPORTED_PROTOCOLS: Set<String> = setOf(
            "openid4vci",
            "openid4vci-v1",
            "openid4vci1.0",
            "openid4vci-1.0",
            "openid4vci-1.1",
        )

        /**
         * Finds the credential offer in a request received from Credential Manager.
         *
         * This returns the first request using one of [SUPPORTED_PROTOCOLS] whose data has a
         * `credential_issuer` and a non-empty `credential_configuration_ids`, in the same way the
         * issuance matcher decides whether to show the application to the user.
         *
         * @param request the request JSON, with a `requests` array.
         * @return the offer or `null` if the request doesn't contain a credential offer.
         */
        fun fromRequest(request: JsonObject): CredmanCredentialOffer? {
            val requests = request["requests"] as? JsonArray ?: return null
            for (element in requests) {
                val protocol = ((element as? JsonObject)?.get("protocol") as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                    ?: continue
                if (protocol !in SUPPORTED_PROTOCOLS) {
                    continue
                }
                val data = element["data"] as? JsonObject ?: continue
                val issuer = data["credential_issuer"] as? JsonPrimitive
                val configurationIds = data["credential_configuration_ids"] as? JsonArray
                if (issuer?.isString != true || configurationIds.isNullOrEmpty()) {
                    continue
                }
                return CredmanCredentialOffer(protocol = protocol, offer = data)
            }
            return null
        }
    }
}

/**
 * Builds the data registered along with the issuance matcher, see `issuance_matcher.cpp`.
 */
internal fun buildCredmanCreationOptions(
    title: String,
    subtitle: String?,
    icon: ByteArray?
): ByteArray = Cbor.encode(
    buildCborMap {
        put("title", title)
        subtitle?.let { put("subtitle", it) }
        icon?.let { put("icon", Bstr(it)) }
    }
)

/**
 * Registers the application with Android Credential Manager for receiving OpenID4VCI credential offers
 * from websites using the W3C Digital Credentials API, i.e. calling `navigator.credentials.create()`.
 *
 * When a website offers a credential, Credential Manager shows an entry with [title], [subtitle] and
 * [icon] for the application. If the user picks it, Credential Manager launches the activity of the
 * application handling the `androidx.credentials.registry.provider.action.CREATE_CREDENTIAL` action,
 * typically a subclass of [org.multipaz.compose.digitalcredentials.CredentialManagerProvisioningActivity].
 *
 * Applications should call this when they start, like [DigitalCredentials.register] for presentment.
 *
 * @param title the title of the entry, e.g. the name of the application.
 * @param subtitle the subtitle of the entry or `null`.
 * @param icon the icon of the entry, as a PNG, or `null`.
 * @param context the [Context] to use.
 */
@OptIn(ExperimentalDigitalCredentialApi::class)
suspend fun registerForCredmanCredentialOffers(
    title: String,
    subtitle: String?,
    icon: ByteArray?,
    context: Context = applicationContext
) {
    val request = RegisterCreationOptionsRequest(
        buildCredmanCreationOptions(title, subtitle, icon),
        loadIssuanceMatcher(context),
        DigitalCredential.TYPE_DIGITAL_CREDENTIAL,
        CREATION_OPTIONS_ID,
        ""
    )
    suspendCancellableCoroutine { continuation ->
        IdentityCredentialManager.getClient(context).registerCreationOptions(request)
            .addOnSuccessListener {
                Logger.i(TAG, "Registered for credential offers")
                continuation.resume(Unit)
            }
            .addOnFailureListener { error ->
                Logger.w(TAG, "Registering for credential offers failed", error)
                continuation.resumeWithException(error)
            }
    }
}

private var issuanceMatcher: ByteArray? = null
private val issuanceMatcherLock = Mutex()

private suspend fun loadIssuanceMatcher(context: Context): ByteArray {
    issuanceMatcherLock.withLock {
        issuanceMatcher?.let { return it }
        val matcherBytes = context.assets.open(ISSUANCE_MATCHER_ASSET).use { it.readBytes() }
        issuanceMatcher = matcherBytes
        return matcherBytes
    }
}
