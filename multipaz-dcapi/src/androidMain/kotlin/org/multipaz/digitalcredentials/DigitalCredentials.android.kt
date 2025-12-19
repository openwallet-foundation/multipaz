package org.multipaz.digitalcredentials

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.credentials.CredentialManager
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.google.android.gms.identitycredentials.IdentityCredentialManager
import com.google.android.gms.identitycredentials.RegistrationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.claim.organizeByNamespace
import org.multipaz.context.AndroidUiContext
import org.multipaz.context.applicationContext
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.presentment.model.DigitalCredentialsPresentmentMechanism
import org.multipaz.presentment.model.PresentmentModel
import org.multipaz.presentment.model.PresentmentSource
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.util.Logger
import org.multipaz.util.toBase64
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

private const val TAG = "DigitalCredentials"

private class RegistrationData (
    val documentStore: DocumentStore,
    val documentTypeRepository: DocumentTypeRepository,
    val listeningJob: Job,
)

private val exportedStores = mutableMapOf<DocumentStore, RegistrationData>()

private fun getAttributeForJsonClaim(
    documentTypeRepository: DocumentTypeRepository,
    vct: String,
    path: JsonArray,
): DocumentAttribute? {
    val documentType = documentTypeRepository.getDocumentTypeForJson(vct)
    if (documentType != null) {
        val flattenedPath = path.joinToString(".") { it.jsonPrimitive.content }
        return documentType.jsonDocumentType?.claims?.get(flattenedPath)
    }
    return null
}

private fun getDataElementDisplayName(
    documentTypeRepository: DocumentTypeRepository,
    docTypeName: String,
    nameSpaceName: String,
    dataElementName: String
): String {
    val documentType = documentTypeRepository.getDocumentTypeForMdoc(docTypeName)
    if (documentType != null) {
        val mdocDataElement = documentType.mdocDocumentType!!
            .namespaces[nameSpaceName]?.dataElements?.get(dataElementName)
        if (mdocDataElement != null) {
            return mdocDataElement.attribute.displayName
        }
    }
    return dataElementName
}

private suspend fun updateCredman() {
    val appInfo = applicationContext.applicationInfo
    val appName = if (appInfo.labelRes != 0) {
        applicationContext.getString(appInfo.labelRes)
    } else {
        appInfo.nonLocalizedLabel.toString()
    }

    val credentialDatabase = calculateCredentialDatabase(
        appName = appName,
        selectedProtocols = selectedProtocols,
        stores = exportedStores.values.map { Pair(it.documentStore, it.documentTypeRepository) }
    )

    val credentialDatabaseCbor = Cbor.encode(credentialDatabase)
    //Logger.iCbor(TAG, "credentialDatabaseCbor", credentialDatabaseCbor)
    val client = IdentityCredentialManager.getClient(applicationContext)
    client.registerCredentials(
        RegistrationRequest(
            credentials = credentialDatabaseCbor,
            matcher = loadMatcher(applicationContext),
            type = "com.credman.IdentityCredential",
            requestType = "",
            protocolTypes = emptyList(),
        )
    )
        .addOnSuccessListener { Logger.i(TAG, "CredMan registry succeeded (old)") }
        .addOnFailureListener { Logger.i(TAG, "CredMan registry failed  (old) $it") }
    client.registerCredentials(
        RegistrationRequest(
            credentials = credentialDatabaseCbor,
            matcher = loadMatcher(applicationContext),
            type = "androidx.credentials.TYPE_DIGITAL_CREDENTIAL",
            requestType = "",
            protocolTypes = emptyList(),
        )
    )
        .addOnSuccessListener { Logger.i(TAG, "CredMan registry succeeded") }
        .addOnFailureListener { Logger.i(TAG, "CredMan registry failed $it") }
}

internal suspend fun calculateCredentialDatabase(
    appName: String,
    selectedProtocols: Set<String>,
    stores: List<Pair<DocumentStore, DocumentTypeRepository>>
): DataItem {
    val credentialsBuilder = CborArray.builder()
    for ((documentStore, documentTypeRepository) in stores) {
        // We sort on displayName b/c otherwise it's sorted on Document.identifier which can be unpredictable
        val documents = documentStore.listDocuments()
            .mapNotNull { documentStore.lookupDocument(it) }
            .sortedBy { it.metadata.displayName ?: it.identifier }
        for (document in documents) {
            val mdocCredential = document.getCertifiedCredentials().find { it is MdocCredential }
            if (mdocCredential != null) {
                credentialsBuilder.add(
                    exportMdocCredential(
                        appName = appName,
                        document = document,
                        credential = mdocCredential as MdocCredential,
                        documentTypeRepository = documentTypeRepository
                    )
                )
            }

            val sdJwtVcCredential = document.getCertifiedCredentials().find { it is SdJwtVcCredential }
            if (sdJwtVcCredential != null) {
                credentialsBuilder.add(
                    exportSdJwtVcCredential(
                        appName = appName,
                        document = document,
                        credential = sdJwtVcCredential as SdJwtVcCredential,
                        documentTypeRepository = documentTypeRepository
                    )
                )
            }
        }
    }

    val credentialDatabase = buildCborMap {
        putCborArray("protocols") { selectedProtocols.forEach { add(it) } }
        put("credentials", credentialsBuilder.end().build())
    }
    return credentialDatabase
}

