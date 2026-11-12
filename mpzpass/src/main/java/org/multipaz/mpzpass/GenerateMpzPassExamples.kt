package org.multipaz.mpzpass

import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.Icon
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mdoc.util.MdocUtil

object GenerateMpzPassExamples {
    private const val LOYALTY_DOCTYPE = "org.example.loyalty.1"
    private const val LOYALTY_NAMESPACE = "org.example.loyalty.1"

    private val IACA_PUBLIC_KEY_PEM = """
        -----BEGIN PUBLIC KEY-----
        MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEZETBjmA7ZO5jlXL48kBfBliM8qTZLlu2
        uhjKUVvjf16ue7DeUcRxCgTSMcuinNui3lEZyKc4DedeYbhV7HMyHGzF9Z7AS+G1
        FHEZNGRnCbVqmih4XbwFnMThjH8bF6rM
        -----END PUBLIC KEY-----
    """.trimIndent()

    private val IACA_PRIVATE_KEY_PEM = """
        -----BEGIN PRIVATE KEY-----
        MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDAip2eKg3FO52Hm3xbE
        3Dsml/LV6hDdqUqdSXnTR4URHkZgAUOSVEAhFJY7HjjN0BqhZANiAARkRMGOYDtk
        7mOVcvjyQF8GWIzypNkuW7a6GMpRW+N/Xq57sN5RxHEKBNIxy6Kc26LeURnIpzgN
        515huFXsczIcbMX1nsBL4bUUcRk0ZGcJtWqaKHhdvAWcxOGMfxsXqsw=
        -----END PRIVATE KEY-----
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val outputDir = if (args.isNotEmpty()) File(args[0]) else File(".")
        val cardArtFile = File(outputDir, "utopia_mini_mart_card_art.png")
        val iacaPemFile = File(outputDir, "utopia_mini_mart_iaca.pem")
        val mpzPassFile = File(outputDir, "utopia_mini_mart_loyalty_card_for_erika.mpzpass")

        require(cardArtFile.exists()) {
            "Card art file not found at ${cardArtFile.absolutePath}"
        }
        val cardArtBytes = ByteString(cardArtFile.readBytes())

        val signedAt = Instant.parse("2026-01-01T00:00:00Z")
        val validFrom = Instant.parse("2026-01-01T00:00:00Z")
        val validUntil = Instant.parse("2036-01-01T00:00:00Z")

        val iacaPublicKey = EcPublicKey.fromPem(IACA_PUBLIC_KEY_PEM)
        val iacaPrivateKey = EcPrivateKey.fromPem(IACA_PRIVATE_KEY_PEM, iacaPublicKey)
        val iacaCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaPrivateKey),
            subject = X500Name.fromName("CN=Utopia Mini Mart IACA,C=US"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = validFrom,
            validUntil = validUntil,
            issuerAltNameUrl = "https://utopiaminimart.example.com",
            crlUrl = "https://utopiaminimart.example.com/crl"
        )
        iacaPemFile.writeText(iacaCert.toPem())
        println("Wrote IACA certificate to ${iacaPemFile.path}")

        val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(iacaCert)),
                privateKey = iacaPrivateKey
            ),
            dsKey = dsPrivateKey.publicKey,
            subject = X500Name.fromName("CN=Utopia Mini Mart DS,C=US"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = validFrom,
            validUntil = validUntil
        )
        val dsKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(dsCert)),
            privateKey = dsPrivateKey
        )

        val deviceKeyPrivate = Crypto.createEcPrivateKey(EcCurve.P256)

        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(LOYALTY_NAMESPACE) {
                addDataElement("merchant_name", "Utopia Mini Mart".toDataItem())
                addDataElement("given_name", "Erika".toDataItem())
                addDataElement("family_name", "Mustermann".toDataItem())
                addDataElement("membership_number", "24601".toDataItem())
                addDataElement("tier", "gold".toDataItem())
                addDataElement("bonus_points", 2450L.toDataItem())
                addDataElement("valid_from", LocalDate.parse("2026-01-01").toDataItemFullDate())
                addDataElement("valid_until", LocalDate.parse("2036-01-01").toDataItemFullDate())
            }
        }

        val mso = MobileSecurityObject(
            version = "1.0",
            docType = LOYALTY_DOCTYPE,
            signedAt = signedAt,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = deviceKeyPrivate.publicKey
        )
        val taggedEncodedMso = Cbor.encode(
            Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem())))
        )
        val signatureAlgorithm = dsKey.algorithm.curve?.defaultSigningAlgorithm ?: dsKey.algorithm
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            CoseNumberLabel(Cose.COSE_LABEL_ALG) to
                signatureAlgorithm.coseAlgorithmIdentifier!!.toDataItem()
        )
        val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
            CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to
                dsKey.certChain.toCoseX5Chain()
        )
        val issuerAuth = Cose.coseSign1Sign(
            signingKey = dsKey,
            message = taggedEncodedMso,
            includeMessageInPayload = true,
            protectedHeaders = protectedHeaders,
            unprotectedHeaders = unprotectedHeaders
        )

        val metadata = MpzPassMetadata(
            claimGroupsMetadata = mapOf(
                "name" to MpzPassGroupMetadata(
                    displayName = mapOf(
                        "en" to "Name",
                        "da" to "Navn",
                        "ja" to "氏名"
                    ),
                    description = mapOf(
                        "en" to "Full name of the member",
                        "da" to "Medlemmets fulde navn",
                        "ja" to "会員の氏名"
                    ),
                    icon = Icon.PERSON,
                    sensitivity = DocumentAttributeSensitivity.PII,
                    minClaimsToGroup = 2
                )
            ),
            isoMdocClaimsMetadata = mapOf(
                LOYALTY_NAMESPACE to mapOf(
                    "merchant_name" to MpzPassClaimMetadata(
                        displayName = mapOf(
                            "en" to "Merchant",
                            "da" to "Forhandler",
                            "ja" to "加盟店"
                        ),
                        description = mapOf(
                            "en" to "Name of the merchant",
                            "da" to "Forhandlerens navn",
                            "ja" to "加盟店の名称"
                        ),
                        icon = Icon.ACCOUNT_BALANCE,
                        sensitivity = DocumentAttributeSensitivity.ISSUER
                    ),
                    "given_name" to MpzPassClaimMetadata(
                        displayName = mapOf(
                            "en" to "Given names",
                            "da" to "Fornavne",
                            "ja" to "名"
                        ),
                        description = mapOf(
                            "en" to "First name(s) of the member",
                            "da" to "Medlemmets fornavn(e)",
                            "ja" to "会員の名"
                        ),
                        icon = Icon.PERSON,
                        sensitivity = DocumentAttributeSensitivity.PII,
                        groupId = "name"
                    ),
                    "family_name" to MpzPassClaimMetadata(
                        displayName = mapOf(
                            "en" to "Family name",
                            "da" to "Efternavn",
                            "ja" to "姓"
                        ),
                        description = mapOf(
                            "en" to "Last name or surname of the member",
                            "da" to "Medlemmets efternavn",
                            "ja" to "会員の姓"
                        ),
                        icon = Icon.PERSON,
                        sensitivity = DocumentAttributeSensitivity.PII,
                        groupId = "name"
                    ),
                    "membership_number" to MpzPassClaimMetadata(
                        displayName = mapOf(
                            "en" to "Membership number",
                            "da" to "Medlemsnummer",
                            "ja" to "会員番号"
                        ),
                        description = mapOf(
                            "en" to "Identifier of the loyalty member",
                            "da" to "Medlemmets identifikationsnummer",
                            "ja" to "会員の識別番号"
                        ),
                        icon = Icon.NUMBERS,
                        sensitivity = DocumentAttributeSensitivity.PII
                    ),
                    "tier" to MpzPassClaimMetadata(
                        displayName = mapOf(
                            "en" to "Tier",
                            "da" to "Niveau",
                            "ja" to "ランク"
                        ),
                        description = mapOf(
                            "en" to "Membership tier",
                            "da" to "Medlemskabsniveau",
                            "ja" to "会員ランク"
                        ),
                        icon = Icon.STARS,
                        sensitivity = DocumentAttributeSensitivity.PII
                    ),
                    "bonus_points" to MpzPassClaimMetadata(
                        displayName = mapOf(
                            "en" to "Bonus points",
                            "da" to "Bonuspoint",
                            "ja" to "ボーナスポイント"
                        ),
                        description = mapOf(
                            "en" to "Accumulated bonus points",
                            "da" to "Optjente bonuspoint",
                            "ja" to "獲得したボーナスポイント"
                        ),
                        type = DocumentAttributeType.Number,
                        icon = Icon.NUMBERS,
                        sensitivity = DocumentAttributeSensitivity.PII
                    ),
                    "valid_from" to MpzPassClaimMetadata(
                        displayName = mapOf(
                            "en" to "Valid from",
                            "da" to "Gyldig fra",
                            "ja" to "有効開始日"
                        ),
                        description = mapOf(
                            "en" to "Date when membership becomes valid",
                            "da" to "Dato hvor medlemskabet træder i kraft",
                            "ja" to "会員資格の有効開始日"
                        ),
                        type = DocumentAttributeType.Date,
                        icon = Icon.CALENDAR_CLOCK,
                        sensitivity = DocumentAttributeSensitivity.VALIDITY
                    ),
                    "valid_until" to MpzPassClaimMetadata(
                        displayName = mapOf(
                            "en" to "Valid until",
                            "da" to "Gyldig indtil",
                            "ja" to "有効期限"
                        ),
                        description = mapOf(
                            "en" to "Date when membership expires",
                            "da" to "Dato hvor medlemskabet udløber",
                            "ja" to "会員資格の有効期限"
                        ),
                        type = DocumentAttributeType.Date,
                        icon = Icon.CALENDAR_CLOCK,
                        sensitivity = DocumentAttributeSensitivity.VALIDITY
                    )
                )
            )
        )

        val mpzPass = MpzPass(
            uniqueId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            version = 0L,
            userAuthenticationRequired = false,
            shareable = false,
            name = "Erika's Mini Mart Loyalty Card",
            typeName = "Loyalty Card",
            cardArt = cardArtBytes,
            metadata = metadata,
            isoMdoc = listOf(
                MpzPassIsoMdoc(
                    docType = LOYALTY_DOCTYPE,
                    deviceKeyPrivate = deviceKeyPrivate,
                    issuerNamespaces = issuerNamespaces,
                    issuerAuth = issuerAuth
                )
            ),
            issuerCertificateChain = dsKey.certChain
        )

        val encodedPass = Cbor.encode(
            mpzPass.toDataItem(
                signingKey = dsKey,
                issuerCertificateChain = dsKey.certChain
            )
        )
        mpzPassFile.writeBytes(encodedPass)
        println("Wrote MpzPass (${encodedPass.size} bytes) to ${mpzPassFile.path}")
    }
}
