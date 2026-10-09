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

use std::ffi::{c_char, CStr};
use std::slice;

use mdoc_zk_runtime::{
    MdocProverErrorCode, MdocVerifierErrorCode, RequestedAttribute,
    CURRENT_VERSION, CURRENT_ZK_SPECS, ZK_SPECS,
};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct RequestedAttributeC {
    pub namespace_id: [u8; 64],
    pub id: [u8; 32],
    pub cbor_value: [u8; 64],
    pub namespace_len: usize,
    pub id_len: usize,
    pub cbor_value_len: usize,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct ZkSpecStructC {
    pub system: *const c_char,
    pub circuit_hash: [c_char; 65],
    pub num_attributes: usize,
    pub version: usize,
    pub block_enc_hash: usize,
    pub block_enc_sig: usize,
}

#[repr(i32)]
pub enum CircuitGenerationErrorCode {
    Success = 0,
    NullInput = 1,
    ZlibFailure = 2,
    GeneralFailure = 3,
    InvalidZkSpecVersion = 4,
}

fn find_spec_from_c(zk_spec_c: *const ZkSpecStructC) -> Option<mdoc_zk_runtime::ZkSpecStruct> {
    if zk_spec_c.is_null() {
        return None;
    }
    unsafe {
        let c_spec = &*zk_spec_c;
        let hash_cstr = CStr::from_ptr(c_spec.circuit_hash.as_ptr());
        let hash_str = hash_cstr.to_str().unwrap_or("");

        // First attempt to match by circuit_hash
        if !hash_str.is_empty() {
            if let Some(spec) = ZK_SPECS.iter().find(|s| s.combined_hash_hex().eq_ignore_ascii_case(hash_str)) {
                return Some(*spec);
            }
            if let Some(spec) = CURRENT_ZK_SPECS.iter().find(|s| s.combined_hash_hex().eq_ignore_ascii_case(hash_str)) {
                return Some(*spec);
            }
        }

        // Next match by version and num_attributes
        if c_spec.version != 0 && c_spec.num_attributes != 0 {
            if let Some(spec) = ZK_SPECS.iter().find(|s| s.version == c_spec.version && s.num_attributes == c_spec.num_attributes) {
                return Some(*spec);
            }
            if c_spec.version == CURRENT_VERSION {
                if let Some(spec) = CURRENT_ZK_SPECS.iter().find(|s| s.num_attributes == c_spec.num_attributes) {
                    return Some(*spec);
                }
            }
        }
    }
    None
}

#[no_mangle]
pub unsafe extern "C" fn run_mdoc_prover(
    bcp: *const u8,
    bcsz: usize,
    mdoc: *const u8,
    mdoc_len: usize,
    pkx: *const c_char,
    pky: *const c_char,
    transcript: *const u8,
    tr_len: usize,
    attrs: *const RequestedAttributeC,
    attrs_len: usize,
    now: *const c_char,
    prf: *mut *mut u8,
    proof_len: *mut usize,
    doc_type: *const c_char,
    zk_spec: *const ZkSpecStructC,
) -> i32 {
    if bcp.is_null() || mdoc.is_null() || pkx.is_null() || pky.is_null()
        || transcript.is_null() || attrs.is_null() || now.is_null()
        || prf.is_null() || proof_len.is_null() || doc_type.is_null() || zk_spec.is_null() {
        return MdocProverErrorCode::NullInput as i32;
    }

    let spec = match find_spec_from_c(zk_spec) {
        Some(s) => s,
        None => return MdocProverErrorCode::InvalidZkSpecVersion as i32,
    };

    let pkx_str = match CStr::from_ptr(pkx).to_str() {
        Ok(s) => s,
        Err(_) => return MdocProverErrorCode::InvalidInput as i32,
    };
    let pky_str = match CStr::from_ptr(pky).to_str() {
        Ok(s) => s,
        Err(_) => return MdocProverErrorCode::InvalidInput as i32,
    };
    let now_str = match CStr::from_ptr(now).to_str() {
        Ok(s) => s,
        Err(_) => return MdocProverErrorCode::InvalidInput as i32,
    };
    let doc_type_str = match CStr::from_ptr(doc_type).to_str() {
        Ok(s) => s,
        Err(_) => return MdocProverErrorCode::InvalidInput as i32,
    };

    let circuit_slice = slice::from_raw_parts(bcp, bcsz);
    let mdoc_slice = slice::from_raw_parts(mdoc, mdoc_len);
    let transcript_slice = slice::from_raw_parts(transcript, tr_len);
    let c_attrs = slice::from_raw_parts(attrs, attrs_len);

    let mut rust_attrs = Vec::with_capacity(attrs_len);
    for a in c_attrs {
        let ns_len = a.namespace_len.min(64);
        let id_len = a.id_len.min(32);
        let val_len = a.cbor_value_len.min(64);
        rust_attrs.push(RequestedAttribute {
            namespace_id: a.namespace_id[..ns_len].to_vec(),
            id: a.id[..id_len].to_vec(),
            cbor_value: a.cbor_value[..val_len].to_vec(),
        });
    }

    match mdoc_zk_runtime::run_mdoc_prover(
        &spec,
        circuit_slice,
        mdoc_slice,
        pkx_str,
        pky_str,
        transcript_slice,
        &rust_attrs,
        now_str,
        doc_type_str,
    ) {
        Ok(proof_vec) => {
            let len = proof_vec.len();
            let dest = libc::malloc(len) as *mut u8;
            if dest.is_null() {
                return MdocProverErrorCode::MemoryAllocationFailure as i32;
            }
            std::ptr::copy_nonoverlapping(proof_vec.as_ptr(), dest, len);
            *prf = dest;
            *proof_len = len;
            MdocProverErrorCode::Success as i32
        }
        Err(err) => err as i32,
    }
}

#[no_mangle]
pub unsafe extern "C" fn run_mdoc_verifier(
    bcp: *const u8,
    bcsz: usize,
    pkx: *const c_char,
    pky: *const c_char,
    transcript: *const u8,
    tr_len: usize,
    attrs: *const RequestedAttributeC,
    attrs_len: usize,
    now: *const c_char,
    zkproof: *const u8,
    proof_len: usize,
    doc_type: *const c_char,
    zk_spec: *const ZkSpecStructC,
) -> i32 {
    if bcp.is_null() || pkx.is_null() || pky.is_null()
        || transcript.is_null() || attrs.is_null() || now.is_null()
        || zkproof.is_null() || doc_type.is_null() || zk_spec.is_null() {
        return MdocVerifierErrorCode::NullInput as i32;
    }

    let spec = match find_spec_from_c(zk_spec) {
        Some(s) => s,
        None => return MdocVerifierErrorCode::InvalidZkSpecVersion as i32,
    };

    let pkx_str = match CStr::from_ptr(pkx).to_str() {
        Ok(s) => s,
        Err(_) => return MdocVerifierErrorCode::InvalidInput as i32,
    };
    let pky_str = match CStr::from_ptr(pky).to_str() {
        Ok(s) => s,
        Err(_) => return MdocVerifierErrorCode::InvalidInput as i32,
    };
    let now_str = match CStr::from_ptr(now).to_str() {
        Ok(s) => s,
        Err(_) => return MdocVerifierErrorCode::InvalidInput as i32,
    };
    let doc_type_str = match CStr::from_ptr(doc_type).to_str() {
        Ok(s) => s,
        Err(_) => return MdocVerifierErrorCode::InvalidInput as i32,
    };

    let circuit_slice = slice::from_raw_parts(bcp, bcsz);
    let transcript_slice = slice::from_raw_parts(transcript, tr_len);
    let proof_slice = slice::from_raw_parts(zkproof, proof_len);
    let c_attrs = slice::from_raw_parts(attrs, attrs_len);

    let mut rust_attrs = Vec::with_capacity(attrs_len);
    for a in c_attrs {
        let ns_len = a.namespace_len.min(64);
        let id_len = a.id_len.min(32);
        let val_len = a.cbor_value_len.min(64);
        rust_attrs.push(RequestedAttribute {
            namespace_id: a.namespace_id[..ns_len].to_vec(),
            id: a.id[..id_len].to_vec(),
            cbor_value: a.cbor_value[..val_len].to_vec(),
        });
    }

    match mdoc_zk_runtime::run_mdoc_verifier(
        &spec,
        circuit_slice,
        pkx_str,
        pky_str,
        transcript_slice,
        &rust_attrs,
        now_str,
        doc_type_str,
        proof_slice,
    ) {
        Ok(()) => MdocVerifierErrorCode::Success as i32,
        Err(err) => err as i32,
    }
}

#[no_mangle]
pub unsafe extern "C" fn generate_circuit(
    zk_spec: *const ZkSpecStructC,
    cb: *mut *mut u8,
    clen: *mut usize,
) -> i32 {
    if zk_spec.is_null() || cb.is_null() || clen.is_null() {
        return CircuitGenerationErrorCode::NullInput as i32;
    }

    let c_spec = &*zk_spec;
    let version = c_spec.version;
    let num_attributes = c_spec.num_attributes;

    match mdoc_zk_runtime::materialize(version, num_attributes) {
        Ok(provided) => {
            let len = provided.compressed.len();
            let dest = libc::malloc(len) as *mut u8;
            if dest.is_null() {
                return CircuitGenerationErrorCode::GeneralFailure as i32;
            }
            std::ptr::copy_nonoverlapping(provided.compressed.as_ptr(), dest, len);
            *cb = dest;
            *clen = len;
            CircuitGenerationErrorCode::Success as i32
        }
        Err(_) => CircuitGenerationErrorCode::InvalidZkSpecVersion as i32,
    }
}

#[no_mangle]
pub unsafe extern "C" fn free_mdoc_proof(prf: *mut u8, _len: usize) {
    if !prf.is_null() {
        libc::free(prf as *mut libc::c_void);
    }
}

#[no_mangle]
pub unsafe extern "C" fn free_circuit_bytes(cb: *mut u8, _len: usize) {
    if !cb.is_null() {
        libc::free(cb as *mut libc::c_void);
    }
}

#[no_mangle]
pub unsafe extern "C" fn find_zk_spec_by_attributes(
    num_attributes: usize,
    version: usize,
    out: *mut ZkSpecStructC,
) -> i32 {
    if out.is_null() {
        return -1;
    }

    let spec_opt = if version == 0 {
        // Find latest version with matching num_attributes
        ZK_SPECS.iter().filter(|s| s.num_attributes == num_attributes).max_by_key(|s| s.version)
    } else {
        ZK_SPECS.iter().find(|s| s.num_attributes == num_attributes && s.version == version)
    };

    if let Some(spec) = spec_opt {
        let hex = spec.combined_hash_hex();
        let mut hash_arr = [0 as c_char; 65];
        for (i, b) in hex.bytes().enumerate().take(64) {
            hash_arr[i] = b as c_char;
        }

        // Static system string
        static SYSTEM_STR: &[u8] = b"longfellow-libzk-v1\0";

        *out = ZkSpecStructC {
            system: SYSTEM_STR.as_ptr() as *const c_char,
            circuit_hash: hash_arr,
            num_attributes: spec.num_attributes,
            version: spec.version,
            block_enc_hash: spec.ligero_hash.block_enc,
            block_enc_sig: spec.ligero_sig.block_enc,
        };
        0
    } else {
        -1
    }
}
