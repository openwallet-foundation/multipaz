package org.multipaz.mdoc.issuersigned

import org.multipaz.cbor.DataItem
import kotlinx.io.bytestring.ByteString
import kotlin.random.Random

class IssuerNamespacesBuilder(
    private val dataElementRandomSize: Int = 16,
    private val randomProvider: Random = Random,
) {
    private val builtNamespaces = mutableListOf<IssuerNamespacesDataElements>()

    fun addNamespace(namespaceName: String, builderAction: IssuerNamespacesDataElementBuilder.() -> Unit) {
        val builder = IssuerNamespacesDataElementBuilder(namespaceName)
        builder.builderAction()
        builtNamespaces.add(builder.build())
    }

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

fun buildIssuerNamespaces(
    dataElementRandomSize: Int = 16,
    randomProvider: Random = Random,
    builderAction: IssuerNamespacesBuilder.() -> Unit
): IssuerNamespaces {
    val builder = IssuerNamespacesBuilder(dataElementRandomSize, randomProvider)
    builder.builderAction()
    return builder.build()
}

data class IssuerNamespacesDataElements(
    val namespaceName: String,
    val dataElements: List<Pair<String, DataItem>>
)

class IssuerNamespacesDataElementBuilder(
    val namespaceName: String
) {
    val dataElements = mutableListOf<Pair<String, DataItem>>()

    fun addDataElement(dataElementName: String, value: DataItem) {
        dataElements.add(Pair(dataElementName, value))
    }

    fun build(): IssuerNamespacesDataElements {
        return IssuerNamespacesDataElements(namespaceName, dataElements)
    }
}
