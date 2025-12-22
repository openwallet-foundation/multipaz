package org.multipaz.mdoc.rical

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.Logger

data class SignedRical(
    val rical: Rical,
    val ricalProviderCertificateChain: X509CertChain,
) {
    /**
     * Generates a RICAL
     *
     * @param signingKey the key used to sign the RICAL. This must match the public key in the leaf
     *    certificate in [ricalProviderCertificateChain].
     * @return the bytes of the CBOR encoded COSE_Sign1 with the rical.
     */
    suspend fun generate(
        signingKey: AsymmetricKey
    ): ByteArray {
        val encodedRical = Cbor.encode(
            buildCborMap {
                put("version", rical.version)
                put("provider", rical.provider)
                put("date", rical.date.toDataItemDateTimeString())
                put("type", rical.type)
                rical.nextUpdate?.let { put("nextUpdate", it.toDataItemDateTimeString()) }
                rical.notAfter?.let { put("notAfter", it.toDataItemDateTimeString()) }
                putCborArray("certificateInfos") {
                    for (certInfo in rical.certificateInfos) {
                        addCborMap {
                            put("certificate", certInfo.certificate.encoded.toByteArray())
                            put(
                                "serialNumber", Tagged(
                                    Tagged.UNSIGNED_BIGNUM,
                                    Bstr(certInfo.certificate.serialNumber.value)
                                )
                            )
                            put("ski", certInfo.certificate.subjectKeyIdentifier!!)
                            put("serialNumber", Tagged(
                                tagNumber = Tagged.UNSIGNED_BIGNUM,
                                taggedItem = Bstr(certInfo.serialNumber.toByteArray())
                            ))
                            certInfo.type?.let { put("type", it) }
                            certInfo.name?.let { put("name", it) }
                            certInfo.extensions?.let {
                                putCborMap("extensions") {
                                    it.forEach { (extName, extValue) -> put(extName, extValue) }
                                }
                            }
                        }
                    }
                }
                rical.id?.let { put("id", it.toDataItem()) }
                rical.latestRicalUrl?.let { put("latestRicalUrl", it.toDataItem()) }
                rical.extensions?.let {
                    putCborMap("extensions") {
                        it.forEach { (extName, extValue) -> put(extName, extValue) }
                    }
                }
            }
        )
        val signature = Cose.coseSign1Sign(
            signingKey = signingKey,
            message = encodedRical,
            includeMessageInPayload = true,
            // Note: Unlike the VICAL, the x5chain appears in the protected header, not unprotected.
            protectedHeaders = mapOf(
                CoseNumberLabel(Cose.COSE_LABEL_ALG) to
                        signingKey.algorithm.coseAlgorithmIdentifier!!.toDataItem(),
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to
                        ricalProviderCertificateChain.toDataItem()
            ),
            unprotectedHeaders = emptyMap()
        )
        return Cbor.encode(signature.toDataItem())
    }

    companion object {
        private const val TAG = "SignedRical"

        /**
         * Parses a signed RICAL.
         *
         * This takes a `COSE_Sign1` according to ISO/IEC 18013-5 Second Edition Annex F.
         *
         * This includes checking that the RICAL is signed by the key in the leaf certificate
         * of the X.509 certificate chain. It is not checked that the certificate chain is
         * well-formed.
         *
         * @param encodedSignedRical the encoded CBOR with the COSE_Sign1 described above.
         * @param disableSignatureVerification set to `true` to disable signature verification.
         * @return a [SignedRical] instance.
         * @throws IllegalArgumentException if the passed in signed RICAL is malformed
         * @throws SignatureVerificationException if signature verification failed.
         */
        suspend fun parse(
            encodedSignedRical: ByteArray,
            disableSignatureVerification: Boolean = false
        ): SignedRical {
            val signature = CoseSign1.fromDataItem(Cbor.decode(encodedSignedRical))

            val ricalPayload = signature?.payload
                ?: throw IllegalArgumentException("Unexpected null payload for signed VICAL")

            val certChain = signature.protectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)]?.asX509CertChain
                ?: throw IllegalArgumentException("x5chain not set in protected header")

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

            //qLogger.iCbor(TAG, "RICAL", ricalPayload)

            val ricalMap = Cbor.decode(ricalPayload)
            val version = ricalMap["version"].asTstr
            val provider = ricalMap["provider"].asTstr
            val date = ricalMap["date"].asDateTimeString
            val type = ricalMap["type"].asTstr
            val nextUpdate = ricalMap.getOrNull("nextUpdate")?.asDateTimeString
            val notAfter = ricalMap.getOrNull("notAfter")?.asDateTimeString
            val ricalIssueID = ricalMap.getOrNull("id")?.asNumber
            val latestRicalUrl = ricalMap.getOrNull("latestRicalUrl")?.asTstr
            val extensions = ricalMap.getOrNull("extensions")?.let {
                it.asMap.entries.associate { (extName, extValue) -> Pair(extName.asTstr, extValue) }
            }

            val certificateInfos = mutableListOf<RicalCertificateInfo>()
            for (certInfo in (ricalMap["certificateInfos"] as CborArray).items) {
                val ski = ByteString(certInfo["ski"].asBstr)
                val certBytes = certInfo["certificate"].asBstr
                val extensionsInCertInfo = certInfo.getOrNull("extensions")?.let {
                    it.asMap.entries.associate { (extName, extValue) -> Pair(extName.asTstr, extValue) }
                }
                val serialNumberTaggedItem = certInfo["serialNumber"] as Tagged
                require(serialNumberTaggedItem.tagNumber == Tagged.UNSIGNED_BIGNUM)
                certificateInfos.add(RicalCertificateInfo(
                    certificate = X509Cert(ByteString(certBytes)),
                    serialNumber = ByteString(serialNumberTaggedItem.taggedItem.asBstr),
                    ski = ski,
                    type = certInfo.getOrNull("type")?.asTstr,
                    name = certInfo.getOrNull("name")?.asTstr,
                    issuingCountry = certInfo.getOrNull("issuingCountry")?.asTstr,
                    stateOrProvinceName = certInfo.getOrNull("stateOrProvinceName")?.asTstr,
                    extensions = extensionsInCertInfo
                ))
            }

            val rical = Rical(
                type = type,
                version = version,
                provider = provider,
                date = date,
                nextUpdate = nextUpdate,
                notAfter = notAfter,
                certificateInfos = certificateInfos,
                id = ricalIssueID,
                latestRicalUrl = latestRicalUrl,
                extensions = extensions
            )

            return SignedRical(rical, certChain)
        }
    }
}
