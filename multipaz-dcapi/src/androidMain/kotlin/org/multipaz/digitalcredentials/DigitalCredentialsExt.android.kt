package org.multipaz.digitalcredentials

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.credentials.CredentialManager
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetDigitalCredentialOption
import com.google.android.gms.identitycredentials.IdentityCredentialManager
import com.google.android.gms.identitycredentials.RegistrationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.cbor.Cbor
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
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.util.Logger
import org.multipaz.util.toBase64
import java.io.ByteArrayOutputStream
import kotlin.time.Clock

private const val TAG = "DigitalCredentials"

private const val CREDMAN_DB_SHA256_KEY = "org.multipaz.CredmanDbSha256"

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

// Called with lock held
private suspend fun updateCredmanUnlocked(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository,
    selectedProtocols: Set<String>
) {
    val startTime = Clock.System.now()
    val appInfo = applicationContext.applicationInfo
    val appName = if (appInfo.labelRes != 0) {
        applicationContext.getString(appInfo.labelRes)
    } else {
        appInfo.nonLocalizedLabel.toString()
    }

    val credentialDatabase = calculateCredentialDatabase(
        appName = appName,
        documentStore = documentStore,
        documentTypeRepository = documentTypeRepository,
        selectedProtocols = selectedProtocols
    )

    val credentialDatabaseCbor = Cbor.encode(credentialDatabase)
    //Logger.iCbor(TAG, "credentialDatabaseCbor", credentialDatabaseCbor)

    val endTime = Clock.System.now()
    Logger.i(TAG, "Credman database of ${credentialDatabaseCbor.size} bytes " +
            "generated in ${(endTime - startTime).inWholeMilliseconds} ms")

    val credDbSha256 = ByteString(Crypto.digest(Algorithm.SHA256, credentialDatabaseCbor))
    val lastCredDbSha256 = documentStore.getTags().get<ByteString>(CREDMAN_DB_SHA256_KEY)
    if (lastCredDbSha256 != null && credDbSha256 == lastCredDbSha256) {
        Logger.i(TAG, "No change in Credman database since last registration")
        return
    }
    documentStore.getTags().edit {
        set(CREDMAN_DB_SHA256_KEY, credDbSha256)
    }

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
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository,
    selectedProtocols: Set<String>
): DataItem {
    val credentialDatabase = buildCborMap {
        putCborArray("protocols") { selectedProtocols.forEach { add(it) } }
        putCborArray("credentials") {
            val documents = documentStore.listDocuments(sort = true)
            for (document in documents) {
                val mdocCredential = document.getCertifiedCredentials().find { it is MdocCredential }
                if (mdocCredential != null) {
                    add(exportMdocCredential(
                        appName = appName,
                        document = document,
                        credential = mdocCredential as MdocCredential,
                        documentTypeRepository = documentTypeRepository
                    ))
                }
                val sdJwtVcCredential = document.getCertifiedCredentials().find { it is SdJwtVcCredential }
                if (sdJwtVcCredential != null) {
                    add(exportSdJwtVcCredential(
                        appName = appName,
                        document = document,
                        credential = sdJwtVcCredential as SdJwtVcCredential,
                        documentTypeRepository = documentTypeRepository
                    ))
                }
            }
        }
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

    val cardArt = document.cardArt?.toByteArray()
    val displayName = document.displayName ?: "Unnamed Credential"
    val displayNameSub = document.typeDisplayName ?: "Unknown Credential Type"

    val cardArtResized = resizedCardArt(cardArt)

    val claims = credential.getClaims(documentTypeRepository)
    return buildCborMap {
        put("title", displayName)
        put("subtitle", displayNameSub)
        put("bitmap", cardArtResized ?: byteArrayOf())
        putCborMap("mdoc") {
            put("documentId", document.identifier)
            put("docType", credential.docType)
            putCborMap("namespaces") {
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
    val cardArt = document.cardArt?.toByteArray()
    val displayName = document.displayName ?: "Unnamed Credential"
    val displayNameSub = document.typeDisplayName ?: "Unknown Credential Type"

    val cardArtResized = resizedCardArt(cardArt)

    val claims = credential.getClaimsImpl(documentTypeRepository)
    return buildCborMap {
        put("title", displayName)
        put("subtitle", displayNameSub)
        put("bitmap", cardArtResized ?: byteArrayOf())
        putCborMap("sdjwt") {
            put("documentId", document.identifier)
            put("vct", credential.vct)
            putCborMap("claims") {
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

private var matcher: ByteArray? = null
private var matcherLock = Mutex()

private suspend fun loadMatcher(context: Context): ByteArray {
    matcherLock.withLock {
        if (matcher != null) {
            return matcher!!
        }
        val stream = context.assets.open("identitycredentialmatcher.wasm")
        val matcherBytes = ByteArray(stream.available())
        stream.read(matcherBytes)
        stream.close()
        matcher = matcherBytes
        return matcher!!
    }
}

internal actual suspend fun defaultInitialize() {
}

internal actual val defaultRegisterAvailable = true

internal actual val defaultRequestAvailable = true

// Always authorized on Android
private val mutableAuthorizationState = MutableStateFlow(DigitalCredentialsAuthorizationState.AUTHORIZED)

internal actual val defaultAuthorizationState: StateFlow<DigitalCredentialsAuthorizationState> = mutableAuthorizationState

internal actual val defaultSupportedProtocols: Set<String>
    get() = supportedProtocols

private val supportedProtocols = setOf(
    "openid4vp-v1-signed",
    "openid4vp-v1-unsigned",
    "org-iso-mdoc",
    "openid4vp",
)

private val registerLock = Mutex()

internal actual suspend fun defaultRegister(
    documentStore: DocumentStore,
    documentTypeRepository: DocumentTypeRepository,
    selectedProtocols: Set<String>
) {
    require(supportedProtocols.containsAll(selectedProtocols)) {
        "The selected protocols is not a subset of supported protocols"
    }
    registerLock.withLock {
        updateCredmanUnlocked(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            selectedProtocols = selectedProtocols
        )
    }
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
 * Calculates the origin for a native Android app.
 *
 * This is implemented in accordance with the guidance at https://developer.android.com/identity/sign-in/credential-manager#verify-origin
 *
 * @param appSigningInfo the bytes of the signing information for the application, typically obtained from
 *   a [android.content.pm.Signature] object.
 * @return the origin string of the form "android:apk-key-hash:<sha256_hash-of-apk-signing-cert>"
 */
suspend fun getAppOrigin(appSigningInfo: ByteArray): String {
    return "android:apk-key-hash:${Crypto.digest(Algorithm.SHA256, appSigningInfo).toBase64()}"
}
