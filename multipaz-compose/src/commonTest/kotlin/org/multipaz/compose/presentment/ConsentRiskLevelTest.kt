package org.multipaz.compose.presentment

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.buildCborArray
import org.multipaz.consent.ConsentRiskAssessment
import org.multipaz.consent.ConsentRiskLevel
import org.multipaz.consent.ConsentRiskReason
import org.multipaz.consent.PresentmentPurpose
import org.multipaz.consent.defaultClassifyRisk
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.documenttype.knowntypes.addKnownTypes
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentUseCaseSolution
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.Iso18013RequesterIdentity
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.truncateToWholeSeconds
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.multipaz.utopia.knowntypes.addUtopiaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class ConsentRiskLevelTest {

    private class TestEnv(
        val documentStore: DocumentStore,
        val documentTypeRepository: DocumentTypeRepository,
        val presentmentSource: SimplePresentmentSource,
        val trustedIdentity: TrustedRequesterIdentity,
    )

    private suspend fun createTestEnv(): TestEnv {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val secureAreaRepository = SecureAreaRepository.Builder().add(secureArea).build()
        val documentTypeRepository = DocumentTypeRepository().apply {
            addKnownTypes()
            addUtopiaTypes()
        }
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

        val docMdl = documentStore.createDocument(displayName = "Test mDL")
        DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = docMdl,
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = now - 1.days,
            validFrom = now - 1.days,
            validUntil = now + 30.days,
            expectedUpdate = null,
            domain = "mdoc",
        )

        val docPayment = documentStore.createDocument(displayName = "Test Payment")
        DigitalPaymentCredential.getDocumentType().createMdocCredentialWithSampleData(
            document = docPayment,
            secureArea = secureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = dsKey,
            signedAt = now - 1.days,
            validFrom = now - 1.days,
            validUntil = now + 30.days,
            expectedUpdate = null,
            domain = "mdoc",
            deviceKeyAuthorizedNamespaces = listOf(PaymentTransaction.openId4VpMdocResponseNamespace),
        )

        val presentmentSource = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            resolveTrustFn = { null },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
        )
        val trustedIdentity = TrustedRequesterIdentity(
            identity = Iso18013RequesterIdentity(X509CertChain(listOf(iacaCert))),
            trustMetadata = TrustMetadata(displayName = "Trusted Reader"),
        )
        return TestEnv(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            presentmentSource = presentmentSource,
            trustedIdentity = trustedIdentity,
        )
    }

    @Test
    fun testNormalWarningAndHighRiskClassification() = runTest {
        val env = createTestEnv()
        val proximityRequester = Requester()
        val webRequester = Requester(origin = "https://verifier.example.com")
        val appRequester = Requester(appId = "com.example.verifier")

        // 1. Low-sensitivity request (age_over_21 + portrait, no retain):
        // Trusted -> NORMAL; Untrusted Proximity -> NORMAL; Untrusted Web/App -> WARNING
        val agePortraitReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_21" to false,
                        "portrait" to false,
                    )
                )
            )
        }
        val agePortraitData = agePortraitReq.execute(presentmentSource = env.presentmentSource)
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(proximityRequester, env.trustedIdentity, agePortraitData)
        )
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(proximityRequester, null, agePortraitData)
        )
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.WARNING, setOf(ConsentRiskReason.UNTRUSTED_REQUESTER)),
            defaultClassifyRisk(webRequester, null, agePortraitData)
        )
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.WARNING, setOf(ConsentRiskReason.UNTRUSTED_REQUESTER)),
            defaultClassifyRisk(appRequester, null, agePortraitData)
        )

        // 2. PII request (given_name + family_name):
        // Trusted -> NORMAL; Untrusted -> HIGH_RISK (UNTRUSTED_REQUESTER + PII_REQUESTED)
        val piiReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "given_name" to false,
                        "family_name" to false,
                    )
                )
            )
        }
        val piiData = piiReq.execute(presentmentSource = env.presentmentSource)
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(proximityRequester, env.trustedIdentity, piiData)
        )
        assertEquals(
            ConsentRiskAssessment(
                ConsentRiskLevel.HIGH_RISK,
                setOf(ConsentRiskReason.UNTRUSTED_REQUESTER, ConsentRiskReason.PII_REQUESTED),
            ),
            defaultClassifyRisk(proximityRequester, null, piiData)
        )

        // 3. Intent to retain:
        // - Proximity: only triggers INTENT_TO_RETAIN for sensitivity >= PII
        // - Non-proximity (Web/App): triggers INTENT_TO_RETAIN for sensitivity >= PORTRAIT_IMAGE
        // - When PII is retained by an untrusted requester, both PII_REQUESTED and INTENT_TO_RETAIN are returned.
        val retainedAgeOnlyReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_21" to true,
                    )
                )
            )
        }
        val retainedAgeOnlyData = retainedAgeOnlyReq.execute(presentmentSource = env.presentmentSource)
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(proximityRequester, null, retainedAgeOnlyData)
        )
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.WARNING, setOf(ConsentRiskReason.UNTRUSTED_REQUESTER)),
            defaultClassifyRisk(webRequester, null, retainedAgeOnlyData)
        )

        val retainedAgePortraitReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_21" to true,
                        "portrait" to true,
                    )
                )
            )
        }
        val retainedAgePortraitData = retainedAgePortraitReq.execute(presentmentSource = env.presentmentSource)
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(proximityRequester, env.trustedIdentity, retainedAgePortraitData)
        )
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(proximityRequester, null, retainedAgePortraitData)
        )
        assertEquals(
            ConsentRiskAssessment(
                ConsentRiskLevel.HIGH_RISK,
                setOf(ConsentRiskReason.UNTRUSTED_REQUESTER, ConsentRiskReason.INTENT_TO_RETAIN),
            ),
            defaultClassifyRisk(webRequester, null, retainedAgePortraitData)
        )
        assertEquals(
            ConsentRiskAssessment(
                ConsentRiskLevel.HIGH_RISK,
                setOf(ConsentRiskReason.UNTRUSTED_REQUESTER, ConsentRiskReason.INTENT_TO_RETAIN),
            ),
            defaultClassifyRisk(appRequester, null, retainedAgePortraitData)
        )

        val retainedPiiReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "given_name" to true,
                        "family_name" to true,
                    )
                )
            )
        }
        val retainedPiiData = retainedPiiReq.execute(presentmentSource = env.presentmentSource)
        assertEquals(
            ConsentRiskAssessment(
                ConsentRiskLevel.HIGH_RISK,
                setOf(
                    ConsentRiskReason.UNTRUSTED_REQUESTER,
                    ConsentRiskReason.PII_REQUESTED,
                    ConsentRiskReason.INTENT_TO_RETAIN,
                ),
            ),
            defaultClassifyRisk(proximityRequester, null, retainedPiiData)
        )

        // 4. Unclassified purpose (e.g. eye_colour requested on mDL) -> HIGH_RISK (UNUSUAL_REQUEST) even when trusted,
        // unless a custom purpose set is provided
        val unusualReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") }
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "given_name" to false,
                        "eye_colour" to false,
                    )
                )
            )
        }
        val unusualData = unusualReq.execute(presentmentSource = env.presentmentSource)
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.HIGH_RISK, setOf(ConsentRiskReason.UNUSUAL_REQUEST)),
            defaultClassifyRisk(proximityRequester, env.trustedIdentity, unusualData)
        )
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(
                requester = proximityRequester,
                trustedRequesterIdentity = env.trustedIdentity,
                presentmentData = unusualData,
                purposes = setOf(PresentmentPurpose.IDENTITY_VERIFICATION),
            )
        )

        // 5. Untrusted encryption target -> HIGH_RISK (UNTRUSTED_ENCRYPTION_TARGET) even when requester is trusted
        val match0 = agePortraitData.useCases[0].solutions[0].matches[0]
        val untrustedEncData = PresentmentData(
            useCases = listOf(
                agePortraitData.useCases[0].copy(
                    solutions = listOf(
                        PresentmentUseCaseSolution(
                            matches = listOf(
                                match0.copy(
                                    encryptionRequested = true,
                                    encryptionTargetTrustMetadata = null,
                                )
                            )
                        )
                    )
                )
            )
        )
        assertEquals(
            ConsentRiskAssessment(
                ConsentRiskLevel.HIGH_RISK,
                setOf(ConsentRiskReason.UNTRUSTED_ENCRYPTION_TARGET),
            ),
            defaultClassifyRisk(proximityRequester, env.trustedIdentity, untrustedEncData)
        )

        // Trusted encryption target + trusted requester -> NORMAL
        val trustedEncData = PresentmentData(
            useCases = listOf(
                agePortraitData.useCases[0].copy(
                    solutions = listOf(
                        PresentmentUseCaseSolution(
                            matches = listOf(
                                match0.copy(
                                    encryptionRequested = true,
                                    encryptionTargetTrustMetadata = env.trustedIdentity.trustMetadata,
                                )
                            )
                        )
                    )
                )
            )
        )
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(proximityRequester, env.trustedIdentity, trustedEncData)
        )

        // 6. Optional use-case omitted (selections = listOf(-1)):
        // Trusted or Proximity -> NORMAL; Untrusted Web/App -> WARNING (UNTRUSTED_REQUESTER)
        val optionalReq = buildDeviceRequest(
            sessionTranscript = buildCborArray { add("session"); add("transcript") },
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = false,
                        documentSets = listOf(DocumentSet(listOf(0))),
                        purposeHints = emptyMap()
                    )
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "given_name" to true,
                    )
                )
            )
        }
        val optionalData = optionalReq.execute(presentmentSource = env.presentmentSource)
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(proximityRequester, env.trustedIdentity, optionalData, selections = listOf(-1))
        )
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.NORMAL, emptySet()),
            defaultClassifyRisk(proximityRequester, null, optionalData, selections = listOf(-1))
        )
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.WARNING, setOf(ConsentRiskReason.UNTRUSTED_REQUESTER)),
            defaultClassifyRisk(webRequester, null, optionalData, selections = listOf(-1))
        )
        assertEquals(
            ConsentRiskAssessment(ConsentRiskLevel.WARNING, setOf(ConsentRiskReason.UNTRUSTED_REQUESTER)),
            defaultClassifyRisk(appRequester, null, optionalData, selections = listOf(-1))
        )
    }
}
