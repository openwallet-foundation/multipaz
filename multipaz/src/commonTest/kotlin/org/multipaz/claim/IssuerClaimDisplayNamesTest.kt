package org.multipaz.claim

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Tstr
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.CredentialMetadata
import org.multipaz.provisioning.Display
import org.multipaz.provisioning.DocumentProvisioningHandler
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.currentLocale
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

// "Family Name" in Japanese. A non-ASCII label is used on purpose so that multi-byte text goes through JSON
// parsing, the CBOR round trip and language matching.
private const val FAMILY_NAME_JA = "氏"

/**
 * Checks that claims of a document type unknown to the [DocumentTypeRepository], such as a national document
 * type issued over OpenID4VCI, are named using the display names from the issuer's metadata.
 */
class IssuerClaimDisplayNamesTest {
    companion object {
        private const val DOCTYPE = "org.example.residence.1"
        private const val NAMESPACE = "org.example.residence.1"
        private const val VCT = "urn:example:residence:1"
    }

    private lateinit var storage: EphemeralStorage
    private lateinit var secureAreaRepository: SecureAreaRepository
    private lateinit var secureArea: SecureArea
    private lateinit var documentStore: DocumentStore
    private lateinit var dsKey: AsymmetricKey.X509CertifiedExplicit

    private val documentType = DocumentType.Builder("Residence Record")
        .addMdocDocumentType(DOCTYPE)
        .addJsonDocumentType(VCT, keyBound = false)
        .addAttribute(
            type = DocumentAttributeType.String,
            identifier = "family_name",
            displayName = "Surname (from repository)",
            description = "Family name",
            mandatory = true,
            mdocNamespace = NAMESPACE,
            sampleValueMdoc = Tstr("Yamada"),
            sampleValueJson = JsonPrimitive("Yamada")
        )
        .addAttribute(
            type = DocumentAttributeType.String,
            identifier = "given_name",
            displayName = "Given name (from repository)",
            description = "Given name",
            mandatory = true,
            mdocNamespace = NAMESPACE,
            sampleValueMdoc = Tstr("Taro"),
            sampleValueJson = JsonPrimitive("Taro")
        )
        .build()

    private val familyNameDisplay = listOf(ClaimDisplay("Family Name", "en-US"), ClaimDisplay(FAMILY_NAME_JA, "ja-JP"))

    // The issuer describes `family_name` but not `given_name`.
    private val claimDescriptions = listOf(
        ClaimDescription(path(NAMESPACE, "family_name"), familyNameDisplay),
        ClaimDescription(path("family_name"), familyNameDisplay)
    )

    private fun path(vararg elements: String): JsonArray = buildJsonArray { elements.forEach { add(it) } }

