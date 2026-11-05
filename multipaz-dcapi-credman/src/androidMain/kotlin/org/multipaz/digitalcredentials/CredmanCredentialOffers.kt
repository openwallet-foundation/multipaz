package org.multipaz.digitalcredentials

import android.content.Context
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import com.google.android.gms.identitycredentials.IdentityCredentialManager
import com.google.android.gms.identitycredentials.RegisterCreationOptionsRequest
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
import org.multipaz.cbor.putCborArray
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
         * Finds the credential offer in a request received from Credential Manager.
         *
         * This returns the first request whose data has a `credential_issuer` and a non-empty
         * `credential_configuration_ids`, in the same way the issuance matcher decides whether to
         * show the application to the user.
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
    icon: ByteArray?,
    protocols: Set<String>,
): ByteArray = Cbor.encode(
    buildCborMap {
        put("title", title)
        subtitle?.let { put("subtitle", it) }
        icon?.let { put("icon", Bstr(it)) }
        putCborArray("protocols") {
            for (protocol in protocols) {
                add(protocol)
            }
        }
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
 * To unregister, call this function with an empty set for [protocols].
 *
 * @param title the title of the entry, e.g. the name of the application.
 * @param subtitle the subtitle of the entry or `null`.
 * @param icon the icon of the entry, as a PNG, or `null`.
 * @param protocols the set of protocol identifiers to match, e.g. `setOf("openid4vci-v1")`, or
 *   `emptySet()` to unregister.
 * @param context the [Context] to use.
 */
@OptIn(ExperimentalDigitalCredentialApi::class)
suspend fun registerForCredmanCredentialOffers(
    title: String,
    subtitle: String?,
    icon: ByteArray?,
    protocols: Set<String>,
    context: Context = applicationContext
) {
    val request = RegisterCreationOptionsRequest(
        buildCredmanCreationOptions(title, subtitle, icon, protocols),
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
