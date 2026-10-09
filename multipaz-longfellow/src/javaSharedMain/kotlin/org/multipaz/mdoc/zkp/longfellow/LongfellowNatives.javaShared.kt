// Copyright 2026 The Multipaz Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.multipaz.mdoc.zkp.longfellow

import com.sun.jna.NativeLong
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.io.bytestring.ByteString

internal actual object LongfellowNatives {

    actual fun getLongfellowZkSystemSpec(numAttributes: Int): LongfellowZkSystemSpec {
        val specStruct = JnaZkSpecStruct()
        val rc = LongfellowCLibrary.instance.find_zk_spec_by_attributes(
            NativeLong(numAttributes.toLong()),
            NativeLong(0),
            specStruct
        )
        if (rc != 0) {
            error("Could not find ZkSpec for $numAttributes attributes")
        }
        val hashLen = specStruct.circuit_hash.indexOf(0).let { if (it < 0) 64 else it }
        val hashStr = String(specStruct.circuit_hash, 0, hashLen, Charsets.US_ASCII)
        return LongfellowZkSystemSpec(
            system = specStruct.system ?: "longfellow-libzk-v1",
            circuitHash = hashStr,
            numAttributes = specStruct.num_attributes.toLong(),
            version = specStruct.version.toLong(),
            blockEncHash = specStruct.block_enc_hash.toLong(),
            blockEncSig = specStruct.block_enc_sig.toLong()
        )
    }

    actual fun generateCircuit(jzkSpec: LongfellowZkSystemSpec): ByteString {
        val spec = toJnaSpec(jzkSpec)
        val cbRef = PointerByReference()
        val clenRef = NativeLongByReference()
        val rc = LongfellowCLibrary.instance.generate_circuit(spec, cbRef, clenRef)
        if (rc != LongfellowCLibrary.CIRCUIT_GENERATION_SUCCESS) {
            error("Failed to generate circuit, error code: $rc")
        }
        val cbPtr = cbRef.value
        val clenVal = clenRef.value.toInt()
        val circuitBytes = cbPtr.getByteArray(0, clenVal)
        LongfellowCLibrary.instance.free_circuit_bytes(cbPtr, clenRef.value)
        return ByteString(circuitBytes)
    }

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
        docType: String,
        zkSpec: LongfellowZkSystemSpec,
        statements: List<NativeAttribute>
    ): ByteArray {
        val spec = toJnaSpec(zkSpec)
        val jnaAttrs = toJnaAttributes(statements)
        val prfRef = PointerByReference()
        val proofLenRef = NativeLongByReference()

        val rc = LongfellowCLibrary.instance.run_mdoc_prover(
            bcp = circuit.toByteArray(),
            bcsz = NativeLong(circuitSize.toLong()),
            mdoc = mdoc.toByteArray(),
            mdoc_len = NativeLong(mdocSize.toLong()),
            pkx = pkx,
            pky = pky,
            transcript = transcript.toByteArray(),
            tr_len = NativeLong(transcriptSize.toLong()),
            attrs = jnaAttrs,
            attrs_len = NativeLong(statements.size.toLong()),
            now = now,
            prf = prfRef,
            proof_len = proofLenRef,
            doc_type = docType,
            zk_spec = spec
        )

        if (rc != LongfellowCLibrary.MDOC_PROVER_SUCCESS) {
            throw ProofGenerationException("Proof generation failed with error code $rc")
        }

        val proofPtr = prfRef.value
        val proofLen = proofLenRef.value.toInt()
        val proofBytes = proofPtr.getByteArray(0, proofLen)
        LongfellowCLibrary.instance.free_mdoc_proof(proofPtr, proofLenRef.value)
        return proofBytes
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
        val spec = toJnaSpec(zkSpec)
        val jnaAttrs = toJnaAttributes(statements.toList())

        return LongfellowCLibrary.instance.run_mdoc_verifier(
            bcp = circuit.toByteArray(),
            bcsz = NativeLong(circuitSize.toLong()),
            pkx = pkx,
            pky = pky,
            transcript = transcript.toByteArray(),
            tr_len = NativeLong(transcriptSize.toLong()),
            attrs = jnaAttrs,
            attrs_len = NativeLong(statements.size.toLong()),
            now = now,
            zkproof = proof.toByteArray(),
            proof_len = NativeLong(proofSize.toLong()),
            doc_type = docType,
            zk_spec = spec
        )
    }

    private fun toJnaSpec(zkSpec: LongfellowZkSystemSpec): JnaZkSpecStruct {
        val spec = JnaZkSpecStruct()
        spec.system = zkSpec.system
        val hashBytes = zkSpec.circuitHash.encodeToByteArray()
        System.arraycopy(hashBytes, 0, spec.circuit_hash, 0, minOf(hashBytes.size, 64))
        spec.num_attributes = NativeLong(zkSpec.numAttributes)
        spec.version = NativeLong(zkSpec.version)
        spec.block_enc_hash = NativeLong(zkSpec.blockEncHash)
        spec.block_enc_sig = NativeLong(zkSpec.blockEncSig)
        return spec
    }

    private fun toJnaAttributes(statements: List<NativeAttribute>): JnaRequestedAttribute? {
        if (statements.isEmpty()) return null
        val template = JnaRequestedAttribute()
        @Suppress("UNCHECKED_CAST")
        val array = template.toArray(statements.size) as Array<JnaRequestedAttribute>
        for (i in statements.indices) {
            val ra = array[i]
            val s = statements[i]
            val nsBytes = s.namespace.encodeToByteArray()
            val idBytes = s.key.encodeToByteArray()
            val valBytes = s.value

            System.arraycopy(nsBytes, 0, ra.namespace_id, 0, minOf(nsBytes.size, 64))
            ra.namespace_len = NativeLong(nsBytes.size.toLong())

            System.arraycopy(idBytes, 0, ra.id, 0, minOf(idBytes.size, 32))
            ra.id_len = NativeLong(idBytes.size.toLong())

            System.arraycopy(valBytes, 0, ra.cbor_value, 0, minOf(valBytes.size, 64))
            ra.cbor_value_len = NativeLong(valBytes.size.toLong())
        }
        array[0].write()
        return array[0]
    }
}
