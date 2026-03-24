package org.multipaz.openid.dcql

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import org.multipaz.claim.Claim
import org.multipaz.claim.findMatchingClaim
import org.multipaz.credential.Credential
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.presentment.CredentialMatchSourceOpenID4VP
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSet
import org.multipaz.presentment.CredentialPresentmentSetOption
import org.multipaz.presentment.CredentialPresentmentSetOptionMember
import org.multipaz.presentment.CredentialPresentmentSetOptionMemberMatch
import org.multipaz.presentment.PresentmentSource
import org.multipaz.presentment.TransactionData
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException

private data class QueryResponse(
    val credentialQuery: DcqlCredentialQuery,
    val credentialSetQuery: DcqlCredentialSetQuery?,

    val matches: List<QueryResponseMatch>
)

private data class QueryResponseMatch(
    val credential: Credential,
    val claims: Map<RequestedClaim, Claim>,
    val transactionData: List<TransactionData>
)

private fun DcqlCredentialSetOption.isSatisfied(
    credentialQueryIdToResponse: MutableMap<String, QueryResponse>
): Boolean {
    for (id in credentialIds) {
        val queryResponse = credentialQueryIdToResponse[id]
        if (queryResponse == null || queryResponse.matches.isEmpty()) {
            return false
        }
    }
    return true
}


/**
 * DCQL (Digital Credentials Query Language) top-level query.
 *
 * Use [Companion.fromJson] to construct an instance from JSON and [execute] to
 * select credentials which satisfy the query.
 *
 * Reference: OpenID4VP 1.0 Section 6.
 *
 * @property credentialQueries list of Credential Queries.
 * @property credentialSetQueries list of Credential Set Queries.
 */
