package org.multipaz.mdoc.request

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.claim.Claim
import org.multipaz.claim.findMatchingClaim
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.toCoseLabel
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.mdoc.util.mdocVersionCompareTo
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.presentment.CredentialMatchSourceIso18013
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSet
import org.multipaz.presentment.CredentialPresentmentSetOption
import org.multipaz.presentment.CredentialPresentmentSetOptionMember
import org.multipaz.presentment.CredentialPresentmentSetOptionMemberMatch
import org.multipaz.presentment.PresentmentSource
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.util.Logger
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.coroutines.cancellation.CancellationException

/**
 * Top-level request in ISO 18013-5.
 *
 * To construct an instance use [buildDeviceRequest].
 *
 * For a request received from a remote mdoc reader use the [DeviceRequest.Companion.fromDataItem]
 * method. Note that you will need to manually call [verifyReaderAuthentication] before accessing
 * the [readerAuthAll] or [DocRequest.readerAuth] fields for instances created this way.
 *
 * @property version the version of the device request, e.g. `1.0` or `1.1`.
 * @property docRequests the document requests embedded in the request.
 * @property deviceRequestInfo a [DeviceRequestInfo] or `null`.
 * @property readerAuthAll zero or more signatures, each covering all document requests.
 */
@ConsistentCopyVisibility
data class DeviceRequest private constructor(
    val version: String,
    val docRequests: List<DocRequest>,
    val deviceRequestInfo: DeviceRequestInfo?,
    private val readerAuthAll_: List<CoseSign1>
) {
    internal var readerAuthAllVerified: Boolean = false

    /**
     * the ReaderAuthAll for the device request or empty if ReaderAuthAll is not used.
     *
     * @throws IllegalStateException if this is accessed before [verifyReaderAuthentication] is called
     *   for instances constructed ia [DeviceRequest.Companion.fromDataItem].
     */
    val readerAuthAll: List<CoseSign1>
        get() {
            if (!readerAuthAllVerified) {
                throw IllegalStateException("readerAuthAll not verified")
            }
            return readerAuthAll_
        }

    /**
     * Verifies reader authentication.
     *
     * @param sessionTranscript the session transcript to use.
     * @throws SignatureVerificationException if reader authentication fails.
     */
    @Throws(SignatureVerificationException::class, CancellationException::class)
    suspend fun verifyReaderAuthentication(sessionTranscript: DataItem) {
        if (readerAuthAll_.isNotEmpty()) {
            val readerAuthenticationAll = buildCborArray {
                add("ReaderAuthenticationAll")
                add(sessionTranscript)
                addCborArray {
                    docRequests.forEach { add(it.itemsRequestBytes) }
                }
                if (deviceRequestInfo != null) {
                    add(Tagged(
                        tagNumber = Tagged.ENCODED_CBOR,
                        taggedItem = Bstr(Cbor.encode(deviceRequestInfo.toDataItem()))
                    ))
                } else {
                    add(Simple.NULL)
                }
            }
            val readerAuthenticationAllBytes =
                Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(readerAuthenticationAll))))
            readerAuthAll_.forEachIndexed { readerAuthAllIndex, readerAuthAllSignature ->
                try {
                    val certChain = (
                            readerAuthAllSignature.protectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                                ?: readerAuthAllSignature.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                            )!!.asX509CertChain
                    val alg = Algorithm.fromCoseAlgorithmIdentifier(
                        readerAuthAllSignature.protectedHeaders[
                            CoseNumberLabel(Cose.COSE_LABEL_ALG)
                        ]!!.asNumber.toInt()
                    )
                    Cose.coseSign1Check(
                        publicKey = certChain.certificates.first().ecPublicKey,
                        detachedData = readerAuthenticationAllBytes,
                        signature = readerAuthAllSignature,
                        signatureAlgorithm = alg
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    throw SignatureVerificationException(
                        message = "Error verifying ReaderAuthAll at index $readerAuthAllIndex",
                        cause = e
                    )
                }
            }
        }
        readerAuthAllVerified = true

        docRequests.forEachIndexed { docRequestIndex, docRequest ->
            if (docRequest.readerAuth_ != null) {
                try {
                    val readerAuthentication = buildCborArray {
                        add("ReaderAuthentication")
                        add(sessionTranscript)
                        add(docRequest.itemsRequestBytes)
                    }
                    val readerAuthenticationBytes =
                        Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(readerAuthentication))))
                    Cose.coseSign1Check(
                        publicKey = docRequest.readerAuthCertChain!!.certificates.first().ecPublicKey,
                        detachedData = readerAuthenticationBytes,
                        signature = docRequest.readerAuth_,
                        signatureAlgorithm = docRequest.readerAuthAlgorithm!!
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw SignatureVerificationException(
                        message = "Error verifying reader authentication for DocRequest at index $docRequestIndex",
                        cause = e
                    )
                }
            }
            docRequest.readerAuthVerified = true
        }
    }

    /**
     * Generates CBOR compliant with the CDDL for `DeviceRequest` according to ISO 18013-5.
     *
     * @return a [DataItem].
     */
    fun toDataItem() = buildCborMap {
        put("version", version)
        putCborArray("docRequests") {
            docRequests.forEach {
                add(it.toDataItem())
            }
        }
        deviceRequestInfo?.let {
            put("deviceRequestInfo", Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(Cbor.encode(it.toDataItem()))
            ))
        }
        if (readerAuthAll_.isNotEmpty()) {
            putCborArray("readerAuthAll") {
                readerAuthAll_.forEach {
                    add(it.toDataItem())
                }
            }
        }
    }

    companion object {
        private const val TAG = "DeviceRequest"

        /**
         * Parses CBOR compliant with the CDDL for `DeviceRequest` according to ISO 18013-5.
         *
         * @param dataItem the CBOR with data.
         * @return a [DeviceRequest].
         */
        fun fromDataItem(dataItem: DataItem): DeviceRequest {
            val version = dataItem["version"].asTstr
            val docRequests = dataItem["docRequests"].asArray.map {
                DocRequest.fromDataItem(it)
            }
            val deviceRequestInfo = dataItem.getOrNull("deviceRequestInfo")?.let {
                if (version.mdocVersionCompareTo("1.1") >= 0) {
                    DeviceRequestInfo.fromDataItem(it.asTaggedEncodedCbor)
                } else {
                    Logger.w(TAG, "Ignoring deviceRequestInfo field since version is less than 1.1")
                    null
                }
            }
            val readerAuthAll = dataItem.getOrNull("readerAuthAll")?.let {
                if (version.mdocVersionCompareTo("1.1") >= 0) {
                    it.asArray.map { elem -> elem.asCoseSign1 }
                } else {
                    Logger.w(TAG, "Ignoring readerAuthAll field since version is less than 1.1")
                    emptyList()
                }
            } ?: emptyList()
            return DeviceRequest(
                version = version,
                docRequests = docRequests,
                deviceRequestInfo = deviceRequestInfo,
                readerAuthAll_ = readerAuthAll
            )
        }
    }

    /**
     * A builder for [DeviceRequest].
     *
     * @property sessionTranscript the `SessionTranscript` CBOR.
     * @property deviceRequestInfo a [DeviceRequestInfo] or `null`.
     * @property version the version to use or `null` to automatically determine which version to use.
     */
    class Builder(
        val sessionTranscript: DataItem,
        val deviceRequestInfo: DeviceRequestInfo? = null,
        val version: String? = null,
    ) {
        private val docRequests = mutableListOf<DocRequest>()
        private val readerAuthAll = mutableListOf<CoseSign1>()

        /**
         * Adds a document request to the builder.
         *
         * @param docType the document type to request.
         * @param nameSpaces the namespaces, data elements, and intent-to-retain values.
         * @param docRequestInfo a [DocRequestInfo] with additional information or `null`.
         * @return the builder.
         */
        fun addDocRequest(
            docType: String,
            nameSpaces: Map<String, Map<String, Boolean>>,
            docRequestInfo: DocRequestInfo? = null,
        ): Builder {
            check(readerAuthAll.isEmpty()) {
                "Cannot call addDocRequest() after addReaderAuthAll()"
            }
            val itemsRequest = buildCborMap {
                put("docType", docType)
                putCborMap("nameSpaces") {
                    for ((namespaceName, dataElementMap) in nameSpaces) {
                        putCborMap(namespaceName) {
                            for ((dataElementName, intentToRetain) in dataElementMap) {
                                put(dataElementName, intentToRetain)
                            }
                        }
                    }
                }
                docRequestInfo?.let {
                    val docRequestInfoDataItem = it.toDataItem()
                    if (docRequestInfoDataItem.asMap.isNotEmpty()) {
                        put("requestInfo", docRequestInfoDataItem)
                    }
                }
            }
            val itemsRequestBytes = Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(itemsRequest)))
            docRequests.add(
                DocRequest(
                    docType = docType,
                    nameSpaces = nameSpaces,
                    docRequestInfo = docRequestInfo,
                    readerAuth_ = null,
                    itemsRequestBytes = itemsRequestBytes
                )
            )
            return this
        }

        /**
         * Adds a document request to the builder.
         *
         * @param docType the document type to request.
         * @param nameSpaces the namespaces, data elements, and intent-to-retain values.
         * @param docRequestInfo a [DocRequestInfo] with additional information or `null`.
         * @param readerKey the key to sign with and its certificate chain
         * @return the builder.
         */
        suspend fun addDocRequest(
            docType: String,
            nameSpaces: Map<String, Map<String, Boolean>>,
            docRequestInfo: DocRequestInfo?,
            readerKey: AsymmetricKey.X509Compatible,
        ): Builder {
            check(readerAuthAll.isEmpty()) {
                "Cannot call addDocRequest() after addReaderAuthAll()"
            }
            val itemsRequest = buildCborMap {
                put("docType", docType)
                putCborMap("nameSpaces") {
                    for ((namespaceName, dataElementMap) in nameSpaces) {
                        putCborMap(namespaceName) {
                            for ((dataElementName, intentToRetain) in dataElementMap) {
                                put(dataElementName, intentToRetain)
                            }
                        }
                    }
                }
                docRequestInfo?.let {
                    val docRequestInfoDataItem = it.toDataItem()
                    if (docRequestInfoDataItem.asMap.isNotEmpty()) {
                        put("requestInfo", docRequestInfoDataItem)
                    }
                }
            }
            val itemsRequestBytes = Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(itemsRequest)))

            val readerAuthentication = buildCborArray {
                add("ReaderAuthentication")
                add(sessionTranscript)
                add(itemsRequestBytes)
            }
            val readerAuthenticationBytes =
                Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(readerAuthentication))))
            // TODO: include x5chain in protected header for v1.1?
            val protectedHeaders = mutableMapOf<CoseLabel, DataItem>()
            // ISO 18013-5 requires use of non-fully-specified algorithms, e.g. -7 instead of -9
            val signatureAlgorithm = readerKey.algorithm.curve!!.defaultSigningAlgorithm
            protectedHeaders.put(Cose.COSE_LABEL_ALG.toCoseLabel, signatureAlgorithm.coseAlgorithmIdentifier!!.toDataItem())
            val unprotectedHeaders = mutableMapOf<CoseLabel, DataItem>()
            readerKey.certChain?.let {
                unprotectedHeaders.put(Cose.COSE_LABEL_X5CHAIN.toCoseLabel, it.toDataItem())
            }
            val readerAuth = Cose.coseSign1Sign(
                signingKey = readerKey,
                message = readerAuthenticationBytes,
                includeMessageInPayload = false,
                protectedHeaders = protectedHeaders,
                unprotectedHeaders = unprotectedHeaders,
            )
            docRequests.add(DocRequest(
                docType = docType,
                nameSpaces = nameSpaces,
                docRequestInfo = docRequestInfo,
                readerAuth_ = readerAuth,
                itemsRequestBytes = itemsRequestBytes
            ))
            return this
        }

        /**
         * Adds a signature over the entire request.
         *
         * After calling this, [addDocRequest] must not be called.
         *
         * @param readerKey the key to sign with and its certificate chain.
         * @return the builder.
         */
        suspend fun addReaderAuthAll(readerKey: AsymmetricKey.X509Compatible): Builder {
            val readerAuthenticationAll = buildCborArray {
                add("ReaderAuthenticationAll")
                add(sessionTranscript)
                addCborArray {
                    docRequests.forEach {
                        add(it.itemsRequestBytes)
                    }
                }
                if (deviceRequestInfo != null) {
                    add(Tagged(
                        tagNumber = Tagged.ENCODED_CBOR,
                        taggedItem = Bstr(Cbor.encode(deviceRequestInfo.toDataItem()))
                    ))
                } else {
                    add(Simple.NULL)
                }
            }
            val readerAuthenticationAllBytes = Cbor.encode(item = Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(Cbor.encode(readerAuthenticationAll)
                ))
            )
            // TODO: include x5chain in protected header for v1.1?
            val protectedHeaders = mutableMapOf<CoseLabel, DataItem>()
            // ISO 18013-5 requires use of non-fully-specified algorithms, e.g. -7 instead of -9
            val signatureAlgorithm = readerKey.algorithm.curve!!.defaultSigningAlgorithm
            protectedHeaders.put(Cose.COSE_LABEL_ALG.toCoseLabel, signatureAlgorithm.coseAlgorithmIdentifier!!.toDataItem())
            val unprotectedHeaders = mutableMapOf<CoseLabel, DataItem>()
            readerKey.certChain?.let {
                unprotectedHeaders.put(Cose.COSE_LABEL_X5CHAIN.toCoseLabel, it.toDataItem())
            }
            val signature = Cose.coseSign1Sign(
                signingKey = readerKey,
                message = readerAuthenticationAllBytes,
                includeMessageInPayload = false,
                protectedHeaders = protectedHeaders,
                unprotectedHeaders = unprotectedHeaders,
            )
            readerAuthAll.add(signature)
            return this
        }

        fun build(): DeviceRequest {
            val versionToUse = version ?: run {
                var docRequestsUsingSecondEditionFeature = false
                for (docRequest in docRequests) {
                    if (docRequest.docRequestInfo?.isUsingSecondEditionFeature() ?: false) {
                        docRequestsUsingSecondEditionFeature = true
                        break
                    }
                }
                if (readerAuthAll.isNotEmpty() || deviceRequestInfo != null || docRequestsUsingSecondEditionFeature) {
                    "1.1"
                } else {
                    "1.0"
                }
            }
            val deviceRequest = DeviceRequest(
                version = versionToUse,
                docRequests = docRequests,
                deviceRequestInfo = deviceRequestInfo,
                readerAuthAll_ = readerAuthAll
            )
            deviceRequest.readerAuthAllVerified = true
            deviceRequest.docRequests.forEach {
                it.readerAuthVerified = true
            }
            return deviceRequest
        }
    }

    private data class DocRequestMatch(
        val credential: Credential,
        val claims: Map<RequestedClaim, Claim>,
        val docRequest: DocRequest
    )

    private data class DocRequestResult(
        val docRequest: DocRequest,
        val matches: List<DocRequestMatch>
    )

    /**
     * Executes the ISO 18013-5 request against a [PresentmentSource].
     *
     * If successful, this returns a [CredentialPresentmentData] which can be used in
     * an user interface for the user to select which combination of credentials to return, see
     * [Consent] composable in `multipaz-compose` and [Consent] view in `multipaz-swift` for examples
     * of how to do this.
     *
     * If the query cannot be satisfied, [Iso18015ResponseException] is thrown.
     *
     * @param presentmentSource the [PresentmentSource] to use as a source of truth for presentment.
     * @param keyAgreementPossible if non-empty, a credential using Key Agreement may be returned provided
     *   its private key is using one of the given curves.
     * @return the resulting [Iso18013Response] if the query was successful.
     * @throws [Iso18015ResponseException] if it's not possible satisfy the query.
     */
    @Throws(Iso18015ResponseException::class, CancellationException::class)
    suspend fun execute(
        presentmentSource: PresentmentSource,
        keyAgreementPossible: List<EcCurve> = emptyList()
    ): CredentialPresentmentData {
        // First find all matches for all DocRequests
        val docRequestResults = docRequests.map { docRequest ->
            findMatchesForDocRequest(docRequest, presentmentSource, keyAgreementPossible)
        }

        val credentialSets = mutableListOf<CredentialPresentmentSet>()

        if (deviceRequestInfo == null || deviceRequestInfo.useCases.isEmpty()) {
            // If useCases isn't set, treat as 18013-5:2021 request. In this case it's undefined
            // what it means if multiple document requests are included (return both documents
            // or one of them?) so just consider the first one
            if (docRequests.isEmpty()) {
                throw Iso18015ResponseException("No DocRequests in request")
            }
            // As per 18013-5:2021 we only look at the first DocRequest.
            val result = docRequestResults[0]
            if (result.matches.isEmpty()) {
                throw Iso18015ResponseException("No matching credentials for first DocRequest")
            }

            // Create a single set, with a single option, containing matches for the first DocRequest.
            val memberMatches = result.matches.map { match ->
                CredentialPresentmentSetOptionMemberMatch(
                    credential = match.credential as MdocCredential,
                    claims = match.claims,
                    source = CredentialMatchSourceIso18013(docRequest = match.docRequest)
                )
            }
            val member = CredentialPresentmentSetOptionMember(memberMatches)
            val option = CredentialPresentmentSetOption(listOf(member))
            credentialSets.add(
                CredentialPresentmentSet(
                    optional = false,
                    options = listOf(option)
                )
            )
        } else {
            // Handle ISO 18013-5 Second Edition UseCases (similar to DCQL credential sets)
            for (useCase in deviceRequestInfo.useCases) {
                val options = mutableListOf<CredentialPresentmentSetOption>()
                var satisfied = false

                // A UseCase has multiple DocumentSets (which act as options).
                // Each DocumentSet is a list of DocRequestIDs (references to docRequests).
                for (documentSet in useCase.documentSets) {
                    // A DocumentSet is satisfied if ALL referenced DocRequests have at least one match.
                    val docRequestIds = documentSet.docRequestIds
                    val allRequestsSatisfied = docRequestIds.all { id ->
                        id >= 0 && id < docRequestResults.size && docRequestResults[id].matches.isNotEmpty()
                    }

                    if (allRequestsSatisfied) {
                        // Create a member for each DocRequest in this set
                        val members = docRequestIds.map { id ->
                            val matches = docRequestResults[id].matches.map { match ->
                                CredentialPresentmentSetOptionMemberMatch(
                                    credential = match.credential as MdocCredential,
                                    claims = match.claims,
                                    source = CredentialMatchSourceIso18013(docRequest = match.docRequest)
                                )
                            }
                            CredentialPresentmentSetOptionMember(matches)
                        }
                        options.add(CredentialPresentmentSetOption(members))
                        satisfied = true
                    }
                }

                if (!satisfied && useCase.mandatory) {
                    throw Iso18015ResponseException("No credentials match required UseCase")
                }

                if (options.isNotEmpty()) {
                    credentialSets.add(
                        CredentialPresentmentSet(
                            optional = !useCase.mandatory,
                            options = options
                        )
                    )
                }
            }
        }

        return CredentialPresentmentData(credentialSets)
    }

    private suspend fun findMatchesForDocRequest(
        docRequest: DocRequest,
        presentmentSource: PresentmentSource,
        keyAgreementPossible: List<EcCurve>
    ): DocRequestResult {
        // Find credentials matching the requested DocType
        val candidates = mutableListOf<Credential>()
        for (documentId in presentmentSource.documentStore.listDocumentIds()) {
            val document = presentmentSource.documentStore.lookupDocument(documentId) ?: continue
            val credential = document.getCertifiedCredentials().find {
                it is MdocCredential && it.docType == docRequest.docType
            }
            if (credential != null) {
                candidates.add(credential)
            }
        }

        val matches = mutableListOf<DocRequestMatch>()
        // Sort by displayName to ensure deterministic order
        for (cred in candidates.sortedBy { it.document.displayName }) {
            val bestMatch = findBestMatchingClaims(cred, docRequest, presentmentSource, keyAgreementPossible)
            if (bestMatch != null) {
                matches.add(DocRequestMatch(
                    credential = bestMatch.first,
                    claims = bestMatch.second,
                    docRequest = bestMatch.third
                ))
            }
        }
        return DocRequestResult(docRequest, matches)
    }

    /**
     * Checks if a credential satisfies a DocRequest, considering alternative data elements.
     * Returns the selected Credential and the map of matching claims if satisfied, null otherwise.
     *
     * This logic generates all valid permutations of claims (base vs alternatives) and selects
     * the one with the highest preference (lowest score).
     */
    private suspend fun findBestMatchingClaims(
        cred: Credential,
        docRequest: DocRequest,
        presentmentSource: PresentmentSource,
        keyAgreementPossible: List<EcCurve>
    ): Triple<Credential, Map<RequestedClaim, Claim>, DocRequest>? {
        val claimsInCredential = cred.getClaims(documentTypeRepository = presentmentSource.documentTypeRepository)

        // 1. Build Logical Requirements
        // Each requested element in 'nameSpaces' creates a requirement.
        // A requirement has a list of Options. Option 0 is the base request.
        // If 'alternativeDataElements' covers this element, it provides additional Options.
        val logicalRequirements = mutableListOf<List<List<MdocRequestedClaim>>>()

        docRequest.nameSpaces.forEach { (namespace, dataElements) ->
            dataElements.forEach { (elementName, intentToRetain) ->
                // Base Option (Index 0)
                val baseClaim = MdocRequestedClaim(
                    id = null, // ID not strictly needed for internal logic
                    docType = docRequest.docType,
                    namespaceName = namespace,
                    dataElementName = elementName,
                    intentToRetain = intentToRetain,
                    values = null
                )
                val optionsForThisField = mutableListOf(listOf(baseClaim))

                // Check for alternatives
                val alternatives = docRequest.docRequestInfo?.alternativeDataElements?.find {
                    it.requestedElement.namespace == namespace &&
                            it.requestedElement.dataElement == elementName
                }

                alternatives?.alternativeElementSets?.forEach { altSet ->
                    val altOptionClaims = altSet.map { altElement ->
                        MdocRequestedClaim(
                            id = null,
                            docType = docRequest.docType,
                            namespaceName = altElement.namespace,
                            dataElementName = altElement.dataElement,
                            intentToRetain = intentToRetain, // Inherit intent from base request
                            values = null
                        )
                    }
                    optionsForThisField.add(altOptionClaims)
                }
                logicalRequirements.add(optionsForThisField)
            }
        }

        // 2. Generate Permutations (Cartesian Product of Options)
        // Score = Sum of indices of chosen options. Lower is better.
        // Result is Pair<List<RequestedClaim>, Score>
        fun generatePermutations(
            depth: Int,
            currentClaims: List<MdocRequestedClaim>,
            currentScore: Int
        ): List<Pair<List<MdocRequestedClaim>, Int>> {
            if (depth == logicalRequirements.size) {
                return listOf(currentClaims to currentScore)
            }

            val results = mutableListOf<Pair<List<MdocRequestedClaim>, Int>>()
            val fieldOptions = logicalRequirements[depth]

            fieldOptions.forEachIndexed { optionIndex, optionClaims ->
                val newScore = currentScore + optionIndex
                val newClaims = currentClaims + optionClaims
                results.addAll(generatePermutations(depth + 1, newClaims, newScore))
            }
            return results
        }

        // Generate and sort by preference
        val allPermutations = generatePermutations(0, emptyList(), 0)
            .sortedBy { it.second }

        // 3. Find the first permutation that the credential satisfies
        for ((requestedClaims, _) in allPermutations) {
            val matchingClaimValues = mutableMapOf<RequestedClaim, Claim>()
            var didNotMatch = false

            for (reqClaim in requestedClaims) {
                val foundClaim = claimsInCredential.findMatchingClaim(reqClaim)
                if (foundClaim != null) {
                    matchingClaimValues[reqClaim] = foundClaim
                } else {
                    didNotMatch = true
                    break
                }
            }

            if (!didNotMatch) {
                // Success! Select the credential with these specific claims
                val selectedCred = presentmentSource.selectCredential(
                    document = cred.document,
                    requestedClaims = requestedClaims,
                    keyAgreementPossible = keyAgreementPossible
                )
                if (selectedCred != null) {
                    return Triple(selectedCred, matchingClaimValues, docRequest)
                }
            }
        }

        return null
    }

    fun getRequester(): X509CertChain? {
        if (readerAuthAll.isNotEmpty()) {
            return (readerAuthAll.first().protectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                ?: readerAuthAll.first().unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                    )!!.asX509CertChain
        }
        for (docRequest in docRequests) {
            docRequest.readerAuth?.let {
                return (it.protectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                    ?: it.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
                        )!!.asX509CertChain
            }
        }
        return null
    }

    /**
     * Converts the credential query part of a ISO/IEC 18013-5 DeviceRequest to a DCQL query.
     *
     * @return a [JsonObject] with the DCQL query.
     */
    fun toDcql(): JsonObject = buildJsonObject {
        putJsonArray("credentials") {
            docRequests.forEachIndexed { index, docRequest ->
                addDcqlCredentialRequest(docRequest, "cred${index}")
            }
        }
        if (deviceRequestInfo?.useCases?.isNotEmpty() == true) {
            putJsonArray("credential_sets") {
                deviceRequestInfo.useCases.forEach { useCase ->
                    addJsonObject {
                        put("required", useCase.mandatory)
                        putJsonArray("options") {
                            useCase.documentSets.forEach { documentSet ->
                                addJsonArray {
                                    documentSet.docRequestIds.forEach { docRequestId ->
                                        add("cred${docRequestId}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}

private fun JsonArrayBuilder.addDcqlCredentialRequest(docRequest: DocRequest, credId: String) {
    addJsonObject {
        put("id", credId)
        if (docRequest.docRequestInfo?.zkRequest != null) {
            put("format", "mso_mdoc_zk")
        } else {
            put("format", "mso_mdoc")
        }
        putJsonObject("meta") {
            put("doctype_value", docRequest.docType)
            if (docRequest.docRequestInfo?.zkRequest != null) {
                putJsonArray("zk_system_type") {
                    docRequest.docRequestInfo.zkRequest.systemSpecs.forEach { spec ->
                        addJsonObject {
                            put("system", spec.system)
                            put("id", spec.id)
                            spec.params.forEach { param ->
                                put(param.key, param.value.toJson())
                            }
                        }
                    }
                }
            }
        }

        // Registry: Track unique claims to ensure every claim (base or alternative)
        // - gets a unique ID and is only listed once in the 'claims' array.
        // - Map Key: "namespace/elementName" -> Map Value: Claim ID (e.g. "claim0")
        val claimIdRegistry = mutableMapOf<String, String>()
        val claimDefinitions = mutableListOf<JsonObject>()
        var claimCounter = 0

        // Helper to register a claim if strictly new
        fun registerClaim(namespace: String, element: String, intentToRetain: Boolean): String {
            val key = "$namespace/$element"
            return claimIdRegistry.getOrPut(key) {
                val id = "claim${claimCounter++}"
                claimDefinitions.add(buildJsonObject {
                    put("id", id)
                    putJsonArray("path") {
                        add(namespace)
                        add(element)
                    }
                    put("intent_to_retain", intentToRetain)
                })
                id
            }
        }

        // Build Logical Requirements:
        // - A list where each item represents a required field.
        // - Each item contains a list of Options (ordered by preference).
        // - Each Option contains a list of Claim IDs (since an alternative can involve multiple claims).
        val logicalRequirements = mutableListOf<List<List<String>>>()

        docRequest.nameSpaces.forEach { (namespace, dataElements) ->
            dataElements.forEach { (elementName, intentToRetain) ->

                // Start with Option 0: The base requested element
                val baseClaimId = registerClaim(namespace, elementName, intentToRetain)
                val optionsForThisField = mutableListOf(listOf(baseClaimId))

                // Check for alternatives
                val alternatives = docRequest.docRequestInfo?.alternativeDataElements?.find {
                    it.requestedElement.namespace == namespace &&
                            it.requestedElement.dataElement == elementName
                }

                // If alternatives exist, add them as subsequent options (Option 1, Option 2...)
                alternatives?.alternativeElementSets?.forEach { altSet ->
                    val altOptionClaimIds = altSet.map { altElement ->
                        // Register alternative claims using the intent of the original requirement
                        registerClaim(altElement.namespace, altElement.dataElement, intentToRetain)
                    }
                    optionsForThisField.add(altOptionClaimIds)
                }

                logicalRequirements.add(optionsForThisField)
            }
        }

        putJsonArray("claims") {
            claimDefinitions.forEach { add(it) }
        }

        // Only necessary if we actually have alternatives (options > 1 for any requirement)
        if (logicalRequirements.any { it.size > 1 }) {
            putJsonArray("claim_sets") {

                // Helper to generate Cartesian product of all options
                // Returns pairs of (List<ClaimID>, Score), where Score = sum of option indices.
                // Lower score = higher preference (closer to the original request).
                fun generatePermutations(
                    depth: Int,
                    currentClaims: List<String>,
                    currentScore: Int
                ): List<Pair<List<String>, Int>> {
                    if (depth == logicalRequirements.size) {
                        return listOf(currentClaims to currentScore)
                    }

                    val results = mutableListOf<Pair<List<String>, Int>>()
                    val fieldOptions = logicalRequirements[depth]

                    fieldOptions.forEachIndexed { optionIndex, optionClaimIds ->
                        val newScore = currentScore + optionIndex
                        val newClaims = currentClaims + optionClaimIds
                        results.addAll(generatePermutations(depth + 1, newClaims, newScore))
                    }
                    return results
                }

                // Generate and sort by score (ascending) to honor Verifier preference
                val allSets = generatePermutations(0, emptyList(), 0)
                    .sortedBy { it.second }

                allSets.forEach { (claimSet, _) ->
                    addJsonArray {
                        claimSet.forEach { add(it) }
                    }
                }
            }
        }
    }
}

/**
 * Builds a [DeviceRequest].
 *
 * @param sessionTranscript the `SessionTranscript` CBOR.
 * @param deviceRequestInfo a [DeviceRequestInfo] or `null`.
 * @param version the version to use or `null` to automatically determine which version to use.
 * @param builderAction the builder action.
 * @return a [DeviceRequest].
 */
inline fun buildDeviceRequest(
    sessionTranscript: DataItem,
    deviceRequestInfo: DeviceRequestInfo? = null,
    version: String? = null,
    builderAction: DeviceRequest.Builder.() -> Unit
): DeviceRequest {
    val builder = DeviceRequest.Builder(
        version = version,
        deviceRequestInfo = deviceRequestInfo,
        sessionTranscript = sessionTranscript
    )
    builder.builderAction()
    return builder.build()
}

/**
 * Builds a [DeviceRequest] from a [DcqlQuery].
 *
 * This performs the inverse transformation of [DeviceRequest.toDcql].
 *
 * @param sessionTranscript the `SessionTranscript` CBOR.
 * @param dcql the DCQL query to convert.
 * @property otherInfo other request info to go into [DeviceRequestInfo].
 * @param builderAction optional builder action to configure the request (e.g. add reader authentication).
 * @return the configured [DeviceRequest].
 * @throws IllegalArgumentException if [dcql] contains features not supported by [DeviceRequest], for
 *   example a request for credentials that aren't ISO mdocs.
 */
@Throws(IllegalArgumentException::class, CancellationException::class)
inline fun buildDeviceRequestFromDcql(
    sessionTranscript: DataItem,
    dcql: JsonObject,
    otherInfo: Map<String, DataItem> = emptyMap(),
    builderAction: DeviceRequest.Builder.() -> Unit = {}
): DeviceRequest {
    val dcqlQuery = DcqlQuery.fromJson(dcql)
    val deviceRequestInfo = deviceRequestCalcDeviceRequestInfo(dcqlQuery, otherInfo)
    val builder = DeviceRequest.Builder(
        sessionTranscript = sessionTranscript,
        deviceRequestInfo = deviceRequestInfo
    )
    deviceRequestAddQueries(dcqlQuery, builder)
    builder.builderAction()
    return builder.build()
}

/**
 * Builds a [DeviceRequest] from a [DcqlQuery].
 *
 * This performs the inverse transformation of [DeviceRequest.toDcql].
 *
 * @param sessionTranscript the `SessionTranscript` CBOR.
 * @param dcqlString a string with the DCQL query to convert.
 * @property otherInfo other request info to go into [DeviceRequestInfo].
 * @param builderAction optional builder action to configure the request (e.g. add reader authentication).
 * @return the configured [DeviceRequest].
 * @throws IllegalArgumentException if [dcqlString] contains features not supported by [DeviceRequest], for
 *   example a request for credentials that aren't ISO mdocs.
 */
@Throws(IllegalArgumentException::class, CancellationException::class)
inline fun buildDeviceRequestFromDcql(
    sessionTranscript: DataItem,
    dcqlString: String,
    otherInfo: Map<String, DataItem> = emptyMap(),
    builderAction: DeviceRequest.Builder.() -> Unit = {}
): DeviceRequest {
    return buildDeviceRequestFromDcql(
        sessionTranscript = sessionTranscript,
        dcql = Json.decodeFromString<JsonObject>(dcqlString),
        otherInfo = otherInfo,
        builderAction = builderAction
    )
}

@PublishedApi
internal fun deviceRequestCalcDeviceRequestInfo(
    dcqlQuery: DcqlQuery,
    otherInfo: Map<String, DataItem>
): DeviceRequestInfo? {
    val useCases = dcqlQuery.credentialSetQueries.map { csq ->
        UseCase(
            mandatory = csq.required,
            documentSets = csq.options.map { option ->
                // Map the credential IDs back to indices in the docRequests list.
                // We assume the order of credentialQueries matches the docRequests order.
                // The IDs in DCQL are arbitrary strings, but our generator uses "credN".
                // We'll rely on the index in the list.
                val indices = option.credentialIds.mapNotNull { credId ->
                    dcqlQuery.credentialQueries.indexOfFirst { it.id == credId }.takeIf { it >= 0 }
                }
                DocumentSet(indices)
            },
            purposeHints = emptyMap() // DCQL doesn't currently carry purpose hints structure directly
        )
    }

    return if (useCases.isNotEmpty()) {
        DeviceRequestInfo(
            useCases = useCases,
            otherInfo = otherInfo
        )
    } else {
        // TODO: UseCases is optional even in a 1.1 request but iOS 26 currently assumes it's set.
        //   This has been reported to Apple so this can be removed once their bug-fix is out.
        DeviceRequestInfo(
            useCases = listOf(
                UseCase(
                    mandatory = true,
                    documentSets = listOf(
                        DocumentSet(
                            docRequestIds = listOf(0)
                        )
                    ),
                    purposeHints = emptyMap()
                )
            ),
            otherInfo = otherInfo
        )
    }
}

@PublishedApi
internal fun deviceRequestAddQueries(
    dcqlQuery: DcqlQuery,
    builder: DeviceRequest.Builder
) {
    for (credQuery in dcqlQuery.credentialQueries) {
        if (!(credQuery.format == "mso_mdoc" || credQuery.format == "mso_mdoc_zk")) {
            throw IllegalArgumentException("Credential format ${credQuery.format} is not supported")
        }

        val docType = credQuery.mdocDocType
            ?: throw IllegalArgumentException("Missing docType in DCQL query ${credQuery.id}")

        // Reconstruct Namespaces and Base Claims
        // In our generation logic, the "base" claim is the one used in the first claim set
        // (or just the claim itself if no sets). To be safe, we collect all claims that appear
        // in *any* claim set (or the claims list) and organize them by namespace.
        // However, strictly speaking, ISO 18013-5 structure requires a 'primary' request
        // and then alternatives. We'll pick the first claim definition for a given (ns, element)
        // as the primary.

        val nameSpaces = mutableMapOf<String, MutableMap<String, Boolean>>()

        // Identify which claims are 'base' (primary).
        // If claim_sets exists, the first set usually represents the primary preference.
        // If not, all claims are primary.
        val primaryClaimIds = if (credQuery.claimSets.isNotEmpty()) {
            credQuery.claimSets.first().claimIdentifiers.toSet()
        } else {
            // FIX: If no claim_sets, use all claim IDs from the 'claims' list.
            // Some claims might not have an ID if they were parsed from a simple request,
            // so we might need to handle claims without IDs if your parser allows them.
            // Assuming your parser generates IDs or we iterate the list directly.
            credQuery.claims.mapNotNull { it.id }.toSet()
        }

        credQuery.claims.forEach { requestedClaim ->
            if (requestedClaim is MdocRequestedClaim) {
                // We include it in the main request if it's in the primary set
                // OR if there are no sets (meaning primaryClaimIds includes everything).
                if (primaryClaimIds.isEmpty() || primaryClaimIds.contains(requestedClaim.id)) {
                    val nsMap = nameSpaces.getOrPut(requestedClaim.namespaceName) { mutableMapOf() }
                    nsMap[requestedClaim.dataElementName] = requestedClaim.intentToRetain
                }
            }
        }

        // 2b. Reconstruct AlternativeDataElements from claim_sets
        val alternativeDataElements = mutableListOf<AlternativeDataElementSet>()

        if (credQuery.claimSets.size > 1) {
            // This is complex because DCQL flattens the structure.
            // A claim set is a list of ALL claims for the credential.
            // ISO alternatives are per-element.

            // We need to find the "diff" between the primary set (Set 0) and subsequent sets.
            // Example:
            // Set 0: [FamilyName, AgeOver18]
            // Set 1: [FamilyName, BirthDate]
            // Diff: AgeOver18 replaced by BirthDate.

            val primarySet = credQuery.claimSets[0].claimIdentifiers

            // We iterate over the primary claims to see if they are replaced in other sets.
            primarySet.forEach { primaryClaimId ->
                val primaryClaim = credQuery.claimIdToClaim[primaryClaimId] as? MdocRequestedClaim
                    ?: return@forEach

                val altSetsForThisElement = mutableListOf<List<ElementReference>>()

                // Check other sets
                for (i in 1 until credQuery.claimSets.size) {
                    val currentSetIds = credQuery.claimSets[i].claimIdentifiers

                    // If this set contains the primary claim, it's not an alternative for it.
                    if (currentSetIds.contains(primaryClaimId)) continue

                    // If it doesn't contain the primary claim, it must contain a replacement
                    // (or it's a completely different disjoint set, which ISO doesn't strictly support per-field).
                    // We identify the replacement by finding which claims in this set are NOT in the primary set.
                    // NOTE: This assumes strict 1-to-1 or 1-to-many replacement logic aligns with ISO.

                    val newClaims = currentSetIds.filter { !primarySet.contains(it) }

                    // Construct the ISO alternative element set
                    if (newClaims.isNotEmpty()) {
                        val altRefs = newClaims.mapNotNull { id ->
                            val claim = credQuery.claimIdToClaim[id] as? MdocRequestedClaim
                            claim?.let {
                                ElementReference(it.namespaceName, it.dataElementName)
                            }
                        }
                        if (altRefs.isNotEmpty()) {
                            altSetsForThisElement.add(altRefs)
                        }
                    }
                }

                if (altSetsForThisElement.isNotEmpty()) {
                    alternativeDataElements.add(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(
                                primaryClaim.namespaceName,
                                primaryClaim.dataElementName
                            ),
                            alternativeElementSets = altSetsForThisElement
                        )
                    )
                }
            }
        }

        val zkRequest = if (credQuery.format == "mso_mdoc_zk") {
            val zkSpecs = mutableListOf<ZkSystemSpec>()
            for (entry in credQuery.meta["zk_system_type"]!!.jsonArray) {
                entry as JsonObject
                val system = entry["system"]!!.jsonPrimitive.content
                val id = entry["id"]!!.jsonPrimitive.content
                val item = ZkSystemSpec(id, system)
                for (param in entry) {
                    if (param.key == "system" || param.key == "id") {
                        continue
                    }
                    item.addParam(param.key, param.value)
                }
                zkSpecs.add(item)
            }
            ZkRequest(
                systemSpecs = zkSpecs,
                zkRequired = true
            )
        } else {
            null
        }

        val docRequestInfo = if (alternativeDataElements.isNotEmpty() || zkRequest != null) {
            DocRequestInfo(
                alternativeDataElements = alternativeDataElements,
                zkRequest = zkRequest
            )
        } else {
            null
        }

        builder.addDocRequest(
            docType = docType,
            nameSpaces = nameSpaces,
            docRequestInfo = docRequestInfo
        )
    }
}