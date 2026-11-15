package org.multipaz.mdoc.request

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.mdoc.zkp.ZkSystemSpec

data class ZkRequest(
    val systemSpecs: List<ZkSystemSpec>,
    val zkRequired: Boolean
) {
    init {
        require(systemSpecs.isNotEmpty()) { "ZkRequest systemSpecs must not be empty" }
    }

    fun toDataItem() = buildCborMap {
        putCborArray("systemSpecs") {
            systemSpecs.forEach { systemSpec ->
                addCborMap {
                    put("zkSystemId", systemSpec.id)
                    put("system", systemSpec.system)
                    if (systemSpec.params.isNotEmpty()) {
                        putCborMap("params") {
                            systemSpec.params.forEach { param ->
                                put(param.key, param.value.toDataItem())
                            }
                        }
                    }
                }
            }
        }
        put("zkRequired", zkRequired)
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): ZkRequest {
            val systemSpecsArray = dataItem["systemSpecs"].asArray
            require(systemSpecsArray.isNotEmpty()) { "ZkRequest systemSpecs must not be empty" }
            val systemSpecs = systemSpecsArray.map { spec ->
                val id = spec["zkSystemId"].asTstr
                val system = spec["system"].asTstr
                val systemSpec = ZkSystemSpec(
                    id = id,
                    system = system
                )
                spec.getOrNull("params")?.asMap?.forEach { (paramKey, paramValue) ->
                    systemSpec.addParam(paramKey.asTstr, paramValue)
                }
                systemSpec
            }
            val zkRequired = dataItem["zkRequired"].asBoolean
            return ZkRequest(
                systemSpecs = systemSpecs,
                zkRequired = zkRequired
            )
        }
    }
}