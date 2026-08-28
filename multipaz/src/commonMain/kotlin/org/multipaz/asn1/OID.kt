package org.multipaz.asn1

import kotlinx.coroutines.CancellationException

/**
 * Registry of known OIDs.
 *
 * @property oid the OID.
 * @property description a textual description of the OID.
 */
enum class OID(
    val oid: String,
    val description: String
) {
    /** Elliptic curve public key cryptography. */
    EC_PUBLIC_KEY("1.2.840.10045.2.1", "Elliptic curve public key cryptography"),

    /** NIST Curve P-256. */
    EC_CURVE_P256("1.2.840.10045.3.1.7", "NIST Curve P-256"),

    /** EC Curve P-384. */
    EC_CURVE_P384("1.3.132.0.34", "EC Curve P-384"),

    /** EC Curve P-521. */
    EC_CURVE_P521("1.3.132.0.35", "EC Curve P-521"),

    /** EC Curve Brainpool P256r1. */
    EC_CURVE_BRAINPOOLP256R1("1.3.36.3.3.2.8.1.1.7", "EC Curve Brainpool P256r1"),

    /** EC Curve Brainpool P320r1. */
    EC_CURVE_BRAINPOOLP320R1("1.3.36.3.3.2.8.1.1.9", "EC Curve Brainpool P320r1"),

    /** EC Curve Brainpool P384r1. */
    EC_CURVE_BRAINPOOLP384R1("1.3.36.3.3.2.8.1.1.11", "EC Curve Brainpool P384r1"),

    /** EC Curve Brainpool P512r1. */
    EC_CURVE_BRAINPOOLP512R1("1.3.36.3.3.2.8.1.1.13", "EC Curve Brainpool P512r1"),

    /** ECDSA coupled with SHA-256. */
    SIGNATURE_ECDSA_SHA256("1.2.840.10045.4.3.2", "ECDSA coupled with SHA-256"),

    /** ECDSA coupled with SHA-384. */
    SIGNATURE_ECDSA_SHA384("1.2.840.10045.4.3.3", "ECDSA coupled with SHA-384"),

    /** ECDSA coupled with SHA-512. */
    SIGNATURE_ECDSA_SHA512("1.2.840.10045.4.3.4", "ECDSA coupled with SHA-512"),

    /** PKCS #1 v1.5 signature algorithm with SHA256 and RSA. */
    SIGNATURE_RS256("1.2.840.113549.1.1.11", "PKCS #1 v1.5 signature algorithm with SHA256 and RSA"),

    /** PKCS #1 v1.5 signature algorithm with SHA384 and RSA. */
    SIGNATURE_RS384("1.2.840.113549.1.1.12", "PKCS #1 v1.5 signature algorithm with SHA384 and RSA"),

    /** PKCS #1 v1.5 signature algorithm with SHA512 and RSA. */
    SIGNATURE_RS512("1.2.840.113549.1.1.13", "PKCS #1 v1.5 signature algorithm with SHA512 and RSA"),

    /** X25519 algorithm used with the Diffie-Hellman operation. */
    X25519("1.3.101.110", "X25519 algorithm used with the Diffie-Hellman operation"),

    /** X448 algorithm used with the Diffie-Hellman operation. */
    X448("1.3.101.111", "X448 algorithm used with the Diffie-Hellman operation"),

    /** Edwards-curve Digital Signature Algorithm (EdDSA) Ed25519. */
    ED25519("1.3.101.112", "Edwards-curve Digital Signature Algorithm (EdDSA) Ed25519"),

    /** Edwards-curve Digital Signature Algorithm (EdDSA) Ed448. */
    ED448("1.3.101.113", "Edwards-curve Digital Signature Algorithm (EdDSA) Ed448"),

    /** commonName (X.520 DN component). */
    COMMON_NAME("2.5.4.3", "commonName (X.520 DN component)"),

    /** serialNumber (X.520 DN component). */
    SERIAL_NUMBER("2.5.4.5", "serialNumber (X.520 DN component)"),

    /** countryName (X.520 DN component). */
    COUNTRY_NAME("2.5.4.6", "countryName (X.520 DN component)"),

    /** localityName (X.520 DN component). */
    LOCALITY_NAME("2.5.4.7", "localityName (X.520 DN component)"),

    /** stateOrProvinceName (X.520 DN component). */
    STATE_OR_PROVINCE_NAME("2.5.4.8", "stateOrProvinceName (X.520 DN component)"),

    /** organizationName (X.520 DN component). */
    ORGANIZATION_NAME("2.5.4.10", "organizationName (X.520 DN component)"),

    /** organizationalUnitName (X.520 DN component). */
    ORGANIZATIONAL_UNIT_NAME("2.5.4.11", "organizationalUnitName (X.520 DN component)"),

    /** keyUsage (X.509 extension). */
    X509_EXTENSION_KEY_USAGE("2.5.29.15", "keyUsage (X.509 extension)"),

    /** extKeyUsage (X.509 extension). */
    X509_EXTENSION_EXTENDED_KEY_USAGE("2.5.29.37", "extKeyUsage (X.509 extension)"),

    /** basicConstraints (X.509 extension). */
    X509_EXTENSION_BASIC_CONSTRAINTS("2.5.29.19", "basicConstraints (X.509 extension)"),

    /** subjectKeyIdentifier (X.509 extension). */
    X509_EXTENSION_SUBJECT_KEY_IDENTIFIER("2.5.29.14", "subjectKeyIdentifier (X.509 extension)"),

    /** authorityKeyIdentifier (X.509 extension). */
    X509_EXTENSION_AUTHORITY_KEY_IDENTIFIER("2.5.29.35", "authorityKeyIdentifier (X.509 extension)"),

    /** subjectAltName (X.509 extension). */
    X509_EXTENSION_SUBJECT_ALT_NAME("2.5.29.17", "subjectAltName (X.509 extension)"),

    /** issuerAltName (X.509 extension). */
    X509_EXTENSION_ISSUER_ALT_NAME("2.5.29.18", "issuerAltName (X.509 extension)"),

    /** cRLDistributionPoints (X.509 extension). */
    X509_EXTENSION_CRL_DISTRIBUTION_POINTS("2.5.29.31", "cRLDistributionPoints (X.509 extension)"),

    /** Android Keystore Key Attestation (X.509 extension). */
    X509_EXTENSION_ANDROID_KEYSTORE_ATTESTATION("1.3.6.1.4.1.11129.2.1.17", "Android Keystore Key Attestation (X.509 extension)"),

    /** Android Keystore Provisioning Information (X.509 extension). */
    X509_EXTENSION_ANDROID_KEYSTORE_PROVISIONING_INFORMATION("1.3.6.1.4.1.11129.2.1.30", "Android Keystore Provisioning Information (X.509 extension)"),

    /** Multipaz Extension (X.509 extension). */
    X509_EXTENSION_MULTIPAZ_EXTENSION("1.3.6.1.4.1.11129.2.1.49", "Multipaz Extension (X.509 extension)"),

    /** Mobile Driving Licence (mDL) Document Signer (DS). */
    ISO_18013_5_MDL_DS("1.0.18013.5.1.2", "Mobile Driving Licence (mDL) Document Signer (DS)"),

    /** Mobile Driving Licence (mDL) Reader Auth. */
    ISO_18013_5_MDL_READER_AUTH("1.0.18013.5.1.6", "Mobile Driving Licence (mDL) Reader Auth"),

    /** mDoc Document Signer (DS). */
    ISO_23220_4_MDOC_DS("1.0.23220.4.1.2", "mDoc Document Signer (DS)"),

    /** mDoc Reader Auth. */
    ISO_23220_4_MDOC_READER_AUTH("1.0.23220.4.1.6", "mDoc Reader Auth"),

    /** PKCS #7 Data content type. */
    PKCS7_DATA("1.2.840.113549.1.7.1", "PKCS #7 Data"),

    /** PKCS #7 SignedData content type. */
    PKCS7_SIGNED_DATA("1.2.840.113549.1.7.2", "PKCS #7 SignedData"),

    /** PKCS #7 EnvelopedData content type. */
    PKCS7_ENVELOPED_DATA("1.2.840.113549.1.7.3", "PKCS #7 EnvelopedData"),

    /** PKCS #7 EncryptedData content type. */
    PKCS7_ENCRYPTED_DATA("1.2.840.113549.1.7.6", "PKCS #7 EncryptedData"),

    /** PKCS #12 keyBag bag type. */
    PKCS12_KEY_BAG("1.2.840.113549.1.12.10.1.1", "PKCS #12 keyBag"),

    /** PKCS #12 pkcs8ShroudedKeyBag bag type. */
    PKCS12_PKCS8_SHROUDED_KEY_BAG("1.2.840.113549.1.12.10.1.2", "PKCS #12 pkcs8ShroudedKeyBag"),

    /** PKCS #12 certBag bag type. */
    PKCS12_CERT_BAG("1.2.840.113549.1.12.10.1.3", "PKCS #12 certBag"),

    /** X.509 Certificate in PKCS #12 certBag. */
    PKCS12_X509_CERTIFICATE("1.2.840.113549.1.9.22.1", "X.509 Certificate in PKCS #12 certBag"),

    /** PKCS #9 friendlyName attribute. */
    PKCS12_FRIENDLY_NAME("1.2.840.113549.1.9.20", "PKCS #9 friendlyName attribute"),

    /** PKCS #9 localKeyID attribute. */
    PKCS12_LOCAL_KEY_ID("1.2.840.113549.1.9.21", "PKCS #9 localKeyID attribute"),

    /** PKCS #5 PBES2 password-based encryption scheme. */
    PBES2("1.2.840.113549.1.5.13", "PKCS #5 PBES2 encryption scheme"),

    /** PKCS #5 PBKDF2 password-based key derivation function. */
    PBKDF2("1.2.840.113549.1.5.12", "PKCS #5 PBKDF2 key derivation function"),

    /** HMAC-SHA1 pseudo-random function / MAC algorithm. */
    HMAC_WITH_SHA1("1.2.840.113549.2.7", "HMAC-SHA1"),

    /** HMAC-SHA224 pseudo-random function / MAC algorithm. */
    HMAC_WITH_SHA224("1.2.840.113549.2.8", "HMAC-SHA224"),

    /** HMAC-SHA256 pseudo-random function / MAC algorithm. */
    HMAC_WITH_SHA256("1.2.840.113549.2.9", "HMAC-SHA256"),

    /** HMAC-SHA384 pseudo-random function / MAC algorithm. */
    HMAC_WITH_SHA384("1.2.840.113549.2.10", "HMAC-SHA384"),

    /** HMAC-SHA512 pseudo-random function / MAC algorithm. */
    HMAC_WITH_SHA512("1.2.840.113549.2.11", "HMAC-SHA512"),

    /** AES-128 in CBC mode. */
    AES128_CBC("2.16.840.1.101.3.4.1.2", "AES-128 in CBC mode"),

    /** AES-192 in CBC mode. */
    AES192_CBC("2.16.840.1.101.3.4.1.22", "AES-192 in CBC mode"),

    /** AES-256 in CBC mode. */
    AES256_CBC("2.16.840.1.101.3.4.1.42", "AES-256 in CBC mode"),

    /** SHA-1 hash algorithm. */
    SHA1("1.3.14.3.2.26", "SHA-1 hash algorithm"),

    /** SHA-256 hash algorithm. */
    SHA256("2.16.840.1.101.3.4.2.1", "SHA-256 hash algorithm"),

    /** SHA-384 hash algorithm. */
    SHA384("2.16.840.1.101.3.4.2.2", "SHA-384 hash algorithm"),

    /** SHA-512 hash algorithm. */
    SHA512("2.16.840.1.101.3.4.2.3", "SHA-512 hash algorithm"),

    /** Legacy PKCS #12 PBE with SHA-1 and 3-Key Triple-DES-CBC. */
    PBE_WITH_SHA_AND_3KEY_TRIPLE_DES_CBC("1.2.840.113549.1.12.1.3", "pbeWithSHAAnd3-KeyTripleDES-CBC"),

    /** Legacy PKCS #12 PBE with SHA-1 and 40-bit RC2-CBC. */
    PBE_WITH_SHA_AND_40BIT_RC2_CBC("1.2.840.113549.1.12.1.6", "pbeWithSHAAnd40BitRC2-CBC"),
    ;

    companion object {
        private val stringToOid: Map<String, OID> by lazy {
            OID.entries.associateBy({it.oid}, {it})
        }

        /**
         * Checks if a given string exists in the [OID] enumeration.
         *
         * @param oid the OID as a string in dotted-decimal notation.
         * @return the entry in the [OID] enumeration or `null` if not found.
         */
        fun lookupByOid(oid: String): OID? = stringToOid[oid]

        /**
         * Checks if a given string is encoded as an OID.
         *
         * @param str the string to check.
         * @return `true` if encoded as a valid OID, `false` otherwise.
         */
        fun isOid(str: String): Boolean {
            val components = str.split(".")
            for (component in components) {
                try {
                    component.toLong(10)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return false
                }
            }
            // First component must be 0, 1, or 2
            return when (components[0]) {
                "0", "1", "2" -> true
                else -> false
            }
        }
    }
}