package org.multipaz.compose.certificateviewer

import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Boolean
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.OID
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.crypto.X509Cert
import org.multipaz.certext.MultipazExtension
import org.multipaz.util.AndroidAttestationExtensionParser
import org.multipaz.util.toHex
import org.multipaz.util.unsignedBigIntToString
import org.multipaz.certext.fromCbor

/**
 * View Model immutable data.
 * All data fields of the Certificate as needed for display on the screen.
 * Also defining and using two extension methods to map Certificate fields.
 */
internal data class CertificateViewData(
    val serialNumber: String,
    val version: String,
    val validFrom: Instant,
    val validUntil: Instant,
    val subject: Map<String, String> = emptyMap(),
    val issuer: Map<String, String> = emptyMap(),
    val pkAlgorithm: String,
    val pkNamedCurve: String?,
    val pkValue: String,
    val extensions: List<Triple<Boolean, String, String>> = emptyList(),
    val pem: String,
) {

    companion object {

        /** Map Certificate data to View fields. */
        fun from(cert: X509Cert): CertificateViewData {
            val version = runCatching { cert.version.toString() }
                .getOrElse { _ -> "" }
                .let { versionString ->
                    // Per RFC 5280.
                    when (versionString) {
                        "" -> "1" // No version means version 1.
                        "1" -> "2"
                        "2" -> "3"
                        else -> versionString
                    }
                }

            val serialNumber: String = with(cert.serialNumber) {
                if (value.size <= 8) {
                    toLong().toString()
                } else {
                    value.unsignedBigIntToString()
                }
            }

            val validFrom = cert.validityNotBefore
            val validUntil = cert.validityNotAfter

            val subject = cert.subject.components.mapValues { it.value.value }

            val issuer = cert.issuer.components.mapValues { it.value.value }

            val pkAlgorithm: String = OID.lookupByOid(cert.signatureAlgorithmOid)?.description
                    ?: "Unexpected algorithm OID ${cert.signatureAlgorithmOid}"

            val pkNamedCurve: String? = runCatching { cert.ecPublicKey.curve.name }.getOrNull()

            val pkValue: String = ASN1.encode(
                (ASN1.decode(cert.tbsCertificate) as ASN1Sequence).elements[6]
            ).toHex(byteDivider = " ")

            val extensions = formatExtensions(cert)

            return CertificateViewData(
                serialNumber,
                version,
                validFrom,
                validUntil,
                subject,
                issuer,
                pkAlgorithm,
                pkNamedCurve,
                pkValue,
                extensions,
                cert.toPem()
            )
        }

        private fun formatExtensions(cert: X509Cert): List<Triple<Boolean, String, String>> {

            return cert.extensions.map { ext ->
                    val displayValue = when (ext.oid) {
                        // Known extensions data is formatted specifically by OID.
                        OID.X509_EXTENSION_SUBJECT_KEY_IDENTIFIER.oid ->
                            cert.subjectKeyIdentifier!!.toHex(byteDivider = " ")

                        OID.X509_EXTENSION_KEY_USAGE.oid ->
                            cert.keyUsage.joinToString(", ") { it.description }

                        OID.X509_EXTENSION_BASIC_CONSTRAINTS.oid -> {
                            try {
                                val seq = ASN1.decode(ext.data.toByteArray()) as ASN1Sequence
                                val sb = StringBuilder("CA: ${(seq.elements[0] as ASN1Boolean).value}\n")
                                if (seq.elements.size > 1) {
                                    sb.append("pathLenConstraint: ${(seq.elements[1] as ASN1Integer).toLong()}\n")
                                }
                                sb.toString()
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                "Error decoding: $e"
                            }
                        }

                        OID.X509_EXTENSION_AUTHORITY_KEY_IDENTIFIER.oid ->
                            cert.authorityKeyIdentifier!!.toHex(byteDivider = " ")

                        OID.X509_EXTENSION_ANDROID_KEYSTORE_ATTESTATION.oid ->
                            AndroidAttestationExtensionParser(cert).prettyPrint()

                        OID.X509_EXTENSION_ANDROID_KEYSTORE_PROVISIONING_INFORMATION.oid ->
                            Cbor.toDiagnostics(
                                ext.data.toByteArray(),
                                setOf(DiagnosticOption.PRETTY_PRINT),
                            )

                        OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid ->
                            MultipazExtension.fromCbor(ext.data.toByteArray()).prettyPrint()

                        else -> {
                            try {
                                // Most extensions are ASN.1 so we opportunistically try and decode
                                // and if it works, pretty print that.
                                ASN1.print(ASN1.decode(ext.data.toByteArray())!!)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                // If decoding fails, fall back to hex encoding.
                                ext.data.toByteArray().toHex(byteDivider = " ", decodeAsString = true)
                            }
                        }
                    }
                    val oidEntry = OID.lookupByOid(ext.oid)
                    val oid = if (oidEntry != null) {
                        "${ext.oid} ${oidEntry.description}"
                    } else {
                        ext.oid
                    }
                    Triple(ext.isCritical, oid, displayValue.trim())
                }

        }
    }
}