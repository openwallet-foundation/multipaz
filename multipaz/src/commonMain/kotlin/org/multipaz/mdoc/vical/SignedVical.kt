package org.multipaz.mdoc.vical

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain

/**
 * A signed VICAL according to ISO/IEC 18013-5:2021.
 *
 * @property vical the VICAL that is signed.
 * @property vicalProviderCertificateChain the X.509 certificate chain.
 */
data class SignedVical(
    val vical: Vical,
    val vicalProviderCertificateChain: X509CertChain,
) {
    /**
     * Generates a VICAL
     *
     * @param signingKey the key used to sign the VICAL. This must match the public key in the leaf
     *    certificate in `vicalProviderCertificateChain`.
     * @return the bytes of the CBOR encoded COSE_Sign1 with the VICAL.
     */
    suspend fun generate(
        signingKey: AsymmetricKey
    ): ByteArray {
        val encodedVical = Cbor.encode(
            buildCborMap {
                put("version", vical.version)
                put("vicalProvider", vical.vicalProvider)
                put("date", vical.date.toDataItemDateTimeString())
                vical.nextUpdate?.let { put("nextUpdate", it.toDataItemDateTimeString())}
                vical.vicalIssueID?.let { put("vicalIssueID", it.toDataItem()) }
                putCborArray("certificateInfos") {
                    for (certInfo in vical.certificateInfos) {
                        addCborMap {
                            put("certificate", certInfo.certificate.encoded.toByteArray())
                            put("serialNumber", Tagged(
                                Tagged.UNSIGNED_BIGNUM,
                                Bstr(certInfo.certificate.serialNumber.value)
                            ))
                            put("ski", certInfo.certificate.subjectKeyIdentifier!!)
                            putCborArray("docType") {
                                certInfo.docTypes.forEach { add(it) }
                            }
                            end()
                        }
                    }
                }
            }
        )
        val signature = Cose.coseSign1Sign(
            signingKey = signingKey,
            message = encodedVical,
            includeMessageInPayload = true,
            protectedHeaders = mapOf(
                CoseNumberLabel(Cose.COSE_LABEL_ALG) to
                    signingKey.algorithm.coseAlgorithmIdentifier!!.toDataItem()
            ),
            unprotectedHeaders = mapOf(
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to
                    vicalProviderCertificateChain.toDataItem()
            )
        )
        return Cbor.encode(signature.toDataItem())
    }

    companion object {
        private const val TAG = "SignedVical"

        /**
         * Parses a signed VAL.
         *
         * This takes a `COSE_Sign1` according to ISO/IEC 18013-5:2021 section
         * C.1.7.1 VICAL CDDL profile.
         *
         * This includes checking that the VICAL is signed by the key in the leaf certificate
         * of the X.509 certificate chain. It is not checked that the certificate chain is
         * well-formed.
         *
         * @param encodedSignedVical the encoded CBOR with the COSE_Sign1 described above.
         * @param disableSignatureVerification set to `true` to disable signature verification.
         * @return a `SignedVical` instance.
         * @throws IllegalArgumentException if the passed in signed VICAL is malformed
         * @throws SignatureVerificationException if signature verification failed.
         */
        fun parse(
            encodedSignedVical: ByteArray,
            disableSignatureVerification: Boolean = false
        ): SignedVical {
            val signature = CoseSign1.fromDataItem(Cbor.decode(encodedSignedVical))

            val vicalPayload = signature?.payload
                ?: throw IllegalArgumentException("Unexpected null payload for signed VICAL")

            val certChain = signature.unprotectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)]?.asX509CertChain
                ?: throw IllegalArgumentException("x5chain not set")

            val signatureAlgorithm = signature.protectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_ALG)]?.asNumber?.toInt()
                ?.let { Algorithm.fromCoseAlgorithmIdentifier(it) }
                ?: throw IllegalArgumentException("Signature Algorithm not set")

            if (!disableSignatureVerification) {
                Cose.coseSign1Check(
                    certChain.certificates.first().ecPublicKey,
                    null,
                    signature,
                    signatureAlgorithm
                )
            }

            val vicalMap = Cbor.decode(vicalPayload)
            val version = vicalMap["version"].asTstr
            val vicalProvider = vicalMap["vicalProvider"].asTstr
            val date = vicalMap["date"].asDateTimeString
            val nextUpdate = vicalMap.getOrNull("nextUpdate")?.asDateTimeString
            val vicalIssueID = vicalMap.getOrNull("vicalIssueID")?.asNumber

            val certificateInfos = mutableListOf<VicalCertificateInfo>()

            for (certInfo in (vicalMap["certificateInfos"] as CborArray).items) {
                val ski = ByteString(certInfo["ski"].asBstr)
                val certBytes = certInfo["certificate"].asBstr
                val docType = (certInfo["docType"] as CborArray).items.map { it.asTstr }
                val certProfiles = certInfo.getOrNull("certificateProfile")?.let {
                    (it as CborArray).items.map { it.asTstr }
                }
                certificateInfos.add(VicalCertificateInfo(
                    certificate = X509Cert(ByteString(certBytes)),
                    ski = ski,
                    issuingAuthority = certInfo.getOrNull("issuingAuthority")?.asTstr,
                    issuingCountry = certInfo.getOrNull("issuingCountry")?.asTstr,
                    stateOrProvinceName = certInfo.getOrNull("stateOrProvinceName")?.asTstr,
                    docTypes = docType,
                    certificateProfiles = certProfiles,
                ))
            }

            val vical = Vical(
                version,
                vicalProvider,
                date,
                nextUpdate,
                vicalIssueID,
                certificateInfos
            )

            return SignedVical(vical, certChain)
        }
    }
}