data class DcqlQuery(
    val credentialQueries: List<DcqlCredentialQuery>,
    val credentialSetQueries: List<DcqlCredentialSetQuery>
) {

    /**
     * Serializes the DCQL query to a JSON object.
     *
     * @return a [JsonObject] representing the DCQL query.
     */
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("credentials") {
            credentialQueries.forEach { query ->
                add(query.toJson())
            }
        }
        if (credentialSetQueries.isNotEmpty()) {
            putJsonArray("credential_sets") {
                credentialSetQueries.forEach { setQuery ->
                    add(setQuery.toJson())
                }
            }
        }
    }

    /**
     * Executes the DCQL query.
     *
     * If successful, this returns a [CredentialPresentmentData] which can be used in
     * an user interface for the user to select which combination of credentials to return, see
     * [Consent] composable in `multipaz-compose` and [Consent] view in `multipaz-swift` for examples
     * of how to do this.
     *
     * If the query cannot be satisfied, [DcqlCredentialQueryException] is thrown.
     *
     * @param presentmentSource the [PresentmentSource] to use as a source of truth for presentment.
     * @param keyAgreementPossible if non-empty, a credential using Key Agreement may be returned provided
     *  its private key is using one of the given curves.
     * @param transactionDataMap list of transaction data for each queried document by query id
     * @return the resulting [CredentialPresentmentData] if the query was successful.
     * @throws [DcqlCredentialQueryException] if it's not possible satisfy the query.
     */
    @Throws(DcqlCredentialQueryException::class, CancellationException::class)
    suspend fun execute(
        presentmentSource: PresentmentSource,
        keyAgreementPossible: List<EcCurve> = emptyList(),
        transactionDataMap: Map<String, List<TransactionData>> = emptyMap()
    ): CredentialPresentmentData {
        val credentialQueryIdToResponse = mutableMapOf<String, QueryResponse>()
        for (credentialQuery in credentialQueries) {
            val credsSatisfyingMeta = when (credentialQuery.format) {
                "mso_mdoc", "mso_mdoc_zk" -> {
                    val ret = mutableListOf<Credential>()
                    for (documentId in presentmentSource.documentStore.listDocumentIds()) {
                        val document =
                            presentmentSource.documentStore.lookupDocument(documentId) ?: continue
                        document.getCertifiedCredentials().find {
                            it is MdocCredential && it.docType == credentialQuery.mdocDocType
                        }?.let { ret.add(it) }
                    }
                    ret
                }

                "dc+sd-jwt" -> {
                    val ret = mutableListOf<Credential>()
                    for (documentId in presentmentSource.documentStore.listDocumentIds()) {
                        val document =
                            presentmentSource.documentStore.lookupDocument(documentId) ?: continue
                        document.getCertifiedCredentials().find {
                            it is SdJwtVcCredential && credentialQuery.vctValues!!.contains(it.vct)
                        }?.let { ret.add(it) }
                    }
                    ret
                }

                else -> emptyList()
            }

            val matches = mutableListOf<QueryResponseMatch>()
            // We sort on displayName b/c otherwise it's sorted on Document.identifier which can be unpredictable
            for (cred in credsSatisfyingMeta.sortedBy { it.document.displayName }) {
                val claimsInCredential =
                    cred.getClaims(documentTypeRepository = presentmentSource.documentTypeRepository)
                if (credentialQuery.claimSets.isEmpty()) {
                    var didNotMatch = false
                    val matchingClaimValues = mutableMapOf< RequestedClaim, Claim>()
                    for (requestedClaim in credentialQuery.claims) {
                        val matchingCredentialClaimValue =
                            claimsInCredential.findMatchingClaim(requestedClaim)
                        if (matchingCredentialClaimValue != null) {
                            matchingClaimValues[requestedClaim] = matchingCredentialClaimValue
                        } else {
                            Logger.w(TAG, "Error resolving requested claim $requestedClaim")
                            didNotMatch = true
                            break
                        }
                    }
                    val transactionData = transactionDataMap[credentialQuery.id] ?: emptyList()
                    for (transaction in transactionData) {
                        if (!transaction.type.isApplicable(transaction, cred)) {
                            didNotMatch = true
                            break
                        }
                    }
                    if (!didNotMatch) {
                        val credential = presentmentSource.selectCredential(
                            document = cred.document,
                            requestedClaims = credentialQuery.claims,
                            keyAgreementPossible = keyAgreementPossible
                        )
                        if (credential == null) {
                            throw DcqlCredentialQueryException("Error selecting credential with id ${credentialQuery.id}")
                        }
                        // All claims matched, we have a candidate
                        matches.add(
                            QueryResponseMatch(
                                credential = credential,
                                claims = matchingClaimValues,
                                transactionData = transactionData
                            )
                        )
                    }
                } else {
                    // Go through all the claim sets, one at a time, pick the first to match
                    for (claimSet in credentialQuery.claimSets) {
                        var didNotMatch = false
                        val matchingClaimValues = mutableMapOf<RequestedClaim, Claim>()
                        for (claimId in claimSet.claimIdentifiers) {
                            val requestedClaim = credentialQuery.claimIdToClaim[claimId]
                            if (requestedClaim == null) {
                                didNotMatch = true
                                break
                            }
                            val credentialClaimValue =
                                claimsInCredential.findMatchingClaim(requestedClaim)
                            if (credentialClaimValue != null) {
                                matchingClaimValues[requestedClaim] = credentialClaimValue
                            } else {
                                didNotMatch = true
                                break
                            }
                        }
                        val transactionData = transactionDataMap[credentialQuery.id] ?: emptyList()
                        for (transaction in transactionData) {
                            if (!transaction.type.isApplicable(transaction, cred)) {
                                didNotMatch = true
                                break
                            }
                        }
                        if (!didNotMatch) {
                            // All claims matched, we have a candidate
                            matches.add(
                                QueryResponseMatch(
                                    credential = presentmentSource.selectCredential(
                                        document = cred.document,
                                        requestedClaims = credentialQuery.claims,
                                        keyAgreementPossible = keyAgreementPossible
                                    )!!,
                                    claims = matchingClaimValues,
                                    transactionData = transactionData
                                )
                            )
                            break
                        }
                    }
                }
            }
            credentialQueryIdToResponse[credentialQuery.id] = QueryResponse(
                credentialQuery = credentialQuery,
                credentialSetQuery = null,
                matches = matches
            )
        }

        val credentialSets = mutableListOf<CredentialPresentmentSet>()
        if (credentialSetQueries.isEmpty()) {
            // From 6.4.2. Selecting Credentials:
            //
            //   If credential_sets is not provided, the Verifier requests presentations for
            //   all Credentials in credentials to be returned.
            //
            for ((_, response) in credentialQueryIdToResponse) {
                if (response.matches.isEmpty()) {
                    throw DcqlCredentialQueryException(
                        "No matches for credential query with id ${response.credentialQuery.id}"
                    )
                }
                val matches = mutableListOf<CredentialPresentmentSetOptionMemberMatch>()
                for (match in response.matches) {
                    matches.add(CredentialPresentmentSetOptionMemberMatch(
                        credential = match.credential,
                        claims = match.claims,
                        source = CredentialMatchSourceOpenID4VP(credentialQuery = response.credentialQuery),
                        transactionData = match.transactionData
                    ))
                }
                val options = mutableListOf<CredentialPresentmentSetOption>()
                options.add(
                    CredentialPresentmentSetOption(
                        members = listOf(
                            CredentialPresentmentSetOptionMember(
                                matches = matches
                            )
                        )
                    )
                )
                credentialSets.add(
                    CredentialPresentmentSet(
                        optional = false,
                        options = options
                    )
                )
            }
        } else {
            // From 6.4.2. Selecting Credentials:
            //
            //   Otherwise, the Verifier requests presentations of Credentials to be returned satisfying
            //
            //     - all of the Credential Set Queries in the credential_sets array where the
            //       required attribute is true or omitted, and
            //     - optionally, any of the other Credential Set Queries.
            //
            for (csq in credentialSetQueries) {
                val options = mutableListOf<CredentialPresentmentSetOption>()
                // In this case, simply go through all the matches produced above and pick the
                // credentials from the highest preferred option. If none of them work, bail only
                // if the credential set was required.
                //
                // It's possible multiple options are satisfied and we _could_ ask the user which
                // one of the options they want to send credentials for...
                //
                var satisfiedCsq = false
                for (option in csq.options) {
                    if (option.isSatisfied(credentialQueryIdToResponse)) {
                        val members = mutableListOf<CredentialPresentmentSetOptionMember>()
                        option.credentialIds.forEachIndexed { n, credentialId ->
                            val response = credentialQueryIdToResponse[credentialId]!!
                            val matches = mutableListOf<CredentialPresentmentSetOptionMemberMatch>()
                            for (match in response.matches) {
                                matches.add(CredentialPresentmentSetOptionMemberMatch(
                                    credential = match.credential,
                                    claims = match.claims,
                                    source = CredentialMatchSourceOpenID4VP(credentialQuery = response.credentialQuery),
                                    transactionData = match.transactionData
                                ))
                            }
                            members.add(
                                CredentialPresentmentSetOptionMember(
                                    matches = matches
                                )
                            )
                        }
                        options.add(CredentialPresentmentSetOption(members = members))
                        satisfiedCsq = true
                    }
                }
                if (!satisfiedCsq && csq.required) {
                    throw DcqlCredentialQueryException(
                        "No credentials match required credential_set query"
                    )
                }
                if (options.isNotEmpty()) {
                    credentialSets.add(
                        CredentialPresentmentSet(
                            optional = !csq.required,
                            options = options
                        )
                    )
                }
            }
        }
        return CredentialPresentmentData(
            credentialSets = credentialSets
        )
    }

    companion object {
        private const val TAG = "DcqlQuery"

        /**
         * Parses a DCQL according to OpenID4VP.
         *
         * Reference: OpenID4VP 1.0 Section 6
         *
         * @param dcql a [JsonObject] with the DCQL.
         * @return a [DcqlQuery] object
         * @throws IllegalArgumentException if the given DCQL isn't well-formed.
         */
        @Throws(IllegalArgumentException::class)
        fun fromJsonString(dcql: String): DcqlQuery = fromJson(Json.decodeFromString<JsonObject>(dcql))

        /**
         * Parses a DCQL according to OpenID4VP.
         *
         * Reference: OpenID4VP 1.0 Section 6.
         *
         * @param dcql a [JsonObject] with the DCQL.
         * @return a [DcqlQuery] object
         * @throws IllegalArgumentException if the given DCQL isn't well-formed.
         */
        @Throws(IllegalArgumentException::class)
        fun fromJson(dcql: JsonObject): DcqlQuery {
            val dcqlCredentialQueries = mutableListOf<DcqlCredentialQuery>()
            val dcqlCredentialSetQueries = mutableListOf<DcqlCredentialSetQuery>()

            val credentials = dcql["credentials"]!!.jsonArray
            for (credential in credentials) {
                val c = credential.jsonObject
                val id = c["id"]!!.jsonPrimitive.content
                val format = c["format"]!!.jsonPrimitive.content
                val meta = c["meta"]!!.jsonObject
                var mdocDocType: String? = null
                var vctValues: List<String>? = null
                when (format) {
                    "mso_mdoc", "mso_mdoc_zk" -> {
                        mdocDocType = meta["doctype_value"]!!.jsonPrimitive.content
                    }

                    "dc+sd-jwt" -> {
                        vctValues = meta["vct_values"]!!.jsonArray.map { it.jsonPrimitive.content }
                    }
                }

                val dcqlClaims = mutableListOf<RequestedClaim>()
                val dcqlClaimIdToClaim = mutableMapOf<String, RequestedClaim>()
                val dcqlClaimSets = mutableListOf<DcqlClaimSet>()

                val claims = c["claims"]!!.jsonArray
                check(claims.isNotEmpty())
                for (claim in claims) {
                    val cl = claim.jsonObject
                    val claimId = cl["id"]?.jsonPrimitive?.content
                    val path = cl["path"]!!.jsonArray
                    val values = cl["values"]?.jsonArray
                    val mdocIntentToRetain = cl["intent_to_retain"]?.jsonPrimitive?.boolean
                    val requestedClaim = if (mdocDocType != null) {
                        require(path.size == 2)
                        MdocRequestedClaim(
                            id = claimId,
                            docType = mdocDocType,
                            namespaceName = path[0].jsonPrimitive.content,
                            dataElementName = path[1].jsonPrimitive.content,
                            intentToRetain = mdocIntentToRetain ?: false,
                            values = values
                        )
                    } else {
                        JsonRequestedClaim(
                            id = claimId,
                            vctValues = vctValues!!,
                            claimPath = path,
                            values = values
                        )
                    }
                    dcqlClaims.add(requestedClaim)
                    if (claimId != null) {
                        dcqlClaimIdToClaim[claimId] = requestedClaim
                    }
                }

                val claimSets = c["claim_sets"]?.jsonArray
                if (claimSets != null) {
                    for (claimSet in claimSets) {
                        val cs = claimSet.jsonArray
                        dcqlClaimSets.add(
                            DcqlClaimSet(
                                claimIdentifiers = cs.map { it.jsonPrimitive.content }
                            )
                        )
                    }
                }

                /*
                 * TODO: add support for
                 * - multiple
                 * - trusted_authorities
                 * - require_cryptographic_holder_binding
                 */
                dcqlCredentialQueries.add(
                    DcqlCredentialQuery(
                        id = id,
                        format = format,
                        meta = meta,
                        mdocDocType = mdocDocType,
                        vctValues = vctValues,
                        claims = dcqlClaims,
                        claimSets = dcqlClaimSets,
                        claimIdToClaim = dcqlClaimIdToClaim
                    )
                )
            }

            val credentialSets = dcql["credential_sets"]?.jsonArray
            if (credentialSets != null) {
                for (credentialSet in credentialSets) {
                    val s = credentialSet.jsonObject
                    val required = s["required"]?.jsonPrimitive?.boolean ?: true

                    val credentialSetOptions = mutableListOf<DcqlCredentialSetOption>()

                    val options = s["options"]!!.jsonArray
                    for (option in options) {
                        credentialSetOptions.add(
                            DcqlCredentialSetOption(
                                credentialIds = option.jsonArray.map { it.jsonPrimitive.content }
                            )
                        )
                    }

                    dcqlCredentialSetQueries.add(
                        DcqlCredentialSetQuery(
                            required = required,
                            options = credentialSetOptions
                        )
                    )
                }
            }

            return DcqlQuery(
                credentialQueries = dcqlCredentialQueries,
                credentialSetQueries = dcqlCredentialSetQueries
            )
        }
    }
}

private fun DcqlCredentialQuery.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("format", format)
    put("meta", meta)
    putJsonArray("claims") {
        claims.forEach { claim ->
            add(claim.toJson())
        }
    }
    if (claimSets.isNotEmpty()) {
        putJsonArray("claim_sets") {
            claimSets.forEach { set ->
                addJsonArray {
                    set.claimIdentifiers.forEach { add(it) }
                }
            }
        }
    }
}

private fun DcqlCredentialSetQuery.toJson(): JsonObject = buildJsonObject {
    put("required", required)
    putJsonArray("options") {
        options.forEach { option ->
            addJsonArray {
                option.credentialIds.forEach { add(it) }
            }
        }
    }
}

private fun RequestedClaim.toJson(): JsonObject = buildJsonObject {
    if (id != null) {
        put("id", id)
    }
    putJsonArray("path") {
        when (this@toJson) {
            is MdocRequestedClaim -> {
                add(namespaceName)
                add(dataElementName)
            }
            is JsonRequestedClaim -> {
                claimPath.forEach { add(it) }
            }
        }
    }
    if (values != null && values!!.isNotEmpty()) {
        put("values", values!!)
    }
    if (this@toJson is MdocRequestedClaim) {
        put("intent_to_retain", intentToRetain)
    }
}