package org.multipaz.provisioning

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.DocumentStore
import org.multipaz.document.NameSpacedData
import org.multipaz.document.buildDocumentStore
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.mso.MobileSecurityObjectGenerator
import org.multipaz.mdoc.mso.StaticAuthDataGenerator
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.prompt.PromptModel
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class ProvisioningModelTest {
    private lateinit var storage: Storage
    private lateinit var secureAreaRepository: SecureAreaRepository

    private lateinit var documentStore: DocumentStore

    private val mockHttpEngine = MockEngine { request ->
        when (request.url.encodedPath) {
            else -> error("Unhandled request ${request.url}")
        }
    }
    private lateinit var model: ProvisioningModel

    @BeforeTest
    fun setup() = runTest {
        storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(secureArea)
            .build()
        documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {}
        model = ProvisioningModel(
            documentProvisioningHandler = DocumentProvisioningHandler(
                documentStore = documentStore,
                secureArea = secureArea
            ),
            httpClient = HttpClient(mockHttpEngine),
            promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build(),
            authorizationSecureArea = secureArea
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun basic() = runTest {
        val doc = model.launch(UnconfinedTestDispatcher(testScheduler)) {
            TestProvisioningClient()
        }.await()
        assertTrue(doc.provisioned)
        assertEquals("Document Title", doc.displayName)
        assertEquals("Test Document", doc.typeDisplayName)
        val credentials = doc.getCredentials()
        assertEquals(2, credentials.size)
        val credential = credentials.first() as MdocCredential
        assertTrue(credential.isCertified)
        assertEquals(TestProvisioningClient.DOCTYPE, credential.docType)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun delayed() = runTest {
        var issue = false
        val client = TestProvisioningClient( obtainCredentialsHook = { issue } )
        val doc = model.launch(UnconfinedTestDispatcher(testScheduler)) {
            client
        }.await()
        assertFalse(doc.provisioned)
        assertEquals("Test Document", doc.displayName)
        assertEquals("Test Document", doc.typeDisplayName)
        val credentials = doc.getCredentials()
        assertEquals(2, credentials.size)
        val credential = credentials.first() as MdocCredential
        assertFalse(credential.isCertified)
        issue = true
        val doc1 = model.launch(UnconfinedTestDispatcher(testScheduler), doc) {
            client
        }.await()
        assertSame(doc, doc1)
        assertTrue(doc.provisioned)
        assertEquals("Document Title", doc.displayName)
        assertEquals("Test Document", doc.typeDisplayName)
        val credentials1 = doc.getCredentials()
        assertEquals(2, credentials1.size)
        val credential1 = credentials.first() as MdocCredential
        assertTrue(credential1.isCertified)
        assertEquals(TestProvisioningClient.DOCTYPE, credential.docType)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun authorization() = runTest {
        backgroundScope.launch {
            model.state.collect { state ->
                if (state is ProvisioningModel.Authorizing) {
                    assertEquals(1, state.authorizationChallenges.size)
                    val oauth =
                        state.authorizationChallenges.first() as AuthorizationChallenge.OAuth
                    assertEquals("id", oauth.id)
                    assertEquals("https://example.com", oauth.url)
                    assertEquals("state", oauth.state)
                    model.provideAuthorizationResponse(
                        AuthorizationResponse.OAuth(
                            id = "id",
                            parameterizedRedirectUrl = "https://redirect.example.com/"
                        )
                    )
                }
            }
        }
        val doc = model.launch(UnconfinedTestDispatcher(testScheduler)) {
            TestProvisioningClient(
                authorizationChallenges = listOf(
                    AuthorizationChallenge.OAuth("id", "https://example.com", "state")
                )
            )
        }.await()
        assertTrue(doc.provisioned)
        assertEquals("foobar_auth",
            doc.authorizationData!!.toByteArray().decodeToString())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellation() = runTest {
        val channel = Channel<Unit>()
        val deferredDoc = model.launch(UnconfinedTestDispatcher(testScheduler)) {
            TestProvisioningClient(obtainCredentialsHook = {
                channel.send(Unit)
                delay(Duration.INFINITE)
                true
            })
        }
        assertTrue(deferredDoc.isActive)
        channel.receive()
        val documentIds = documentStore.listDocumentIds()
        assertEquals(1, documentIds.size)
        val doc = documentStore.lookupDocument(documentIds.first())!!
        val credentials = doc.getCredentials()
        assertEquals(2, credentials.size)
        val credential = credentials.first() as MdocCredential
        assertFalse(credential.isCertified)
        assertEquals(TestProvisioningClient.DOCTYPE, credential.docType)
        assertFalse(doc.provisioned)
        deferredDoc.cancel()
        try {
            deferredDoc.await()
            fail()
        } catch (_: CancellationException) {
        }
        // Initial provisioning failed, document must be cleaned up
        assertTrue(documentStore.listDocumentIds().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun error() = runTest {
        val deferredDoc = model.launch(UnconfinedTestDispatcher(testScheduler)) {
            TestProvisioningClient(obtainCredentialsHook = {
                throw RuntimeException("foobar")
            })
        }
        try {
            deferredDoc.await()
            fail()
        } catch (e: RuntimeException) {
            assertEquals("foobar", e.message)
        }
        // Initial provisioning failed, document must be cleaned up
        assertTrue(documentStore.listDocumentIds().isEmpty())
    }

    class TestProvisioningClient(
        val obtainCredentialsHook: suspend () -> Boolean = { true },
        val authorizationChallenges: List<AuthorizationChallenge> = listOf()
    ) : ProvisioningClient {
        companion object {
            const val DOCTYPE = "http://doctype.example.org"
        }

        val metadata = ProvisioningMetadata(
            display = Display("Test Issuer", null),
            credentials = mapOf(
                "testId" to CredentialMetadata(
                    display = Display("Test Document", null),
                    format = CredentialFormat.Mdoc(DOCTYPE),
                    keyBindingType = KeyBindingType.Attestation(Algorithm.ESP256),
                    maxBatchSize = 2
                )
            )
        )
        var authorizationResponse: AuthorizationResponse? = null

        val pending = mutableListOf<CredentialKeyAttestation>()

        override suspend fun getMetadata(): ProvisioningMetadata = metadata

        override suspend fun getAuthorizationChallenges(): List<AuthorizationChallenge> =
            if (authorizationResponse == null) authorizationChallenges else listOf()

        override suspend fun authorize(response: AuthorizationResponse) {
            if (authorizationResponse != null) {
                throw IllegalStateException()
            }
            authorizationResponse = response
        }

        override suspend fun getAuthorizationData(): ByteString =
            ByteString("foobar_auth".encodeToByteArray())

        override suspend fun getKeyBindingChallenge(): String = "test_challenge"

        override suspend fun obtainCredentials(keyInfo: KeyBindingInfo): Credentials {
            when (keyInfo) {
                is KeyBindingInfo.Attestation ->
                    pending.addAll(keyInfo.attestations)
                else -> throw IllegalArgumentException()
            }
            if (!obtainCredentialsHook()) {
                return Credentials(listOf(), null)
            }
            return generateTestMDoc(
                docType = DOCTYPE,
                credentialIds = pending.map { it.credentialId },
                publicKeys = pending.map { it.keyAttestation.publicKey }
            ).also {
                pending.clear()
            }
        }
    }

    class TestPromptModel private constructor(builder: Builder): PromptModel(builder) {
        override val promptModelScope =
            CoroutineScope(Dispatchers.Default + SupervisorJob() + this)

        class Builder: PromptModel.Builder(
            toHumanReadable = { _, _ -> throw IllegalStateException("unexpected state") }
        ) {
            override fun build(): TestPromptModel = TestPromptModel(this)
        }
    }

    companion object {
        // TODO: move to some shared test utility?
        suspend fun generateTestMDoc(
            docType: String,
            credentialIds: List<String>,
            publicKeys: List<EcPublicKey>
        ): Credentials {
            val now = Clock.System.now()
            val nameSpacedData = NameSpacedData.Builder()
                .putEntryString("ns", "name", "value")
                .build()

            // Generate an MSO and issuer-signed data for these authentication keys.
            val validFrom = now - 1.days
            val validUntil = now + 100.days
            val dsKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val dsCert = X509Cert.Builder(
                publicKey = dsKey.publicKey,
                signingKey = AsymmetricKey.anonymous(dsKey),
                serialNumber = ASN1Integer(1),
                subject = X500Name.fromName("CN=State of Utopia DS Key"),
                issuer = X500Name.fromName("CN=State of Utopia DS Key"),
                validFrom = validFrom,
                validUntil = validUntil
            ).build()
            val credentials = publicKeys.map { publicKey ->
                val msoGenerator = MobileSecurityObjectGenerator(
                    Algorithm.SHA256,
                    docType,
                    publicKey
                )
                msoGenerator.setValidityInfo(now, now, now + 30.days, null)
                val issuerNameSpaces = MdocUtil.generateIssuerNameSpaces(
                    nameSpacedData,
                    Random,
                    16,
                    null
                )
                for (nameSpaceName in issuerNameSpaces.keys) {
                    val digests = MdocUtil.calculateDigestsForNameSpace(
                        nameSpaceName,
                        issuerNameSpaces,
                        Algorithm.SHA256
                    )
                    msoGenerator.addDigestIdsForNamespace(nameSpaceName, digests)
                }
                val mso = msoGenerator.generate()
                val taggedEncodedMso = Cbor.encode(Tagged(24, Bstr(mso)))

                // IssuerAuth is a COSE_Sign1 where payload is MobileSecurityObjectBytes
                //
                // MobileSecurityObjectBytes = #6.24(bstr .cbor MobileSecurityObject)
                //
                val protectedHeaders = mapOf<CoseLabel, DataItem>(
                    Pair(
                        CoseNumberLabel(Cose.COSE_LABEL_ALG),
                        Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
                    )
                )
                val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
                    Pair(
                        CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN),
                        X509CertChain(listOf(dsCert)).toDataItem()
                    )
                )
                val encodedIssuerAuth = Cbor.encode(
                    Cose.coseSign1Sign(
                        dsKey,
                        taggedEncodedMso,
                        true,
                        Algorithm.ES256,
                        protectedHeaders,
                        unprotectedHeaders
                    ).toDataItem()
                )
                ByteString(
                    StaticAuthDataGenerator(
                        MdocUtil.stripIssuerNameSpaces(issuerNameSpaces, null),
                        encodedIssuerAuth
                    ).generate()
                )
            }
            return Credentials(
                certifications = credentials.zip(credentialIds).map { (data, id) ->
                    CredentialCertification(id, data)
                },
                display = Display(text = "Document Title", logo = null)
            )
        }
    }
}
