package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Null
import org.multipaz.asn1.ASN1Object
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1Time
import org.multipaz.cbor.DataItem
import org.multipaz.util.Logger
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

/**
 * X.509 CRL (Certificate Revocation List).
 *
 * @param encoded the bytes of the X.509 CRL in DER encoding.
 */
data class X509Crl(override val encoded: ByteString): X509Signed() {
    override val name: String get() = NAME
    override val extensionTag: Int = EXTENSION_TAG

    private val tbsList: NormalizedTbs by lazy {
        NormalizedTbs.from(parsed.elements[0] as ASN1Sequence)
    }

    /**
     * The CRL version.
     *
     * This returns the encoded value and for X.509 Version 2 CRL (the most common
     * version in use) this value is 1.
     */
    val version: Int
        get() {
            val versionCode = (tbsList.elements[0] as ASN1Integer).toLong().toInt()
            return versionCode
        }

    /**
     * The issuer of the CRL.
     */
    val issuer: X500Name
        get() = parseName(tbsList.elements[2] as ASN1Sequence)

    /**
     * The point in time when this CRL was issued.
     */
    val thisUpdate: Instant
        get() = (tbsList.elements[3] as ASN1Time).value

    /**
     * The point in time when the next CRL is expected to be issued.
     */
    val nextUpdate: Instant?
        get() = (tbsList.elements[4] as? ASN1Time)?.value

    private val revokedCertificates: ASN1Sequence
        get() = tbsList.elements[5] as ASN1Sequence

    /** List of revoked unexpired certificate serial numbers at the time of this CRL issuance. */
    val revokedSerials: List<ASN1Integer>
        get() = revokedCertificates.elements.map { revokedCertificate ->
            (revokedCertificate as ASN1Sequence).elements[0] as ASN1Integer
        }


    class Builder(
        signingKey: AsymmetricKey,
        issuer: X500Name,
        private val thisUpdate: Instant,
        private val nextUpdate: Instant?,
    ): X509SignedBuilder<Builder>(signingKey, issuer) {
        override val self get() = this
        override val extensionTag: Int = EXTENSION_TAG
        private val revoked = mutableListOf<ASN1Sequence>()

        fun addRevoked(serial: ASN1Integer, time: Instant): Builder {
            if (time.nanosecondsOfSecond != 0) {
                Logger.w(TAG, "Truncating fractional seconds of revocation time")
            }
            val timeTruncated = Instant.fromEpochSeconds(time.epochSeconds)
            revoked.add(ASN1Sequence(listOf(
                serial,
                ASN1Time(timeTruncated)
            )))
            return this
        }

        suspend fun build(): X509Crl =
            X509Crl(ByteString(ASN1.encode(buildASN1())))

        override fun buildTbs(tbsList: MutableList<ASN1Object>) {
            val signatureAlgorithmSeq =
                signingKey.algorithm.getSignatureAlgorithmSeq(signingKey.publicKey.curve)

            if (thisUpdate.nanosecondsOfSecond != 0) {
                Logger.w(TAG, "Truncating fractional seconds of thisUpdate")
            }
            val thisUpdateTruncated = Instant.fromEpochSeconds(thisUpdate.epochSeconds)
            val nextUpdateTruncated = nextUpdate?.let {
                if (it.nanosecondsOfSecond != 0) {
                    Logger.w(TAG, "Truncating fractional seconds of nextUpdate")
                }
                Instant.fromEpochSeconds(it.epochSeconds)
            }
            tbsList.add(ASN1Integer(1L))
            tbsList.add(signatureAlgorithmSeq)
            tbsList.add(generateName(issuer))
            tbsList.add(ASN1Time(thisUpdateTruncated))

            if (nextUpdateTruncated != null) {
                tbsList.add(ASN1Time(nextUpdateTruncated))
            }

            tbsList.add(ASN1Sequence(revoked))
        }
    }

    /**
     * View of the certificate structure without omitted default fields (specifically, version,
     * which is often omitted for X.509 v1 CRLs, and optional `nextUpdate` value).
     *
     * @param elements TBS sequence with the default fields added
     */
    internal class NormalizedTbs private constructor(
        val elements: List<ASN1Object>
    ) {
        companion object {
            /** Creates [NormalizedTbs] from the actual TBS data in the certificate. */
            fun from(tbs: ASN1Sequence): NormalizedTbs = NormalizedTbs(buildList {
                val iterator = tbs.elements.iterator()
                var curr = iterator.next()
                // Version is optional and is often omitted for v1 certificates
                if (curr is ASN1Integer) {
                    // version is present, pass it on
                    add(curr)
                    curr = iterator.next()
                } else {
                    // "insert" omitted version code, so that the rest of the code does
                    // not have to worry about it
                    add(ASN1Integer(0L))
                }
                add(curr)  // signature algorithm
                add(iterator.next()) // issuer
                add(iterator.next()) // thisUpdate time
                curr = if (iterator.hasNext()) iterator.next() else ASN1Null()
                if (curr is ASN1Time) {
                    // nextUpdate is present, pass it on
                    add(curr)
                    curr = if (iterator.hasNext()) iterator.next() else ASN1Null()
                } else {
                    // nextUpdate is absent, add a placeholder
                    add(ASN1Null())
                }
                if (curr is ASN1Sequence) {
                    add(curr)  // revoked list
                } else {
                    // empty revoked list
                    add(ASN1Sequence(listOf(ASN1Sequence(listOf()))))
                }
                // extensions are not part of NormalizedTbs
            })
        }
    }

    companion object {
        private const val NAME = "X509 CRL"
        private const val EXTENSION_TAG = 0

        const val TAG = "X509Crl"

        /**
         * Creates a [X509Crl] from a PEM encoded string.
         *
         * @param pemEncoding the PEM encoded string.
         * @return a new [X509Cert].
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(pemEncoding: String): X509Crl =
            X509Crl(fromPemHelper(pemEncoding, NAME))

        /**
         * Gets a [X509Crl] from a [DataItem].
         *
         * @param dataItem the data item, must have been encoded with [toDataItem].
         * @return the certificate.
         */
        fun fromDataItem(dataItem: DataItem): X509Cert {
            return X509Cert(ByteString(dataItem.asBstr))
        }
    }
}

suspend fun buildCrl(
    signingKey: AsymmetricKey,
    issuer: X500Name,
    thisUpdate: Instant,
    nextUpdate: Instant?,
    block: X509Crl.Builder.() -> Unit
): X509Crl {
    val builder = X509Crl.Builder(signingKey, issuer, thisUpdate, nextUpdate)
    block.invoke(builder)
    return builder.build()
}