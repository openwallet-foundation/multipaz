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

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

@Structure.FieldOrder("namespace_id", "id", "cbor_value", "namespace_len", "id_len", "cbor_value_len")
internal open class JnaRequestedAttribute : Structure() {
    @JvmField var namespace_id = ByteArray(64)
    @JvmField var id = ByteArray(32)
    @JvmField var cbor_value = ByteArray(64)
    @JvmField var namespace_len = NativeLong(0)
    @JvmField var id_len = NativeLong(0)
    @JvmField var cbor_value_len = NativeLong(0)
}

@Structure.FieldOrder("system", "circuit_hash", "num_attributes", "version", "block_enc_hash", "block_enc_sig")
internal open class JnaZkSpecStruct : Structure() {
    @JvmField var system: String? = null
    @JvmField var circuit_hash = ByteArray(65)
    @JvmField var num_attributes = NativeLong(0)
    @JvmField var version = NativeLong(0)
    @JvmField var block_enc_hash = NativeLong(0)
    @JvmField var block_enc_sig = NativeLong(0)
}

internal interface LongfellowCLibrary : Library {
    fun run_mdoc_prover(
        bcp: ByteArray,
        bcsz: NativeLong,
        mdoc: ByteArray,
        mdoc_len: NativeLong,
        pkx: String,
        pky: String,
        transcript: ByteArray,
        tr_len: NativeLong,
        attrs: JnaRequestedAttribute?,
        attrs_len: NativeLong,
        now: String,
        prf: PointerByReference,
        proof_len: NativeLongByReference,
        doc_type: String,
        zk_spec: JnaZkSpecStruct
    ): Int

    fun run_mdoc_verifier(
        bcp: ByteArray,
        bcsz: NativeLong,
        pkx: String,
        pky: String,
        transcript: ByteArray,
        tr_len: NativeLong,
        attrs: JnaRequestedAttribute?,
        attrs_len: NativeLong,
        now: String,
        zkproof: ByteArray,
        proof_len: NativeLong,
        doc_type: String?,
        zk_spec: JnaZkSpecStruct
    ): Int

    fun generate_circuit(
        zk_spec: JnaZkSpecStruct,
        cb: PointerByReference,
        clen: NativeLongByReference
    ): Int

    fun free_mdoc_proof(prf: Pointer?, len: NativeLong)

    fun free_circuit_bytes(cb: Pointer?, len: NativeLong)

    fun find_zk_spec_by_attributes(
        num_attributes: NativeLong,
        version: NativeLong,
        out: JnaZkSpecStruct
    ): Int

    companion object {
        const val MDOC_PROVER_SUCCESS = 0
        const val MDOC_VERIFIER_SUCCESS = 0
        const val CIRCUIT_GENERATION_SUCCESS = 0

        val instance: LongfellowCLibrary by lazy {
            val libPathOrName = NativeLoader.loadLibrary("zkp") ?: "zkp"
            Native.load(libPathOrName, LongfellowCLibrary::class.java)
        }
    }
}
