package org.multipaz.util

import org.multipaz.asn1.OID
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.X509CertChain
import org.multipaz.certext.MultipazExtension
import kotlinx.io.bytestring.ByteString
import org.multipaz.certext.fromCbor

private const val TAG = "validateCloudSecureAreaAttestation"

fun isCloudKeyAttestation(chain: X509CertChain): Boolean {
    return chain.certificates.first()
        .getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid) != null
}

suspend fun validateCloudKeyAttestation(
    chain: X509CertChain,
    nonce: ByteString,
    trustedRootKeys: Set<ByteString>
) {
    check(chain.validate()) {
        "Certificate chain did not validate"
    }
    val certificates = chain.certificates
    val leafX509Cert = certificates.first()
    val extensionDerEncodedString = leafX509Cert.getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid)
        ?: throw IllegalStateException(
            "No attestation extension at OID ${OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid}")

    val extension = MultipazExtension.fromCbor(extensionDerEncodedString)
    if (extension.cloudKeyAttestation == null || extension.cloudKeyAttestation.challenge != nonce) {
        throw IllegalStateException("Challenge in attestation does match expected nonce")
    }

    val rootPublicKey = certificates.last().ecPublicKey.toDataItem()
    val trusted = trustedRootKeys.firstOrNull { trustedKey ->
        Cbor.decode(trustedKey.toByteArray()) == rootPublicKey
    }

    if (trusted == null) {
        throw IllegalArgumentException("Unexpected cloud attestation root")
    }
}