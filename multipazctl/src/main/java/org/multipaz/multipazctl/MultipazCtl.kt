package org.multipaz.multipazctl

import kotlinx.coroutines.runBlocking
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.util.MdocUtil
import kotlin.time.Clock
import kotlinx.datetime.DateTimePeriod
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.buildCrl
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.Security
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object MultipazCtl {

    private val jsonPrettyPrint = Json { prettyPrint = true }

    fun getArg(
        args: Array<String>,
        argName: String,
        defaultValue: String
        ): String {
        val prefixedArgName = "--" + argName
        for (n in IntRange(0, args.size - 1)) {
            val arg = args[n]
            if (arg.equals(prefixedArgName)) {
                if (n + 1 < args.size) {
                    return args[n + 1]
                }
            }
        }
        return defaultValue
    }

    fun generateIaca(args: Array<String>) {
        val certificateOutputFilename =
            getArg(args,"out_certificate", "iaca_certificate.pem")
        val privateKeyOutputFilename =
            getArg(args,"out_private_key", "iaca_private_key.pem")
        val crlOutputFilename =
            getArg(args,"out_crl", "iaca_crl.pem")

        // Requirements for the IACA certificate is defined in ISO/IEC 18013-5:2021 Annex B

        // From 18013-5 table B.1: countryName is mandatory
        //                         stateOrProvinceName is optional.
        //                         organizationName is optional.
        //                         commonName shall be present.
        //                         serialNumber is optional.
        //
        val subjectAndIssuer = X500Name.fromName(
            getArg(args, "subject_and_issuer", "CN=OWF Multipaz TEST IACA,C=US")
        )

        // From 18013-5 Annex B: 3-5 years is recommended
        //                       Maximum of 20 years after “Not before” date
        val validityInYears = getArg(args, "validity_in_years", "5").toInt()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validFrom = now
        val validUntil = now.plus(DateTimePeriod(years = validityInYears), TimeZone.currentSystemDefault())

        val curveName = getArg(args, "curve", "P-384")
        val curve = EcCurve.fromJwkName(curveName)

        val iacaKey = Crypto.createEcPrivateKey(curve)

        val issuerAltNameUrl = getArg(args,
            "issuer_alt_name_url",
            "https://issuer.example.com/website"
        )

        val crlUrl = getArg(
            args,
            "crl_url",
            "https://issuer.example.com/crl.crl"
        )

        val serial = ASN1Integer.fromRandom(128)

        val iacaCertificate = runBlocking {
            MdocUtil.generateIacaCertificate(
                AsymmetricKey.anonymous(iacaKey),
                subjectAndIssuer,
                serial,
                validFrom,
                validUntil,
                issuerAltNameUrl,
                crlUrl
            )
        }

        val iacaCrl = runBlocking {
            buildCrl(
                signingKey = AsymmetricKey.anonymous(iacaKey),
                issuer = subjectAndIssuer,
                thisUpdate = validFrom,
                nextUpdate = null
            ) {
                // empty
            }
        }

        println("- Generated self-signed IACA cert and private key with curve $curve")

        File(privateKeyOutputFilename).outputStream().bufferedWriter().let {
            it.write(iacaKey.toPem())
            it.close()
        }
        println("- Wrote private key to $privateKeyOutputFilename")

        File(certificateOutputFilename).outputStream().bufferedWriter().let {
            it.write(iacaCertificate.toPem())
            it.close()
        }
        println("- Wrote IACA cert to $certificateOutputFilename")

        File(crlOutputFilename).outputStream().bufferedWriter().let {
            it.write(iacaCrl.toPem())
            it.close()
        }
        println("- Wrote IACA CRL to $crlOutputFilename")
    }

    fun generateDs(args: Array<String>) {
        val iacaCertificateFilename =
            getArg(args, "iaca_certificate", "iaca_certificate.pem")
        val iacaPrivateKeyFilename =
            getArg(args, "iaca_private_key", "iaca_private_key.pem")

        val iacaCert = X509Cert.fromPem(
            String(File(iacaCertificateFilename).readBytes(), StandardCharsets.US_ASCII))

        val iacaPrivateKey = EcPrivateKey.fromPem(
            String(File(iacaPrivateKeyFilename).readBytes(), StandardCharsets.US_ASCII),
            iacaCert.ecPublicKey)

        val certificateOutputFilename =
            getArg(args, "out_certificate", "ds_certificate.pem")
        val privateKeyOutputFilename =
            getArg(args, "out_private_key", "ds_private_key.pem")

        // Requirements for the IACA certificate is defined in ISO/IEC 18013-5:2021 Annex B

        val subject = X500Name.fromName(
            getArg(args, "subject", "CN=OWF Multipaz TEST DS,C=US")
        )

        val validityInYears = getArg(args, "validity_in_years", "1").toInt()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validFrom = now
        val validUntil = now.plus(DateTimePeriod(years = validityInYears), TimeZone.currentSystemDefault())

        val curveName = getArg(args, "curve", "P-256")
        val curve = EcCurve.fromJwkName(curveName)

        val dsKey = Crypto.createEcPrivateKey(curve)

        val serial = ASN1Integer.fromRandom(128)

        val dsCertificate = runBlocking {
            MdocUtil.generateDsCertificate(
                AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaPrivateKey),
                dsKey.publicKey,
                subject,
                serial,
                validFrom,
                validUntil
            )
        }

        println("- Generated DS cert and private key with curve $curve")

        println("- Loaded IACA cert from $iacaCertificateFilename")
        println("- Loaded IACA private key from $iacaPrivateKeyFilename")

        File(privateKeyOutputFilename).outputStream().bufferedWriter().let {
            it.write(dsKey.toPem())
            it.close()
        }
        println("- Wrote DS private key to $privateKeyOutputFilename")

        File(certificateOutputFilename).outputStream().bufferedWriter().let {
            it.write(dsCertificate.toPem())
            it.close()
        }
        println("- Wrote DS cert to $certificateOutputFilename")
    }

    fun generateReaderRoot(args: Array<String>) {
        val subjectAndIssuer = X500Name.fromName(
            getArg(args, "subject_and_issuer", "CN=OWF Multipaz TEST Reader CA,C=US")
        )

        // From 18013-5 Annex B: 3-5 years is recommended
        //                       Maximum of 20 years after “Not before” date
        val validityInYears = getArg(args, "validity_in_years", "5").toInt()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validFrom = now
        val validUntil = now.plus(DateTimePeriod(years = validityInYears), TimeZone.currentSystemDefault())

        val curveName = getArg(args, "curve", "P-384")
        val curve = EcCurve.fromJwkName(curveName)

        val serial = ASN1Integer.fromRandom(128)

        val crlUrl = getArg(
            args,
            "crl_url",
            "https://reader-ca.example.com/crl.crl"
        )

        val readerRootKey = Crypto.createEcPrivateKey(curve)

        val readerRootCertificate = runBlocking {
            MdocUtil.generateReaderRootCertificate(
                readerRootKey = AsymmetricKey.anonymous(readerRootKey),
                subject = subjectAndIssuer,
                serial = serial,
                validFrom = validFrom,
                validUntil = validUntil,
                crlUrl = crlUrl
            )
        }

        val readerRootCrl = runBlocking {
            buildCrl(
                signingKey = AsymmetricKey.anonymous(readerRootKey),
                issuer = subjectAndIssuer,
                thisUpdate = validFrom,
                nextUpdate = null,
            ) {
                // empty
            }
        }

        println("- Generated self-signed reader root cert and private key with curve $curve")

        val certificateOutputFilename = getArg(args, "out_certificate", "reader_root_certificate.pem")
        val privateKeyOutputFilename = getArg(args, "out_private_key", "reader_root_private_key.pem")
        val crlOutputFilename = getArg(args, "out_crl", "reader_root_crl.pem")
        File(privateKeyOutputFilename).writer().let {
            it.write(readerRootKey.toPem())
            it.close()
        }
        println("- Wrote reader root private key to $privateKeyOutputFilename")

        File(certificateOutputFilename).writer().let {
            it.write(readerRootCertificate.toPem())
            it.close()
        }
        println("- Wrote reader root cert to $certificateOutputFilename")

        File(crlOutputFilename).writer().let {
            it.write(readerRootCrl.toPem())
            it.close()
        }
        println("- Wrote reader root CRL to $crlOutputFilename")
    }

    fun generateReaderCert(args: Array<String>) {
        val readerRootCertificateFilename =
            getArg(args, "reader_root_certificate", "reader_root_certificate.pem")
        val readerRootPrivateKeyFilename =
            getArg(args, "reader_root_private_key", "reader_root_private_key.pem")

        val readerRootCert = X509Cert.fromPem(
            String(File(readerRootCertificateFilename).readBytes(), StandardCharsets.US_ASCII))

        val readerRootPrivateKey = EcPrivateKey.fromPem(
            String(File(readerRootPrivateKeyFilename).readBytes(), StandardCharsets.US_ASCII),
            readerRootCert.ecPublicKey)

        val certificateOutputFilename =
            getArg(args, "out_certificate", "reader_certificate.pem")
        val privateKeyOutputFilename =
            getArg(args, "out_private_key", "reader_private_key.pem")

        // Requirements for the Reader Root certificate is defined in ISO/IEC 18013-5:2021 Annex B

        val subject = X500Name.fromName(
            getArg(args, "subject", "CN=OWF Multipaz TEST Reader,C=US")
        )

        val validityInYears = getArg(args, "validity_in_years", "1").toInt()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validFrom = now
        val validUntil = now.plus(DateTimePeriod(years = validityInYears), TimeZone.currentSystemDefault())

        val curveName = getArg(args, "curve", "P-256")
        val curve = EcCurve.fromJwkName(curveName)

        val readerKey = Crypto.createEcPrivateKey(curve)

        val serial = ASN1Integer.fromRandom(128)

        val readerCertificate = runBlocking {
            MdocUtil.generateReaderCertificate(
                readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                    certChain = X509CertChain(listOf(readerRootCert)),
                    privateKey = readerRootPrivateKey
                ),
                readerKey = readerKey.publicKey,
                subject = subject,
                serial = serial,
                validFrom = validFrom,
                validUntil = validUntil
            )
        }

        println("- Generated Reader cert and private key with curve $curve")

        println("- Loaded reader root cert from $readerRootCertificateFilename")
        println("- Loaded reader root private key from $readerRootPrivateKeyFilename")

        File(privateKeyOutputFilename).outputStream().bufferedWriter().let {
            it.write(readerKey.toPem())
            it.close()
        }
        println("- Wrote reader private key to $privateKeyOutputFilename")

        File(certificateOutputFilename).outputStream().bufferedWriter().let {
            it.write(readerCertificate.toPem())
            it.close()
        }
        println("- Wrote reader cert to $certificateOutputFilename")
    }

    fun printJwk(args: Array<String>) {
        val certificateFilename = getArg(args, "certificate", "")
        val privateKeyFilename = getArg(args, "private_key", "")

        check(certificateFilename.length > 0) { "Certificate must be specified" }
        check(privateKeyFilename.length > 0) { "Private key must be specified" }

        val certificate = X509Cert.fromPem(
            String(File(certificateFilename).readBytes(), StandardCharsets.US_ASCII))

        val privateKey = EcPrivateKey.fromPem(
            String(File(privateKeyFilename).readBytes(), StandardCharsets.US_ASCII),
            certificate.ecPublicKey)

        println("- Loaded cert from $certificateFilename")
        println("- Loaded private key from $privateKeyFilename")
        println("")

        val json = buildJsonObject {
            put("jwk", privateKey.toJwk())
            put("x5c", X509CertChain(listOf(certificate)).toX5c())
        }
        println(jsonPrettyPrint.encodeToString(json))
        println("")
    }

    fun usage(args: Array<String>) {
        println(
"""
Generate a IACA certificate and corresponding private key:

    multipazctl generateIaca
        [--out_certificate iaca_certificate.pem]
        [--out_private_key iaca_private_key.pem]
        [--out_crl iaca_crl.pem]
        [--subject_and_issuer 'CN=OWF Multipaz TEST IACA,C=US']
        [--validity_in_years 5]
        [--curve P-384]
        [--issuer_alt_name_url https://issuer.example.com/website]
        [--crl_url https://issuer.example.com/crl.crl]

Generate a DS certificate and corresponding private key:

    multipazctl generateDs
        [--iaca_certificate iaca_certificate.pem]
        [--iaca_private_key iaca_private_key.pem]
        [--out_certificate ds_certificate.pem]
        [--out_private_key ds_private_key.pem]
        [--subject 'CN=OWF Multipaz TEST DS,C=US']
        [--validity_in_years 1]
        [--curve P-256]

Generate a reader root and corresponding private key:

    multipazctl generateReaderRoot
        [--out_certificate reader_root_certificate.pem]
        [--out_private_key reader_root_private_key.pem]
        [--out_crl reader_root_crl.pem]
        [--subject_and_issuer 'CN=OWF Multipaz TEST Reader CA,C=US']
        [--validity_in_years 3]
        [--curve P-384]
        [--crl_url https://reader-ca.example.com/crl.crl]

Generate a reader certificate and corresponding private key:

    multipazctl generateReaderCert
        [--reader_root_certificate reader_root_certificate.pem]
        [--reader_root_private_key reader_root_private_key.pem]
        [--out_certificate reader_certificate.pem]
        [--out_private_key reader_private_key.pem]
        [--subject 'CN=OWF Multipaz TEST Reader,C=US']
        [--validity_in_years 1]
        [--curve P-256]

Generate JSON for a private key and certificate according to RFC 7517:

    multipazctl printJwk
        --private_key private_key.pem
        --certificate certificate.pem

Prints out version:

    multipazctl version
""")
    }

    fun version(args: Array<String>) {
        println(BuildConfig.VERSION)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Load BouncyCastle for Brainpool curve support
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        if (args.size == 0) {
            usage(args)
        } else {
            val command = args[0]
            when (command) {
                "generateIaca" -> generateIaca(args)
                "generateDs" -> generateDs(args)
                "generateReaderRoot" -> generateReaderRoot(args)
                "generateReaderCert" -> generateReaderCert(args)
                "printJwk" -> printJwk(args)
                "help" -> usage(args)
                "version" -> version(args)
                else -> {
                    println("Unknown command '$command'")
                    usage(args)
                }
            }
        }
    }
}
