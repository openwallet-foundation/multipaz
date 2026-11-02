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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MapBuilder
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.claim.organizeByNamespace
import org.multipaz.context.AndroidUiContext
import org.multipaz.context.applicationContext
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.androidCredmanBitmap
import org.multipaz.document.androidCredmanExchangeProtocols
import org.multipaz.document.androidCredmanOmitClaims
import org.multipaz.document.androidCredmanSubtitle
import org.multipaz.document.androidCredmanTitle
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import java.io.ByteArrayOutputStream
import kotlin.time.Clock

private const val TAG = "DigitalCredentials"

private const val CREDMAN_DB_SHA256_KEY = "org.multipaz.CredmanDbSha256"

private const val CREDMAN_MATCHER_SHA256_KEY = "org.multipaz.CredmanMatcherSha256"

private fun getAttributeForJsonClaim(
    documentTypeRepository: DocumentTypeRepository,
    vct: String,
    path: JsonArray,
): DocumentAttribute? {
    val documentType = documentTypeRepository.getDocumentTypeForJson(vct)
    if (documentType != null) {
        val flattenedPath = path
            .filter { it is JsonPrimitive && it.isString }
            .joinToString(".") { it.jsonPrimitive.content }
        return documentType.jsonDocumentType?.getDocumentAttribute(flattenedPath)
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
    selectedProtocols: Set<String>,
    forceRegistration: Boolean
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

    val endTime = Clock.System.now()
    Logger.i(TAG, "Credman database of ${credentialDatabaseCbor.size} bytes " +
            "generated in ${(endTime - startTime).inWholeMilliseconds} ms")

    val matcher = loadMatcher(applicationContext)
    val matcherSha256 = ByteString(Crypto.digest(Algorithm.SHA256, matcher))
    val lastMatcherSha256 = documentStore.getTags().get<ByteString>(CREDMAN_MATCHER_SHA256_KEY)
    val matcherNotChanged = lastMatcherSha256 != null && matcherSha256 == lastMatcherSha256

    val credDbSha256 = ByteString(Crypto.digest(Algorithm.SHA256, credentialDatabaseCbor))
    val lastCredDbSha256 = documentStore.getTags().get<ByteString>(CREDMAN_DB_SHA256_KEY)
    val credDbNotChanged = lastCredDbSha256 != null && credDbSha256 == lastCredDbSha256

    if (!forceRegistration && matcherNotChanged && credDbNotChanged) {
        Logger.i(TAG, "No change in Credman database or matcher since last registration")
        return
    }
    documentStore.getTags().edit {
        if (!matcherNotChanged) {
            Logger.i(TAG, "Credman matcher changed since last registration")
            set(CREDMAN_MATCHER_SHA256_KEY, matcherSha256)
        }
        if (!credDbNotChanged) {
            Logger.i(TAG, "Credman database changed since last registration")
            set(CREDMAN_DB_SHA256_KEY, credDbSha256)
        }
    }
    Logger.dCbor(TAG, "credentialDatabase", credentialDatabase)

    val documents = documentStore.listDocuments(sort = true)
    for (document in documents) {
        val hasCredential = document.getCertifiedCredentials().any { it is MdocCredential || it is SdJwtVcCredential }
        if (hasCredential) {
            val displayName = document.androidCredmanTitle ?: document.displayName ?: document.typeDisplayName ?: "Unnamed Document"
            Logger.i(TAG, "Registering document with docId ${document.identifier} ('$displayName') with CredMan")
        }
    }

    val client = IdentityCredentialManager.getClient(applicationContext)
    client.registerCredentials(
        RegistrationRequest(
            credentials = credentialDatabaseCbor,
            matcher = matcher,
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

    val cardArt = document.androidCredmanBitmap?.toByteArray() ?: document.cardArt?.toByteArray()
    val displayName = document.androidCredmanTitle ?: document.displayName ?: "Unnamed Document"
    val displayNameSub = document.androidCredmanSubtitle ?: document.typeDisplayName ?: "Unknown Document Type"
    val exchangeProtocols = document.androidCredmanExchangeProtocols
    val omitClaims = document.androidCredmanOmitClaims

    val cardArtResized = resizedCardArt(cardArt)

    val issuerIdentifiers = credential.issuerCertChain.certificates.mapNotNull {
        it.authorityKeyIdentifier
    }
    return buildCborMap {
        put("title", displayName)
        put("subtitle", displayNameSub)
        put("bitmap", cardArtResized ?: byteArrayOf())
        if (exchangeProtocols != null) {
            putCborArray("protocols") {
                exchangeProtocols.forEach { add(it) }
            }
        }
        putCborMap("mdoc") {
            put("documentId", document.identifier)
            put("docType", credential.docType)
            if (issuerIdentifiers.isNotEmpty()) {
                putCborArray("issuerIdentifiers") {
                    issuerIdentifiers.forEach { add(it) }
                }
            }
            if (document.readerIdentifiers.isNotEmpty()) {
                putCborArray("readerIdentifiers") {
                    document.readerIdentifiers.forEach { add(it.toByteArray()) }
                }
            }
            if (omitClaims) {
                put("omitClaims", true)
                putCborMap("claimDisplayNames") {
                    credentialType?.mdocDocumentType?.namespaces?.forEach { (namespaceName, mdocNamespace) ->
                        mdocNamespace.dataElements.forEach { (dataElementName, mdocDataElement) ->
                            put("$namespaceName.$dataElementName", mdocDataElement.attribute.displayName)
                        }
                    }
                }
            } else {
                val claims = try {
                    credential.getClaims(documentTypeRepository)
                } catch (err: IllegalStateException) {
                    Logger.e(TAG,
                        "Error reading claims: '${credential.document.identifier}.${credential.identifier}'", err)
                    emptyList()
                }
                val peData = credential.document.portraitEquivalenceData ?: credentialType?.mdocDocumentType?.supportsPortraitImageEquivalence
                val hasPortrait = peData != null && claims.any {
                    it.namespaceName == peData.namespace && it.dataElementName == peData.dataElementName
                }
                if (hasPortrait) {
                    put("supportsPortraitImageEquivalence", true)
                }
                if (credential.mso.deviceKeyAuthorizedNamespaces.isNotEmpty() ||
                    credential.mso.deviceKeyAuthorizedDataElements.isNotEmpty()
                ) {
                    putCborMap("keyAuthorizations") {
                        if (credential.mso.deviceKeyAuthorizedNamespaces.isNotEmpty()) {
                            putCborArray("nameSpaces") {
                                credential.mso.deviceKeyAuthorizedNamespaces.forEach { add(it) }
                            }
                        }
                        if (credential.mso.deviceKeyAuthorizedDataElements.isNotEmpty()) {
                            putCborMap("dataElements") {
                                credential.mso.deviceKeyAuthorizedDataElements.forEach { (namespace, dataElementList) ->
                                    putCborArray(namespace) {
                                        dataElementList.forEach { add(it) }
                                    }
                                }
                            }
                        }
                    }
                }
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
}

private suspend fun exportSdJwtVcCredential(
    appName: String,
    document: Document,
    credential: SdJwtVcCredential,
    documentTypeRepository: DocumentTypeRepository
): DataItem {
    val cardArt = document.androidCredmanBitmap?.toByteArray() ?: document.cardArt?.toByteArray()
    val displayName = document.androidCredmanTitle ?: document.displayName ?: "Unnamed Document"
    val displayNameSub = document.androidCredmanSubtitle ?: document.typeDisplayName ?: "Unknown Document Type"
    val exchangeProtocols = document.androidCredmanExchangeProtocols
    val omitClaims = document.androidCredmanOmitClaims

    val cardArtResized = resizedCardArt(cardArt)

    val issuerIdentifiers = credential.getIssuerCertChain()?.certificates?.mapNotNull {
        it.authorityKeyIdentifier
    } ?: emptyList()
    return buildCborMap {
        put("title", displayName)
        put("subtitle", displayNameSub)
        put("bitmap", cardArtResized ?: byteArrayOf())
        if (exchangeProtocols != null) {
            putCborArray("protocols") {
                exchangeProtocols.forEach { add(it) }
            }
        }
        putCborMap("sdjwt") {
            put("documentId", document.identifier)
            put("vct", credential.vct)
            if (issuerIdentifiers.isNotEmpty()) {
                putCborArray("issuerIdentifiers") {
                    issuerIdentifiers.forEach { add(it) }
                }
            }
            if (document.readerIdentifiers.isNotEmpty()) {
                putCborArray("readerIdentifiers") {
                    document.readerIdentifiers.forEach { add(it.toByteArray()) }
                }
            }
            if (omitClaims) {
                put("omitClaims", true)
                putCborMap("claimDisplayNames") {
                    val jsonDocumentType = documentTypeRepository
                        .getDocumentTypeForJson(credential.vct)?.jsonDocumentType
                    jsonDocumentType?.claims?.forEach { (claimName, attribute) ->
                        exportJsonAttributeDisplayNames(claimName, attribute)
                    }
                }
            } else {
                val claims = try {
                    credential.getClaimsImpl(documentTypeRepository)
                } catch (err: IllegalStateException) {
                    val cred = credential as Credential
                    Logger.e(TAG,
                        "Error reading claims: '${cred.document.identifier}.${credential.identifier}'", err)
                    emptyList()
                }
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
                        exportSubClaims(
                            pathPrefix = claimName,
                            currentValue = claim.value,
                            currentAttr = claimAttr,
                            displayName = claimDisplayName,
                            isWildcardSelection = false,
                        )
                    }
                }
            }
        }
    }
}

private fun MapBuilder<*>.exportJsonAttributeDisplayNames(
    pathPrefix: String,
    attribute: DocumentAttribute,
) {
    put(pathPrefix, attribute.displayName)
    for (embeddedAttribute in attribute.embeddedAttributes) {
        exportJsonAttributeDisplayNames(
            pathPrefix = "$pathPrefix.${embeddedAttribute.identifier}",
            attribute = embeddedAttribute,
        )
    }
}

private fun MapBuilder<*>.exportSubClaims(
    pathPrefix: String,
    currentValue: JsonElement,
    currentAttr: DocumentAttribute?,
    displayName: String,
    isWildcardSelection: Boolean,
) {
    if (!isWildcardSelection) {
        when (currentValue) {
            is JsonObject -> {
                for ((subClaimIdentifier, subClaimValue) in currentValue) {
                    val subClaimAttr = currentAttr?.embeddedAttributes?.find { it.identifier == subClaimIdentifier }
                    val subClaimDisplayName = subClaimAttr?.displayName ?: subClaimIdentifier
                    val subPath = "$pathPrefix.$subClaimIdentifier"
                    putCborArray(subPath) {
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
                    exportSubClaims(
                        pathPrefix = subPath,
                        currentValue = subClaimValue,
                        currentAttr = subClaimAttr,
                        displayName = subClaimDisplayName,
                        isWildcardSelection = false,
                    )
                }
            }
            is JsonArray -> {
                for ((index, elementValue) in currentValue.withIndex()) {
                    val indexPath = "$pathPrefix.$index"
                    val renderedValue = when (elementValue) {
                        is JsonPrimitive -> elementValue.content
                        else -> elementValue.toString()
                    }
                    putCborArray(indexPath) {
                        add(displayName)
                        add(renderedValue)
                        add(renderedValue.let { if (it.length < 128) it else "" })
                    }
                    exportSubClaims(
                        pathPrefix = indexPath,
                        currentValue = elementValue,
                        currentAttr = currentAttr,
                        displayName = displayName,
                        isWildcardSelection = false,
                    )
                }
                if (currentValue.isNotEmpty()) {
                    val wildcardPath = "$pathPrefix.null"
                    putCborArray(wildcardPath) {
                        add(displayName)
                        add(currentValue.toString())
                        add("")
                    }
                    exportSubClaims(
                        pathPrefix = wildcardPath,
                        currentValue = currentValue,
                        currentAttr = currentAttr,
                        displayName = displayName,
                        isWildcardSelection = true,
                    )
                }
            }
            else -> {}
        }
    } else if (currentValue is JsonArray) {
        val objectKeys = currentValue
            .filterIsInstance<JsonObject>()
            .flatMap { it.keys }
            .toSet()
        for (subKey in objectKeys) {
            val projected = buildJsonArray {
                for (elem in currentValue) {
                    if (elem is JsonObject) {
                        elem[subKey]?.let { add(it) }
                    }
                }
            }
            if (projected.isNotEmpty()) {
                val subAttr = currentAttr?.embeddedAttributes?.find { it.identifier == subKey }
                val subDisplayName = subAttr?.displayName ?: subKey
                val subPath = "$pathPrefix.$subKey"
                putCborArray(subPath) {
                    add(subDisplayName)
                    add(projected.toString())
                    add("")
                }
                exportSubClaims(
                    pathPrefix = subPath,
                    currentValue = projected,
                    currentAttr = subAttr,
                    displayName = subDisplayName,
                    isWildcardSelection = true,
                )
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
            //Logger.i(TAG, "Resized cardart to 48x48, ${cardArt.size} bytes to ${cardArtResized.size} bytes")
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

/**
 * An implementation of [DigitalCredentials] using Android Credential Manager.
 */
class DigitalCredentialsCredman private constructor() : DigitalCredentials {
    companion object {
        private var instance: DigitalCredentialsCredman? = null
        private val instanceLock = Mutex()

        /**
         * Creates or returns the [DigitalCredentialsCredman] instance.
         *
         * @return a [DigitalCredentialsCredman] instance.
         */
        suspend fun create(): DigitalCredentialsCredman {
            instanceLock.withLock {
                if (instance == null) {
                    instance = DigitalCredentialsCredman()
                }
                return instance!!
            }
        }
    }

    override val registerAvailable: Boolean = true

    override val requestAvailable: Boolean = true

    // Always authorized on Android
    private val mutableAuthorizationState = MutableStateFlow(DigitalCredentialsAuthorizationState.AUTHORIZED)

    override val authorizationState: StateFlow<DigitalCredentialsAuthorizationState> = mutableAuthorizationState

    override val supportedProtocols: Set<String> = setOf(
        "openid4vp-v1-signed",
        "openid4vp-v1-unsigned",
        "openid4vp-v1-multisigned",
        "org-iso-mdoc",
        "openid4vp",
    )

    private val registerLock = Mutex()

    override suspend fun register(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
        selectedProtocols: Set<String>,
        forceRegistration: Boolean
    ) {
        require(supportedProtocols.containsAll(selectedProtocols)) {
            "The selected protocols is not a subset of supported protocols"
        }
        registerLock.withLock {
            updateCredmanUnlocked(
                documentStore = documentStore,
                documentTypeRepository = documentTypeRepository,
                selectedProtocols = selectedProtocols,
                forceRegistration = forceRegistration
            )
        }
    }

    @OptIn(ExperimentalDigitalCredentialApi::class)
    override suspend fun request(request: JsonObject): JsonObject {
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
}

/**
 * Looks up a [Document] in the [DocumentStore] by its Android Credential Manager identifier.
 *
 * @param credManId the Credential Manager document identifier.
 * @return the [Document] or `null` if not found.
 */
suspend fun DocumentStore.lookupForCredmanId(credManId: String): Document? {
    return lookupDocument(credManId)
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
    return "android:apk-key-hash:${Crypto.digest(Algorithm.SHA256, appSigningInfo).toBase64Url()}"
}
