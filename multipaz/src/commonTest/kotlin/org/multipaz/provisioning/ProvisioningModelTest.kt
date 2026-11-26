package org.multipaz.provisioning

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
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
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import org.multipaz.crypto.PublicKey
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.DocumentStore
import org.multipaz.document.Document
import org.multipaz.document.NameSpacedData
import org.multipaz.document.buildDocumentStore
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.EventLogger
import org.multipaz.mdoc.credential.KeyBoundMdocCredential
import org.multipaz.mdoc.mso.MobileSecurityObjectGenerator
import org.multipaz.mdoc.mso.StaticAuthDataGenerator
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.prompt.PromptModel
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.sdjwt.SdJwt
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class ProvisioningModelTest {
    private lateinit var storage: Storage
    private lateinit var secureAreaRepository: SecureAreaRepository
    private lateinit var secureArea: SecureArea
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
        secureArea = SoftwareSecureArea.create(storage)
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
        assertNull(doc.appData)
        assertEquals("Document Title", doc.displayName)
        assertEquals("Test Document", doc.typeDisplayName)
        val credentials = doc.getCredentials()
        assertEquals(2, credentials.size)
        val credential = credentials.first() as KeyBoundMdocCredential
        assertTrue(credential.isCertified)
        assertEquals(TestProvisioningClient.DOCTYPE, credential.docType)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun notificationAcceptedAfterBatchIsStored() = runTest {
        val notifications = mutableListOf<CredentialNotificationEvent>()
        val client = object : TestProvisioningClient() {
            override suspend fun obtainCredentials(keyInfo: KeyBindingInfo): Credentials {
                assertTrue(notifications.isEmpty())
                val credentials = super.obtainCredentials(keyInfo)
                val document = documentStore.lookupDocument(documentStore.listDocumentIds().single())!!
                assertEquals(2, document.getPendingCredentials().size)
                assertTrue(document.getCertifiedCredentials().isEmpty())
                return credentials
            }

            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                val document = documentStore.lookupDocument(documentStore.listDocumentIds().single())!!
                assertEquals(2, document.getCertifiedCredentials().size)
                assertTrue(document.getPendingCredentials().isEmpty())
                assertEquals("Document Title", document.displayName)
                notifications.add(event)
            }
        }

        val document = model.launch(UnconfinedTestDispatcher(testScheduler)) { client }.await()

        assertTrue(document.provisioned)
        assertEquals(listOf(CredentialNotificationEvent.ACCEPTED), notifications)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun notificationFailureWhenDocumentUpdateFails() = runTest {
        val notifications = mutableListOf<CredentialNotificationEvent>()
        val storageFailure = IllegalStateException("Simulated document storage failure")
        val handler = object : DocumentProvisioningHandler(secureArea, documentStore) {
            override suspend fun updateDocument(
                document: Document,
                display: Display?,
                documentAuthorizationData: ByteString?
            ) {
                assertEquals(2, document.getCertifiedCredentials().size)
                throw storageFailure
            }
        }
        val failingModel = ProvisioningModel(
            documentProvisioningHandler = handler,
            httpClient = HttpClient(mockHttpEngine),
            promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build(),
            authorizationSecureArea = secureArea
        )
        val client = object : TestProvisioningClient() {
            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                notifications.add(event)
            }
        }

        val failure = assertFailsWith<IllegalStateException> {
            failingModel.launch(UnconfinedTestDispatcher(testScheduler)) { client }.await()
        }

        assertEquals(storageFailure.message, failure.message)
        assertEquals(listOf(CredentialNotificationEvent.FAILURE), notifications)
        assertTrue(documentStore.listDocumentIds().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshFailurePreservesReplacedCredentials() = runTest {
        val document = model.launch(UnconfinedTestDispatcher(testScheduler)) {
            TestProvisioningClient()
        }.await()
        val originalCredentials = document.getCertifiedCredentials()
        val originalIds = originalCredentials.map { it.identifier }.toSet()
        originalCredentials.forEach { it.increaseUsageCount() }
        val notifications = mutableListOf<CredentialNotificationEvent>()
        val handler = object : DocumentProvisioningHandler(secureArea, documentStore) {
            override suspend fun updateDocument(
                document: Document,
                display: Display?,
                documentAuthorizationData: ByteString?
            ) {
                assertTrue(document.getCertifiedCredentials().size > originalIds.size)
                throw IllegalStateException("Simulated document storage failure")
            }
        }
        val failingModel = ProvisioningModel(
            documentProvisioningHandler = handler,
            httpClient = HttpClient(mockHttpEngine),
            promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build(),
            authorizationSecureArea = secureArea
        )
        val client = object : TestProvisioningClient() {
            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                notifications.add(event)
            }
        }
        assertFailsWith<IllegalStateException> {
            failingModel.launch(UnconfinedTestDispatcher(testScheduler), document) { client }.await()
        }
        assertEquals(originalIds, document.getCertifiedCredentials().map { it.identifier }.toSet())
        assertTrue(document.getPendingCredentials().isEmpty())
        assertEquals(listOf(CredentialNotificationEvent.FAILURE), notifications)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failureBeforeObtainDoesNotNotify() = runTest {
        val notifications = mutableListOf<CredentialNotificationEvent>()
        val client = object : TestProvisioningClient(
            keyBindingChallengeHook = { throw IllegalStateException("Challenge failed") }
        ) {
            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                notifications.add(event)
            }
        }
        assertFailsWith<IllegalStateException> {
            model.launch(UnconfinedTestDispatcher(testScheduler)) { client }.await()
        }
        assertTrue(notifications.isEmpty())
        assertTrue(documentStore.listDocumentIds().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun delayedIssuanceDoesNotReportAcceptance() = runTest {
        val notifications = mutableListOf<CredentialNotificationEvent>()
        val client = object : TestProvisioningClient(obtainCredentialsHook = { false }) {
            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                notifications.add(event)
            }
        }
        val document = model.launch(UnconfinedTestDispatcher(testScheduler)) { client }.await()
        assertFalse(document.provisioned)
        assertTrue(notifications.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun notificationFailureWhenBatchContainsUnknownCredential() = runTest {
        val notifications = mutableListOf<CredentialNotificationEvent>()
        val client = object : TestProvisioningClient() {
            override suspend fun obtainCredentials(keyInfo: KeyBindingInfo): Credentials {
                val credentials = super.obtainCredentials(keyInfo)
                return credentials.copy(certifications = listOf(
                    credentials.certifications.first(),
                    credentials.certifications.last().copy(credentialId = "unknown")
                ))
            }

            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                notifications.add(event)
            }
        }
        val document = model.launch(UnconfinedTestDispatcher(testScheduler)) { client }.await()
        assertEquals(1, document.getCertifiedCredentials().size)
        assertEquals(listOf(CredentialNotificationEvent.FAILURE), notifications)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun delayedIssuanceWithEventLogger() = runTest {
        val loggingModel = ProvisioningModel(
            documentProvisioningHandler = DocumentProvisioningHandler(secureArea, documentStore),
            httpClient = HttpClient(mockHttpEngine),
            promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build(),
            authorizationSecureArea = secureArea,
            eventLogger = object : EventLogger {
                override suspend fun addEvent(event: Event): Event? = event
                override fun addEventAsync(event: Event) = Unit
            }
        )
        val document = loggingModel.launch(UnconfinedTestDispatcher(testScheduler)) {
            TestProvisioningClient(obtainCredentialsHook = { false })
        }.await()
        assertFalse(document.provisioned)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationDuringAcceptedNotificationKeepsDocument() = runTest {
        val notificationStarted = Channel<Unit>()
        val notifications = mutableListOf<CredentialNotificationEvent>()
        val client = object : TestProvisioningClient() {
            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                notifications.add(event)
                notificationStarted.send(Unit)
                delay(Duration.INFINITE)
            }
        }
        val deferred = model.launch(UnconfinedTestDispatcher(testScheduler)) { client }
        notificationStarted.receive()
        model.cancel()
        assertFailsWith<CancellationException> { deferred.await() }
        val document = documentStore.lookupDocument(documentStore.listDocumentIds().single())!!
        assertTrue(document.provisioned)
        assertEquals(2, document.getCertifiedCredentials().size)
        assertEquals(listOf(CredentialNotificationEvent.ACCEPTED), notifications)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun eventLoggerFailureDoesNotDeleteAcceptedDocument() = runTest {
        val notifications = mutableListOf<CredentialNotificationEvent>()
        val failingModel = ProvisioningModel(
            documentProvisioningHandler = DocumentProvisioningHandler(secureArea, documentStore),
            httpClient = HttpClient(mockHttpEngine),
            promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build(),
            authorizationSecureArea = secureArea,
            eventLogger = object : EventLogger {
                override suspend fun addEvent(event: Event): Event? {
                    throw IllegalStateException("Event logger failed")
                }

                override fun addEventAsync(event: Event) = Unit
            }
        )
        val client = object : TestProvisioningClient() {
            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                notifications.add(event)
            }
        }
        assertFailsWith<IllegalStateException> {
            failingModel.launch(UnconfinedTestDispatcher(testScheduler)) { client }.await()
        }
        val document = documentStore.lookupDocument(documentStore.listDocumentIds().single())!!
        assertTrue(document.provisioned)
        assertEquals(2, document.getCertifiedCredentials().size)
        assertEquals(listOf(CredentialNotificationEvent.ACCEPTED), notifications)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun laterKeylessFailureKeepsEarlierAcceptedCredential() = runTest {
        val now = Clock.System.now()
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val cert = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key),
            serialNumber = ASN1Integer(1),
            subject = X500Name.fromName("CN=Test Issuer"),
            issuer = X500Name.fromName("CN=Test Issuer"),
            validFrom = now - 1.days,
            validUntil = now + 30.days
        ).build()
        val vct = "https://example.com/vct"
        val sdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(cert)), key),
            kbKey = null,
            claims = buildJsonObject { put("given_name", "Jane") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.com")
                put("vct", vct)
                put("iat", now.epochSeconds)
                put("exp", (now + 10.days).epochSeconds)
            }
        )
        val notifications = mutableListOf<CredentialNotificationEvent>()
        var requests = 0
        val client = object : TestProvisioningClient() {
            override suspend fun getMetadata(): ProvisioningMetadata = metadata.copy(
                credentials = mapOf(
                    "testId" to metadata.credentials.getValue("testId").copy(
                        format = CredentialFormat.SdJwt(vct),
                        keyBindingType = KeyBindingType.Keyless
                    )
                )
            )

            override suspend fun obtainCredentials(keyInfo: KeyBindingInfo): Credentials {
                if (++requests == 2) throw IllegalStateException("Second request failed")
                return Credentials(
                    listOf(CredentialCertification("", sdJwt.compactSerialization.encodeToByteString())),
                    null
                )
            }

            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                notifications.add(event)
            }
        }
        val keylessModel = ProvisioningModel(
            documentProvisioningHandler = DocumentProvisioningHandler(
                secureArea = secureArea,
                documentStore = documentStore,
                defaultDocumentProvisioningSettings = DocumentProvisioningSettings(keylessCredentialNumPerDomain = 2)
            ),
            httpClient = HttpClient(mockHttpEngine),
            promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build(),
            authorizationSecureArea = secureArea
        )
        assertFailsWith<IllegalStateException> {
            keylessModel.launch(UnconfinedTestDispatcher(testScheduler)) { client }.await()
        }
        val document = documentStore.lookupDocument(documentStore.listDocumentIds().single())!!
        assertEquals(1, document.getCertifiedCredentials().size)
        assertTrue(document.getPendingCredentials().isEmpty())
        assertEquals(
            listOf(CredentialNotificationEvent.ACCEPTED, CredentialNotificationEvent.FAILURE),
            notifications
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun notificationFailureWhenSecondBatchCredentialIsMalformed() = runTest {
        val notifications = mutableListOf<CredentialNotificationEvent>()
        var firstCertification: CredentialCertification? = null
        val client = object : TestProvisioningClient() {
            override suspend fun obtainCredentials(keyInfo: KeyBindingInfo): Credentials {
                val credentials = super.obtainCredentials(keyInfo)
                firstCertification = credentials.certifications.first()
                return credentials.copy(certifications = listOf(
                    credentials.certifications.first(),
                    credentials.certifications.last().copy(issuerData = ByteString(0xff.toByte()))
                ))
            }

            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                val document = documentStore.lookupDocument(documentStore.listDocumentIds().single())!!
                val certification = firstCertification!!
                val firstCredential = document.lookupCredential(certification.credentialId)!!
                assertTrue(firstCredential.isCertified)
                assertEquals(certification.issuerData, firstCredential.issuerProvidedData)
                notifications.add(event)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            model.launch(UnconfinedTestDispatcher(testScheduler)) { client }.await()
        }

        assertEquals(listOf(CredentialNotificationEvent.FAILURE), notifications)
        assertTrue(documentStore.listDocumentIds().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun basicWithAppData() = runTest {
        val expectedAppData = ByteString(10, 20, 30, 40)
        val doc = model.launch(
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
            appData = expectedAppData
        ) {
            TestProvisioningClient()
        }.await()
        assertTrue(doc.provisioned)
        assertEquals(expectedAppData, doc.appData)
        assertEquals("Document Title", doc.displayName)
        assertEquals("Test Document", doc.typeDisplayName)

        // Verify it was persisted in DocumentStore
        val docFromStore = documentStore.lookupDocument(doc.identifier)!!
        assertEquals(expectedAppData, docFromStore.appData)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun basicWithCustomSecureAreaSelection() = runTest {
        val alternateSecureArea = object : SecureArea by secureArea {
            override val identifier: String get() = "AlternateSecureArea"
            override val displayName: String get() = "Alternate Secure Area"
        }
        val customRepo = SecureAreaRepository.Builder()
            .add(secureArea)
            .add(alternateSecureArea)
            .build()
        val customDocStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = customRepo
        ) {}
        val customHandler = DocumentProvisioningHandler(
            documentStore = customDocStore,
            secureArea = secureArea,
            selectSecureArea = { appData, suggestedSettings ->
                if (appData == ByteString(0x01)) {
                    SelectedSecureArea(alternateSecureArea, suggestedSettings)
                } else {
                    SelectedSecureArea(secureArea, suggestedSettings)
                }
            }
        )
        val customModel = ProvisioningModel(
            documentProvisioningHandler = customHandler,
            httpClient = HttpClient(mockHttpEngine),
            promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build(),
            authorizationSecureArea = secureArea
        )

        // Provision with appData = 0x01 -> should use alternateSecureArea
        val docWithAlternate = customModel.launch(
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
            appData = ByteString(0x01)
        ) {
            TestProvisioningClient()
        }.await()
        assertEquals(ByteString(0x01), docWithAlternate.appData)
        val cred1 = docWithAlternate.getCredentials().first() as KeyBoundMdocCredential
        assertEquals(alternateSecureArea.identifier, cred1.secureArea.identifier)

        // Provision with appData = null -> should use default secureArea
        val docWithDefault = customModel.launch(
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
            appData = null
        ) {
            TestProvisioningClient()
        }.await()
        assertNull(docWithDefault.appData)
        val cred2 = docWithDefault.getCredentials().first() as KeyBoundMdocCredential
        assertEquals(secureArea.identifier, cred2.secureArea.identifier)
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
        val credential = credentials.first() as KeyBoundMdocCredential
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
        val credential1 = credentials.first() as KeyBoundMdocCredential
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
        val notifications = mutableListOf<CredentialNotificationEvent>()
        val deferredDoc = model.launch(UnconfinedTestDispatcher(testScheduler)) {
            object : TestProvisioningClient(obtainCredentialsHook = {
                channel.send(Unit)
                delay(Duration.INFINITE)
                true
            }) {
                override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                    notifications.add(event)
                }
            }
        }
        assertTrue(deferredDoc.isActive)
        channel.receive()
        val documentIds = documentStore.listDocumentIds()
        assertEquals(1, documentIds.size)
        val doc = documentStore.lookupDocument(documentIds.first())!!
        val credentials = doc.getCredentials()
        assertEquals(2, credentials.size)
        val credential = credentials.first() as KeyBoundMdocCredential
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
        assertTrue(notifications.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun userCancellationDuringCredentialStorageNotifiesCredentialDeleted() = runTest {
        val updateStarted = Channel<Unit>()
        val notifications = mutableListOf<CredentialNotificationEvent>()
        val handler = object : DocumentProvisioningHandler(secureArea, documentStore) {
            override suspend fun updateDocument(
                document: Document,
                display: Display?,
                documentAuthorizationData: ByteString?
            ) {
                updateStarted.send(Unit)
                delay(Duration.INFINITE)
            }
        }
        val cancelModel = ProvisioningModel(
            documentProvisioningHandler = handler,
            httpClient = HttpClient(mockHttpEngine),
            promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build(),
            authorizationSecureArea = secureArea
        )
        val client = object : TestProvisioningClient() {
            override suspend fun notifyCredentialResult(event: CredentialNotificationEvent) {
                notifications.add(event)
            }
        }

        val deferredDocument = cancelModel.launch(UnconfinedTestDispatcher(testScheduler)) { client }
        updateStarted.receive()
        cancelModel.cancel()

        assertFailsWith<CancellationException> { deferredDocument.await() }
        assertEquals(listOf(CredentialNotificationEvent.DELETED), notifications)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshNoOpWhenNoCredentialsToFetch() = runTest {
        val doc = model.launch(UnconfinedTestDispatcher(testScheduler)) {
            TestProvisioningClient()
        }.await()
        assertTrue(doc.provisioned)

        val handler = DocumentProvisioningHandler(
            documentStore = documentStore,
            secureArea = secureArea
        )
        // With all credentials certified and up to date, no credentials need refresh
        assertFalse(handler.haveCredentialsToRefresh(doc))

        // openID4VCIRefreshCredentials should immediately return 0 without attempting network I/O
        // (Invalid authorizationData would throw an exception if network I/O / client creation was attempted)
        val numRefreshed = model.openID4VCIRefreshCredentials(
            document = doc,
            authorizationData = ByteString(byteArrayOf(0x00)),
            clientPreferences = OpenID4VCIClientPreferences(
                clientId = "test",
                redirectUrl = "https://example.com/redirect",
                locales = listOf("en-US"),
                signingAlgorithms = listOf(Algorithm.ES256)
            ),
            backend = object : OpenID4VCIBackend {
                override suspend fun getClientId(): String = "test"
                override suspend fun createJwtClientAssertion(authorizationServerIdentifier: String): String = ""
                override suspend fun createJwtWalletAttestation(keyAttestation: KeyAttestation): String = ""
                override suspend fun createJwtKeyAttestation(
                    credentialKeyAttestations: List<CredentialKeyAttestation>,
                    challenge: String,
                    userAuthentication: List<String>?,
                    keyStorage: List<String>?
                ): String = ""
            }
        )
        assertEquals(0, numRefreshed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retryAfterFailureWithNewChallenge() = runTest {
        var failFirst = true
        val client = TestProvisioningClient(
            obtainCredentialsHook = {
                if (failFirst) {
                    failFirst = false
                    throw IllegalStateException("Simulated network error")
                }
                true
            }
        )
        // First attempt fails during initial provisioning
        try {
            model.launch(UnconfinedTestDispatcher(testScheduler)) {
                client
            }.await()
            fail("Expected exception")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated network error", e.message)
        }

        // Verify document was deleted on error
        assertEquals(0, documentStore.listDocuments().size)

        // Second attempt succeeds
        val doc = model.launch(UnconfinedTestDispatcher(testScheduler)) {
            client
        }.await()
        assertTrue(doc.provisioned)
        assertEquals(2, doc.getCredentials().size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshRetryAfterFailureWithNewChallenge() = runTest {
        var currentChallenge = "challenge_1"
        var failRefresh = false
        val attestedPublicKeys = mutableListOf<List<PublicKey>>()

        val client = object : TestProvisioningClient(
            keyBindingChallengeHook = { currentChallenge }
        ) {
            override suspend fun obtainCredentials(keyInfo: KeyBindingInfo): Credentials {
                if (keyInfo is KeyBindingInfo.Attestation) {
                    attestedPublicKeys.add(keyInfo.attestations.map { it.keyAttestation.publicKey })
                }
                if (failRefresh) {
                    failRefresh = false
                    throw IllegalStateException("Simulated network failure on refresh")
                }
                return super.obtainCredentials(keyInfo)
            }
        }

        // 1. Initial provisioning succeeds
        val doc = model.launch(UnconfinedTestDispatcher(testScheduler)) {
            client
        }.await()
        assertTrue(doc.provisioned)
        assertEquals(2, doc.getCertifiedCredentials().size)
        assertEquals(0, doc.getPendingCredentials().size)

        // Mark existing credentials as used so they require replacement during refresh
        for (cred in doc.getCertifiedCredentials()) {
            cred.increaseUsageCount()
        }

        // 2. Refresh fails midway
        currentChallenge = "challenge_2"
        failRefresh = true
        try {
            model.launch(UnconfinedTestDispatcher(testScheduler), doc) {
                client
            }.await()
            fail("Expected refresh to fail")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated network failure on refresh", e.message)
        }

        // Verify pending credentials were cleaned up on failure
        assertEquals(0, doc.getPendingCredentials().size)
        val failedAttemptKeys = attestedPublicKeys.last()

        // 3. Retry refresh with a new challenge
        currentChallenge = "challenge_3"
        val docAfterRetry = model.launch(UnconfinedTestDispatcher(testScheduler), doc) {
            client
        }.await()
        assertTrue(docAfterRetry.provisioned)
        assertEquals(0, docAfterRetry.getPendingCredentials().size)
        val retryAttemptKeys = attestedPublicKeys.last()

        // Verify that retry attempt generated fresh keys and did NOT reuse the keys from the failed attempt
        for (key in retryAttemptKeys) {
            assertFalse(failedAttemptKeys.contains(key))
        }
    }

    open class TestProvisioningClient(
        val obtainCredentialsHook: suspend () -> Boolean = { true },
        val authorizationChallenges: List<AuthorizationChallenge> = listOf(),
        val keyBindingChallengeHook: suspend () -> String = { "test_challenge" }
    ) : ProvisioningClient {
        companion object {
            const val DOCTYPE = "http://doctype.example.org"
        }

        val metadata = ProvisioningMetadata(
            url = "https://example.com/test",
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

        override suspend fun getKeyBindingChallenge(): String = keyBindingChallengeHook()

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
                publicKeys = pending.map { it.keyAttestation.ecPublicKey }
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