    @BeforeTest
    fun setup() = runTest {
        storage = EphemeralStorage()
        secureArea = SoftwareSecureArea.create(storage)
        secureAreaRepository = SecureAreaRepository.Builder().add(secureArea).build()
        documentStore = buildDocumentStore(storage = storage, secureAreaRepository = secureAreaRepository) {}

        val random = Random(42)
        val validFrom = LocalDate.parse("2025-12-01").atStartOfDayIn(TimeZone.UTC)
        val validUntil = LocalDate.parse("2035-12-01").atStartOfDayIn(TimeZone.UTC)
        val iacaKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val iacaCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.AnonymousExplicit(iacaKey),
            subject = X500Name.fromName("C=JP,CN=Test IACA"),
            serial = ASN1Integer.fromRandom(128, random = random),
            validFrom = validFrom,
            validUntil = validUntil,
            issuerAltNameUrl = "https://example.com",
            crlUrl = "https://example.com/crl"
        )
        val dsPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaKey),
            dsKey = dsPrivateKey.publicKey,
            subject = X500Name.fromName("C=JP,CN=Test DS"),
            serial = ASN1Integer.fromRandom(128, random = random),
            validFrom = validFrom,
            validUntil = validUntil
        )
        dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsCert)), dsPrivateKey)
    }

    private suspend fun createDocument(): Document =
        documentStore.createDocumentInternal(claimDescriptions = claimDescriptions)

    private suspend fun addMdocCredential(document: Document) {
        // The MSO cannot hold fractional seconds.
        val now = LocalDate.parse("2026-01-01").atStartOfDayIn(TimeZone.UTC)
        documentType.createMdocCredentialWithSampleData(
            document = document,
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(algorithm = Algorithm.ESP256),
            dsKey = dsKey,
            signedAt = now,
            validFrom = now,
            validUntil = now + 30.days
        )
    }

    private suspend fun addSdJwtCredential(document: Document) {
        // The MSO cannot hold fractional seconds.
        val now = LocalDate.parse("2026-01-01").atStartOfDayIn(TimeZone.UTC)
        documentType.createKeylessSdJwtVcCredentialWithSampleData(
            document = document,
            dsKey = dsKey,
            signedAt = now,
            validFrom = now,
            validUntil = now + 30.days
        )
    }

    private suspend fun Document.claimNames(
        repository: DocumentTypeRepository,
        locales: List<String>
    ): Map<String, String> =
        getCredentials().single().getClaims(repository, locales).associate { claim ->
            when (claim) {
                is MdocClaim -> claim.dataElementName to claim.displayName
                is JsonClaim -> claim.claimPath.last().let { (it as JsonPrimitive).content } to claim.displayName
                else -> throw IllegalStateException("Unexpected claim $claim")
            }
        }

    @Test
    fun mdocClaimsUseIssuerDisplayNames() = runTest {
        val document = createDocument()
        addMdocCredential(document)
        val names = document.claimNames(DocumentTypeRepository(), listOf("ja-JP", "en-US"))
        assertEquals(FAMILY_NAME_JA, names["family_name"])
        // Not described by the issuer, so the data element name is used as before.
        assertEquals("given_name", names["given_name"])
        assertEquals("Family Name", document.claimNames(DocumentTypeRepository(), listOf("fr"))["family_name"])
    }

    @Test
    fun sdJwtClaimsUseIssuerDisplayNames() = runTest {
        val document = createDocument()
        addSdJwtCredential(document)
        val names = document.claimNames(DocumentTypeRepository(), listOf("ja"))
        assertEquals(FAMILY_NAME_JA, names["family_name"])
        assertEquals("given_name", names["given_name"])
    }

    @Test
    fun documentTypeRepositoryTakesPrecedence() = runTest {
        val document = createDocument()
        addMdocCredential(document)
        val repository = DocumentTypeRepository().apply { addDocumentType(documentType) }
        val names = document.claimNames(repository, listOf("ja-JP"))
        assertEquals("Surname (from repository)", names["family_name"])
        assertEquals("Given name (from repository)", names["given_name"])
    }

    @Test
    fun getClaimsWithoutLanguagesUsesCurrentLocale() = runTest {
        val document = createDocument()
        addMdocCredential(document)
        val credential = document.getCredentials().single()
        assertEquals(
            credential.getClaims(DocumentTypeRepository(), listOf(currentLocale)),
            credential.getClaims(DocumentTypeRepository())
        )
    }

    @Test
    fun claimDescriptionsArePersisted() = runTest {
        createDocument()
        val reloadedStore = buildDocumentStore(
            storage = EphemeralStorage.deserialize(storage.serialize()),
            secureAreaRepository = secureAreaRepository
        ) {}
        assertEquals(claimDescriptions, reloadedStore.listDocuments().single().claimDescriptions)
    }

    @Test
    fun claimDescriptionsSurviveEditsAndTagChanges() = runTest {
        val document = createDocument()
        document.edit { displayName = "My residence record" }
        assertEquals(claimDescriptions, document.claimDescriptions)
        document.tags.edit { set("key", "value") }
        assertEquals(claimDescriptions, document.claimDescriptions)
    }

    @Test
    fun claimDescriptionsCanBeEdited() = runTest {
        val document = documentStore.createDocument()
        assertEquals(emptyList(), document.claimDescriptions)
        document.edit { claimDescriptions = this@IssuerClaimDisplayNamesTest.claimDescriptions }
        assertEquals(claimDescriptions, document.claimDescriptions)
        document.edit { claimDescriptions = emptyList() }
        assertEquals(emptyList(), document.claimDescriptions)
    }

    @Test
    fun provisioningStoresClaimDescriptions() = runTest {
        val handler = DocumentProvisioningHandler(secureArea = secureArea, documentStore = documentStore)
        val document = handler.createDocument(
            credentialMetadata = CredentialMetadata(
                display = Display("Residence Record"),
                format = CredentialFormat.Mdoc(DOCTYPE),
                keyBindingType = KeyBindingType.Keyless,
                maxBatchSize = 1,
                claims = claimDescriptions
            ),
            issuerMetadata = ProvisioningMetadata(
                url = "https://issuer.example.com",
                display = Display("Example Issuer"),
                credentials = emptyMap()
            ),
            documentAuthorizationData = null,
            appData = null
        )
        assertEquals(claimDescriptions, document.claimDescriptions)
    }
}
