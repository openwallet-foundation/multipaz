package org.multipaz.mpzpass

import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.deflate
import org.multipaz.util.inflate

/**
 * Represents a Multipaz `.mpzpass` credential container.
 *
 * This format provides a highly portable, lightweight mechanism to exchange low-assurance
 * verifiable credentials (such as transit passes or movie tickets) where strict
 * hardware device-binding is unnecessary.
 *
 * See [this page](https://github.com/openwallet-foundation/multipaz/tree/main/mpzpass/README.md)
 * for the definition of the Multipaz Pass file format.
 *
 * @property uniqueId A unique identifier for this pass, as assigned by the issuer. This should contain at least 128
 * bits of entropy and should only contain alphanumeric characters, hyphens, and underscores.
 * @property version the version of the pass.
 * @property updateUrl Optional URL which can be used to check for an update.
 * @property userAuthenticationRequired whether platform user authentication is required to present the pass.
 * @property readerIdentifiers A list of reader identifiers for reader authentication.
 * @property shareable whether the pass can be shared/forwarded to others.
 * @property name The display name of the credential (e.g., "Erika's Driving License").
 * @property typeName The display type of the credential (e.g., "Utopia Driving License").
 * @property cardArt The card art for the pass as a PNG ByteString.
 * @property isoMdoc The ISO mDoc credentials in the payload.
 * @property sdJwtVc The SD-JWT VC credentials in the payload.
 * @property issuerCertificateChain The X.509 certificate chain of the pass issuer, if the pass is signed.
 * @throws IllegalArgumentException if both [isoMdoc] and [sdJwtVc] are empty.
 */
