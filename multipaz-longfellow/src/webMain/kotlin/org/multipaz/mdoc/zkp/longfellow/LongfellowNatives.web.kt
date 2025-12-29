package org.multipaz.mdoc.zkp.longfellow

import kotlinx.io.bytestring.ByteString

internal actual object LongfellowNatives {
    actual fun getLongfellowZkSystemSpec(numAttributes: Int): LongfellowZkSystemSpec = TODO()

    actual fun generateCircuit(jzkSpec: LongfellowZkSystemSpec): ByteString = TODO()

    actual fun runMdocProver(
        circuit: ByteString,
        circuitSize: Int,
        mdoc: ByteString,
        mdocSize: Int,
        pkx: String,
        pky: String,
        transcript: ByteString,
        transcriptSize: Int,
        now: String,
        zkSpec: LongfellowZkSystemSpec,
        statements: List<NativeAttribute>
    ): ByteArray {
        TODO()
    }

    actual fun runMdocVerifier(
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
    ): Int {
        TODO()
    }
}