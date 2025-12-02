package org.multipaz.mdoc.zkp.longfellow

import Longfellow.MDOC_PROVER_SUCCESS
import Longfellow.RequestedAttribute
import Longfellow.ZkSpecStruct
import Longfellow.run_mdoc_prover
import Longfellow.run_mdoc_verifier
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.bytestring.ByteString
import platform.posix.memcpy
import platform.posix.memset

internal actual object LongfellowNatives {
    actual fun getLongfellowZkSystemSpec(numAttributes: Int): LongfellowZkSystemSpec = TODO()

    actual fun generateCircuit(jzkSpec: LongfellowZkSystemSpec): ByteString = TODO()

    @OptIn(ExperimentalForeignApi::class)
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
        memScoped {
            val requestedAttributesArray: CPointer<RequestedAttribute> = allocArray(statements.size)
            statements.forEachIndexed { index, s ->
                val ra = requestedAttributesArray.get(index)

                require(s.namespace.length < 64)
                require(s.key.length < 32)
                require(s.value.size < 64)

                s.namespace.encodeToByteArray().usePinned { pinnedArray ->
                    memcpy(
                        __dst = ra.namespace_id.pointed.ptr,
                        __src = pinnedArray.addressOf(0),
                        __n = pinnedArray.get().size.toULong()
                    )
                }
                ra.namespace_len = s.namespace.length.toULong()

                s.key.encodeToByteArray().usePinned { pinnedArray ->
                    memcpy(
                        __dst = ra.id.pointed.ptr,
                        __src = pinnedArray.addressOf(0),
                        __n = pinnedArray.get().size.toULong()
                    )
                }
                ra.id_len = s.key.length.toULong()

                s.value.usePinned { pinnedArray ->
                    memcpy(
                        __dst = ra.cbor_value.pointed.ptr,
                        __src = pinnedArray.addressOf(0),
                        __n = pinnedArray.get().size.toULong()
                    )
                }
                ra.cbor_value_len = s.value.size.toULong()
            }

            /*
             * typedef struct {
             *   // The ZK system name and version- "longfellow-libzk-v*" for Google library.
             *   const char* system;
             *   // The hash of the compressed circuit (the way it's generated and passed to
             *   // prover/verifier)
             *   const char circuit_hash[65];
             *   // The number of attributes that the circuit supports.
             *   size_t num_attributes;
             *   // The version of the ZK specification.
             *   size_t version;
             *   // The block_enc parameter for the ZK proof.
             *   size_t block_enc_hash, block_enc_sig;
             * } ZkSpecStruct;
             */
            val systemName: CValuesRef<ByteVar> = "longfellow-libzk-v1".cstr

            val zkSpecStruct: ZkSpecStruct = alloc()
            zkSpecStruct.system = systemName.getPointer(this)
            memset(zkSpecStruct.circuit_hash, 0, 65UL)
            zkSpec.circuitHash.encodeToByteArray().usePinned { pinned ->
                val len = pinned.get().size
                require(len <= 64)
                memcpy(zkSpecStruct.circuit_hash, pinned.addressOf(0), len.toULong())
            }
            zkSpecStruct.num_attributes = zkSpec.numAttributes.toULong()
            zkSpecStruct.version = zkSpec.version.toULong()
            zkSpecStruct.block_enc_hash = zkSpec.blockEncHash.toULong()
            zkSpecStruct.block_enc_sig = zkSpec.blockEncSig.toULong()

            val proofPtr = alloc<CPointerVar<UByteVar>>().ptr
            val proofLenPtr = alloc<ULongVar>().ptr

            val rc = run_mdoc_prover(
                bcp = circuit.toByteArray().toUByteArray().refTo(0),
                bcsz = circuitSize.toULong(),
                mdoc = mdoc.toByteArray().toUByteArray().refTo(0),
                mdoc_len = mdocSize.toULong(),
                pkx = pkx,
                pky = pky,
                transcript = transcript.toByteArray().toUByteArray().refTo(0),
                tr_len = transcriptSize.toULong(),
                attrs = requestedAttributesArray,
                attrs_len = statements.size.toULong(),
                now = now,
                prf = proofPtr,
                proof_len = proofLenPtr,
                zk_spec_version = zkSpecStruct.ptr
            )
            if (rc == MDOC_PROVER_SUCCESS) {
                val proofBufferPointer: CPointer<UByteVar>? = proofPtr.pointed.value
                val proofLength = proofLenPtr.pointed.value.toUInt()
                if (proofBufferPointer != null) {
                    val proof = proofBufferPointer.readBytes(proofLength.toInt())
                    return proof
                } else {
                    throw ProofGenerationException("Proof generation returned MDOC_PROVER_SUCCESS but proof is empty")
                }
            } else {
                throw ProofGenerationException("Proof generation failed with error code $rc")
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
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
        memScoped {
            val requestedAttributesArray: CPointer<RequestedAttribute> = allocArray(statements.size)
            statements.forEachIndexed { index, s ->
                val ra = requestedAttributesArray.get(index)

                require(s.namespace.length < 64)
                require(s.key.length < 32)
                require(s.value.size < 64)

                s.namespace.encodeToByteArray().usePinned { pinnedArray ->
                    memcpy(
                        __dst = ra.namespace_id.pointed.ptr,
                        __src = pinnedArray.addressOf(0),
                        __n = pinnedArray.get().size.toULong()
                    )
                }
                ra.namespace_len = s.namespace.length.toULong()

                s.key.encodeToByteArray().usePinned { pinnedArray ->
                    memcpy(
                        __dst = ra.id.pointed.ptr,
                        __src = pinnedArray.addressOf(0),
                        __n = pinnedArray.get().size.toULong()
                    )
                }
                ra.id_len = s.key.length.toULong()

                s.value.usePinned { pinnedArray ->
                    memcpy(
                        __dst = ra.cbor_value.pointed.ptr,
                        __src = pinnedArray.addressOf(0),
                        __n = pinnedArray.get().size.toULong()
                    )
                }
                ra.cbor_value_len = s.value.size.toULong()
            }

            val systemName: CValuesRef<ByteVar> = "longfellow-libzk-v1".cstr

            val zkSpecStruct: ZkSpecStruct = alloc()
            zkSpecStruct.system = systemName.getPointer(this)
            memset(zkSpecStruct.circuit_hash, 0, 65UL)
            zkSpec.circuitHash.encodeToByteArray().usePinned { pinned ->
                val len = pinned.get().size
                require(len <= 64)
                memcpy(zkSpecStruct.circuit_hash, pinned.addressOf(0), len.toULong())
            }
            zkSpecStruct.num_attributes = zkSpec.numAttributes.toULong()
            zkSpecStruct.version = zkSpec.version.toULong()
            zkSpecStruct.block_enc_hash = zkSpec.blockEncHash.toULong()
            zkSpecStruct.block_enc_sig = zkSpec.blockEncSig.toULong()

            return run_mdoc_verifier(
                bcp = circuit.toByteArray().toUByteArray().refTo(0),
                bcsz = circuitSize.toULong(),
                pkx = pkx,
                pky = pky,
                transcript = transcript.toByteArray().toUByteArray().refTo(0),
                tr_len = transcriptSize.toULong(),
                attrs = requestedAttributesArray,
                attrs_len = statements.size.toULong(),
                now = now,
                zkproof = proof.toByteArray().toUByteArray().refTo(0),
                proof_len = proof.size.toULong(),
                docType = docType,
                zk_spec_version = zkSpecStruct.ptr
            ).toInt()
        }
    }
}