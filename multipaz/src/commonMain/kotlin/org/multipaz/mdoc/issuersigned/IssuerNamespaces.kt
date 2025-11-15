package org.multipaz.mdoc.issuersigned

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.request.MdocRequestedClaim
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
                            Bstr(Cbor.encode(issuerSignedItem.toDataItem()))
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
    fun getValueDigests(digestAlgorithm: Algorithm): Map<String, Map<Long, ByteString>> {
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

    companion object {
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
                    val issuerSignedItem = IssuerSignedItem.fromDataItem(
                        issuerSignedItemBytes.asTaggedEncodedCbor
                    )
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
        private val randomProvider: Random = Random,
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
                    items.put(
                        deName,
                        IssuerSignedItem(
                            digestId = digestIt.next(),
                            random = ByteString(randomProvider.nextBytes(dataElementRandomSize)),
                            dataElementIdentifier = deName,
                            dataElementValue = deValue
                        )
                    )
                }
                ret.put(ns.namespaceName, items)
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
fun buildIssuerNamespaces(
    dataElementRandomSize: Int = 16,
    randomProvider: Random = Random,
    builderAction: IssuerNamespaces.Builder.() -> Unit
): IssuerNamespaces {
    val builder = IssuerNamespaces.Builder(dataElementRandomSize, randomProvider)
    builder.builderAction()
    return builder.build()
}