data class MpzPass(
    val uniqueId: String = UUID.randomUUID().toString(),
    val version: Long = 0L,
    val updateUrl: String? = null,
    val userAuthenticationRequired: Boolean = false,
    val readerIdentifiers: List<ByteString> = emptyList(),
    val shareable: Boolean = false,
    val name: String? = null,
    val typeName: String? = null,
    val cardArt: ByteString? = null,
    val isoMdoc: List<MpzPassIsoMdoc> = emptyList(),
    val sdJwtVc: List<MpzPassSdJwtVc> = emptyList(),
    val issuerCertificateChain: X509CertChain? = null
) {
    /**
     * Whether this pass is signed with an issuer certificate chain.
     */
    val isSigned: Boolean get() = issuerCertificateChain != null

    init {
        if (isoMdoc.isEmpty() && sdJwtVc.isEmpty()) {
            throw IllegalArgumentException("Both isoMdoc and sdJwtVc cannot be empty")
        }
    }

    /**
     * Serializes and compresses this [MpzPass] into a [DataItem].
     *
     * If [signingKey] and [issuerCertificateChain] are provided, the compressed credential data payload
     * is signed using `COSE_Sign1` according to RFC 9052 and wrapped as `#6.18(COSE_Sign1)`.
     *
     * @param signingKey Optional key used to sign the pass.
     * @param issuerCertificateChain Optional X.509 certificate chain for the pass issuer.
     * @param compressionLevel The DEFLATE compression level to use (0-9). Defaults to 5.
     * @return A [DataItem].
     * @throws IllegalArgumentException if only one of [signingKey] or [issuerCertificateChain] is provided,
     * or if the compression level is out of range.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun toDataItem(
        signingKey: AsymmetricKey? = null,
        issuerCertificateChain: X509CertChain? = null,
        compressionLevel: Int = 5
    ) = buildCborArray {
        require((signingKey == null && issuerCertificateChain == null) || (signingKey != null && issuerCertificateChain != null)) {
            "Both signingKey and issuerCertificateChain must be provided together or both null"
        }
        add("MpzPass")
        val credentialData = buildCborMap {
            put("uniqueId", uniqueId)
            put("version", version)
            updateUrl?.let { put("updateUrl", it) }
            if (userAuthenticationRequired) {
                put("userAuthenticationRequired", true)
            }
            if (readerIdentifiers.isNotEmpty()) {
                putCborArray("readerIdentifiers") {
                    readerIdentifiers.forEach { add(it.toByteArray()) }
                }
            }
            if (shareable) {
                put("shareable", true)
            }
            putCborMap("credential") {
                if (isoMdoc.isNotEmpty()) {
                    putCborArray("isoMdoc") {
                        isoMdoc.forEach { add(it.toDataItem()) }
                    }
                }
                if (sdJwtVc.isNotEmpty()) {
                    putCborArray("sdJwtVc") {
                        sdJwtVc.forEach { add(it.toDataItem()) }
                    }
                }
            }
            putCborMap("display") {
                name?.let { put("name", it) }
                typeName?.let { put("typeName", it) }
                cardArt?.let { put("cardArt", it.toByteArray()) }
            }
        }
        val credentialDataBytes = Cbor.encode(credentialData)
        val compressedCredentialDataBytes = credentialDataBytes.deflate(compressionLevel)
        if (signingKey != null && issuerCertificateChain != null) {
            val cose = Cose.coseSign1Sign(
                signingKey = signingKey,
                message = compressedCredentialDataBytes,
                includeMessageInPayload = true,
                protectedHeaders = mapOf(
                    CoseNumberLabel(Cose.COSE_LABEL_ALG) to
                        signingKey.algorithm.coseAlgorithmIdentifier!!.toDataItem(),
                    CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to
                        issuerCertificateChain.toDataItem()
                ),
                unprotectedHeaders = emptyMap()
            )
            add(Tagged(Tagged.COSE_SIGN1, cose.toDataItem()))
        } else {
            add(compressedCredentialDataBytes)
        }
    }

    companion object {
        private const val TAG = "MpzPass"

        /**
         * Parses a CBOR array [DataItem] into an [MpzPass].
         *
         * If the pass contains a signed container (`#6.18(COSE_Sign1)`), the signature is checked against
         * the leaf certificate in the X.509 certificate chain (unless [disableSignatureVerification] is true).
         * Note that this method only verifies the cryptographic signature; it is the caller's responsibility
         * to examine [issuerCertificateChain] and check whether it originates from a trusted pass provider.
         *
         * @param dataItem The top-level CBOR array containing the MpzPass string tag and (signed or unsigned) compressed bytes.
         * @param disableSignatureVerification Set to `true` to skip cryptographic signature verification.
         * @return The parsed [MpzPass].
         * @throws IllegalArgumentException if CBOR decoding or decompression fails, or if signature headers are malformed.
         * @throws SignatureVerificationException if signature verification fails.
         */
        @Throws(
            IllegalArgumentException::class,
            SignatureVerificationException::class,
            CancellationException::class
        )
        suspend fun fromDataItem(
            dataItem: DataItem,
            disableSignatureVerification: Boolean = false
        ): MpzPass {
            check(dataItem is CborArray) { "Expected an array" }
            require(dataItem[0].asTstr == "MpzPass") { "Wrong string at start" }

            val secondElement = dataItem[1]
            val (compressedCredentialDataBytes, issuerCertChain) = when {
                secondElement is Bstr -> {
                    Pair(secondElement.asBstr, null)
                }
                secondElement is Tagged && secondElement.tagNumber == Tagged.COSE_SIGN1 -> {
                    val cose = CoseSign1.fromDataItem(secondElement.taggedItem)
                    val payload = cose.payload
                        ?: throw IllegalArgumentException("Missing payload in COSE_Sign1")
                    val certChain = cose.protectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)]?.asX509CertChain
                        ?: throw IllegalArgumentException("x5chain header not found in protected headers")
                    val algNumber = cose.protectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_ALG)]?.asNumber?.toInt()
                        ?: throw IllegalArgumentException("alg header not found in protected headers")
                    val alg = Algorithm.fromCoseAlgorithmIdentifier(algNumber)

                    if (!disableSignatureVerification) {
                        Cose.coseSign1Check(
                            publicKey = certChain.certificates.first().ecPublicKey,
                            detachedData = null,
                            signature = cose,
                            signatureAlgorithm = alg
                        )
                    }
                    Pair(payload, certChain)
                }
                else -> throw IllegalArgumentException("Expected bstr or Tagged.COSE_SIGN1 in dataItem[1]")
            }

            val credentialDataBytes = compressedCredentialDataBytes.inflate()
            val credentialData = Cbor.decode(credentialDataBytes)

            val uniqueId = credentialData["uniqueId"].asTstr
            val version = credentialData["version"].asNumber
            val updateUrl = credentialData.getOrNull("updateUrl")?.asTstr
            val userAuthenticationRequired = credentialData.getOrNull("userAuthenticationRequired")?.asBoolean ?: false
            val readerIdentifiers = credentialData.getOrNull("readerIdentifiers")?.asArray?.map {
                ByteString(it.asBstr)
            } ?: emptyList()
            val shareable = credentialData.getOrNull("shareable")?.asBoolean ?: false

            val display = credentialData["display"]
            val name = display.getOrNull("name")?.asTstr
            val typeName = display.getOrNull("typeName")?.asTstr
            val cardArt = display.getOrNull("cardArt")?.asBstr?.let { ByteString(it) }

            val credential = credentialData["credential"]
            val isoMdoc = credential.getOrNull("isoMdoc")?.asArray?.map {
                MpzPassIsoMdoc.fromDataItem(it)
            } ?: emptyList()
            val sdJwtVc = credential.getOrNull("sdJwtVc")?.asArray?.map {
                MpzPassSdJwtVc.fromDataItem(it)
            } ?: emptyList()

            return MpzPass(
                uniqueId = uniqueId,
                version = version,
                updateUrl = updateUrl,
                userAuthenticationRequired = userAuthenticationRequired,
                readerIdentifiers = readerIdentifiers,
                shareable = shareable,
                name = name,
                typeName = typeName,
                cardArt = cardArt,
                isoMdoc = isoMdoc,
                sdJwtVc = sdJwtVc,
                issuerCertificateChain = issuerCertChain
            )
        }
    }
}

