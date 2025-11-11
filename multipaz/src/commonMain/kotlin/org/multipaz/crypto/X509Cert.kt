package org.multipaz.crypto

import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1Boolean
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Object
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.ASN1Time
import org.multipaz.asn1.OID
import org.multipaz.cbor.DataItem
import org.multipaz.util.Logger
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializationImplemented
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A data type for a X.509 certificate.
 *
 * @param encoded the bytes of the X.509 certificate in DER encoding.
 */
@CborSerializationImplemented(schemaId = "")
data class X509Cert(
    override val encoded: ByteString
): X509Signed() {
    override val name: String get() = NAME
    override val extensionTag: Int = EXTENSION_TAG

    private val tbsCert: NormalizedTbs by lazy {
        NormalizedTbs.from(parsed.elements[0] as ASN1Sequence)
    }

    /**
     * The certificate version.
     *
     * This returns the encoded value and for X.509 Version 3 Certificate (the most common
     * version in use) this value is 2.
     */
    val version: Int
        get() {
            val child = ASN1.decode((tbsCert.elements[0] as ASN1TaggedObject).content)
            val versionCode = (child as ASN1Integer).toLong().toInt()
            return versionCode
        }

    /**
     * The certificate serial number.
     */
    val serialNumber: ASN1Integer
        get() = (tbsCert.elements[1] as ASN1Integer)

    /**
     * The subject of the certificate.
     */
    val subject: X500Name
        get() = parseName(tbsCert.elements[5] as ASN1Sequence)

    /**
     * The issuer of the certificate.
     */
    val issuer: X500Name
        get() = parseName(tbsCert.elements[3] as ASN1Sequence)

    /**
     * The point in time where the certificate is valid from.
     */
    val validityNotBefore: Instant
        get() = ((tbsCert.elements[4] as ASN1Sequence).elements[0] as ASN1Time).value

    /**
     * The point in time where the certificate is valid until.
     */
    val validityNotAfter: Instant
        get() = ((tbsCert.elements[4] as ASN1Sequence).elements[1] as ASN1Time).value

    /**
     * The public key in the certificate, as an Elliptic Curve key.
     *
     * Note that this is only supported for curves in [Crypto.supportedCurves].
     *
     * @throws IllegalStateException if the public key for the certificate isn't an EC key or
     * its EC curve isn't supported by the platform.
     */
    val ecPublicKey: EcPublicKey
        get() {
            val subjectPublicKeyInfo = tbsCert.elements[6] as ASN1Sequence
            val algorithmIdentifier = subjectPublicKeyInfo.elements[0] as ASN1Sequence
            val algorithmOid = (algorithmIdentifier.elements[0] as ASN1ObjectIdentifier).oid
            val curve = when (algorithmOid) {
                // https://datatracker.ietf.org/doc/html/rfc5480#section-2.1.1
                OID.EC_PUBLIC_KEY.oid -> {
                    val ecCurveString = (algorithmIdentifier.elements[1] as ASN1ObjectIdentifier).oid
                    when (ecCurveString) {
                        "1.2.840.10045.3.1.7" -> EcCurve.P256
                        "1.3.132.0.34" -> EcCurve.P384
                        "1.3.132.0.35" -> EcCurve.P521
                        "1.3.36.3.3.2.8.1.1.7" -> EcCurve.BRAINPOOLP256R1
                        "1.3.36.3.3.2.8.1.1.9" -> EcCurve.BRAINPOOLP320R1
                        "1.3.36.3.3.2.8.1.1.11" -> EcCurve.BRAINPOOLP384R1
                        "1.3.36.3.3.2.8.1.1.13" -> EcCurve.BRAINPOOLP512R1
                        else -> throw IllegalStateException("Unexpected curve OID $ecCurveString")
                    }
                }
                "1.3.101.110" -> EcCurve.X25519
                "1.3.101.111" -> EcCurve.X448
                "1.3.101.112" -> EcCurve.ED25519
                "1.3.101.113" -> EcCurve.ED448
                else -> throw IllegalStateException("Unexpected OID $algorithmOid")
            }
            val keyMaterial = (subjectPublicKeyInfo.elements[1] as ASN1BitString).value
            return when (curve) {
                EcCurve.P256,
                EcCurve.P384,
                EcCurve.P521,
                EcCurve.BRAINPOOLP256R1,
                EcCurve.BRAINPOOLP320R1,
                EcCurve.BRAINPOOLP384R1,
                EcCurve.BRAINPOOLP512R1 -> {
                    EcPublicKeyDoubleCoordinate.fromUncompressedPointEncoding(curve, keyMaterial)
                }
                EcCurve.ED25519,
                EcCurve.X25519,
                EcCurve.ED448,
                EcCurve.X448 -> {
                    EcPublicKeyOkp(curve, keyMaterial)
                }
            }
        }

    /**
     * The subject key identifier (OID 2.5.29.14), or `null` if not present in the certificate.
     */
    val subjectKeyIdentifier: ByteArray?
        get() {
            val extVal = getExtensionValue(OID.X509_EXTENSION_SUBJECT_KEY_IDENTIFIER.oid) ?: return null
            return (ASN1.decode(extVal) as ASN1OctetString).value
        }

    /**
     * The authority key identifier (OID 2.5.29.35), or `null` if not present in the certificate.
     */
    val authorityKeyIdentifier: ByteArray?
        get() {
            val extVal = getExtensionValue(OID.X509_EXTENSION_AUTHORITY_KEY_IDENTIFIER.oid) ?: return null
            val seq = ASN1.decode(extVal) as ASN1Sequence
            val taggedObject = seq.elements[0] as ASN1TaggedObject
            check(taggedObject.cls == ASN1TagClass.CONTEXT_SPECIFIC) { "Expected context-specific tag" }
            check(taggedObject.enc == ASN1Encoding.PRIMITIVE)
            check(taggedObject.tag == 0) { "Expected tag 0" }
            // Note: tags in AuthorityKeyIdentifier are IMPLICIT b/c its definition appear in
            // the implicitly tagged ASN.1 module, see RFC 5280 Appendix A.2.
            //
            return taggedObject.content
        }

    /**
     * The key usage (OID 2.5.29.15) or the empty set if not present.
     */
    val keyUsage: Set<X509KeyUsage>
        get() {
            val extVal = getExtensionValue(OID.X509_EXTENSION_KEY_USAGE.oid) ?: return emptySet()
            return X509KeyUsage.decodeSet(ASN1.decode(extVal) as ASN1BitString)
        }

    companion object {
        private const val NAME = "CERTIFICATE"
        private const val EXTENSION_TAG = 0x3

        /**
         * Creates a [X509Cert] from a PEM encoded string.
         *
         * @param pemEncoding the PEM encoded string.
         * @return a new [X509Cert].
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String): X509Cert =
            X509Cert(fromPemHelper(pemEncoding, NAME))

        /**
         * Gets a [X509Cert] from a [DataItem].
         *
         * @param dataItem the data item, must have been encoded with [toDataItem].
         * @return the certificate.
         */
        fun fromDataItem(dataItem: DataItem): X509Cert {
            return X509Cert(ByteString(dataItem.asBstr))
        }
    }

    /**
     * Builder for X.509 certificate.
     *
     * @param publicKey the public key for the certificate.
     * @param signingKey the key to sign the TBSCertificate with.
     * @param serialNumber the serial number in the certificate.
     * @param subject the subject of the certificate.
     * @param issuer the issuer of the certificate.
     * @param validFrom the point in time the certificate is valid from.
     * @param validUntil the point in time the certificate is valid until.
     */
    class Builder(
        private val publicKey: EcPublicKey,
        signingKey: AsymmetricKey,
        private val serialNumber: ASN1Integer,
        private val subject: X500Name,
        issuer: X500Name,
        private val validFrom: Instant,
        private val validUntil: Instant,
    ): X509SignedBuilder<Builder>(signingKey, issuer) {
        override val self get() = this
        override val extensionTag: Int = EXTENSION_TAG

        private var includeSubjectKeyIdentifierFlag: Boolean = false
        private var includeAuthorityKeyIdentifierAsSubjectKeyIdentifierFlag: Boolean = false

        /**
         * Generate and include the Subject Key Identifier extension .
         *
         * The extension will be marked as non-critical.
         *
         * @param `true` to include the Subject Key Identifier, `false to not.
         * @return the builder.
         */
        fun includeSubjectKeyIdentifier(value: Boolean = true): Builder {
            includeSubjectKeyIdentifierFlag = value
            return this
        }

        /**
         * Set the Authority Key Identifier with keyIdentifier set to the same value as the
         * Subject Key Identifier.
         *
         * This is only meaningful when creating a self-signed certificate.
         *
         * The extension will be marked as non-critical.
         *
         * @param `true` to include the Authority Key Identifier, `false to not.
         * @return the builder.
         */
        fun includeAuthorityKeyIdentifierAsSubjectKeyIdentifier(value: Boolean = true): Builder {
            includeAuthorityKeyIdentifierAsSubjectKeyIdentifierFlag = value
            return this
        }

        /**
         * Sets Authority Key Identifier extension to the Subject Key Identifier of another certificate.
         *
         * The extension will be marked as non-critical.
         *
         * @param certificate the certificate to get the Subject Key Identifier from.
         * @return the builder.
         */
        fun setAuthorityKeyIdentifierToCertificate(certificate: X509Cert): Builder {
            addExtension(
                OID.X509_EXTENSION_AUTHORITY_KEY_IDENTIFIER.oid,
                false,
                // Note: AuthorityKeyIdentifier uses IMPLICIT tags
                ASN1.encode(
                    ASN1Sequence(listOf(
                        ASN1TaggedObject(
                            ASN1TagClass.CONTEXT_SPECIFIC,
                            ASN1Encoding.PRIMITIVE,
                            0,
                            certificate.subjectKeyIdentifier!!
                        )
                    ))
                )
            )
            return this
        }

        /**
         * Sets the key usage.
         *
         * @param keyUsage a set of [X509KeyUsage].
         * @return the builder
         */
        fun setKeyUsage(keyUsage: Set<X509KeyUsage>): Builder {
            addExtension(
                OID.X509_EXTENSION_KEY_USAGE.oid,
                true,
                ASN1.encode(X509KeyUsage.encodeSet(keyUsage))
            )
            return this
        }

        /**
         * Sets the basic constraints of the certificate
         *
         * @param ca the CA flag.
         * @param pathLenConstraint the path length constraint value.
         * @return the builder.
         */
        fun setBasicConstraints(
            ca: Boolean,
            pathLenConstraint: Int?,
        ): Builder {
            val seq = mutableListOf<ASN1Object>(
                ASN1Boolean(ca)
            )
            if (pathLenConstraint != null) {
                seq.add(ASN1Integer(pathLenConstraint.toLong()))
            }
            addExtension(
                OID.X509_EXTENSION_BASIC_CONSTRAINTS.oid,
                true,
                ASN1.encode(ASN1Sequence(seq))
            )
            return this
        }

        /**
         * Builds the [X509Cert].
         *
         * @return the built [X509Cert].
         */
        suspend fun build(): X509Cert =
            X509Cert(ByteString(ASN1.encode(buildASN1())))

        override fun buildTbs(tbsList: MutableList<ASN1Object>) {
            val signatureAlgorithmSeq =
                signingKey.algorithm.getSignatureAlgorithmSeq(signingKey.publicKey.curve)

            val subjectPublicKey = when (publicKey) {
                is EcPublicKeyDoubleCoordinate -> {
                    publicKey.asUncompressedPointEncoding
                }
                is EcPublicKeyOkp -> {
                    publicKey.x
                }
            }
            val subjectPublicKeyInfoSeq = ASN1Sequence(listOf(
                publicKey.curve.getCurveAlgorithmSeq(),
                ASN1BitString(0, subjectPublicKey)
            ))

            if (validFrom.nanosecondsOfSecond != 0) {
                Logger.w(TAG, "Truncating fractional seconds of validFrom")
            }
            if (validUntil.nanosecondsOfSecond != 0) {
                Logger.w(TAG, "Truncating fractional seconds of validUntil")
            }
            val validFromTruncated = Instant.fromEpochSeconds(validFrom.epochSeconds)
            val validUntilTruncated = Instant.fromEpochSeconds(validUntil.epochSeconds)
            tbsList.add(versionObject(2L))
            tbsList.add(serialNumber)
            tbsList.add(signatureAlgorithmSeq)
            tbsList.add(generateName(issuer))
            tbsList.add(ASN1Sequence(listOf(
                    ASN1Time(validFromTruncated),
                    ASN1Time(validUntilTruncated)
                )))
            tbsList.add(generateName(subject))
            tbsList.add(subjectPublicKeyInfoSeq)

            if (includeSubjectKeyIdentifierFlag) {
                // https://datatracker.ietf.org/doc/html/rfc5280#section-4.2.1.2
                addExtension(
                    OID.X509_EXTENSION_SUBJECT_KEY_IDENTIFIER.oid,
                    false,
                    ASN1.encode(ASN1OctetString(Crypto.digest(Algorithm.INSECURE_SHA1, subjectPublicKey)))
                )
            }

            if (includeAuthorityKeyIdentifierAsSubjectKeyIdentifierFlag) {
                addExtension(
                    OID.X509_EXTENSION_AUTHORITY_KEY_IDENTIFIER.oid,
                    false,
                    // Note: AuthorityKeyIdentifier uses IMPLICIT tags
                    ASN1.encode(
                        ASN1Sequence(listOf(
                            ASN1TaggedObject(
                                ASN1TagClass.CONTEXT_SPECIFIC,
                                ASN1Encoding.PRIMITIVE,
                                0,
                                Crypto.digest(Algorithm.INSECURE_SHA1, subjectPublicKey)
                            )
                        ))
                    )
                )
            }
        }
    }

    /**
     * View of the certificate structure without omitted default fields (specifically, version,
     * which is often omitted for X.509 v1 certificates.
     *
     * @param elements TBS sequence with the default fields added
     */
    internal class NormalizedTbs private constructor(
        val elements: List<ASN1Object>
    ) {
        companion object Companion {
            /** Creates [NormalizedTbs] from the actual TBS data in the certificate. */
            fun from(tbs: ASN1Sequence): NormalizedTbs {
                val first = tbs.elements.first()
                // Version is optional and is often omitted for v1 certificates
                val elements = if (first is ASN1TaggedObject && first.tag == 0) {
                    tbs.elements
                } else {
                    // "insert" omitted version tag, so that the rest of the code does
                    // not have to worry about it
                    listOf(versionObject(0L)) + tbs.elements
                }
                return NormalizedTbs(elements)
            }
        }
    }
}

/**
 * Builds a new [X509Cert].
 *
 * @param publicKey the public key for the certificate.
 * @param signingKey the key to sign the TBSCertificate with.
 * @param serialNumber the serial number in the certificate.
 * @param subject the subject of the certificate.
 * @param issuer the issuer of the certificate.
 * @param validFrom the point in time the certificate is valid from.
 * @param validUntil the point in time the certificate is valid until.
 * @param builderAction the builder action.
 * @return a [X509Cert].
 */
suspend fun buildX509Cert(
    publicKey: EcPublicKey,
    signingKey: AsymmetricKey,
    serialNumber: ASN1Integer,
    subject: X500Name,
    issuer: X500Name,
    validFrom: Instant,
    validUntil: Instant,
    builderAction: X509Cert.Builder.() -> Unit
): X509Cert {
    val builder = X509Cert.Builder(
        publicKey = publicKey,
        signingKey = signingKey,
        serialNumber = serialNumber,
        subject = subject,
        issuer = issuer,
        validFrom = validFrom,
        validUntil = validUntil
    )
    builder.builderAction()
    return builder.build()
}