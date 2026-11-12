package org.multipaz.compose.presentment

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.claim.MdocClaim
import org.multipaz.credential.Credential
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentAttributeGroup
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.presentment.PresentmentCredentialMatch
import org.multipaz.presentment.PresentmentCredentialMatchSourceIso18013
import org.multipaz.presentment.PresentmentUseCase
import org.multipaz.presentment.PresentmentUseCaseSolution
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.truncateToWholeSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class DocumentMatchGroupsTest {

    private class TestCredentials(
        val credDocA1: Credential,
        val credDocA2: Credential,
        val credDocB: Credential,
        val source: PresentmentCredentialMatchSourceIso18013,
    )

    private suspend fun createTestCredentials(): TestCredentials {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val secureAreaRepository = SecureAreaRepository.Builder().add(secureArea).build()
        val documentStore = buildDocumentStore(storage, secureAreaRepository) {}

        val now = Clock.System.now().truncateToWholeSeconds()
        val iacaKey = Crypto.createEcPrivateKey(EcCurve.P384)
        val iacaCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaKey),
            subject = X500Name.fromName("C=US,CN=Test IACA"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = now - 1.days,
            validUntil = now + 365.days,
            issuerAltNameUrl = "https://example.com",
            crlUrl = "https://example.com/crl",
        )
        val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaKey),
            dsKey = dsPrivateKey.publicKey,
            subject = X500Name.fromName("C=US,CN=Test DS"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = now - 1.days,
            validUntil = now + 365.days,
        )
        val dsKey = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(dsCert, iacaCert)),
            dsPrivateKey,
        )

        val docA = documentStore.createDocument(displayName = "Erika's Photo ID")
        val credDocA1 = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = docA,
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = now - 1.days,
            validFrom = now - 1.days,
            validUntil = now + 30.days,
            expectedUpdate = null,
            domain = "mdoc",
        )
        val credDocA2 = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = docA,
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = now - 1.days,
            validFrom = now - 1.days,
            validUntil = now + 30.days,
            expectedUpdate = null,
            domain = "mdoc",
        )

        val docB = documentStore.createDocument(displayName = "Erika's Driving License")
        val credDocB = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = docB,
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = now - 1.days,
            validFrom = now - 1.days,
            validUntil = now + 30.days,
            expectedUpdate = null,
            domain = "mdoc",
        )

        val deviceRequest = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)
                )
            )
        }
        val source = PresentmentCredentialMatchSourceIso18013(deviceRequest.docRequests.first())
        return TestCredentials(
            credDocA1 = credDocA1,
            credDocA2 = credDocA2,
            credDocB = credDocB,
            source = source,
        )
    }

    private fun makeClaimPair(
        name: String,
        displayName: String,
        intentToRetain: Boolean = false,
        sensitivity: DocumentAttributeSensitivity = DocumentAttributeSensitivity.PII,
        group: DocumentAttributeGroup? = null,
    ): Pair<MdocRequestedClaim, MdocClaim> {
        val req = MdocRequestedClaim(
            docType = DrivingLicense.MDL_DOCTYPE,
            namespaceName = DrivingLicense.MDL_NAMESPACE,
            dataElementName = name,
            intentToRetain = intentToRetain,
        )
        val attr = DocumentAttribute(
            type = DocumentAttributeType.String,
            identifier = name,
            displayName = displayName,
            description = displayName,
            icon = Icon.PERSON,
            sampleValueMdoc = Tstr(name),
            sampleValueJson = null,
            parentAttribute = null,
            embeddedAttributes = emptyList(),
            sensitivity = sensitivity,
            group = group,
        )
        val claim = MdocClaim(
            displayName = displayName,
            attribute = attr,
            docType = DrivingLicense.MDL_DOCTYPE,
            namespaceName = DrivingLicense.MDL_NAMESPACE,
            dataElementName = name,
            value = Tstr("val_$name"),
        )
        return req to claim
    }

    @Test
    fun sameDocumentPlainAndEncryptedGroupedWithPlainFirst() = runTest {
        val creds = createTestCredentials()
        val borderTrust = TrustMetadata(displayName = "Utopia Border Protection")

        val plainMatch = PresentmentCredentialMatch(
            credential = creds.credDocA1,
            claims = mapOf(
                makeClaimPair("given_name", "Given Names"),
                makeClaimPair("family_name", "Family Name"),
            ),
            source = creds.source,
            transactionData = emptyList(),
            encryptionRequested = false,
        )
        val encryptedMatch = PresentmentCredentialMatch(
            credential = creds.credDocA2,
            claims = mapOf(
                makeClaimPair("document_number", "Document Number"),
            ),
            source = creds.source,
            transactionData = emptyList(),
            encryptionRequested = true,
            encryptionTargetTrustMetadata = borderTrust,
        )

        // Even when the encrypted match appears first in solution.matches, direct claims precede encrypted claims.
        for (input in listOf(listOf(plainMatch, encryptedMatch), listOf(encryptedMatch, plainMatch))) {
            val groups = groupMatchesByDocument(input)
            assertEquals(1, groups.size)
            val group = groups.single()
            assertEquals(0, group.firstMatchIndex)
            assertEquals(creds.credDocA1.document.identifier, group.document.identifier)
            assertEquals(2, group.matches.size)
            assertEquals(2, group.claimSections.size)

            val directSection = group.claimSections[0]
            assertFalse(directSection.encryptionRequested)
            assertFalse(directSection.storesData)
            assertEquals(listOf("Given Names", "Family Name"), directSection.claims.map { it.displayName })

            val encryptedSection = group.claimSections[1]
            assertTrue(encryptedSection.encryptionRequested)
            assertFalse(encryptedSection.storesData)
            assertEquals(borderTrust, encryptedSection.encryptionTargetTrustMetadata)
            assertEquals(listOf("Document Number"), encryptedSection.claims.map { it.displayName })
        }
    }

    @Test
    fun differentDocumentsRemainSeparatePreservingFirstSeenOrder() = runTest {
        val creds = createTestCredentials()
        val borderTrust = TrustMetadata(displayName = "Utopia Border Protection")

        val docAPlain = PresentmentCredentialMatch(
            credential = creds.credDocA1,
            claims = mapOf(makeClaimPair("given_name", "Given Names")),
            source = creds.source,
            transactionData = emptyList(),
        )
        val docBPlain = PresentmentCredentialMatch(
            credential = creds.credDocB,
            claims = mapOf(makeClaimPair("driving_privileges", "Driving Privileges")),
            source = creds.source,
            transactionData = emptyList(),
        )
        val docAEncrypted = PresentmentCredentialMatch(
            credential = creds.credDocA2,
            claims = mapOf(makeClaimPair("document_number", "Document Number")),
            source = creds.source,
            transactionData = emptyList(),
            encryptionRequested = true,
            encryptionTargetTrustMetadata = borderTrust,
        )

        val groups = groupMatchesByDocument(listOf(docAPlain, docBPlain, docAEncrypted))
        assertEquals(2, groups.size)

        assertEquals(creds.credDocA1.document.identifier, groups[0].document.identifier)
        assertEquals(0, groups[0].firstMatchIndex)
        assertEquals(listOf(docAPlain, docAEncrypted), groups[0].matches)
        assertEquals(2, groups[0].claimSections.size)

        assertEquals(creds.credDocB.document.identifier, groups[1].document.identifier)
        assertEquals(1, groups[1].firstMatchIndex)
        assertEquals(listOf(docBPlain), groups[1].matches)
        assertEquals(1, groups[1].claimSections.size)
    }

    @Test
    fun partialRetentionOrdersNonStoredBeforeStoredPerRecipient() = runTest {
        val creds = createTestCredentials()
        val borderTrust = TrustMetadata(displayName = "Utopia Border Protection")

        val plainMatch = PresentmentCredentialMatch(
            credential = creds.credDocA1,
            claims = mapOf(
                makeClaimPair("family_name", "Family Name", intentToRetain = true),
                makeClaimPair("given_name", "Given Names", intentToRetain = false),
            ),
            source = creds.source,
            transactionData = emptyList(),
            encryptionRequested = false,
        )
        val encryptedMatch = PresentmentCredentialMatch(
            credential = creds.credDocA2,
            claims = mapOf(
                makeClaimPair("document_number", "Document Number", intentToRetain = true),
                makeClaimPair("portrait", "Photo of Holder", intentToRetain = false),
            ),
            source = creds.source,
            transactionData = emptyList(),
            encryptionRequested = true,
            encryptionTargetTrustMetadata = borderTrust,
        )

        val groups = groupMatchesByDocument(listOf(plainMatch, encryptedMatch))
        val sections = groups.single().claimSections
        assertEquals(4, sections.size)

        assertEquals(false to false, sections[0].encryptionRequested to sections[0].storesData)
        assertEquals(listOf("Given Names"), sections[0].claims.map { it.displayName })

        assertEquals(false to true, sections[1].encryptionRequested to sections[1].storesData)
        assertEquals(listOf("Family Name"), sections[1].claims.map { it.displayName })

        assertEquals(true to false, sections[2].encryptionRequested to sections[2].storesData)
        assertEquals(listOf("Photo of Holder"), sections[2].claims.map { it.displayName })

        assertEquals(true to true, sections[3].encryptionRequested to sections[3].storesData)
        assertEquals(listOf("Document Number"), sections[3].claims.map { it.displayName })
    }

    @Test
    fun sameRecipientMatchesMergeDeduplicateAndSortBySensitivity() = runTest {
        val creds = createTestCredentials()

        val match1 = PresentmentCredentialMatch(
            credential = creds.credDocA1,
            claims = mapOf(
                makeClaimPair(
                    name = "issuing_country",
                    displayName = "Issuing Country",
                    sensitivity = DocumentAttributeSensitivity.ISSUER,
                ),
                makeClaimPair(
                    name = "age_over_21",
                    displayName = "Older Than 21",
                    sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                ),
            ),
            source = creds.source,
            transactionData = emptyList(),
        )
        val match2 = PresentmentCredentialMatch(
            credential = creds.credDocA2,
            claims = mapOf(
                makeClaimPair(
                    name = "age_over_21",
                    displayName = "Older Than 21",
                    sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                ),
                makeClaimPair(
                    name = "document_number",
                    displayName = "Document Number",
                    sensitivity = DocumentAttributeSensitivity.PII,
                ),
            ),
            source = creds.source,
            transactionData = emptyList(),
        )

        val groups = groupMatchesByDocument(listOf(match1, match2))
        val section = groups.single().claimSections.single()
        assertEquals(
            listOf("Document Number", "Older Than 21", "Issuing Country"),
            section.claims.map { it.displayName },
        )
    }

    @Test
    fun distinctEncryptionTargetsRemainSeparateSections() = runTest {
        val creds = createTestCredentials()
        val targetA = TrustMetadata(displayName = "Target A")
        val targetB = TrustMetadata(displayName = "Target B")

        val encA = PresentmentCredentialMatch(
            credential = creds.credDocA1,
            claims = mapOf(makeClaimPair("given_name", "Given Names")),
            source = creds.source,
            transactionData = emptyList(),
            encryptionRequested = true,
            encryptionTargetTrustMetadata = targetA,
        )
        val encB = PresentmentCredentialMatch(
            credential = creds.credDocA2,
            claims = mapOf(makeClaimPair("document_number", "Document Number")),
            source = creds.source,
            transactionData = emptyList(),
            encryptionRequested = true,
            encryptionTargetTrustMetadata = targetB,
        )

        val groups = groupMatchesByDocument(listOf(encA, encB))
        val sections = groups.single().claimSections
        assertEquals(2, sections.size)
        assertEquals(targetA, sections[0].encryptionTargetTrustMetadata)
        assertEquals(targetB, sections[1].encryptionTargetTrustMetadata)
    }

    @Test
    fun switchableDocumentIdsSuppressesDocumentsInEverySolution() = runTest {
        val creds = createTestCredentials()
        val matchA = PresentmentCredentialMatch(
            credential = creds.credDocA1,
            claims = mapOf(makeClaimPair("given_name", "Given Names")),
            source = creds.source,
            transactionData = emptyList(),
        )
        val matchB = PresentmentCredentialMatch(
            credential = creds.credDocB,
            claims = mapOf(makeClaimPair("document_number", "Document Number")),
            source = creds.source,
            transactionData = emptyList(),
        )
        val docAId = creds.credDocA1.document.identifier
        val docBId = creds.credDocB.document.identifier

        // Compound non-optional use-case: Solution 0 = [DocA, DocB], Solution 1 = [DocB]
        // DocB is in every solution, so only DocA is switchable when Solution 0 is shown.
        val compoundUseCase = PresentmentUseCase(
            optional = false,
            solutions = listOf(
                PresentmentUseCaseSolution(listOf(matchA, matchB)),
                PresentmentUseCaseSolution(listOf(matchB)),
            ),
        )
        val sol0Groups = groupMatchesByDocument(compoundUseCase.solutions[0].matches)
        assertEquals(setOf(docAId), switchableDocumentIds(compoundUseCase, sol0Groups))

        // When Solution 1 ([DocB] alone) is shown, DocB is the only displayed document, so the
        // fallback keeps DocB switchable so the chooser remains reachable.
        val sol1Groups = groupMatchesByDocument(compoundUseCase.solutions[1].matches)
        assertEquals(setOf(docBId), switchableDocumentIds(compoundUseCase, sol1Groups))

        // When the use-case is optional, all displayed documents are switchable even if a document
        // appears in every solution.
        val optionalCompound = compoundUseCase.copy(optional = true)
        assertEquals(setOf(docAId, docBId), switchableDocumentIds(optionalCompound, sol0Groups))

        // Single-solution non-optional use-case: no documents are switchable.
        val singleMandatory = PresentmentUseCase(
            optional = false,
            solutions = listOf(PresentmentUseCaseSolution(listOf(matchA, matchB))),
        )
        assertEquals(emptySet(), switchableDocumentIds(singleMandatory, sol0Groups))
    }

    @Test
    fun collapseClaimsForDisplayGroupsCompositeAndSingleThresholdClaims() = runTest {
        val creds = createTestCredentials()
        val nameGroup = DocumentAttributeGroup(
            identifier = "name",
            displayName = "Name",
            icon = Icon.PERSON,
            minClaimsToGroup = 2,
        )
        val passportDataGroup = DocumentAttributeGroup(
            identifier = "passport_data",
            displayName = "Passport data",
            icon = Icon.LANGUAGE,
            minClaimsToGroup = 1,
        )

        // When both given_name and family_name are requested, they collapse into "Name",
        // while raw section.claims still preserves the individual claims for DocumentDetailsPage.
        val matchBothNames = PresentmentCredentialMatch(
            credential = creds.credDocA1,
            claims = mapOf(
                makeClaimPair("given_name", "Given names", group = nameGroup),
                makeClaimPair("family_name", "Family name", group = nameGroup),
                makeClaimPair("sod", "eMRTD SOD", group = passportDataGroup),
            ),
            source = creds.source,
            transactionData = emptyList(),
        )
        val sectionBoth = groupMatchesByDocument(listOf(matchBothNames)).single().claimSections.single()
        assertEquals(
            listOf("Given names", "Family name", "eMRTD SOD"),
            sectionBoth.claims.map { it.displayName },
        )
        assertEquals(
            listOf(
                DisplayClaim("Name", Icon.PERSON, DocumentAttributeSensitivity.PII),
                DisplayClaim("Passport data", Icon.LANGUAGE, DocumentAttributeSensitivity.PII),
            ),
            sectionBoth.displayClaims,
        )

        // When only 1 claim from a minClaimsToGroup = 2 group is requested, it remains uncollapsed.
        val matchSingleName = PresentmentCredentialMatch(
            credential = creds.credDocA1,
            claims = mapOf(
                makeClaimPair("given_name", "Given names", group = nameGroup),
            ),
            source = creds.source,
            transactionData = emptyList(),
        )
        val sectionSingle = groupMatchesByDocument(listOf(matchSingleName)).single().claimSections.single()
        assertEquals(
            listOf(DisplayClaim("Given names", Icon.PERSON, DocumentAttributeSensitivity.PII)),
            sectionSingle.displayClaims,
        )
    }
}
