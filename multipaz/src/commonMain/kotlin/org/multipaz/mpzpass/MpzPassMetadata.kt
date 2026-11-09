package org.multipaz.mpzpass

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.MdocPortraitEquivalenceData
import org.multipaz.documenttype.fromDataItem
import org.multipaz.documenttype.toDataItem
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.SdJwtVcCredential

/**
 * Credential metadata for an [MpzPass].
 *
 * @property claimGroupsMetadata Metadata for logical claim groups, keyed by group identifier.
 * @property isoMdocClaimsMetadata Per-claim metadata for ISO mdoc credentials, keyed by namespace and data element name.
 * @property isoMdocPortraitEquivalenceData Information about portrait image equivalence support for ISO mdoc credentials.
 * @property sdJwtVcClaimsMetadata Per-claim metadata for SD-JWT VC credentials, keyed by claim path (a [JsonArray] of strings).
 * @throws IllegalArgumentException if any namespace map in [isoMdocClaimsMetadata] is empty, if any key in [sdJwtVcClaimsMetadata]
 *   is empty or contains non-string elements, or if any claim references a `groupId` not present in [claimGroupsMetadata].
 */
@CborSerializationImplemented(schemaId = "")
data class MpzPassMetadata(
    val claimGroupsMetadata: Map<String, MpzPassGroupMetadata> = emptyMap(),
    val isoMdocClaimsMetadata: Map<String, Map<String, MpzPassClaimMetadata>> = emptyMap(),
    val isoMdocPortraitEquivalenceData: MdocPortraitEquivalenceData? = null,
    val sdJwtVcClaimsMetadata: Map<JsonArray, MpzPassClaimMetadata> = emptyMap(),
) {
    init {
        for ((namespace, elements) in isoMdocClaimsMetadata) {
            require(elements.isNotEmpty()) {
                "isoMdocClaimsMetadata for namespace '$namespace' cannot be empty"
            }
            for ((dataElementName, claimMeta) in elements) {
                val groupId = claimMeta.groupId
                if (groupId != null) {
                    require(claimGroupsMetadata.containsKey(groupId)) {
                        "Unknown groupId '$groupId' referenced by ISO mdoc claim '$namespace/$dataElementName'"
                    }
                }
            }
        }
        for ((claimPath, claimMeta) in sdJwtVcClaimsMetadata) {
            require(claimPath.isNotEmpty()) {
                "ClaimPath in sdJwtVcClaimsMetadata cannot be empty"
            }
            for (element in claimPath) {
                require(element is JsonPrimitive && element.isString) {
                    "Each element of ClaimPath in sdJwtVcClaimsMetadata must be a string: $claimPath"
                }
            }
            val groupId = claimMeta.groupId
            if (groupId != null) {
                require(claimGroupsMetadata.containsKey(groupId)) {
                    "Unknown groupId '$groupId' referenced by SD-JWT VC claim $claimPath"
                }
            }
        }
    }

    /**
     * Looks up metadata for a claim group by [groupId].
     *
     * @param groupId The group identifier.
     * @return The [MpzPassGroupMetadata], or `null` if no group with [groupId] is defined.
     */
    fun getClaimGroupMetadata(
        groupId: String
    ): MpzPassGroupMetadata? = claimGroupsMetadata[groupId]

    /**
     * Looks up metadata for an ISO mdoc claim by [namespace] and [dataElementName].
     *
     * @param namespace The ISO mdoc namespace.
     * @param dataElementName The data element identifier within [namespace].
     * @return The [MpzPassClaimMetadata], or `null` if no metadata is defined for this claim.
     */
    fun getIsoMdocClaimMetadata(
        namespace: String,
        dataElementName: String
    ): MpzPassClaimMetadata? = isoMdocClaimsMetadata[namespace]?.get(dataElementName)

    /**
     * Looks up metadata for an SD-JWT VC claim by [claimPath].
     *
     * @param claimPath The claim path as a [JsonArray] of strings (e.g., `["given_name"]` or
     *   `["address", "street_address"]`).
     * @return The [MpzPassClaimMetadata], or `null` if no metadata is defined for this claim.
     */
    fun getSdJwtVcClaimMetadata(
        claimPath: JsonArray
    ): MpzPassClaimMetadata? = sdJwtVcClaimsMetadata[claimPath]

    internal fun buildDocumentType(
        displayName: String,
        mdocCred: MdocCredential?,
        sdJwtCred: SdJwtVcCredential?,
        locales: List<String> = emptyList()
    ): DocumentType? {
        val builder = DocumentType.Builder(displayName)
        val attributeGroups = claimGroupsMetadata.mapValues { (groupId, groupMeta) ->
            groupMeta.toDocumentAttributeGroup(groupId, locales)
        }
        if (mdocCred != null &&
            (isoMdocClaimsMetadata.isNotEmpty() || isoMdocPortraitEquivalenceData != null)
        ) {
            builder.addMdocDocumentType(mdocCred.docType)
            builder.setSupportsPortraitImageEquivalence(isoMdocPortraitEquivalenceData)
            for ((namespace, elements) in isoMdocClaimsMetadata) {
                for ((dataElementName, claimMeta) in elements) {
                    builder.addMdocAttribute(
                        type = claimMeta.type,
                        identifier = dataElementName,
                        displayName = claimMeta.getDisplayName(locales),
                        description = claimMeta.getDescription(locales) ?: "",
                        mandatory = false,
                        mdocNamespace = namespace,
                        sensitivity = claimMeta.sensitivity,
                        icon = claimMeta.icon,
                        group = claimMeta.groupId?.let { attributeGroups[it] }
                    )
                }
            }
        }
        if (sdJwtCred != null && sdJwtVcClaimsMetadata.isNotEmpty()) {
            builder.addJsonDocumentType(
                type = sdJwtCred.vct,
                keyBound = sdJwtCred is KeyBoundSdJwtVcCredential
            )
            val jsonBuilder = builder.jsonBuilder!!
            // First add top-level claims so parent attributes exist before embedded attributes are added
            for ((claimPath, claimMeta) in sdJwtVcClaimsMetadata) {
                if (claimPath.size == 1) {
                    val id = (claimPath[0] as JsonPrimitive).content
                    jsonBuilder.addClaim(
                        type = claimMeta.type,
                        identifier = id,
                        displayName = claimMeta.getDisplayName(locales),
                        description = claimMeta.getDescription(locales) ?: "",
                        sensitivity = claimMeta.sensitivity,
                        icon = claimMeta.icon,
                        group = claimMeta.groupId?.let { attributeGroups[it] }
                    )
                }
            }
            for ((claimPath, claimMeta) in sdJwtVcClaimsMetadata) {
                if (claimPath.size == 2) {
                    val parentId = (claimPath[0] as JsonPrimitive).content
                    val childId = (claimPath[1] as JsonPrimitive).content
                    if (!jsonBuilder.claims.containsKey(parentId)) {
                        jsonBuilder.addClaim(
                            type = DocumentAttributeType.ComplexType,
                            identifier = parentId,
                            displayName = parentId,
                            description = ""
                        )
                    }
                    jsonBuilder.addEmbeddedAttribute(
                        parentIdentifier = parentId,
                        type = claimMeta.type,
                        identifier = childId,
                        displayName = claimMeta.getDisplayName(locales),
                        description = claimMeta.getDescription(locales) ?: "",
                        sensitivity = claimMeta.sensitivity,
                        icon = claimMeta.icon,
                        group = claimMeta.groupId?.let { attributeGroups[it] }
                    )
                }
            }
        }
        if (builder.mdocBuilder == null && builder.jsonBuilder == null) {
            return null
        }
        return builder.build()
    }

    /**
     * Serializes this [MpzPassMetadata] into a CBOR map [DataItem].
     *
     * @return A CBOR map containing the credential metadata.
     */
    fun toDataItem(): DataItem = buildCborMap {
        if (claimGroupsMetadata.isNotEmpty()) {
            putCborMap("claimGroupsMetadata") {
                claimGroupsMetadata.forEach { (groupId, groupMetadata) ->
                    put(groupId, groupMetadata.toDataItem())
                }
            }
        }
        if (isoMdocClaimsMetadata.isNotEmpty()) {
            putCborMap("isoMdocClaimsMetadata") {
                isoMdocClaimsMetadata.forEach { (namespace, elements) ->
                    putCborMap(namespace) {
                        elements.forEach { (dataElementName, metadata) ->
                            put(dataElementName, metadata.toDataItem())
                        }
                    }
                }
            }
        }
        isoMdocPortraitEquivalenceData?.let {
            put("isoMdocPortraitEquivalenceData", it.toDataItem())
        }
        if (sdJwtVcClaimsMetadata.isNotEmpty()) {
            putCborMap("sdJwtVcClaimsMetadata") {
                sdJwtVcClaimsMetadata.forEach { (claimPath, metadata) ->
                    val keyItem = buildCborArray {
                        claimPath.forEach { element ->
                            add((element as JsonPrimitive).content)
                        }
                    }
                    put(keyItem, metadata.toDataItem())
                }
            }
        }
    }

    /**
     * Serializes this [MpzPassMetadata] to CBOR bytes.
     *
     * @return The CBOR-encoded bytes.
     */
    fun toCbor(): ByteArray = Cbor.encode(toDataItem())

    companion object {
        /**
         * Parses a CBOR map [DataItem] into an [MpzPassMetadata].
         *
         * @param dataItem The CBOR map containing `claimGroupsMetadata`, `isoMdocClaimsMetadata`,
         *   `isoMdocPortraitEquivalenceData`, and/or `sdJwtVcClaimsMetadata`.
         * @return The parsed [MpzPassMetadata].
         * @throws IllegalArgumentException if the CBOR structure is malformed.
         */
        @Throws(IllegalArgumentException::class)
        fun fromDataItem(dataItem: DataItem): MpzPassMetadata {
            val claimGroupsMetadata = dataItem.getOrNull("claimGroupsMetadata")?.asMap?.entries?.associate { (idItem, groupItem) ->
                idItem.asTstr to MpzPassGroupMetadata.fromDataItem(groupItem)
            } ?: emptyMap()
            val isoMdocClaimsMetadata = dataItem.getOrNull("isoMdocClaimsMetadata")?.asMap?.entries?.associate { (nsItem, elementsItem) ->
                val ns = nsItem.asTstr
                val elementsMap = elementsItem.asMap.entries.associate { (deItem, metadataItem) ->
                    deItem.asTstr to MpzPassClaimMetadata.fromDataItem(metadataItem)
                }
                ns to elementsMap
            } ?: emptyMap()
            val isoMdocPortraitEquivalenceData = dataItem.getOrNull("isoMdocPortraitEquivalenceData")?.let {
                MdocPortraitEquivalenceData.fromDataItem(it)
            }
            val sdJwtVcClaimsMetadata = dataItem.getOrNull("sdJwtVcClaimsMetadata")?.asMap?.entries?.associate { (pathItem, metadataItem) ->
                val pathArray = pathItem.asArray
                require(pathArray.isNotEmpty()) { "ClaimPath in sdJwtVcClaimsMetadata cannot be empty" }
                val jsonPath = buildJsonArray {
                    pathArray.forEach { elem ->
                        add(elem.asTstr)
                    }
                }
                jsonPath to MpzPassClaimMetadata.fromDataItem(metadataItem)
            } ?: emptyMap()
            return MpzPassMetadata(
                claimGroupsMetadata = claimGroupsMetadata,
                isoMdocClaimsMetadata = isoMdocClaimsMetadata,
                isoMdocPortraitEquivalenceData = isoMdocPortraitEquivalenceData,
                sdJwtVcClaimsMetadata = sdJwtVcClaimsMetadata
            )
        }

        /**
         * Parses CBOR bytes into an [MpzPassMetadata].
         *
         * @param data The CBOR-encoded bytes.
         * @return The parsed [MpzPassMetadata].
         * @throws IllegalArgumentException if CBOR decoding fails or the structure is malformed.
         */
        @Throws(IllegalArgumentException::class)
        fun fromCbor(data: ByteArray): MpzPassMetadata = fromDataItem(Cbor.decode(data))
    }
}
