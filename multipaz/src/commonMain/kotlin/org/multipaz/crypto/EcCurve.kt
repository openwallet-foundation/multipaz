package org.multipaz.crypto

/**
 * Elliptic curve identifiers.
 *
 * All curve identifiers are from the
 * [IANA COSE registry](https://www.iana.org/assignments/cose/cose.xhtml).
 *
 * @property coseCurveIdentifier the COSE curve identifier.
 * @property supportsSigning `true` if the curve supports Signing.
 * @property supportsKeyAgreement `true` if the curve supports Key Agreement.
 */
enum class EcCurve(
    val coseCurveIdentifier: Int,
    val supportsSigning: Boolean,
    val supportsKeyAgreement: Boolean,
) {
    /** The curve identifier for P-256  */
    P256(coseCurveIdentifier = 1, supportsSigning = true, supportsKeyAgreement = true),

    /** The curve identifier for P-384  */
    P384(coseCurveIdentifier = 2, supportsSigning = true, supportsKeyAgreement = true),

    /** The curve identifier for P-521  */
    P521(coseCurveIdentifier = 3, supportsSigning = true, supportsKeyAgreement = true),

    /** The curve identifier for brainpoolP256r1  */
    BRAINPOOLP256R1(coseCurveIdentifier = 256, supportsSigning = true, supportsKeyAgreement = true),

    /** The curve identifier for brainpoolP320r1  */
    BRAINPOOLP320R1(coseCurveIdentifier = 257, supportsSigning = true, supportsKeyAgreement = true),

    /** The curve identifier for brainpoolP384r1  */
    BRAINPOOLP384R1(coseCurveIdentifier = 258, supportsSigning = true, supportsKeyAgreement = true),

    /** The curve identifier for brainpoolP512r1  */
    BRAINPOOLP512R1(coseCurveIdentifier = 259, supportsSigning = true, supportsKeyAgreement = true),

    /** The curve identifier for Ed25519 (EdDSA only)  */
    ED25519(coseCurveIdentifier = 6, supportsSigning = true, supportsKeyAgreement = false),

    /** The curve identifier for X25519 (ECDH only)  */
    X25519(coseCurveIdentifier = 4, supportsSigning = false, supportsKeyAgreement = true),

    /** The curve identifier for Ed448 (EdDSA only)  */
    ED448(coseCurveIdentifier = 7, supportsSigning = true, supportsKeyAgreement = false),

    /** The curve identifier for X448 (ECDH only)  */
    X448(coseCurveIdentifier =5, supportsSigning = false, supportsKeyAgreement = true);

    companion object {
        private val coseToJwk = mapOf(
            P256 to "P-256",
            P384 to "P-384",
            P521 to "P-521",
            ED25519 to "Ed25519",
            ED448 to "Ed448",
            X25519 to "X25519",
            X448 to "X448",
            BRAINPOOLP256R1 to "brainpoolP256r1",
            BRAINPOOLP320R1 to "brainpoolP320r1",
            BRAINPOOLP384R1 to "brainpoolP384r1",
            BRAINPOOLP512R1 to "brainpoolP512r1",
        )

        private val jwkToCose = mapOf(
            "P-256" to P256,
            "P-384" to P384,
            "P-521" to P521,
            "Ed25519" to ED25519,
            "Ed448" to ED448,
            "X25519" to X25519,
            "X448" to X448,
            "brainpoolP256r1" to BRAINPOOLP256R1,
            "brainpoolP320r1" to BRAINPOOLP320R1,
            "brainpoolP384r1" to BRAINPOOLP384R1,
            "brainpoolP512r1" to BRAINPOOLP512R1,
        )

        fun fromInt(coseCurveIdentifier: Int): EcCurve =
            EcCurve.values().find { it.coseCurveIdentifier == coseCurveIdentifier }
                ?: throw IllegalArgumentException("No curve with COSE identifier $coseCurveIdentifier")

        /**
         * The name of the curve according to
         * [JSON Web Key Elliptic Curve](https://www.iana.org/assignments/jose/jose.xhtml#web-key-elliptic-curve)
         *
         * @throws IllegalArgumentException if there is no JWK name for the curve
         */
        fun fromJwkName(jwkName: String): EcCurve =
            jwkToCose[jwkName] ?: throw IllegalArgumentException("No EcCurve value for $this")

    }


    /**
     * The curve size in bits
     */
    val bitSize: Int
        get() = when (this) {
            P256 -> 256
            P384 -> 384
            P521 -> 521
            BRAINPOOLP256R1 -> 256
            BRAINPOOLP320R1 -> 320
            BRAINPOOLP384R1 -> 384
            BRAINPOOLP512R1 -> 512
            X25519 -> 256
            ED25519 -> 256
            X448 -> 448
            ED448 -> 448
        }

    /**
     * The name of the curve according to [Standards for Efficient Cryptography Group](https://www.secg.org/).
     */
    val SECGName: String
        get() = when (this) {
            P256 -> "secp256r1"
            P384 -> "secp384r1"
            P521 -> "secp521r1"
            BRAINPOOLP256R1 -> "brainpoolP256r1"
            BRAINPOOLP320R1 -> "brainpoolP320r1"
            BRAINPOOLP384R1 -> "brainpoolP384r1"
            BRAINPOOLP512R1 -> "brainpoolP512r1"
            X25519 -> "x25519"
            ED25519 -> "ed25519"
            X448 -> "x448"
            ED448 -> "ed448"
        }

    /**
     * The name of the curve according to
     * [JSON Web Key Elliptic Curve](https://www.iana.org/assignments/jose/jose.xhtml#web-key-elliptic-curve)
     *
     * @throws IllegalArgumentException if there is no JWK name for the curve
     */
    val jwkName: String
        get() = coseToJwk[this] ?: throw IllegalArgumentException("No JWK entry for $this")

    /**
     * The default signing algorithm for the curve.
     *
     * For interoperability, a fully specified algorithm is never returned.
     */
    val defaultSigningAlgorithm: Algorithm
        get() = when (this) {
            P256 -> Algorithm.ES256
            P384 -> Algorithm.ES384
            P521 -> Algorithm.ES512
            BRAINPOOLP256R1 -> Algorithm.ES256
            BRAINPOOLP320R1 -> Algorithm.ES384
            BRAINPOOLP384R1 -> Algorithm.ES384
            BRAINPOOLP512R1 -> Algorithm.ES512
            ED25519 -> Algorithm.EDDSA
            X25519 -> Algorithm.UNSET
            ED448 -> Algorithm.EDDSA
            X448 -> Algorithm.UNSET
        }

    /**
     * The default signing algorithm for the curve, fully specified.
     */
    val defaultSigningAlgorithmFullySpecified: Algorithm
        get() = when (this) {
            P256 -> Algorithm.ESP256
            P384 -> Algorithm.ESP384
            P521 -> Algorithm.ESP512
            BRAINPOOLP256R1 -> Algorithm.ESB256
            BRAINPOOLP320R1 -> Algorithm.ESB384
            BRAINPOOLP384R1 -> Algorithm.ESB384
            BRAINPOOLP512R1 -> Algorithm.ESB512
            ED25519 -> Algorithm.ED25519
            X25519 -> Algorithm.UNSET
            ED448 -> Algorithm.ED448
            X448 -> Algorithm.UNSET
        }

    val defaultKeyAgreementAlgorithm: Algorithm
        get() = when (this) {
            P256 -> Algorithm.ECDH_P256
            P384 -> Algorithm.ECDH_P384
            P521 -> Algorithm.ECDH_P521
            BRAINPOOLP256R1 -> Algorithm.ECDH_BRAINPOOLP256R1
            BRAINPOOLP320R1 -> Algorithm.ECDH_BRAINPOOLP320R1
            BRAINPOOLP384R1 -> Algorithm.ECDH_BRAINPOOLP384R1
            BRAINPOOLP512R1 -> Algorithm.ECDH_BRAINPOOLP512R1
            ED25519 -> Algorithm.UNSET
            X25519 -> Algorithm.ECDH_X25519
            ED448 -> Algorithm.UNSET
            X448 -> Algorithm.ECDH_X448
        }

}