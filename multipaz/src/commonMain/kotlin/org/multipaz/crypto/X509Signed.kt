package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1Boolean
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Object
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1Set
import org.multipaz.asn1.ASN1String
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.OID
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Base class for signed X.509 sequences: certificates and CRLs.
 *
 * Signed sequence is an [ASN1Sequence] that contains three elements: TBS sequence,
 * signature algorithm, and signature, where the signature cryptographically signs the bytes of
 * TBS sequence. TBS sequence syntax is determined by the format (certificate and CRL), but in
 * both cases can contain specially-tagged extension object.
 *
 * This class encapsulates the common functionality:
 *  - dealing with the top-level sequence
 *  - signature
 *  - extensions
 */
sealed class X509Signed() {
    abstract val encoded: ByteString
    protected abstract val name: String
    protected abstract val extensionTag: Int

    fun toDataItem(): DataItem = Bstr(encoded.toByteArray())

    /**
     * Encode this certificate or CRL in PEM format
     *
     * @return a PEM encoded string.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun toPem(): String {
        val sb = StringBuilder()
        sb.append("-----BEGIN $name-----\n")
        sb.append(Base64.Mime.encode(encoded.toByteArray()))
        sb.append("\n-----END $name-----\n")
        return sb.toString()
    }

    /**
     * Checks if the certificate or CRL was signed with a given key.
     *
     * @param publicKey the key to check the signature with.
     * @throws SignatureVerificationException if the signature check fails.
     */
    fun verify(publicKey: EcPublicKey) {
        val ecSignature = when (signatureAlgorithm) {
            Algorithm.ES256, Algorithm.ESP256, Algorithm.ESB256,
            Algorithm.ES384, Algorithm.ESP384, Algorithm.ESB384, Algorithm.ESB320,
            Algorithm.ES512 -> {
                EcSignature.fromDerEncoded(publicKey.curve.bitSize, signature)
            }
            Algorithm.EDDSA, Algorithm.ED25519, Algorithm.ED448 -> {
                val len = signature.size
                val r = signature.sliceArray(IntRange(0, len/2 - 1))
                val s = signature.sliceArray(IntRange(len/2, len - 1))
                EcSignature(r, s)
            }
            else -> throw IllegalArgumentException("Unsupported algorithm $signatureAlgorithm")
        }
        Crypto.checkSignature(
            publicKey,
            tbsCertificate,
            signatureAlgorithm,
            ecSignature
        )
    }

    protected val parsed: ASN1Sequence by lazy {
        ASN1.decode(encoded.toByteArray())!! as ASN1Sequence
    }

    protected val tbs: ASN1Sequence get() = parsed.elements[0] as ASN1Sequence

    /**
     * The bytes of TBS sequence.
     */
    val tbsCertificate: ByteArray
        get() = ASN1.encode(tbs)

    /**
     * The certificate or CRL signature.
     */
    val signature: ByteArray
        get() = (parsed.elements[2] as ASN1BitString).value

    /**
     * The signature algorithm for the CRL as OID string.
     */
    val signatureAlgorithmOid: String
        get() {
            val algorithmIdentifier = parsed.elements[1] as ASN1Sequence
            return (algorithmIdentifier.elements[0] as ASN1ObjectIdentifier).oid
        }

    /**
     * The signature algorithm for the certificate or CRL as [Algorithm].
     *
     * @throws IllegalArgumentException if the OID for the algorithm doesn't correspond with a signature algorithm
     *   value in the [Algorithm] enumeration.
     */
    val signatureAlgorithm: Algorithm
        get() {
            return when (signatureAlgorithmOid) {
                OID.SIGNATURE_ECDSA_SHA256.oid -> Algorithm.ES256
                OID.SIGNATURE_ECDSA_SHA384.oid -> Algorithm.ES384
                OID.SIGNATURE_ECDSA_SHA512.oid -> Algorithm.ES512
                OID.ED25519.oid, OID.ED448.oid -> Algorithm.EDDSA  // ED25519, ED448
                OID.SIGNATURE_RS256.oid -> Algorithm.RS256
                OID.SIGNATURE_RS384.oid -> Algorithm.RS384
                OID.SIGNATURE_RS512.oid -> Algorithm.RS512
                else -> throw IllegalArgumentException(
                    "Unexpected algorithm OID $signatureAlgorithmOid")
            }
        }

