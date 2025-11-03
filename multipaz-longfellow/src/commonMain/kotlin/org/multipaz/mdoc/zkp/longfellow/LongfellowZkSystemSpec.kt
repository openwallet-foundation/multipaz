package org.multipaz.mdoc.zkp.longfellow

/**
 * Kotlin representation of the native ZkSpecStruct
 *
 * @property system the ZK system name.
 * @property circuitHash the hash of the circuit.
 * @property numAttributes the number of attributes that the circuit supports.
 * @property version the version of the ZK spec.
 * @property blockEncHash
 * @property blockEncSig
 */
data class LongfellowZkSystemSpec(
    val system: String,
    val circuitHash: String,
    val numAttributes: Long,
    val version: Long,
    val blockEncHash: Long,
    val blockEncSig: Long
)
