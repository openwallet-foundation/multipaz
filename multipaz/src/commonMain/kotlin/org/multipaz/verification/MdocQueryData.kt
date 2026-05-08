package org.multipaz.verification

import org.multipaz.cbor.annotation.CborSerializable

/**
 * Details about query for a particular ISO mdoc-formatted credential in DCQL query.
 *
 * @param docType docType of this credential
 * @param namespaces queried claims grouped by their namespaces
 */
@CborSerializable
data class MdocQueryData(
    override val id: String?,
    override val multiple: Boolean,
    val docType: String,
    val namespaces: List<QueryNamespace>,
): QueryData() {

    /**
     * List of queried claims for a particular namespace
     *
     * @param namespace namespace
     * @param claims claims in this namespace
     */
    @CborSerializable
    data class QueryNamespace(
        val namespace: String,
        val claims: List<QueryClaim>
    ) {
        companion object
    }

    /**
     * Query for a claim
     *
     * @param name of the claim that is being queried
     * @param identifier "id" of this claim's query in DCQL if any
     */
    @CborSerializable
    data class QueryClaim(
        val name: String,
        val identifier: String?
    ) {
        companion object
    }

    /**
     * Map of namespace names in the query claims to a map of all the queries in that namespace
     * indexed by the element name.
     */
    val claimMap: Map<String, Map<String, QueryClaim>> get() =
        namespaces.associate { ns -> ns.namespace to ns.claims.associateBy { it.name } }

    companion object
}