    /**
     * The OIDs for X.509 extensions which are marked as critical.
     */
    val criticalExtensionOIDs: Set<String>
        get() = getExtensionOIDs(true)

    /**
     * The OIDs for X.509 extensions which are not marked as critical.
     */
    val nonCriticalExtensionOIDs: Set<String>
        get() = getExtensionOIDs(false)

    fun getExtensionsSeq(): ASN1Sequence? {
        for (elem in tbs.elements) {
            if (elem is ASN1TaggedObject &&
                elem.cls == ASN1TagClass.CONTEXT_SPECIFIC &&
                elem.enc == ASN1Encoding.CONSTRUCTED &&
                elem.tag == extensionTag) {
                return ASN1.decode(elem.content) as ASN1Sequence
            }
        }
        return null
    }

    private fun getExtensionOIDs(getCritical: Boolean): Set<String> {
        val extSeq = getExtensionsSeq() ?: return emptySet()
        val ret = mutableSetOf<String>()
        for (ext in extSeq.elements) {
            ext as ASN1Sequence
            val isCritical = if (ext.elements.size == 3) {
                (ext.elements[1] as ASN1Boolean).value
            } else {
                false
            }
            if ((isCritical && getCritical) || (!isCritical && !getCritical)) {
                ret.add((ext.elements[0] as ASN1ObjectIdentifier).oid)
            }
        }
        return ret
    }

    /**
     * Gets the bytes of a X.509 extension.
     *
     * @param oid the OID to get the extension from
     * @return the bytes of the extension or `null` if no such extension exist.
     */
    fun getExtensionValue(oid: String): ByteArray? {
        val extSeq = getExtensionsSeq() ?: return null
        for (ext in extSeq.elements) {
            ext as ASN1Sequence
            if ((ext.elements[0] as ASN1ObjectIdentifier).oid == oid) {
                val index = if (ext.elements.size == 3) 2 else 1
                return (ext.elements[index] as ASN1OctetString).value
            }
        }
        return null
    }

    /** The list of decoded extensions information. */
    val extensions: List<X509Extension>
        get() {
            val extSeq = getExtensionsSeq() ?: return emptyList()
            return buildList {
                for (ext in extSeq.elements) {
                    ext as ASN1Sequence
                    val dataField =
                        (ext.elements[(if (ext.elements.size == 3) 2 else 1)] as ASN1OctetString)
                            .value
                    add(
                        X509Extension(
                            oid = (ext.elements[0] as ASN1ObjectIdentifier).oid,
                            isCritical = (ext.elements.size == 3)
                                    && (ext.elements[1] as ASN1Boolean).value,
                            data = ByteString(dataField)
                        )
                    )
                }
            }
        }

    companion object {
        @OptIn(ExperimentalEncodingApi::class)
        internal fun fromPemHelper(pemEncoding: String, name: String): ByteString =
            ByteString(Base64.Mime.decode(pemEncoding
                .replace("-----BEGIN $name-----", "")
                .replace("-----END $name-----", "")
                .trim()))

        internal fun parseName(obj: ASN1Sequence): X500Name {
            val components = mutableMapOf<String, ASN1String>()
            for (elem in obj.elements) {
                val dnSet = elem as ASN1Set
                val typeAndValue = dnSet.elements[0] as ASN1Sequence
                val oidObject = typeAndValue.elements[0] as ASN1ObjectIdentifier
                val nameObject = typeAndValue.elements[1] as ASN1String
                components.put(oidObject.oid, nameObject)
            }
            return X500Name(components)
        }

        internal fun versionObject(version: Long): ASN1TaggedObject =
            ASN1TaggedObject(
                ASN1TagClass.CONTEXT_SPECIFIC,
                ASN1Encoding.CONSTRUCTED,
                0,
                ASN1.encode(ASN1Integer(version))
            )
    }
}

