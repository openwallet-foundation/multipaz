package org.multipaz.mdoc.zkp.longfellow

import kotlinx.io.bytestring.ByteString

internal expect object LongfellowNatives {
    fun getLongfellowZkSystemSpec(numAttributes: Int): LongfellowZkSystemSpec

    fun generateCircuit(jzkSpec: LongfellowZkSystemSpec): ByteString

    fun runMdocProver(
        circuit: ByteString,
        circuitSize: Int,
        mdoc: ByteString,
        mdocSize: Int,
        pkx: String,
        pky: String,
        transcript: ByteString,
        transcriptSize: Int,
        now: String,
        docType: String,
        zkSpec: LongfellowZkSystemSpec,
        statements: List<NativeAttribute>
    ): ByteArray

    fun runMdocVerifier(
        circuit: ByteString,
        circuitSize: Int,
        pkx: String,
        pky: String,
        transcript: ByteString,
        transcriptSize: Int,
        now: String,
        proof: ByteString,
        proofSize: Int,
        docType: String,
        zkSpec: LongfellowZkSystemSpec,
        statements: Array<NativeAttribute>
    ): Int
}
