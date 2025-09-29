package org.multipaz.util

import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import kotlinx.io.bytestring.ByteString
import org.multipaz.device.AndroidKeystoreSecurityLevel

private const val TAG = "validateAndroidKeyAttestation"

fun validateAndroidKeyAttestation(
    chain: X509CertChain,
    challenge: ByteString?,
    requireGmsAttestation: Boolean,
    requireVerifiedBootGreen: Boolean,
    requireKeyMintSecurityLevel: AndroidKeystoreSecurityLevel,
    requireAppSignatureCertificateDigests: Set<ByteString>,
    requireAppPackages: Set<String>
) {
    if (requireGmsAttestation) {
        // Google root certificate uses RSA private key (and not EC key that we currently support
        // in Kotlin Multiplatform code). Instead of comparing the keys, just replace the root
        // in the chain with the known root certificate before validation.
        // TODO: add functionality to extract the public key in any format from the certificate.
        // Then we  can compare the key in the root certificate instead of injecting the known root
        // certificate and worry about its expiration date.
        val truncatedChain = chain.certificates.subList(0, chain.certificates.lastIndex)
        val chainToVerify = truncatedChain + listOf(GOOGLE_ATTESTATION_ROOT_CERTIFICATE)
        check(X509CertChain(chainToVerify).validate()) {
            "Certificate chain did not validate"
        }
    } else {
        check(chain.validate()) {
            "Certificate chain did not validate"
        }
    }

    // Check the Attestation Extension...
    val parser = try {
        AndroidAttestationExtensionParser(chain.certificates.first())
    } catch (e: Throwable) {
        throw IllegalArgumentException("Error parsing Android Attestation Extension", e)
    }

    // Challenge must match...
    check(challenge == null || challenge == ByteString(parser.attestationChallenge)) {
        "Challenge didn't match what was expected"
    }

    if (requireVerifiedBootGreen) {
        // Verified Boot state must be GREEN
        check(
            parser.verifiedBootState ==
                    AndroidAttestationExtensionParser.VerifiedBootState.GREEN
        ) { "Verified boot state is not GREEN" }
    }

    check(parser.keymasterSecurityLevel >= requireKeyMintSecurityLevel) {
        "KeyMint security level is below required"
    }

    if (requireAppSignatureCertificateDigests.isNotEmpty()) {
        check(parser.applicationSignatureDigests.isNotEmpty()) {
            "No application signature digests"
        }
        for (digest in parser.applicationSignatureDigests) {
            check (requireAppSignatureCertificateDigests.contains(digest))
            { "Signing certificate '${digest.toByteArray().toBase64Url()}' is not trusted" }
        }
    }

    if (requireAppPackages.isNotEmpty()) {
        check(parser.applicationPackages.isNotEmpty()) {
            "No application package names"
        }
        for (packageName in parser.applicationPackages) {
            check (requireAppPackages.contains(packageName))
            { "Package '$packageName' is not trusted" }
        }
    }

    // Log the digests for easy copy-pasting into config file.
    Logger.d(
        TAG, "Accepting Android client with ${parser.applicationSignatureDigests.size} " +
                "signing certificates digests")
    for (n in 0..<parser.applicationSignatureDigests.size) {
        Logger.d(TAG,
            "Digest $n: ${parser.applicationSignatureDigests[n].toByteArray().toBase64Url()}")
    }
}

// This certificate is from https://developer.android.com/training/articles/security-key-attestation
// We really only care about the private key in this certificate.
// Note that this certificate expires on May 24 2026 GMT.
private val GOOGLE_ATTESTATION_ROOT_CERTIFICATE = X509Cert.fromPem(
"""
    -----BEGIN CERTIFICATE-----
    MIIFYDCCA0igAwIBAgIJAOj6GWMU0voYMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV
    BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMTYwNTI2MTYyODUyWhcNMjYwNTI0MTYy
    ODUyWjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B
    AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS
    Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7
    tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj
    nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq
    C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ
    oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O
    JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg
    sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi
    igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M
    RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E
    aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um
    AGMCAwEAAaOBpjCBozAdBgNVHQ4EFgQUNmHhAHyIBQlRi0RsR/8aTMnqTxIwHwYD
    VR0jBBgwFoAUNmHhAHyIBQlRi0RsR/8aTMnqTxIwDwYDVR0TAQH/BAUwAwEB/zAO
    BgNVHQ8BAf8EBAMCAYYwQAYDVR0fBDkwNzA1oDOgMYYvaHR0cHM6Ly9hbmRyb2lk
    Lmdvb2dsZWFwaXMuY29tL2F0dGVzdGF0aW9uL2NybC8wDQYJKoZIhvcNAQELBQAD
    ggIBACDIw41L3KlXG0aMiS//cqrG+EShHUGo8HNsw30W1kJtjn6UBwRM6jnmiwfB
    Pb8VA91chb2vssAtX2zbTvqBJ9+LBPGCdw/E53Rbf86qhxKaiAHOjpvAy5Y3m00m
    qC0w/Zwvju1twb4vhLaJ5NkUJYsUS7rmJKHHBnETLi8GFqiEsqTWpG/6ibYCv7rY
    DBJDcR9W62BW9jfIoBQcxUCUJouMPH25lLNcDc1ssqvC2v7iUgI9LeoM1sNovqPm
    QUiG9rHli1vXxzCyaMTjwftkJLkf6724DFhuKug2jITV0QkXvaJWF4nUaHOTNA4u
    JU9WDvZLI1j83A+/xnAJUucIv/zGJ1AMH2boHqF8CY16LpsYgBt6tKxxWH00XcyD
    CdW2KlBCeqbQPcsFmWyWugxdcekhYsAWyoSf818NUsZdBWBaR/OukXrNLfkQ79Iy
    ZohZbvabO/X+MVT3rriAoKc8oE2Uws6DF+60PV7/WIPjNvXySdqspImSN78mflxD
    qwLqRBYkA3I75qppLGG9rp7UCdRjxMl8ZDBld+7yvHVgt1cVzJx9xnyGCC23Uaic
    MDSXYrB4I4WHXPGjxhZuCuPBLTdOLU8YRvMYdEvYebWHMpvwGCF6bAx3JBpIeOQ1
    wDB5y0USicV3YgYGmi+NZfhA4URSh77Yd6uuJOJENRaNVTzk
    -----END CERTIFICATE-----
""".trimIndent())
