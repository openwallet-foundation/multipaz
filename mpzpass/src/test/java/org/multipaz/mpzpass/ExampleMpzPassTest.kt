package org.multipaz.mpzpass

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.Icon
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage

class ExampleMpzPassTest {

    @Test
    fun testUtopiaMiniMartLoyaltyPass() = runTest {
        val iacaPem = File("utopia_mini_mart_iaca.pem").readText()
        val iacaCert = X509Cert.fromPem(iacaPem)

        val passBytes = File("utopia_mini_mart_loyalty_card_for_erika.mpzpass").readBytes()
        val pass = MpzPass.fromDataItem(Cbor.decode(passBytes))

        assertTrue(pass.isSigned)
        assertFalse(pass.shareable)
        assertFalse(pass.userAuthenticationRequired)
        assertEquals("Erika's Mini Mart Loyalty Card", pass.name)
        assertEquals("Loyalty Card", pass.typeName)
        assertNotNull(pass.cardArt)

        val dsCert = pass.issuerCertificateChain!!.certificates.first()
        X509CertChain(listOf(dsCert, iacaCert)).validate()

        val storage = EphemeralStorage()
        val softwareSecureArea = SoftwareSecureArea.create(storage)
        val secureAreaRepository = SecureAreaRepository.Builder()
            .add(softwareSecureArea)
            .build()
        val documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {}

        val doc = documentStore.importMpzPass(
            mpzPass = pass,
            isoMdocDomain = "mdoc",
            sdJwtVcDomain = "sdjwt",
            keylessSdJwtVcDomain = "sdjwt-keyless"
        )

        val cred = doc.getCredentials().single() as MdocCredential
        assertEquals("org.example.loyalty.1", cred.docType)
        val keyInfo = softwareSecureArea.getKeyInfo(cred.alias)
        assertFalse(keyInfo.isUserAuthenticationRequired)

        // Verify synthesized DocumentType
        val docType = doc.getDocumentType()
        assertNotNull(docType)
        assertEquals("Loyalty Card", docType.displayName)
        val ns = docType.mdocDocumentType!!.namespaces["org.example.loyalty.1"]!!
        assertEquals(8, ns.dataElements.size)

        val givenName = ns.dataElements["given_name"]!!.attribute
        val familyName = ns.dataElements["family_name"]!!.attribute
        val merchantName = ns.dataElements["merchant_name"]!!.attribute
        val membershipNumber = ns.dataElements["membership_number"]!!.attribute
        val bonusPoints = ns.dataElements["bonus_points"]!!.attribute
        val validFrom = ns.dataElements["valid_from"]!!.attribute
        val validUntil = ns.dataElements["valid_until"]!!.attribute
        assertEquals("Given names", givenName.displayName)
        assertEquals(DocumentAttributeType.String, givenName.type)
        assertEquals("Family name", familyName.displayName)
        assertEquals(DocumentAttributeType.String, familyName.type)
        assertEquals("Merchant", merchantName.displayName)
        assertEquals(DocumentAttributeType.String, merchantName.type)
        assertEquals(Icon.ACCOUNT_BALANCE, merchantName.icon)
        assertEquals(DocumentAttributeSensitivity.ISSUER, merchantName.sensitivity)
        assertNull(merchantName.group)
        assertEquals("Membership number", membershipNumber.displayName)
        assertEquals(DocumentAttributeType.String, membershipNumber.type)
        assertEquals(Icon.NUMBERS, membershipNumber.icon)
        assertEquals(DocumentAttributeSensitivity.PII, membershipNumber.sensitivity)
        assertNull(membershipNumber.group)
        assertEquals(DocumentAttributeType.Number, bonusPoints.type)
        assertEquals(DocumentAttributeType.Date, validFrom.type)
        assertEquals(DocumentAttributeType.Date, validUntil.type)

        val nameGroup = givenName.group
        assertNotNull(nameGroup)
        assertSame(nameGroup, familyName.group)
        assertEquals("name", nameGroup.identifier)
        assertEquals("Name", nameGroup.displayName)
        assertEquals("Full name of the member", nameGroup.description)
        assertEquals(Icon.PERSON, nameGroup.icon)
        assertEquals(2, nameGroup.minClaimsToGroup)

        // Verify Danish translations
        val daLocales = listOf("da")
        val metadata = pass.metadata!!
        assertEquals("Forhandler", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "merchant_name")!!.getDisplayName(daLocales))
        assertEquals("Fornavne", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "given_name")!!.getDisplayName(daLocales))
        assertEquals("Efternavn", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "family_name")!!.getDisplayName(daLocales))
        assertEquals("Medlemsnummer", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "membership_number")!!.getDisplayName(daLocales))
        assertEquals("Niveau", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "tier")!!.getDisplayName(daLocales))
        assertEquals("Bonuspoint", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "bonus_points")!!.getDisplayName(daLocales))
        assertEquals("Gyldig fra", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "valid_from")!!.getDisplayName(daLocales))
        assertEquals("Gyldig indtil", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "valid_until")!!.getDisplayName(daLocales))
        assertEquals("Navn", metadata.getClaimGroupMetadata("name")!!.getDisplayName(daLocales))
        assertEquals("Medlemmets fulde navn", metadata.getClaimGroupMetadata("name")!!.getDescription(daLocales))

        // Verify Japanese translations
        val jaLocales = listOf("ja")
        assertEquals("加盟店", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "merchant_name")!!.getDisplayName(jaLocales))
        assertEquals("名", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "given_name")!!.getDisplayName(jaLocales))
        assertEquals("姓", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "family_name")!!.getDisplayName(jaLocales))
        assertEquals("会員番号", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "membership_number")!!.getDisplayName(jaLocales))
        assertEquals("ランク", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "tier")!!.getDisplayName(jaLocales))
        assertEquals("ボーナスポイント", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "bonus_points")!!.getDisplayName(jaLocales))
        assertEquals("有効開始日", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "valid_from")!!.getDisplayName(jaLocales))
        assertEquals("有効期限", metadata.getIsoMdocClaimMetadata("org.example.loyalty.1", "valid_until")!!.getDisplayName(jaLocales))
        assertEquals("氏名", metadata.getClaimGroupMetadata("name")!!.getDisplayName(jaLocales))
        assertEquals("会員の氏名", metadata.getClaimGroupMetadata("name")!!.getDescription(jaLocales))

        // Verify Credential.getClaims() falls back to Document.getDocumentType() when not in DocumentTypeRepository
        val emptyRepo = DocumentTypeRepository()
        val claimsEn = cred.getClaims(emptyRepo, listOf("en")).associateBy { it.dataElementName }
        assertEquals("Merchant", claimsEn["merchant_name"]!!.displayName)
        assertEquals(Icon.ACCOUNT_BALANCE, claimsEn["merchant_name"]!!.attribute?.icon)
        assertEquals(DocumentAttributeSensitivity.ISSUER, claimsEn["merchant_name"]!!.attribute?.sensitivity)
        assertEquals("Membership number", claimsEn["membership_number"]!!.displayName)
        assertEquals(DocumentAttributeType.String, claimsEn["membership_number"]!!.attribute?.type)
        assertEquals("24601", claimsEn["membership_number"]!!.render())
        assertEquals("Name", claimsEn["given_name"]!!.attribute?.group?.displayName)
        assertSame(claimsEn["given_name"]!!.attribute?.group, claimsEn["family_name"]!!.attribute?.group)
        assertEquals(DocumentAttributeType.Number, claimsEn["bonus_points"]!!.attribute?.type)
        assertEquals("2450", claimsEn["bonus_points"]!!.render())
        assertEquals(DocumentAttributeType.Date, claimsEn["valid_from"]!!.attribute?.type)
        assertFalse(claimsEn["valid_from"]!!.render().contains("fallback"))
        assertEquals(DocumentAttributeType.Date, claimsEn["valid_until"]!!.attribute?.type)
        assertFalse(claimsEn["valid_until"]!!.render().contains("fallback"))

        val claimsJa = cred.getClaims(emptyRepo, jaLocales).associateBy { it.dataElementName }
        assertEquals("加盟店", claimsJa["merchant_name"]!!.displayName)
        assertEquals("名", claimsJa["given_name"]!!.displayName)
        assertEquals("姓", claimsJa["family_name"]!!.displayName)
        assertEquals("氏名", claimsJa["given_name"]!!.attribute?.group?.displayName)
    }
}