/**
 * Base builder class for X509 signed sequences (certificates and CRLs).
 *
 * @param signingKey key that is used to sign the sequence
 * @param issuer the issuer of the certificate or CRL
 */
sealed class X509SignedBuilder<BuilderT: X509SignedBuilder<BuilderT>>(
    val signingKey: AsymmetricKey,
    val issuer: X500Name,
) {
    protected abstract val self: BuilderT
    protected abstract val extensionTag: Int
    private val extensions = mutableMapOf<String, Extension>()

    /**
     * Adds an X.509 extension to the certificate or CRL
     *
     * @param oid the OID for the extension.
     * @param critical the criticality flag.
     * @param value the bytes of the extension
     * @return the builder.
     */
    fun addExtension(oid: String, critical: Boolean, value: ByteArray): BuilderT =
        addExtension(oid, critical, ByteString(value))

    /**
     * Adds an X.509 extension to the certificate or CRL
     *
     * @param oid the OID for the extension.
     * @param critical the criticality flag.
     * @param value the bytes of the extension
     * @return the builder.
     */
    fun addExtension(oid: String, critical: Boolean, value: ByteString): BuilderT {
        extensions.put(oid, Extension(critical, value))
        return self
    }

    /**
     * Adds an X.509 extension to the certificate or CRL.
     *
     * @param extension a [X509Extension] to add.
     * @return the builder
     */
    fun addExtension(extension: X509Extension): BuilderT {
        extensions.put(extension.oid, Extension(extension.isCritical, extension.data))
        return self
    }


    protected abstract fun buildTbs(tbsList: MutableList<ASN1Object>)

    /**
     * Builds three-element [ASN1Sequence] that contains TBS [ASN1Sequence], signature algorithm
     * and the signature.
     */
    protected suspend fun buildASN1(): ASN1Sequence {
        val signatureAlgorithmSeq =
            signingKey.algorithm.getSignatureAlgorithmSeq(signingKey.publicKey.curve)

        val tbsList = mutableListOf<ASN1Object>()

        buildTbs(tbsList)

        if (extensions.isNotEmpty()) {
            val extensionObjs = mutableListOf<ASN1Object>()
            for ((oid, ext) in extensions) {
                extensionObjs.add(
                    if (ext.critical) {
                        ASN1Sequence(
                            listOf(
                                ASN1ObjectIdentifier(oid),
                                ASN1Boolean(true),
                                ASN1OctetString(ext.value.toByteArray())
                            )
                        )
                    } else {
                        ASN1Sequence(
                            listOf(
                                ASN1ObjectIdentifier(oid),
                                ASN1OctetString(ext.value.toByteArray())
                            )
                        )
                    }
                )
            }
            tbsList.add(ASN1TaggedObject(
                ASN1TagClass.CONTEXT_SPECIFIC,
                ASN1Encoding.CONSTRUCTED,
                extensionTag,
                ASN1.encode(ASN1Sequence(extensionObjs))
            ))
        }

        val tbsCert = ASN1Sequence(tbsList)

        val encodedTbsCert = ASN1.encode(tbsCert)
        val signature = signingKey.sign(encodedTbsCert)
        val encodedSignature = when (signingKey.algorithm) {
            Algorithm.ES256, Algorithm.ESP256, Algorithm.ESB256,
            Algorithm.ES384, Algorithm.ESP384, Algorithm.ESB384, Algorithm.ESB320,
            Algorithm.ES512, Algorithm.ESP512, Algorithm.ESB512 -> signature.toDerEncoded()
            Algorithm.EDDSA, Algorithm.ED25519, Algorithm.ED448 -> signature.r + signature.s
            else -> throw IllegalArgumentException("Unsupported signature algorithm ${signingKey.algorithm}")
        }
        return ASN1Sequence(listOf(
            tbsCert,
            signatureAlgorithmSeq,
            ASN1BitString(0, encodedSignature),
        ))
    }

    private data class Extension(
        val critical: Boolean,
        val value: ByteString
    )

    companion object {
        const val TAG = "X509SignedBuilder"

        internal fun Algorithm.getSignatureAlgorithmSeq(signingKeyCurve: EcCurve): ASN1Sequence {
            val signatureAlgorithmOid = when (this) {
                Algorithm.ES256, Algorithm.ESP256, Algorithm.ESB256 -> "1.2.840.10045.4.3.2"
                Algorithm.ES384, Algorithm.ESP384, Algorithm.ESB384, Algorithm.ESB320 -> "1.2.840.10045.4.3.3"
                Algorithm.ES512, Algorithm.ESP512, Algorithm.ESB512 -> "1.2.840.10045.4.3.4"
                Algorithm.EDDSA -> {
                    when (signingKeyCurve) {
                        EcCurve.ED25519 -> "1.3.101.112"
                        EcCurve.ED448 -> "1.3.101.113"
                        else -> throw IllegalArgumentException(
                            "Unsupported curve $signingKeyCurve for $this")
                    }
                }
                else -> {
                    throw IllegalArgumentException("Unsupported signature algorithm $this")
                }
            }
            return ASN1Sequence(listOf(ASN1ObjectIdentifier(signatureAlgorithmOid)))
        }

        internal fun EcCurve.getCurveAlgorithmSeq(): ASN1Sequence {
            val (algOid, paramOid) = when (this) {
                EcCurve.P256 -> Pair(OID.EC_PUBLIC_KEY.oid, OID.EC_CURVE_P256.oid)
                EcCurve.P384 -> Pair(OID.EC_PUBLIC_KEY.oid, OID.EC_CURVE_P384.oid)
                EcCurve.P521 -> Pair(OID.EC_PUBLIC_KEY.oid, OID.EC_CURVE_P521.oid)
                EcCurve.BRAINPOOLP256R1 -> Pair(OID.EC_PUBLIC_KEY.oid, OID.EC_CURVE_BRAINPOOLP256R1.oid)
                EcCurve.BRAINPOOLP320R1 -> Pair(OID.EC_PUBLIC_KEY.oid, OID.EC_CURVE_BRAINPOOLP320R1.oid)
                EcCurve.BRAINPOOLP384R1 -> Pair(OID.EC_PUBLIC_KEY.oid, OID.EC_CURVE_BRAINPOOLP384R1.oid)
                EcCurve.BRAINPOOLP512R1 -> Pair(OID.EC_PUBLIC_KEY.oid, OID.EC_CURVE_BRAINPOOLP512R1.oid)
                EcCurve.X25519 -> Pair(OID.X25519.oid, null)
                EcCurve.X448 -> Pair(OID.X448.oid, null)
                EcCurve.ED25519 -> Pair(OID.ED25519.oid, null)
                EcCurve.ED448 -> Pair(OID.ED448.oid, null)
            }
            if (paramOid != null) {
                return ASN1Sequence(listOf(
                    ASN1ObjectIdentifier(algOid),
                    ASN1ObjectIdentifier(paramOid)
                ))
            }
            return ASN1Sequence(listOf(
                ASN1ObjectIdentifier(algOid),
            ))
        }

        internal fun generateName(name: X500Name): ASN1Sequence {
            val objs = mutableListOf<ASN1Object>()
            for ((oid, value) in name.components) {
                objs.add(
                    ASN1Set(listOf(
                        ASN1Sequence(listOf(
                            ASN1ObjectIdentifier(oid),
                            value
                        ))
                    ))
                )
            }
            return ASN1Sequence(objs)
        }
    }
}