private suspend fun exportMdocCredential(
    appName: String,
    document: Document,
    credential: MdocCredential,
    documentTypeRepository: DocumentTypeRepository
): DataItem {
    val credentialType = documentTypeRepository.getDocumentTypeForMdoc(credential.docType)

    val documentMetadata = document.metadata
    val cardArt = documentMetadata.cardArt?.toByteArray()
    val displayName = documentMetadata.displayName ?: "Unnamed Credential"
    val displayNameSub = documentMetadata.typeDisplayName ?: "Unknown Credential Type"

    val cardArtResized = resizedCardArt(cardArt)

    return buildCborMap {
        put("title", displayName)
        put("subtitle", displayNameSub)
        put("bitmap", cardArtResized ?: byteArrayOf())
        putCborMap("mdoc") {
            put("documentId", document.identifier)
            put("docType", credential.docType)
            putCborMap("namespaces") {
                val claims = credential.getClaims(documentTypeRepository)
                for ((namespace, claimsInNamespace) in claims.organizeByNamespace()) {
                    putCborMap(namespace) {
                        for (claim in claimsInNamespace) {
                            val mdocDataElement = credentialType?.mdocDocumentType?.namespaces
                                ?.get(namespace)?.dataElements?.get(claim.dataElementName)
                            val valueString = mdocDataElement
                                ?.renderValue(claim.value)
                                ?: Cbor.toDiagnostics(claim.value)

                            val dataElementDisplayName = getDataElementDisplayName(
                                documentTypeRepository,
                                credential.docType,
                                claim.namespaceName,
                                claim.dataElementName
                            )
                            putCborArray(claim.dataElementName) {
                                add(dataElementDisplayName)
                                add(valueString)
                                // Need the raw value (converted to JSON then converted to a string) for matching but
                                // skip if 128 characters or more since e.g. portrait photos can be quite large...
                                val asString = when (val asJson = claim.value.toJson()) {
                                    is JsonPrimitive -> asJson.content
                                    else -> asJson.toString()
                                }
                                add(asString.let { if (it.length < 128) it else "" })
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun exportSdJwtVcCredential(
    appName: String,
    document: Document,
    credential: SdJwtVcCredential,
    documentTypeRepository: DocumentTypeRepository
): DataItem {
    val documentMetadata = document.metadata
    val cardArt = documentMetadata.cardArt?.toByteArray()
    val displayName = documentMetadata.displayName ?: "Unnamed Credential"
    val displayNameSub = documentMetadata.typeDisplayName ?: "Unknown Credential Type"

    val cardArtResized = resizedCardArt(cardArt)

    return buildCborMap {
        put("title", displayName)
        put("subtitle", displayNameSub)
        put("bitmap", cardArtResized ?: byteArrayOf())
        putCborMap("sdjwt") {
            put("documentId", document.identifier)
            put("vct", credential.vct)
            putCborMap("claims") {
                val claims = credential.getClaimsImpl(documentTypeRepository)
                for (claim in claims) {
                    val claimName = claim.claimPath[0].jsonPrimitive.content
                    val claimAttr = getAttributeForJsonClaim(
                        documentTypeRepository,
                        credential.vct,
                        claim.claimPath,
                    )
                    val claimDisplayName = claimAttr?.displayName ?: claimName
                    putCborArray(claimName) {
                        add(claimDisplayName)
                        add(claim.render())
                        // Need the raw value (converted to a string) for matching but skip if 128
                        // characters or more since e.g. portrait photos can be quite large...
                        val asString = when (claim.value) {
                            is JsonPrimitive -> (claim.value as JsonPrimitive).content
                            else -> claim.value.toString()
                        }
                        add(asString.let { if (it.length < 128) it else "" })
                    }
                    // Our matcher currently combines paths to a single string, using `.` as separator. So do
                    // the same here for all subclaims... yes, we only support a single level of subclaims
                    // right now. In the future we'll modify the matcher to be smarter about things.
                    //
                    if (claim.value is JsonObject) {
                        for ((subClaimIdentifier, subClaimValue) in claim.value) {
                            val subClaimAttr = claimAttr?.embeddedAttributes?.find { it.identifier == subClaimIdentifier }
                            val subClaimDisplayName = subClaimAttr?.displayName ?: subClaimIdentifier
                            putCborArray("$claimName.$subClaimIdentifier") {
                                add(subClaimDisplayName)
                                add(subClaimValue.toString())
                                // Need the raw value (converted to a string) for matching but skip if 128
                                // characters or more since e.g. portrait photos can be quite large...
                                val asString = when (subClaimValue) {
                                    is JsonPrimitive -> subClaimValue.content
                                    else -> subClaimValue.toString()
                                }
                                add(asString.let { if (it.length < 128) it else "" })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resizedCardArt(cardArt: ByteArray?): ByteArray? {
    return BitmapFactory.decodeByteArray(
            cardArt ?: return null,
            0,
            cardArt.size,
            BitmapFactory.Options().also { it.inMutable = true }
        )?.let { bitmap ->
            val dstHeight = 48
            val dstWidth = dstHeight * bitmap.width / bitmap.height
            val scaledIcon = Bitmap.createScaledBitmap(bitmap, dstWidth, dstHeight, true)
            val stream = ByteArrayOutputStream()
            scaledIcon.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val cardArtResized = stream.toByteArray()
            Logger.i(
                TAG,
                "Resized cardart to 48x48, ${cardArt.size} bytes to ${cardArtResized.size} bytes"
            )
            cardArtResized
        }
}

private fun loadMatcher(context: Context): ByteArray {
    val stream = context.assets.open("identitycredentialmatcher.wasm")
    val matcher = ByteArray(stream.available())
    stream.read(matcher)
    stream.close()
    return matcher
}

internal actual val defaultAvailable = true

internal actual val defaultSupportedProtocols: Set<String>
    get() = supportedProtocols

private val supportedProtocols = setOf(
    "openid4vp-v1-signed",
    "openid4vp-v1-unsigned",
    "org-iso-mdoc",
    "openid4vp",
)

internal actual val defaultSelectedProtocols: Set<String>
    get() = selectedProtocols

private var selectedProtocols = supportedProtocols

internal actual suspend fun defaultSetSelectedProtocols(
    protocols: Set<String>
) {
    selectedProtocols = protocols.mapNotNull {
        if (supportedProtocols.contains(it)) {
            it
        } else {
            Logger.w(TAG, "Protocol $it is not supported")
            null
        }
    }.toSet()
    updateCredman()
}

@OptIn(FlowPreview::class)
internal actual suspend fun defaultStartExportingCredentials(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository
) {
    val listeningJob = CoroutineScope(Dispatchers.IO).launch {
        documentStore.eventFlow
            .onEach { event ->
                Logger.i(TAG, "DocumentStore event ${event::class.simpleName} ${event.documentId}")
                try {
                    updateCredman()
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive()
                    Logger.w(TAG, "Exception while updating Credman", e)
                    e.printStackTrace()
                }
            }
    }
    exportedStores.put(documentStore, RegistrationData(
        documentStore = documentStore,
        documentTypeRepository = documentTypeRepository,
        listeningJob = listeningJob,
    ))
    updateCredman()

    // To avoid continually updating Credman when documents are added one after the other, sample
    // only every 10 seconds.
    documentStore.eventFlow
        .sample(10.seconds)
        .onEach { event ->
            Logger.i(TAG, "DocumentStore event ${event::class.simpleName} ${event.documentId}")
            updateCredman()
        }
        .launchIn(CoroutineScope(Dispatchers.IO))
}

internal actual suspend fun defaultStopExportingCredentials(
    documentStore: DocumentStore,
) {
    val registrationData = exportedStores.remove(documentStore)
    if (registrationData == null) {
        return
    }
    registrationData.listeningJob.cancel()
    updateCredman()
}

suspend fun DocumentStore.lookupForCredmanId(credManId: String): Document? {
    return lookupDocument(credManId)
}

@OptIn(ExperimentalDigitalCredentialApi::class)
internal actual suspend fun defaultRequest(request: JsonObject): JsonObject {
    val uiContext = AndroidUiContext.current()
    val credentialManager = CredentialManager.create(applicationContext)
    val requestString = Json.encodeToString(request)
    val digitalCredentialOption = GetDigitalCredentialOption(requestJson = requestString)
    val getCredRequest = GetCredentialRequest(listOf(digitalCredentialOption))
    val result = withContext(Dispatchers.Main) {
        credentialManager.getCredential(
            context = uiContext,
            request = getCredRequest
        )
    }
    val credential = result.credential
    when (credential) {
        is DigitalCredential -> {
            val responseJson = credential.credentialJson
            return Json.decodeFromString<JsonObject>(responseJson)
        }
        else -> {
            // Workaround to make this work with Google Wallet versions not yet switched to the new Credman API
            if (credential.type == DigitalCredential.TYPE_DIGITAL_CREDENTIAL) {
                val protocolType = credential.data.getString("protocolType")
                val identityToken = credential.data.getByteArray("identityToken")
                if (protocolType != null && identityToken != null) {
                    val responseJson = buildJsonObject {
                        put("protocol", protocolType)
                        put("data", Json.decodeFromString<JsonObject>(identityToken.decodeToString()))
                    }
                    return responseJson
                }
            }
            throw IllegalStateException("Unexpected result type of credential ${credential.type}")
        }
    }
}

/**
 * Takes a ProviderGetCredentialRequest and sets a presentmentModel mechanism if successful
 *
 * This may throw for example when documents that was registered is no longer registered
 *
 * @param credentialRequest the [ProviderGetCredentialRequest] parsed from [Intent]
 * @param getSelectedEntryId the extension function coming from the registry provider sdk
 * @param privilegedAllowlist the list of privileged apps
 * @param presentmentSource the [PresentmentSource] where we set what we want to present
 * @param presentmentModel the [PresentmentModel] where we set the state of our presentment
 * @param setResult here you can set the resultCode and data for your Activity
 */
@OptIn(ExperimentalDigitalCredentialApi::class)
@Throws(IllegalArgumentException::class, IllegalStateException::class, SerializationException::class)
suspend fun setPresentmentModelMechanism(
    credentialRequest: ProviderGetCredentialRequest,
    getSelectedEntryId: ProviderGetCredentialRequest.() -> String?,
    privilegedAllowlist: String,
    presentmentSource: PresentmentSource,
    presentmentModel: PresentmentModel,
    setResult: (Int, Intent) -> Unit,
) {
    val callingAppInfo = credentialRequest.callingAppInfo
    val callingPackageName = callingAppInfo.packageName
    val origin = callingAppInfo.getOrigin(privilegedAllowlist)
        ?: getAppOrigin(callingAppInfo.signingInfoCompat.signingCertificateHistory[0].toByteArray())
    val option = credentialRequest.credentialOptions[0] as GetDigitalCredentialOption
    val json = Json.parseToJsonElement(option.requestJson).jsonObject
    Logger.iJson(TAG, "Request Json", json)
    val selectionInfo = try {
        getSetSelection(credentialRequest)
            ?: getSelection(getSelectedEntryId(credentialRequest))
    } catch (_: IllegalArgumentException) {
        throw IllegalStateException("Unable to get credman selection")
    }
    Logger.i(TAG, "SelectionInfo: $selectionInfo")

    val documents = selectionInfo.documentIds.map {
        presentmentSource.documentStore.lookupForCredmanId(it)
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
}

private data class SelectionInfo(
    val protocol: String,
    val documentIds: List<String>
)

@Throws(IllegalArgumentException::class)
private fun getSetSelection(request: ProviderGetCredentialRequest): SelectionInfo? {
    // TODO: replace sourceBundle peeking when we upgrade to a new Credman Jetpack..
    val setId =
        request.sourceBundle!!.getString("androidx.credentials.registry.provider.extra.CREDENTIAL_SET_ID")
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

@Throws(IllegalArgumentException::class)
private fun getSelection(selectedEntryId: String?): SelectionInfo {
    require(selectedEntryId != null) { "Expected selectedEntryId to not be null" }
    val splits = selectedEntryId.split(" ")
    require(splits.size == 3) { "Expected CredId to have three parts, got ${splits.size}" }
    return SelectionInfo(
        protocol = splits[1],
        documentIds = listOf(splits[2])
    )
}

/**
 * Calculates the origin for a native Android app.
 *
 * This is implemented in accordance with the guidance at https://developer.android.com/identity/sign-in/credential-manager#verify-origin
 *
 * @param appSigningInfo the bytes of the signing information for the application, typically obtained from
 *   a [android.content.pm.Signature] object.
 * @return the origin string of the form "android:apk-key-hash:<sha256_hash-of-apk-signing-cert>"
 */
fun getAppOrigin(appSigningInfo: ByteArray): String {
    return "android:apk-key-hash:${Crypto.digest(Algorithm.SHA256, appSigningInfo).toBase64()}"
}
