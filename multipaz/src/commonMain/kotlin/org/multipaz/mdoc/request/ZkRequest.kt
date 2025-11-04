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
    fun toDataItem() = buildCborMap {
        putCborArray("systemSpecs") {
            systemSpecs.forEach { systemSpec ->
                addCborMap {
                    put("zkSystemId", systemSpec.id)
                    put("system", systemSpec.system)
                    putCborMap("params") {
                        systemSpec.params.forEach { param ->
                            put(param.key, param.value.toDataItem())
                        }
                    }
                }
            }
        }
        put("zkRequired", zkRequired)
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): ZkRequest {
            val systemSpecs = dataItem["systemSpecs"].asArray.map { spec ->
                val id = spec["zkSystemId"].asTstr
                val system = spec["system"].asTstr
                val systemSpec = ZkSystemSpec(
                    id = id,
                    system = system
                )
                for ((paramKey, paramValue) in spec["params"].asMap) {
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