package org.multipaz.mdoc.issuersigned

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.validation.ValidationResult
import org.multipaz.validation.buildValidationResult
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.collections.set
import kotlin.random.Random

/**
 * A data structure for representing `IssuerNameSpaces` in ISO/IEC 18013-5:2021.
 *
 * Use [fromDataItem] to parse CBOR and [toDataItem] to generate CBOR.
 *
 * @property data map from namespace name to a map from data element name to [IssuerSignedItem].
 */
data class IssuerNamespaces(
    val data: Map<String, Map<String, IssuerSignedItem>>
) {

    /**
     * Generate `IssuerNameSpaces` CBOR
     *
     * @return a [DataItem] for `IssuerNameSpaces` CBOR.
     */
    fun toDataItem(): DataItem {
        return buildCborMap {
            for ((namespaceName, innerMap) in data) {
                putCborArray(namespaceName) {
                    for ((_, issuerSignedItem) in innerMap) {
                        add(Tagged(
                            Tagged.ENCODED_CBOR,
                            Bstr(Cbor.encode(issuerSignedItem.dataItem))
                        ))
                    }
                }
            }
        }
    }

    /**
     * Returns a new object filtering the [IssuerSignedItem] so they match a request.
     *
     * @param requestedClaims the list of data elements to request.
     * @return a new object containing the [IssuerSignedItem] present that are also requested in [requestedClaims].
     */
    fun filter(requestedClaims: List<MdocRequestedClaim>): IssuerNamespaces {
        val ret = mutableMapOf<String, MutableMap<String, IssuerSignedItem>>()
        for (claim in requestedClaims) {
            val issuerSignedItem = data[claim.namespaceName]?.get(claim.dataElementName)
            if (issuerSignedItem != null) {
                val innerMap = ret.getOrPut(claim.namespaceName, { mutableMapOf<String, IssuerSignedItem>() })
                innerMap.put(claim.dataElementName, issuerSignedItem)
            }
        }
        return IssuerNamespaces(ret)
    }

    /**
     * Calculate digests suitable for inclusion in a [org.multipaz.mdoc.mso.MobileSecurityObject].
     *
     * @param digestAlgorithm the algorithm to use for calculating the digests.
     * @return a map from namespaces into a map from digestId to the digest.
     */
    suspend fun getValueDigests(digestAlgorithm: Algorithm): Map<String, Map<Long, ByteString>> {
        val ret = mutableMapOf<String, Map<Long, ByteString>>()
        data.forEach { (namespace, innerMap) ->
            val innerMapTransformed = mutableMapOf<Long, ByteString>()
            innerMap.forEach { (_, issuerSignedItem) ->
                innerMapTransformed.put(issuerSignedItem.digestId, issuerSignedItem.calculateDigest(digestAlgorithm))
            }
            ret.put(namespace, innerMapTransformed)
        }
        return ret
    }

    /**
     * Validates the internal structure of this [IssuerNamespaces].
     *
     * @return a [ValidationResult] containing any errors or warnings.
     */
    fun validate(): ValidationResult = buildValidationResult {
        if (data.isEmpty()) {
            addWarning("IssuerNamespaces has no namespaces")
        }
        data.forEach { (namespaceName, innerMap) ->
            if (namespaceName.isEmpty()) {
                addError("Namespace name cannot be empty")
            }
            if (innerMap.isEmpty()) {
                addError("Namespace '$namespaceName' contains no data elements")
            }
            val digestIdsSeen = mutableMapOf<Long, String>()
            innerMap.forEach { (elementName, item) ->
                if (elementName != item.dataElementIdentifier) {
                    addError("Key '$elementName' in namespace '$namespaceName' does not match dataElementIdentifier '${item.dataElementIdentifier}'")
                }
                addAll(item.validate())
                val prevLocation = digestIdsSeen[item.digestId]
                if (prevLocation != null) {
                    addError("Duplicate digestID ${item.digestId} used in namespace '$namespaceName' for both '$prevLocation' and '$elementName'")
                } else {
                    digestIdsSeen[item.digestId] = elementName
                }
            }
        }
    }

    /**
     * Validates that all data elements in this [IssuerNamespaces] are authorized by and match the digests
     * in the given [MobileSecurityObject].
     *
     * @param mso the [MobileSecurityObject] to validate against.
     * @return a [ValidationResult] containing any errors or warnings.
     */
    suspend fun validateAgainstMso(mso: MobileSecurityObject): ValidationResult = buildValidationResult {
        data.forEach { (namespace, innerMap) ->
            val digestMap = mso.valueDigests[namespace]
            if (digestMap == null) {
                addError("Namespace '$namespace' in IssuerNamespaces is not present in MSO valueDigests")
                return@forEach
            }
            innerMap.forEach { (elementName, item) ->
                val expectedDigest = digestMap[item.digestId]
                if (expectedDigest == null) {
                    addError("digestID ${item.digestId} for element '$elementName' in namespace '$namespace' is not present in MSO valueDigests")
                } else {
                    val digest = item.calculateDigest(mso.digestAlgorithm)
                    if (digest != expectedDigest) {
                        addError("Digest mismatch for data element '$elementName' in namespace '$namespace' (digestID ${item.digestId})")
                    }
                }
            }
        }

        mso.valueDigests.forEach { (namespace, digestMap) ->
            val innerMap = data[namespace]
            if (innerMap == null) {
                addWarning("Namespace '$namespace' is present in MSO valueDigests but missing from IssuerNamespaces")
            } else {
                val presentDigestIds = innerMap.values.map { it.digestId }.toSet()
                digestMap.keys.forEach { digestId ->
                    if (digestId !in presentDigestIds) {
                        addWarning("digestID $digestId in namespace '$namespace' is present in MSO valueDigests but missing from IssuerNamespaces")
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Validates the CBOR structure of `IssuerNameSpaces` according to ISO/IEC 18013-5:2021.
         *
         * @param nameSpaces a [DataItem] for `IssuerNameSpaces` CBOR.
         * @return a [ValidationResult] containing any errors or warnings.
         */
        fun validate(nameSpaces: DataItem): ValidationResult = buildValidationResult {
            if (nameSpaces !is CborMap) {
                addError("IssuerNamespaces dataItem is not a CBOR map")
                return@buildValidationResult
            }
            if (nameSpaces.asMap.isEmpty()) {
                addWarning("IssuerNamespaces has no namespaces")
            }
            for ((namespaceKey, namespaceValue) in nameSpaces.asMap) {
                if (namespaceKey !is Tstr) {
                    addError("Namespace key is not a text string")
                    continue
                }
                val namespaceName = namespaceKey.asTstr
                if (namespaceName.isEmpty()) {
                    addError("Namespace name cannot be empty")
                }
                if (namespaceValue !is CborArray) {
                    addError("Value for namespace '$namespaceName' is not a CBOR array")
                    continue
                }
                if (namespaceValue.asArray.isEmpty()) {
                    addError("Namespace '$namespaceName' array contains no elements")
                    continue
                }
                val seenInNamespace = mutableSetOf<String>()
                val digestIdsSeen = mutableMapOf<Long, String>()
                for ((idx, item) in namespaceValue.asArray.withIndex()) {
                    if (item !is Tagged || item.tagNumber != Tagged.ENCODED_CBOR) {
                        addError("Item at index $idx in namespace '$namespaceName' is not tagged with CBOR tag 24")
                        continue
                    }
                    if (item.taggedItem !is Bstr) {
                        addError("Tag 24 item at index $idx in namespace '$namespaceName' does not wrap a byte string")
                        continue
                    }
                    val decoded = try {
                        Cbor.decode(item.taggedItem.asBstr)
                    } catch (e: Throwable) {
                        addError("Failed to decode CBOR for IssuerSignedItem at index $idx in namespace '$namespaceName': ${e.message}")
                        continue
                    }
                    val signedItem = IssuerSignedItem(decoded)
                    addAll(signedItem.validate())
                    if (signedItem.dataItem.hasKey("elementIdentifier")) {
                        val elemId = try {
                            signedItem.dataElementIdentifier
                        } catch (_: Throwable) {
                            null
                        }
                        if (elemId != null) {
                            if (!seenInNamespace.add(elemId)) {
                                addError("Duplicate elementIdentifier '$elemId' in namespace '$namespaceName'")
                            }
                        }
                    }
                    if (signedItem.dataItem.hasKey("digestID")) {
                        val dId = try {
                            signedItem.digestId
                        } catch (_: Throwable) {
                            null
                        }
                        if (dId != null) {
                            val elementName = signedItem.dataItem.getOrNull("elementIdentifier")?.asTstr ?: "index_$idx"
                            val prev = digestIdsSeen[dId]
                            if (prev != null) {
                                addError("Duplicate digestID $dId used in namespace '$namespaceName' for both '$prev' and '$elementName'")
                            } else {
                                digestIdsSeen[dId] = elementName
                            }
                        }
                    }
                }
            }
        }

        /**
         * Parse `IssuerNameSpaces` CBOR.
         *
         * @param nameSpaces a [DataItem] for `IssuerNameSpaces` CBOR.
         * @return the parsed representation.
         */
        fun fromDataItem(nameSpaces: DataItem): IssuerNamespaces {
            val ret = mutableMapOf<String, MutableMap<String, IssuerSignedItem>>()
            for ((namespaceDataItemKey, namespaceDataItemValue) in nameSpaces.asMap) {
                val namespaceName = namespaceDataItemKey.asTstr
                val innerMap = mutableMapOf<String, IssuerSignedItem>()
                for (issuerSignedItemBytes in namespaceDataItemValue.asArray) {
                    val issuerSignedItem = IssuerSignedItem(dataItem = issuerSignedItemBytes.asTaggedEncodedCbor)
                    innerMap[issuerSignedItem.dataElementIdentifier] = issuerSignedItem
                }
                ret[namespaceName] = innerMap
            }
            return IssuerNamespaces(ret)
        }
    }

    internal data class DataElements(
        val namespaceName: String,
        val dataElements: List<Pair<String, DataItem>>
    )

    /**
     * A builder for populating a namespace in a [IssuerNamespaces].
     *
     * @param namespaceName the namespace name.
     */
    data class DataElementBuilder(
        val namespaceName: String
    ) {
        private val dataElements = mutableListOf<Pair<String, DataItem>>()

        /**
         * Adds a data element to the builder.
         *
         * @param dataElementName the data element name.
         * @param value the data element value.
         * @return the builder
         */
        fun addDataElement(dataElementName: String, value: DataItem): DataElementBuilder {
            dataElements.add(Pair(dataElementName, value))
            return this
        }

        internal fun build(): DataElements {
            return DataElements(namespaceName, dataElements)
        }
    }

    /**
     * A builder for [IssuerNamespaces].
     *
     * @param dataElementRandomSize the random size to use for generating `IssuerSignedItem`
     * @param randomProvider the [Random] to use.
     */
    class Builder(
        private val dataElementRandomSize: Int = 16,
        private val randomProvider: Random = Crypto.secureRandom,
    ) {
        private val builtNamespaces = mutableListOf<DataElements>()

        /**
         * Adds a new namespace.
         *
         * @param namespaceName the namespace name.
         * @param builderAction the builder action.
         * @return the builder
         */
        fun addNamespace(namespaceName: String, builderAction: DataElementBuilder.() -> Unit): Builder {
            val builder = DataElementBuilder(namespaceName)
            builder.builderAction()
            builtNamespaces.add(builder.build())
            return this
        }

        /**
         * Builds the [IssuerNamespaces].
         *
         * @return the built [IssuerNamespaces].
         */
        fun build(): IssuerNamespaces {
            // ISO 18013-5 section 9.1.2.5 Message digest function says that random must
            // be at least 16 bytes long.
            require(dataElementRandomSize >= 16) {
                "Random size must be at least 16 bytes"
            }

            // Generate and shuffle digestIds..
            var numDataElements = 0
            for (ns in builtNamespaces) {
                numDataElements += ns.dataElements.size
            }
            val digestIds = mutableListOf<Long>()
            for (n in 0L until numDataElements) {
                digestIds.add(n)
            }
            digestIds.shuffle(randomProvider)

            val digestIt = digestIds.iterator()
            val ret = mutableMapOf<String, Map<String, IssuerSignedItem>>()
            for (ns in builtNamespaces) {
                val items = mutableMapOf<String, IssuerSignedItem>()
                for ((deName, deValue) in ns.dataElements) {
                    items[deName] = IssuerSignedItem.fromValues(
                        digestId = digestIt.next(),
                        random = ByteString(randomProvider.nextBytes(dataElementRandomSize)),
                        dataElementIdentifier = deName,
                        dataElementValue = deValue
                    )
                }
                ret[ns.namespaceName] = items
            }
            return IssuerNamespaces(ret)
        }
    }
}

/**
 * A builder for [IssuerNamespaces].
 *
 * @param dataElementRandomSize the random size to use for generating `IssuerSignedItem`
 * @param randomProvider the [Random] to use.
 * @param builderAction the builder action.
 * @return the built [IssuerNamespaces].
 */
inline fun buildIssuerNamespaces(
    dataElementRandomSize: Int = 16,
    randomProvider: Random = Crypto.secureRandom,
    builderAction: IssuerNamespaces.Builder.() -> Unit
): IssuerNamespaces {
    val builder = IssuerNamespaces.Builder(dataElementRandomSize, randomProvider)
    builder.builderAction()
    return builder.build()
}
