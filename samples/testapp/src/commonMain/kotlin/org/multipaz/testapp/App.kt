package org.multipaz.testapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.ktor.client.HttpClient
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.network.ktor2.KtorNetworkFetcherFactory
import org.multipaz.testapp.ui.AppTheme
import org.multipaz.testapp.ui.BarcodeScanningScreen
import org.multipaz.testapp.ui.CameraScreen
import org.multipaz.testapp.ui.FaceDetectionScreen
import org.multipaz.testapp.ui.SelfieCheckScreen
import org.multipaz.models.digitalcredentials.DigitalCredentials
import org.multipaz.models.presentment.PresentmentModel
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.documenttype.knowntypes.UtopiaMovieTicket
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.secure_area_test_app.ui.CloudSecureAreaScreen
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.cloud.CloudSecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.testapp.ui.AboutScreen
import org.multipaz.testapp.ui.AndroidKeystoreSecureAreaScreen
import org.multipaz.testapp.ui.CertificateScreen
import org.multipaz.testapp.ui.CertificateViewerExamplesScreen
import org.multipaz.testapp.ui.ConsentPromptScreen
import org.multipaz.testapp.ui.CredentialClaimsViewerScreen
import org.multipaz.testapp.ui.CredentialViewerScreen
import org.multipaz.testapp.ui.DocumentStoreScreen
import org.multipaz.testapp.ui.DocumentViewerScreen
import org.multipaz.testapp.ui.IsoMdocMultiDeviceTestingScreen
import org.multipaz.testapp.ui.IsoMdocProximityReadingScreen
import org.multipaz.testapp.ui.IsoMdocProximitySharingScreen
import org.multipaz.testapp.ui.NfcScreen
import org.multipaz.testapp.ui.NotificationsScreen
import org.multipaz.testapp.ui.PassphraseEntryFieldScreen
import org.multipaz.testapp.ui.PassphrasePromptScreen
import org.multipaz.testapp.ui.ProvisioningTestScreen
import org.multipaz.testapp.ui.QrCodesScreen
import org.multipaz.testapp.ui.RichTextScreen
import org.multipaz.testapp.ui.ScreenLockScreen
import org.multipaz.testapp.ui.SecureEnclaveSecureAreaScreen
import org.multipaz.testapp.ui.SettingsScreen
import org.multipaz.testapp.ui.SoftwareSecureAreaScreen
import org.multipaz.testapp.ui.StartScreen
import org.multipaz.util.Logger
import multipazproject.samples.testapp.generated.resources.back_button
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import multipazproject.samples.testapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.multipaz.asn1.OID
import org.multipaz.cbor.DataItem
import org.multipaz.certext.MultipazExtension
import org.multipaz.certext.fromCbor
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.document.AbstractDocumentMetadata
import org.multipaz.document.DocumentMetadata
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.knowntypes.IDPass
import org.multipaz.facematch.FaceMatchLiteRtModel
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.mdoc.zkp.longfellow.LongfellowZkSystem
import org.multipaz.models.presentment.PresentmentSource
import org.multipaz.models.presentment.SimplePresentmentSource
import org.multipaz.models.provisioning.ProvisioningModel
import org.multipaz.prompt.PromptModel
import org.multipaz.provisioning.Display
import org.multipaz.request.Requester
import org.multipaz.storage.base.BaseStorageTable
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testapp.provisioning.ProvisioningSupport
import org.multipaz.testapp.ui.DcRequestScreen
import org.multipaz.util.Platform
import org.multipaz.testapp.ui.FaceMatchScreen
import org.multipaz.testapp.ui.ShowResponseScreen
import org.multipaz.testapp.ui.TrustManagerScreen
import org.multipaz.testapp.ui.TrustPointViewerScreen
import org.multipaz.trustmanagement.CompositeTrustManager
import org.multipaz.util.fromHex
import org.multipaz.trustmanagement.TrustManagerLocal
import org.multipaz.trustmanagement.TrustPointAlreadyExistsException
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.trustmanagement.VicalTrustManager
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex

/**
 * Application singleton.
 */
class App private constructor (val promptModel: PromptModel) {

    private val MULTIPAZ_IDENTITY_READER_CERT_UNTRUSTED_DEVICES = X509Cert(
        "308202893082020FA003020102021041DFFB3D7133B2623E535E09D9C3B56E300A06082A8648CE3D0403033047310B300906035504060C0255533138303606035504030C2F4D756C746970617A204964656E74697479205265616465722043412028556E74727573746564204465766963657329301E170D3235303731393233303831345A170D3330303731393233303831345A3047310B300906035504060C0255533138303606035504030C2F4D756C746970617A204964656E74697479205265616465722043412028556E747275737465642044657669636573293076301006072A8648CE3D020106052B8104002203620004EA8A139ED395B79C877255FEF2138987262CFBB6CA1F72688D4E89F062C3CA05B2704531DAEC0304F93A007CD84F31A119F3794151306082C4D4352855A752F9C733D2FA32B4B462644769F2F7E53280F1AD519C443AE9462B923C64877EDF91A381BF3081BC300E0603551D0F0101FF04040302010630120603551D130101FF040830060101FF02010030560603551D1F044F304D304BA049A047864568747470733A2F2F6769746875622E636F6D2F6F70656E77616C6C65742D666F756E646174696F6E2D6C6162732F6964656E746974792D63726564656E7469616C2F63726C301D0603551D0E041604149BCFDAFD2059978E21869C7DD28AAF7481EBABC5301F0603551D230418301680149BCFDAFD2059978E21869C7DD28AAF7481EBABC5300A06082A8648CE3D0403030368003065023100A26AA37C97B6935EB64B959ACB7B04053723EFE0CFBDA2C972C96812C8FF1DA4E122C296A909502B180DBB5AC4FD7AF202307F1AAE9412B8162A5B29A7E2A9CEE00059A2A4F9B32370CE1A28E28E5378AD981FBD8D74D0DBDD0373C327595C1006CE".fromHex()
    )

    private val MULTIPAZ_IDENTITY_READER_CERT_UNTRUSTED_DEVICES_PUBLIC_KEY by lazy {
        MULTIPAZ_IDENTITY_READER_CERT_UNTRUSTED_DEVICES.ecPublicKey
    }

    private val MULTIPAZ_IDENTITY_READER_CERT = X509Cert(
        encodedCertificate = "30820261308201E7A00302010202103925792727AC38B28778373ED2A9ADB9300A06082A8648CE3D0403033033310B300906035504060C0255533124302206035504030C1B4D756C746970617A204964656E7469747920526561646572204341301E170D3235303730353132323032315A170D3330303730353132323032315A3033310B300906035504060C0255533124302206035504030C1B4D756C746970617A204964656E74697479205265616465722043413076301006072A8648CE3D020106052B81040022036200043E145F98DA6C32EE4688C4A7DAEC6640046CFF0872E8F7A8DE3005462AE9488E92850B30E2D46FEEFC620A279BEB09470AB20C9F66C584E396A9625BC3E90DFBA54197A3668D901AAA41F493C89E4AC20689794FED1352CD2086413965006C54A381BF3081BC300E0603551D0F0101FF04040302010630120603551D130101FF040830060101FF02010030560603551D1F044F304D304BA049A047864568747470733A2F2F6769746875622E636F6D2F6F70656E77616C6C65742D666F756E646174696F6E2D6C6162732F6964656E746974792D63726564656E7469616C2F63726C301D0603551D0E04160414CFA4AF87907312962E4D7A17646ACC1C45719B21301F0603551D23041830168014CFA4AF87907312962E4D7A17646ACC1C45719B21300A06082A8648CE3D040303036800306502310090FB8F814BCC87DB42957D22B54D20BF45F44CE0CF5734167ED27F5E3E0F5FB57505B797B894175D2BD98BF16CE726EA02305BA4F1ECB894A9DBE27B9BBF988F233C2E0BB0B4BADAA3EC5B3EA9D99C58DAD26128A4B363849E32626A9D5C3CE3E4DA".fromHex()
    )

    private val MULTIPAZ_IDENTITY_READER_CERT_PUBLIC_KEY by lazy {
        MULTIPAZ_IDENTITY_READER_CERT.ecPublicKey
    }

    lateinit var settingsModel: TestAppSettingsModel

    lateinit var documentTypeRepository: DocumentTypeRepository

    lateinit var secureAreaRepository: SecureAreaRepository
    lateinit var softwareSecureArea: SoftwareSecureArea
    lateinit var documentStore: DocumentStore
    lateinit var documentModel: DocumentModel

    lateinit var iacaKey: EcPrivateKey
    lateinit var iacaCert: X509Cert

    lateinit var readerRootKey: EcPrivateKey
    lateinit var readerRootCert: X509Cert

    lateinit var readerKey: EcPrivateKey
    lateinit var readerCert: X509Cert

    lateinit var issuerTrustManager: CompositeTrustManager

    lateinit var readerTrustManager: CompositeTrustManager

    private lateinit var provisioningModel: ProvisioningModel

    private val credentialOffers = Channel<String>()

    private val provisioningSupport = ProvisioningSupport()

    lateinit var zkSystemRepository: ZkSystemRepository

    lateinit var faceMatchLiteRtModel: FaceMatchLiteRtModel

    private val initLock = Mutex()
    private var initialized = false

    fun getPresentmentSource(): PresentmentSource {
        val useAuth = settingsModel.presentmentRequireAuthentication.value
        return SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            readerTrustManager = readerTrustManager,
            zkSystemRepository = zkSystemRepository,
            skipConsentPrompt = !settingsModel.presentmentShowConsentPrompt.value,
            dynamicMetadataResolver = ::dynamicMetadataResolver,
            preferSignatureToKeyAgreement = settingsModel.presentmentPreferSignatureToKeyAgreement.value,
            domainMdocSignature = if (useAuth) {
                TestAppUtils.CREDENTIAL_DOMAIN_MDOC_USER_AUTH
            } else {
                TestAppUtils.CREDENTIAL_DOMAIN_MDOC_NO_USER_AUTH
            },
            domainMdocKeyAgreement = if (useAuth) {
                TestAppUtils.CREDENTIAL_DOMAIN_MDOC_MAC_USER_AUTH
            } else {
                TestAppUtils.CREDENTIAL_DOMAIN_MDOC_MAC_NO_USER_AUTH
            },
            domainKeylessSdJwt = TestAppUtils.CREDENTIAL_DOMAIN_SDJWT_KEYLESS,
            domainKeyBoundSdJwt = if (useAuth) {
                TestAppUtils.CREDENTIAL_DOMAIN_SDJWT_USER_AUTH
            } else {
                TestAppUtils.CREDENTIAL_DOMAIN_SDJWT_NO_USER_AUTH
            },
        )
    }

    fun dynamicMetadataResolver(requester: Requester): TrustMetadata? {
        if (requester.certChain != null &&
            requester.certChain!!.certificates.last().ecPublicKey == MULTIPAZ_IDENTITY_READER_CERT_PUBLIC_KEY
        ) {
            val readerCert = requester.certChain!!.certificates.first()
            readerCert.getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid)?.let { extData ->
                MultipazExtension.fromCbor(extData).googleAccount?.let { googleAccount ->
                    if (googleAccount.emailAddress != null && googleAccount.profilePictureUri != null) {
                        return TrustMetadata(
                            displayName = googleAccount.emailAddress,
                            displayIconUrl = googleAccount.profilePictureUri,
                            disclaimer = "The email and picture shown are from the requester's Google Account. " +
                                    "This information has been verified but may not be their real identity"
                        )
                    }
                }
            }
        }
        return null
    }

    suspend fun init() {
        initLock.withLock {
            if (initialized) {
                return
            }
            val initFuncs = listOf<Pair<suspend () -> Unit, String>>(
                Pair(::platformInit, "platformInit"),
                Pair(::settingsInit, "settingsInit"),
                Pair(::platformCryptoInit, "platformCryptoInit"),
                Pair(::documentTypeRepositoryInit, "documentTypeRepositoryInit"),
                Pair(::documentStoreInit, "documentStoreInit"),
                Pair(::documentModelInit, "documentModelInit"),
                Pair(::keyStorageInit, "keyStorageInit"),
                Pair(::iacaInit, "iacaInit"),
                Pair(::readerRootInit, "readerRootInit"),
                Pair(::readerInit, "readerInit"),
                Pair(::trustManagersInit, "trustManagersInit"),
                Pair(::provisioningModelInit, "provisioningModelInit"),
                Pair(::zkSystemRepositoryInit, "zkSystemRepositoryInit"),
                Pair(::faceMatchLiteRtModelInit, "faceMatchLiteRtModelInit")
            )

            val begin = Clock.System.now()
            for ((func, name) in initFuncs) {
                val funcBegin = Clock.System.now()
                func()
                val funcEnd = Clock.System.now()
                Logger.i(TAG, "$name initialization time: ${(funcEnd - funcBegin).inWholeMilliseconds} ms")
            }
            val end = Clock.System.now()
            Logger.i(TAG, "Total application initialization time: ${(end - begin).inWholeMilliseconds} ms")
            initialized = true
        }
    }

    private suspend fun platformCryptoInit() {
        platformCryptoInit(settingsModel)
    }

    private suspend fun settingsInit() {
        settingsModel = TestAppSettingsModel.create(Platform.storage)
    }

    private suspend fun documentTypeRepositoryInit() {
        documentTypeRepository = DocumentTypeRepository()
        documentTypeRepository.addDocumentType(DrivingLicense.getDocumentType())
        documentTypeRepository.addDocumentType(PhotoID.getDocumentType())
        documentTypeRepository.addDocumentType(EUPersonalID.getDocumentType())
        documentTypeRepository.addDocumentType(UtopiaMovieTicket.getDocumentType())
        documentTypeRepository.addDocumentType(IDPass.getDocumentType())
    }

    private suspend fun documentStoreInit() {
        softwareSecureArea = SoftwareSecureArea.create(Platform.storage)
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(softwareSecureArea)
            .add(Platform.getSecureArea())
            .addFactory(CloudSecureArea.IDENTIFIER_PREFIX) { identifier ->
                val queryString = identifier.substring(CloudSecureArea.IDENTIFIER_PREFIX.length + 1)
                val params = queryString.split("&").associate {
                    val parts = it.split("=", ignoreCase = false, limit = 2)
                    parts[0] to parts[1].decodeURLPart()
                }
                val cloudSecureAreaUrl = params["url"]!!
                Logger.i(TAG, "Creating CSA with url $cloudSecureAreaUrl for $identifier")
                CloudSecureArea.create(
                    Platform.nonBackedUpStorage,
                    identifier,
                    cloudSecureAreaUrl,
                    platformHttpClientEngineFactory()
                )
            }
            .build()
        documentStore = buildDocumentStore(
            storage = Platform.storage,
            secureAreaRepository = secureAreaRepository
        ) {
            //setTableSpec(testDocumentTableSpec)
        }
    }

    private suspend fun documentModelInit() {
        documentModel = DocumentModel(
            scope = CoroutineScope(Dispatchers.IO),
            documentStore = documentStore
        )
        documentModel.initialize()
    }

    private suspend fun trustManagersInit() {
        generateTrustManagers()
    }

    private suspend fun provisioningModelInit() {
        provisioningModel = ProvisioningModel(
            documentStore = documentStore,
            secureArea = Platform.getSecureArea(),
            httpClient = HttpClient(platformHttpClientEngineFactory()) {
                followRedirects = false
            },
            promptModel = promptModel,
            documentMetadataInitializer = App::initializeDocumentMetadata
        )
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun zkSystemRepositoryInit() {
        val circuitsToAdd = listOf(
            "files/longfellow-libzk-v1/3_1_bd3168ea0a9096b4f7b9b61d1c210dac1b7126a9ec40b8bc770d4d485efce4e9",
            "files/longfellow-libzk-v1/3_2_40b2b68088f1d4c93a42edf01330fed8cac471cdae2b192b198b4d4fc41c9083",
            "files/longfellow-libzk-v1/3_3_99a5da3739df68c87c7a380cc904bb275dbd4f1b916c3d297ba9d15ee86dd585",
            "files/longfellow-libzk-v1/3_4_5249dac202b61e03361a2857867297ee7b1d96a8a4c477d15a4560bde29f704f",
        )

        val longfellowSystem = LongfellowZkSystem()
        for (circuit in circuitsToAdd) {
            val circuitBytes = Res.readBytes(circuit)
            val pathParts = circuit.split("/")
            longfellowSystem.addCircuit(pathParts[pathParts.size - 1], ByteString(circuitBytes))
        }
        zkSystemRepository = ZkSystemRepository().apply {
            add(longfellowSystem)
        }
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun faceMatchLiteRtModelInit() {
        val modelData = ByteString(*Res.readBytes("files/facenet_512.tflite"))
        faceMatchLiteRtModel = FaceMatchLiteRtModel(modelData, imageSquareSize = 160, embeddingsArraySize = 512)
    }

    private val certsValidFrom = LocalDate.parse("2024-12-01").atStartOfDayIn(TimeZone.UTC)
    private val certsValidUntil = LocalDate.parse("2034-12-01").atStartOfDayIn(TimeZone.UTC)

    private val bundledIacaKey: EcPrivateKey by lazy {
        val iacaKeyPub = EcPublicKey.fromPem(
            """
                    -----BEGIN PUBLIC KEY-----
                    MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE+QDye70m2O0llPXMjVjxVZz3m5k6agT+
                    wih+L79b7jyqUl99sbeUnpxaLD+cmB3HK3twkA7fmVJSobBc+9CDhkh3mx6n+YoH
                    5RulaSWThWBfMyRjsfVODkosHLCDnbPV
                    -----END PUBLIC KEY-----
                """.trimIndent().trim(),
            EcCurve.P384
        )
        EcPrivateKey.fromPem(
            """
                    -----BEGIN PRIVATE KEY-----
                    MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDCcRuzXW3pW2h9W8pu5
                    /CSR6JSnfnZVATq+408WPoNC3LzXqJEQSMzPsI9U1q+wZ2yhZANiAAT5APJ7vSbY
                    7SWU9cyNWPFVnPebmTpqBP7CKH4vv1vuPKpSX32xt5SenFosP5yYHccre3CQDt+Z
                    UlKhsFz70IOGSHebHqf5igflG6VpJZOFYF8zJGOx9U4OSiwcsIOds9U=
                    -----END PRIVATE KEY-----
                """.trimIndent().trim(),
            iacaKeyPub
        )
    }

    val bundledIacaCert: X509Cert by lazy {
        MdocUtil.generateIacaCertificate(
            iacaKey = iacaKey,
            subject = X500Name.fromName("C=US,CN=OWF Multipaz TEST IACA"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = certsValidFrom,
            validUntil = certsValidUntil,
            issuerAltNameUrl = "https://github.com/openwallet-foundation-labs/identity-credential",
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )
    }

    private val bundledReaderRootKey: EcPrivateKey by lazy {
        val readerRootKeyPub = EcPublicKey.fromPem(
            """
                    -----BEGIN PUBLIC KEY-----
                    MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE+QDye70m2O0llPXMjVjxVZz3m5k6agT+
                    wih+L79b7jyqUl99sbeUnpxaLD+cmB3HK3twkA7fmVJSobBc+9CDhkh3mx6n+YoH
                    5RulaSWThWBfMyRjsfVODkosHLCDnbPV
                    -----END PUBLIC KEY-----
                """.trimIndent().trim(),
            EcCurve.P384
        )
        EcPrivateKey.fromPem(
            """
                    -----BEGIN PRIVATE KEY-----
                    MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDCcRuzXW3pW2h9W8pu5
                    /CSR6JSnfnZVATq+408WPoNC3LzXqJEQSMzPsI9U1q+wZ2yhZANiAAT5APJ7vSbY
                    7SWU9cyNWPFVnPebmTpqBP7CKH4vv1vuPKpSX32xt5SenFosP5yYHccre3CQDt+Z
                    UlKhsFz70IOGSHebHqf5igflG6VpJZOFYF8zJGOx9U4OSiwcsIOds9U=
                    -----END PRIVATE KEY-----
                """.trimIndent().trim(),
            readerRootKeyPub
        )
    }

    private val bundledReaderRootCert: X509Cert by lazy {
        MdocUtil.generateReaderRootCertificate(
            readerRootKey = bundledReaderRootKey,
            subject = X500Name.fromName("CN=OWF Multipaz TestApp Reader Root"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            validFrom = certsValidFrom,
            validUntil = certsValidUntil,
            crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
        )
    }

    private lateinit var keyStorage: StorageTable

    private suspend fun keyStorageInit() {
        keyStorage = Platform.storage.getTable(
            StorageTableSpec(
                name = "TestAppKeys",
                supportPartitions = false,
                supportExpiration = false
            )
        )
    }

    private suspend fun iacaInit() {
        iacaKey = keyStorage.get("iacaKey")?.let { EcPrivateKey.fromDataItem(Cbor.decode(it.toByteArray())) }
            ?: run {
                keyStorage.insert("iacaKey", ByteString(Cbor.encode(bundledIacaKey.toDataItem())))
                bundledIacaKey
            }
        iacaCert = keyStorage.get("iacaCert")?.let { X509Cert.fromDataItem(Cbor.decode(it.toByteArray())) }
            ?: run {
                keyStorage.insert("iacaCert", ByteString(Cbor.encode(bundledIacaCert.toDataItem())))
                bundledIacaCert
            }
    }

    private suspend fun readerRootInit() {
        readerRootKey = keyStorage.get("readerRootKey")?.let { EcPrivateKey.fromDataItem(Cbor.decode(it.toByteArray())) }
            ?: run {
                keyStorage.insert("readerRootKey", ByteString(Cbor.encode(bundledReaderRootKey.toDataItem())))
                bundledReaderRootKey
            }
        readerRootCert = keyStorage.get("readerRootCert")?.let { X509Cert.fromDataItem(Cbor.decode(it.toByteArray())) }
            ?: run {
                keyStorage.insert("readerRootCert", ByteString(Cbor.encode(bundledReaderRootCert.toDataItem())))
                bundledReaderRootCert
            }
        println("readerRootCert: ${readerRootCert.toPem()}")
    }

    private suspend fun readerInit() {
        readerKey = keyStorage.get("readerKey")?.let { EcPrivateKey.fromDataItem(Cbor.decode(it.toByteArray())) }
            ?: run {
                val key = Crypto.createEcPrivateKey(EcCurve.P256)
                keyStorage.insert("readerKey", ByteString(Cbor.encode(key.toDataItem())))
                key
            }
        readerCert = keyStorage.get("readerCert")?.let { X509Cert.fromDataItem(Cbor.decode(it.toByteArray())) }
            ?: run {
                val cert = MdocUtil.generateReaderCertificate(
                    readerRootCert = readerRootCert,
                    readerRootKey = readerRootKey,
                    readerKey = readerKey.publicKey,
                    subject = X500Name.fromName("CN=OWF Multipaz TestApp Reader Cert"),
                    serial = ASN1Integer.fromRandom(numBits = 128),
                    validFrom = certsValidFrom,
                    validUntil = certsValidUntil,
                )
                keyStorage.insert("readerCert", ByteString(Cbor.encode(cert.toDataItem())))
                cert
            }
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun generateTrustManagers() {
        val builtInIssuerTrustManager = TrustManagerLocal(
            storage = EphemeralStorage(),
            partitionId = "BuiltInTrustedIssuers",
            identifier = "Built-in Trusted Issuers"
        )
        builtInIssuerTrustManager.addX509Cert(
            certificate = iacaCert,
            metadata = TrustMetadata(displayName = "OWF Multipaz TestApp Issuer"),
        )
        val signedVical = SignedVical.parse(Res.readBytes("files/20250225 RDW Test Vical.vical"))
        // TODO: validate the Vical is signed by someone we trust, probably force this
        //   by having the caller pass in the public key
        val vicalTrustManager = VicalTrustManager(signedVical)
        issuerTrustManager = CompositeTrustManager(listOf(vicalTrustManager, builtInIssuerTrustManager))


        val readerCertFileNames = listOf(
            "Animo Reader CA.cer",
            "Bundesdruckerei Reader CA.cer",
            "CLR Labs Reader CA.cer",
            "Credence ID Reader CA.cer",
            "Fast Enterprises Reader CA.cer",
            "Fime Reader CA 1.cer",
            "Fime Reader CA 2.cer",
            "Google Reader CA.cer",
            "Idakto Reader CA.cer",
            "Idemia Reader CA.cer",
            "LapID Reader CA.cer",
            "MATTR Reader CA.cer",
            "Nearform Reader CA.cer",
            "OGCIO Reader CA.cer",
            "RDW Test Reader CA.cer",
            "Scytales Reader CA.cer",
            "SpruceID Reader CA.cer",
            "Thales Reader CA 1.cer",
            "Thales Reader CA 2.cer",
            "Thales Root CA.cer",
            "Toppan Reader CA.cer",
            "Zetes Reader CA.cer"
        )
        val builtInReaderTrustManager = TrustManagerLocal(
            storage = EphemeralStorage(),
            partitionId = "BuiltInTrustedReaders",
            identifier = "Built-in Trusted Readers"
        )
        readerTrustManager = CompositeTrustManager(listOf(builtInReaderTrustManager))
        if (builtInReaderTrustManager.getTrustPoints().isEmpty()) {
            try {
                builtInReaderTrustManager.addX509Cert(
                    certificate = readerRootCert,
                    metadata = TrustMetadata(
                        displayName = "Multipaz TestApp",
                        displayIcon = ByteString(Res.readBytes("files/utopia-brewery.png")),
                        privacyPolicyUrl = "https://apps.multipaz.org"
                    )
                )
            } catch (e: TrustPointAlreadyExistsException) {
                // Do nothing, it's possible our certificate is in the list above.
            }
            for (readerCertFileName in readerCertFileNames) {
                val certData = Res.readBytes("files/20250225 Reader CA Certificates/" + readerCertFileName)
                val readerCert = X509Cert.fromPem(certData.decodeToString())
                try {
                    builtInReaderTrustManager.addX509Cert(
                        certificate = readerCert,
                        metadata = TrustMetadata(
                            displayName = readerCertFileName.substringBeforeLast(".")
                        ),
                    )
                } catch (e: TrustPointAlreadyExistsException) {
                    val existingTrustPoint = builtInIssuerTrustManager.getTrustPoints().first {
                        it.certificate.subjectKeyIdentifier!!.toHex() == readerCert.subjectKeyIdentifier!!.toHex()
                    }
                    Logger.w(TAG, "builtInReaderTrustManager: Error adding certificate with subject " +
                            "${readerCert.subject.name} - already contains a certificate with " +
                            "subject ${existingTrustPoint.certificate.subject.name} with the same " +
                            "Subject Key Identifier", e)
                }
            }
            // This is for https://verifier.multipaz.org website.
            try {
                builtInReaderTrustManager.addX509Cert(
                    certificate = X509Cert(
                        "30820269308201efa0030201020210b7352f14308a2d40564006785270b0e7300a06082a8648ce3d0403033037310b300906035504060c0255533128302606035504030c1f76657269666965722e6d756c746970617a2e6f726720526561646572204341301e170d3235303631393232313633325a170d3330303631393232313633325a3037310b300906035504060c0255533128302606035504030c1f76657269666965722e6d756c746970617a2e6f7267205265616465722043413076301006072a8648ce3d020106052b81040022036200046baa02cc2f2b7c77f054e9907fcdd6c87110144f07acb2be371b2e7c90eb48580c5e3851bcfb777c88e533244069ff78636e54c7db5783edbc133cc1ff11bbabc3ff150f67392264c38710255743fee7cde7df6e55d7e9d5445d1bde559dcba8a381bf3081bc300e0603551d0f0101ff04040302010630120603551d130101ff040830060101ff02010030560603551d1f044f304d304ba049a047864568747470733a2f2f6769746875622e636f6d2f6f70656e77616c6c65742d666f756e646174696f6e2d6c6162732f6964656e746974792d63726564656e7469616c2f63726c301d0603551d0e04160414b18439852f4a6eeabfea62adbc51d081f7488729301f0603551d23041830168014b18439852f4a6eeabfea62adbc51d081f7488729300a06082a8648ce3d040303036800306502302a1f3bb0afdc31bcee73d3c5bf289245e76bd91a0fd1fb852b45fc75d3a98ba84430e6a91cbfc6b3f401c91382a43a64023100db22d2243644bb5188f2e0a102c0c167024fb6fe4a1d48ead55a6893af52367fb3cdbd66369aa689ecbeb5c84f063666".fromHex()
                    ),
                    metadata = TrustMetadata(
                        displayName = "Multipaz Verifier",
                        displayIcon = ByteString(Res.readBytes("drawable/app_icon.webp")),
                        privacyPolicyUrl = "https://apps.multipaz.org"
                    )
                )
            } catch (e: TrustPointAlreadyExistsException) {
                // Do nothing, it's possible our certificate is in the list above.
            }
            // This is for Multipaz Identity Reader app from https://apps.multipaz.org on devices in
            // the GREEN boot state.
            try {
                builtInReaderTrustManager.addX509Cert(
                    certificate = MULTIPAZ_IDENTITY_READER_CERT,
                    metadata = TrustMetadata(
                        displayName = "Multipaz Identity Reader",
                        displayIcon = ByteString(Res.readBytes("drawable/mpz_identity_reader.webp")),
                        privacyPolicyUrl = "https://apps.multipaz.org"
                    )
                )
            } catch (e: TrustPointAlreadyExistsException) {
                // Do nothing, it's possible our certificate is in the list above.
            }
            // This is for Multipaz Identity Reader either compiled locally or the APK from https://apps.multipaz.org
            // but running on a device that isn't in the GREEN boot state.
            try {
                builtInReaderTrustManager.addX509Cert(
                    certificate = MULTIPAZ_IDENTITY_READER_CERT_UNTRUSTED_DEVICES,
                    metadata = TrustMetadata(
                        displayName = "Multipaz Identity Reader (Untrusted Devices)",
                        displayIcon = ByteString(Res.readBytes("drawable/mpz_identity_reader.webp")),
                        privacyPolicyUrl = "https://apps.multipaz.org"
                    )
                )
            } catch (e: TrustPointAlreadyExistsException) {
                // Do nothing, it's possible our certificate is in the list above.
            }
            // Some reader identities from the Multipaz Identity Reader as distributed from apps.multipaz.org
            for ((displayName: String, displayIcon: ByteString?, cert: X509Cert) in listOf(
                Triple(
                    "Utopia Brewing Company",
                    "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAAXNSR0IB2cksfwAAAARnQU1BAACxjwv8YQUAAAAgY0hSTQAAeiYAAICEAAD6AAAAgOgAAHUwAADqYAAAOpgAABdwnLpRPAAAAAZiS0dEAAAAAAAA-UO7fwAAAAlwSFlzAAAuIwAALiMBeKU_dgAAAAd0SU1FB-kHGBUYJH7nXpkAABOZSURBVHjavZt5dJTlvcc_zzv7mmXIhCQYIAiIIiKyt1KLdcPtKi703rb2VL22t0W62HrusafLbe05VtvqvfZaq1dblyouLRY3RLEKYiAsIiIkAULIMkkmmSwzmf19n_vHvDOTSSbJJGDfnJyTycw87_P8nt_v-_v-vr_nFQCB9vrlwL1CiM8BFikln8UlhADgdIwvBEiZGnOC48WAD4B7Sivn1gp98dsAG5_hlV58XgMICVJMeLxTNGQEWG0E7k0v_jQMOuo15rgTXPxp8iIbcK8ItNdHActnbYAc12XsHR8-j4mHjgQKMmpMSS9-6M2GuuupuHr-nWPcxY_2uuB5FT59i_JPdfUJGnLozqfHzbdBOa_FxO6vfJaLOB3GlFKOaoi885X6b4GXcfgNT8X9T9ei03MYLfbH3iAxUQ8YCTajGSHtfqdqpBELmmRoTOS9MQwgxrVseuC0C55qiKTHUrU44cE-QEtlhlGMlM_wk92E4d9TRtud4Tc9nbgg0RgMdvPuU7_lrYfupLXxYDY9TiAUT4cnTBgETy0EJFKoNB2q482H1lNUVsH8K77Brqd_Qu3mZ0hEQ0ihjQC8UUMnD1cYbw3D_y8C7UdkOgwKJULZz8nCQUdI4tEQ-976K76P3-T867_PjHOWANDX3cqujY-gJuIsX7eeUu80pBQIJcUZhBB6epMglbyAmfXckURrrHWJgO-IzOKnUhjSiiHINS6PlyAkXScbqd34IMVVs1lyzS1YnSUIqUOQkGjJOJ_s2EL9lj8wb816zl5xMUIYdY9TQGipG0sxIlRzd1ibUDYQgfZ6mRpDFB4KYgh2D1-8yE3EWjLOoZ1v0_DOY5yz5jvMXfJFFMWIRNPfT6AYTSnjKxJfUz27Nz6Es_xMllz7VZzFUzKG0v20INcv2JsD7fVyeM49tZpAZow0EOig9sVHkckIy2--k-KyaSkvRgUkxz7eReP2V7n4tnswWeyp-wLxSIg9bzxP6_4tLLn5bmaesygVEoiCwTA7_7HD1Hi6mODQGASVhn07-eiV_-bMC9ex4AtrMJrtWdwQ0N1-nIYdr9HfspvGj3cy-7yVmCx2AMw2Jyuu-wbNc85jz0sP0Hn0Cs6_5DrMNhcgdUMUCsRiHA_w1Q9JwQWAmtB0t8_nepJ4tJ-6116g5_huFq_dwLTZ5wKQTMSRUsNoshAd7Gfzr2_Hs2QtdqcD3773mLviMs5eeQlSShLRCGabEwSEAp3sfOkx4v2dLL1pA2XTpgPKBI0wRhrMulUKrBDaiIJiaOpLAeXIGwsFfE2f8uZDdyFkgsvX_45pZy7I3sho1EFMxdd0CLN3PuZ4P_7aTXjOWkZP21FCvX5A6p_VQJO4SrxccuuPqFlxJe8-sp4D725GUxM6Z5CTKoDygKDIEJRcYioQKCNAJ_1aIhFIEokIH23bTOvul1lwzQZmnbcCIYwZjwn1d2N3lSCEQmdzAztefpSqJVfQ_NZjWOnHOP8mTEYDDjXEypv-A0UoBPt7cBVNycxHAt1tTdS99AhGm5vF191KsWeq7g2cmgFGoKaO5OndzsWE3DDp87dQ-8LvUUxmVtz4LdyeCn3Oeq5Mpy89TPa-83cSMknH7s1UrriBzpZmXIQwFk3F39HJtbd_F8Vgzs5HSwGLEKk9T8Si7NvyAq11mzj_hv9kxvwLECgFk7Ph6VPJWwRJoRMOkVdpSXlKksO1W3nn4fWcsXAVX7r1Hlyl5dm0KDQQEOzrydy0P9CJarbSuucfEG6j23cSzxkz8TfspmP3M5TOPJOWxkOZiQ70-pEZApRSU0xmC0uv_gpLv_IzPtr0P2zf-CjxWFA39MSLqdwQyIP-ue-l3o8M9lG36U-Euo6y-Ib1eKbVEOzpRqINM5mguKwi86p-9zYOb32CypU30XbiJMWmBKGWjyhaeDXxSAQZOI4CXHz7T3OMH49FGezvQU2GMRjMlEydAVIyOBBg96anCLY3sPzffkhZVc3Y4ZCHtCljFjtDSE3GckLS03aCsP8Yl3z7Psqq5zLQ7cM71UZllZvKSjcVVW4qqlxYTIPEoyEdJAXBbh9EuvHteI7KGTPoOlKLGvYRDgWRgROEW2oZ8DUi1WRmCp3NB4mFTlBeYaNquoeSMgudzXuRUsPhLuWir66n9MwLaPukroDCeqRaYhw75-sZUg4ri5EYnSWYranc7vf38uHT_4WUgotuvYvtzz1BMtpDNDzIhbf9mvKpTno7WkiqcaZ9_su0732L9vefpnr1vyIUhfCJw_SfrKNy8fWYbE6aDu1l1oLlIMBgNuJwO-np6sDudGGyWojGIqhqEqPRjEDBXjwFLREdn_tn6ogsjhnHVVXlyFyPGMrKBOFQGDXoQyAJ-HzEOz5m2oVf5tjuLUSjCYQQqKpGR91GqlfdQtWyK4lFkxjVCMEOH-ayCmZM_yqK0cyJrY9gX_PDtN-BNOJrbqenw4cmJUJAdyCE23MWNrsTq9WiA6QcUSSN3n8QYzPB7OKVvLaRMpf2CqMh5VxCEEwYCEVVmnduAk0vawGzzYKKCUVROL7t_5h75fdo3PIoqFFmXHwHTR9sYvrSy0hIK1anE6SWqdbr64_je__PGAwGkppKQpUMJm0c_vgg6390V5p-FsAJlRHCS8GqcFYgUbK1kD6W1e5AlRKTu5r9rz2Pzarg9NaQjEewWMwgVY7v3cGsL34FYTLhnvF5uo5_ipaMAJIT7z1L9ZJLifQHmHPFLTQd2AGaCkAorNL2yX5UTZJUJaqmYTBYuezqq1mweDGJRELfMKXgFDgpA0i0LCiK3BTpcDnRVEki2o8zdhwDEk1ViSclRUUlgIK1yMvRd__CoL-DWStX4z_yDzA58Z57KSI5gM3txu7x0vDm08ikBkYTAIkk4P8o65QyxViFQPeSVGIWaHnCdXz5TMmJDzE-isp0Th5yFbndhGMqQhE6BxIkgj6Swo3D6SAaCRHsaqZm1U0YrFbMdjsIC1IaMLtK0DSJ1V1CIhFjxqobcZR66Dx5VK_ttSGZSyKkXgxJ8Hf6URSdYgtl1NJgLIlNGdqfkzK10ymio2X-TlPkDAWWuZTZbLUQSQoSoQAm93QwlZCI9OIsn4UQAovNQaC5nt4TDQhM1D19P3O-tA6DfQpgoPqiW2h8_00G29tIhAdp3v0yruJSXQgZQlw0kFKgJeM8fP_9LFm5AqfTSV7Az_ykBFdNZv-TvxwWkmQ8RkdTw3B9FoFACrA6nHirZjJ15hw8lRuQusnD4Qg1S9bgsBkwWW0kYlE0NUlfWEPVUsaavepaPn7-xwjFQMXC1Rzb-hRzr7gdT1UFBzdvxOQoxeGtpP3DZ6m-YB12V4leRSaxGI0ppJdgwsRgLMkdGzbgsKfK47lLv4CiG6qvp5OBHn-GPcoc6i4pKa_CXeTJYwApMBpNlJ8xPY8tU6nDYDSABLPFjtlizxhH0zSSwkl_REIEJBYkFjAZScTjmMxmpp-9iJPzrqL38GY0bTnTll1Hy8E9KCzDbHfjnHoGrR88g7GohvMuWau7tSAYDLHwa79CSjUzk3A4QmhgAIfNBQjsjqKMSuVwu7HZbEMMMLQpCUarPSctZoqh8Vu6clTZS6RjEC03lQqJ1LKfSySi1Ndtp6t-D1Nmn8vsRRdisjhoP3qAxu2v466oYd7nL8dVPCUzejwWIxaLjchIDqczJZUxFLuymuGY5xGGendBBshDE8KDIQYG-rHb7bjdxSAEgR4_8Xg8x-KKwUhZuZd4PEFz03E0CRWV5bjdboRiwNfaBkKgCFBMJkpLPRgUhU6fD4Ap3jIMigGEoNvfhVAMeDyeLKeXMlswC3SjjK8GTzgNDidDg4MhfnH7tbS3taXlTxobGmk92cz777zNY7-5j2gsyvb33qWtpYWH7_sVFouZ4mI3zz_5Z_bu3gtobNn8Cs_84fdEY1H21u7ioV_8F4ODQba98TrPP_6HIYuR_P2FjfzktpsJDvTr3qYhgYa696j_8C2kLKw3MLoBhFZQp04IidVqJSnB7rDr4SooKnIx-6yzKK-qxOZyMaNmFldeew2_v_fnrFl7A9Nn1lBRUcnNX_86T_7ye_ja2ynxTKHU62X6zDO57Kqr6Gg6zIF9-ykqKcZdXIKiGFKldF8_8xctoqx6Lgf27c_ZjUion0gomCXnE2jcGHPjWoyp86cGzvRRMCpiCCUUOiWVekimxjrZ3Ex_60HKvN4sbyh2U1I1h8ZDR1AURT80oYGUaKqGQckSLSFT27Svro6Vq1ahaZJX_vw4S1csw2yyZmu8PDJeIQq3klsppoWQ_I1JKbWcllWqOFFywCHN_YUOwYpiQKQnkO73I1BViUHXCcP9vRxtaGTTCy9y5sKlnHf-ogzmSpECwrod77P19S243C7UeISGI_VDdEEJBbTC8nmGklc-FjLj1jmF8RC0NZlNoNgIh6MZo4RCIcwmU85w06qrKaleSIevIyuj9fbR19XC3LPPQmoaisGExWph1cUX8a27foTN7hjSeJEcPnyYr33zDlZfupq2lhaMBoW3N2_WNyU9ZTmpPmZ-EBzWf5NS5rbCkFitNq7_5t28-MRjHDr4CXV1ewgPDuJ0F-H3dxMMdBONRrGYLXzzxz_l9Ree48inn3KyuZnnnvwTd_7qITxeL_6uLsLhMFMrKvB4ynRU1wh0Bwj4_fT29rDznbcp807Fbndy2ZqruOPHv-Ro7d_Zu7suHXXZ8jnPCZOxQLHgNJiO_7TbDQb7sDld9Pi7GegfwOlyUV5RmUqH3X60ZJzi0lKMRnNKzIxH8bW1o6oqU6sqsdsdSAk9_i40qeKZ4k2lO_3q9vvRNBWH00lkcJApXm-qnBUQjUQIBQcwmU0UFZewb-vfEGqChZeuG1USG617VHg1KFNUVKAQDvbxxoN38d7zj-NyOpg1ezblU3U1WGqUekpJhPsI9nYDKu3HDjE40EtlVQVWo0SqSQKd7fT3dGC3GXE6bPT7fbQ3HQZUOk42IpNhvOXlxIK9mA2aLsCnYt1qtTKlzIvb7ebQzm0ce-8p3FOrJ3TuKO0NE-YBUkrsrhIu33AfajLBq7_9Aa3HjugAqWUwqbezha7WoyiKQk_rUfo6WtFUlV5_OyaTAdDwHTvAsY92YrHa6PO3Y7akCE-g9SjdrU0Ig4H2piMIZeQ0w8E-tj39MA3b_8qq2-9n1nkrxxREpRxZCAEY7v7Bd36WSYEToIJmi4MZ5y5GM9moffbnJBUX3ukz9bwtGegLYHE4cZWUoakaUkgcxVOIhAawO4twl5QRi0Sw2JyUlJ9BeDBIUk1SVOIlHo9jNJsp9pSjauAq9mDU9QEh4MThj3j_iZ_hmTGPC9dtoNhTOT4AZjiOGOV8gJxkf0VIAh0n2bnxf0ExsPzGO_CUn5GZbLrjK4bUJKnSQsvSdylySIzUP5jtU6SmHouG2PvWJjoOvMmi6zfoByyUvClwZC2g8xeZzwDyFE99CUkiHmbf1ldo2v4si9bezewLViCEYYhcJzI1lZQqyUQMg8FAsC9AkcdLMpHAaLLknMBIExkhoKutmR3P_g6Hp5LP3XAbdveUUduCYx6XGUb0CswChYWIFBodTfV8-OwDuKrOZsX1t-AoKqX9-BHUZAKJxrSas1GMCtv_-ieqZs0h2NODJiQ185dR5K2io6meRCIOUmPq9NkYTWYOvPcG9VsfY-G132XO4lUIxTTmbLK9zpx6eNgadK5TWDksh_T7xhfNwqFe9rz6HD2Nu1h04_epPmt-SkwVKVdPJuNs-ePPifYcw2AwoSgGln35HrzVc1LiCxKhQF93B3V_e5L4YIClN3yLKZU1BR4qHELa0jL4CIqfxwCj8uY8B5TGNJbudo37P2Dvi79h5oq1XHDZWowWK4reWPG3nmDHX35HPNDIvCu-zYIvXIWQSkbCajpYx96XHqB66bVccOlaDEZrphdR2NE9mT3KI4V-xEbkyhpC5PeAXBExP3iMbXmR8biBng4-fOlxwv1drFh3J-Vn1GREFjURIxzsx11armOEIBLqZddrz9F3fA9LbvwulbPmp3QnOdkQHX3-oxpgYvEvc4-OiZSnSL2LA4KkGuWT7VtofPuPzLv8O8xbeXGqSBp28tN3ooG6Fx7EWTmHZdfcgt1VWrjLT_KUwOQUoTHdf6g4L7LWF9DVcpTdGx_E7C5n6fW3UuSZihCQTMTYv-1VTtY-z_n_8n1qFqwAYRi1wjtdi5-UAQo6QSY0kMqIz0ohiUVC7H1jI23732TxjT-kuLycXS8_jlAEy9feQXHZtEys_zOO7Z9mAxSYLlFpqT9A3cYHQO1j9up_55wL16AYTAgpChQ0CzgcJv_ZIVAA8KQ9REoIDXQT7uvDO312CgTl-G6ebwPG7gSfbgNMgBOMNUbOfEV28fl3PyvDpTq82uSp-zAvVSa9uwUvVht5fkcKvU7IHBUeR8zU9X6p77Qc_7mBMcc6NVlcTMxbRiu0pBhxWOFUTqjmLFoUPpZxUh4whv6ea3k5IaOd6sMZme9N4OsKqWdpR1VO8u7caLsmmOTi5dj3LMi1J3XFFFIPEk9gN8SoL3NPc-sSe0FgJQrEkvyqzilcHyjAPaQeJC5YVs5xeUTOQ1WnnahkZF_dmJO5R_78GgHuUUor59YCq0k9QR4rqMTMIy7mW_zpMEhqDDE5EM4J26zb62tdXVo5t_b_AdFN7yyheCI4AAAAAElFTkSuQmCC".fromBase64Url().let { ByteString(it) },
                    "MIICRTCCAcugAwIBAgIQJGQ57Z_GIpsZ1bBj_H9w-TAKBggqhkjOPQQDAzA1MQswCQYDVQQGDAJVVDEmMCQGA1UEAwwdVXRvcGlhIEJyZXdlcnkgVEVTVCBSZWFkZXIgQ0EwHhcNMjUwNzI0MjAzMTUxWhcNMzAwNzI0MjAzMTUxWjA1MQswCQYDVQQGDAJVVDEmMCQGA1UEAwwdVXRvcGlhIEJyZXdlcnkgVEVTVCBSZWFkZXIgQ0EwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAATxQFn8bIEIaMONgvtN3ndBNB6piOwHo8XF1vj7Lpd77w-wmdWD60Ia8nHh7z4LmdxbxcgtODb5oDehnW8kR4lR0Pw0V5iUMJRiVb0AsZSOk-WKdfS847NlT0Ip5B608WqjgZ8wgZwwDgYDVR0PAQH_BAQDAgEGMBIGA1UdEwEB_wQIMAYBAf8CAQAwNgYDVR0fBC8wLTAroCmgJ4YlaHR0cHM6Ly9yZWFkZXItY2EuZXhhbXBsZS5jb20vY3JsLmNybDAdBgNVHQ4EFgQUddH-bvOvKZEFSkMWS8ncN1LvXdswHwYDVR0jBBgwFoAUddH-bvOvKZEFSkMWS8ncN1LvXdswCgYIKoZIzj0EAwMDaAAwZQIxAOCgXroeWZOkvMcZHn9hijZesYMTC-3yWZGS39ieBRupLjTalHoy6CDZlE_H9CYAbQIwZa-iyQpLzghYOCiXkhRtoe8V8XCP8JwxuQblWQYdNWGohOeLowR3punD3UcJTAPS".fromBase64Url().let { X509Cert(it) },
                ),
                Triple(
                    "Utopia Plumbing Company",
                    "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAAXNSR0IB2cksfwAAAARnQU1BAACxjwv8YQUAAAAgY0hSTQAAeiYAAICEAAD6AAAAgOgAAHUwAADqYAAAOpgAABdwnLpRPAAAAAZiS0dEAAAAAAAA-UO7fwAAAAlwSFlzAAALEwAACxMBAJqcGAAAAAd0SU1FB-kHGBUjMFiPZdwAABswSURBVHjarZt7kGVXdd5_a59z7vv2-90z3TOjkeTR6C2BZISewRYvBygSoIxtqMJlIARSEFKOY8c25aSwYyCOsYmxDSmDTTAUtrEDoTACSQZDJCHQICHNaGY0PU91T3dPP-7znLP3yh_n3GffHinlXOnM7fO4Z--99tprfetba8v0Q6uqKOn_7SMCGgpRer39kfY_nY9K981_wke7vk3fNZgwMJ8Fzwin6pZ1FdDu3pl2L3r63L6ife0J_v4saDqA7kdUpD2cWJWmg5qFixaq2i-Q1sf1XtCuU-30Q9JWVJKb0hpEeo4qiusMRIQDPuQMbMTgizIZeOSs43yc_lRb7dN5l7be6XqF2dUv4evL2vOjlipIIs8MUPaEsi-UfUPGEzZDpeIcHrDhoGI1eXX6O1HpUg5NzlW7tEd2zO5Oibb6oYz7wrgv-CJkPUPTKSuRJWuEnBFONhwORRRUtWfwyYi0_d09VgH8Xg3UntlzQANoxMrFWBFRDhU8fKP4FozAlTmPulXONC0VBx29ac1uW9SoKiLSO9hWh3eojbSmlWHPEKDk_GSwRh0zGY9KrGzFyr6McLLp0u4Loq0JURCT9El1h5xVOwvtRX32-FCLHU_XHTWFrBHONSzLkWNP1sOY7oXUdWhySPfqTK8NNAPqUHXpbwWnSt4z4JQojlmNHT-uxRR9IQAsMOl1aY4IRgREOu_pXmIALtEK0-lIp6ODOjZsIOvBqaZjQpLzjcgx6gtjvmHLKpMmmeXkcF2HTb5Jz12cXMOh2OS8daTPtISwEEDRE043Lduxsp2Ox6lyohYxkTEsNy3DfmcuRZWFjGmPQ1UT9e8bl6p2LYEdhk27jAhMZ4SlpiMnwlggrETKRCCcaFgWcz4jnoDnsRbHxIPWtejOZdbW-HTNdtkfEPIoJd9wtG7ZmxUaTnuWawOhEjumA0PolJJARSErUA6Ea4yHdS7VICFjwBjBdHk8f8f6b3emc33IQOygqXAwK5xpOhQ43nQownZkiYGSEQ7mDc_U7C4ern_wOuCets8nA4_1piVSR-w8qrFDnGM2MKxZaKJsRo6cETatY9hLBDJsDKv1mA2r5AU84FKkbV_gC2QEPBF8cbqjp3kRYlWidDbGPGGt6RgWIVlSSkag7pL1umKhaIShrAfAlIEVqwPtiGi_RqTWue0SO8LIGFiJHKhyvhGzLxcw5gsVq0z5hoIn-EBghOVIGTVCVpURD47XHTY14gMhS9q4CQSCVCJZgSxQEphprSlVsgLbDsZ8YTm0zAeGulXEJa4HhYpTlhoxy3XLZMZDXDKYnqNbHbpspKTrVtq2KPlqWKXpFKNgFWqxJRDDROAhqhyvxyw3LaFT8gpDnrA38DjfsNhdMFZ_f_xbyobErkjLOGJViBWaqqynPl5VCVCqLuld5DpLRrWDJDdQZrOGolGq2nH_2mdaehDbAGXxgAuhZcwInioXnZITYSu25Iyh4BkyVhkODE2rDHnCWsNywbrBy68fHqbX_GPbyfptprDXpT40Y2DK89if9TCJu6Cl1eebbgB87fxVjRzjvqESul6Aq4OBb3-n9gZCIMJzoaPkG9ajZIl4AqFNvFHVOuZ8IXZKyTNkBZ4O7QBMsws8Ty_7a_HgtRpaOGsthMqVBR8vfWcOmAsMz9bj5CUDBL4ROeayXqJOl_vsBO0EQMkzHG24VB5K7BJjGztHbB1nIstcJsEdeRHqseNsMx4IK3rg74CP_4KxiQiRTWyCJzBkhIwnLGQNz4eOcMDPtp3iiwwa3wuoAEz4hq0o8c9xurRMujbrVimIsO4cpxrxznhjoN5fvgO-DFozPbMknK5FzGY9coHBGSUAYqfM-8Kphu2DsnRshjqiltntCRZ2b8sqeAILgWGpGScG0CbGdjWyLGY9xGmfMUnskAwY4AuJwe-fDNnhujVd95aVMEFcjVjJeULTaU8E1w6mEKwqRRE2BrjZzsynwujS3dXIsifjsRVZYpd4qGYiUUaNwZMBKt2P8F6EwrXG6SvKbtF8_3mswlpkyRlhwfd4rhn3AqfUjZU9IbKOghE22hrWweqd-KdPGCmMPtPU9uyEVvFQYlUmsgar4KPEAyJ-dvMusvu4_CT27g0WRYQbvEQVN6xywpFGccp1BgKNKfqGhidMimsF7e0353wQHFu-cD5KQ1R1vCJnOJw31KzycNVx1CpiOsTHQZNoDTgMECIcrTWZz_ucCRMsMCXKkA-rFpb6nNH1fmI7agpP2z700yUV7bJOwl-d0h5znKrkxVfOMZTxePC5Te7_0XYbvC3dN8V0KcMPlms8vdrkZw-PIh2KI4n4BJxTvvD0Om872eDtJcMHri1z9UQJ3yT3K82Yh5c2-bdH6xyNk5E8etso108X23PoVKmElofP1_m5H2_jx5bTr5mn6Ht87cQl_vlTlXbXX5kTvvSKOXwjrNUirntgmbUewDHYJJsdqyO1L74IgTF4pmPEVME3yXVfBFGX_G2EoHXdCB6Q8QwZA28vKL9_5zSHp8r40jGQxcDjNVdN8KXbRpnVRABB2mZgBE-EjGeYKGR5wxXDfHxvhpGMR87z8E1CjnRTWG9bKJD3PQJjmC5m-NeTQb9V3sUI6gDLp71RV3IuvetMhC8tRxz99nkA3nPDOHPlLKc26nzqmS08gRNNx6_fNE7BNziFB05u8uWlbUYyhrdePcqhyQKHJgr8lytL_MLxWnsazm81eN8_LjOTC_i1W8eZLmZ4xYFhPr6yilUHJOCsRXx4wMv3FBMX3IwoZwNeu6_Eh5bXd6563SEA7aXylC5gvAuESx_8dj3m77YdgvCLqRrXQstn1iIip7x6xOPgeAEBHjy5ziuPbKVvdnxu7SIP3zPFnuE89yyU0KPb7TatKn9VU6iGvOlijZlSlrwvPLfV7FPUxIi-e9RjbihHrMrnnlrlnTfPcs1UgTv8Nb4TSifg0C6nk56bnvgf7URpfdTADlZVlWIaaqr2wmFJtea6oUzbOnzjXD11Esm9U1Y5slIHYLQQMN_VbjYw_NZslo8v5LhhtgTA8lYDda6jh13EzRv3lQBl6VKdj55ush3G5DzDOxeLncinjQta58m33yEre8GT9i-BAWBHVMkDlT69cqo4TeKH1guDAWjEl05jtou3mypk-Q-3z7T5w8gpn392G0V6iU2nXOXBLfNlBOHxC1VOWnhqucrte4e5c6GEOVbBmd0xaSIA3YkWXApgyjk_GbyDg55Syph2pxTICVRcvxNJQtvHNkOcKkaE1x4Y4pNr60QuISYOB8LNswVQWK40CY1pCzm0jrV6hFNYrcd88fgWn75kGUv5BgAxBhHhPXMZSpkEz_3UFcOcXyxTCJLzPUNZfnFI-OOt3SGSvxtsOrfZYDQfcONcmc8erPPkWpN_cfUIpUyAqvLsRgOjycz2kCoKTZeEzt-vxTy5XOH6mTK3zJb46u3CgxdqjAaGn1ooM1HM4tTxzTNVRnzBpB5npRpy50MrIAkh2gqSciYlOwGMcrjs84orhtuqO5LL9IbURnjTFcP8yeMbCciSFwyGOtbh88c2-dB0GV-En71-Kp2c5A1rtYhPnqygCJkBL92KlelU53_j8XU-eVeGqVKWm6ZL3DRd6iElHzm7zb85uk0dwaWo0aXkSr8FL6trC8SGlgNRzFUTifX_xsl1PnemRhg5okj51RtHuX52iFvmSyw8vs6SSB9OTL6NdDGpoh0T-eGVmN_97nnObNSTDI4IkXP86Pkt3v8PyzwbC4LDmKRDW2HERiOkElliBYvSjJXvNBxv_eYFHj61QTWybVO6XGnyF0cu8qr_s0Y9FX4lsmw2QqqR3cEUtVzeZiNpp-ngZ-ZybDVCNhoRXzxe4YsrId-tO55Q4YGzVbaaEYrj56eCnnSMdKXyRD5_XAdGQ6nl91DuznvMZQxP1WJ-ECXRV94I83lDaB1nmornUhMlhhjIeUJBlKmcx-mapa7CJI7b8h4V6_h2M8k_dsMQH8WQqH0sg2O4INUAJwmiNNYlkyOd7NOIb5jL-2w3Y55vxFjj4XalJD73rA7KSg2KHEQ7sB_gmrLPRtOy3HT4niEjsG2ThyYzhvVYGQ-EiZxHLXLU4oRodSS4vuUtWkytl_5eL0OV7dDiQTnI9H178x6qyumGSwTQA42TH_k7YPAOCrWbve1tpdqivEQInTKZ9ajESZy2EVn25jyebzo2I8t4YCh6hthpOymqKomDUSV0ynbkegKVF5VEHnRNIFY4VYvZk_PZn_c5WYu6XHsXIYIdRAKQJP5I6FhJbUA_KlxpOvaVfDZiS906qrFjX87nVD0mVjjfsMxkDarCpRiq1raDKh0Ub6cC10HQvD9_2B1lt9RfU3hs0rSYwNl6zFzWY0_WcKZud4zTyI6UWHKIKn957RBH7pvmR_fN8Nhdk_ynPRlQx2d-oswP7plmTh2NyDERwCeuLPHwy6e4Oass5A0f2Zfj0bumuT2AX92b57t3TvDHVxbaQdV_ns_yxF1TfPG6YYZQ_uH2cY7cO82Re2d47OWT_O7eHDjl01eVeOLeGV7qKZ84UOBH90zzvrEAUeVnioYn7pnivy7mEIRbM4Yv3zTMifvneO6V83zlplGuN8r5eoQvwnRgEmDY5bYNXVa2-8A5rhjJcs1MmVwgHBjP88E75nnfRIYDo1kOz5YooJytRhR9j33DWQ5NF8nHllqkHBwvcO1MifGsx8JQwOGZId54eIIbczBlLG85PMa1s2X2DweECoemCiyO5RJeYKLAe2-f459lhH3DAYdnywwZZf9whsOzZT542xSHfWEiMFw3N8Ri2WfeWT5_3wyvPTxNPjDkfeH-q8b4pcU8KJyrxYxmPTItHiAdp2mrmPato5a1Be77-jk-9o8XCIzhZdP5rucVi7Bcjdt2KJsNWI-UqBkjImhk2-xwOePx7j0F3jaZY2E0n9SBiKEgya9XKk1-_bFVvn92E2OErNcbgWqa_98zUuDjLxlHXRLFGePxgX0F9o3m-eazaxz827Nc8TdL_Pu_P8X7jtfayHW9HjOb83rD_uQl3Sa-i2hMB_WJG8fYP5pJEWDI3pFsmsV2qBMuNWNcGCEiBL4hq67DMaVxuwLHViu8-upRVqsRS5dqzAzncc61hX1gvMgXX7MPI8JTKxVOYMBLoHc-5yNeAn-PLG9xx_4Rzmw3U-DkuHo8jxjhr49tcG0An71vHiPwlobl1m8tJyRPM-ZgNkteLDXnkJ76gF0MrwHuPjjKzHCev31ymQ8tVdsMrIgB53oKHwIbs1gMyOc8BEljiuTeV05sMVvOcd10ia8e22il8lmLkmBiaa3GLz9wmr95cpnDUyXeMe6hcSKgWi3G2SRX8AePrHBhs8GbrptuI8rNRgKeDo1maChc2AoZyWdYHM2B61jMzaZlPGPS0gO5TF4gHWSscPBzz7CM1wMLjAj_8fAwcezYipWTl5o4dbznhknuuFDlZQtDVMKYI6HyBj9R5e9txZzdalAMDJ9ZbvAOSczwFSUfBApZn-un88yP5pIlkw8QIz2EKyT1Sh_-zgU-dv8-8MD3fT59dI3XXjPG22-dZWFojY16TC4wNGLbqRMA1pqWA2W_HbT5OJem43eWqUROidJ1Jl21N04hdo7XXT-FIFzYavDSvz7JS-aL3LIwwpWTRSphzP949AKPbEZEoSV0js2tJu_6-zNkRXh0OyJ0jjh2nNiKiB0M533ecu0ksXX86PwWf3h0g9-7bYooZYtjl-QknXN8cj3mjiPLvOmmWSJRHg2V3374HO-5bZrXXDuNAhe360mStKsYyylEsWPETwquhE892aqs2ZEh2GuUPMoxJz0C2iOOktAmQ0NVTjpBjHBPFvbmPB7ZinnGJo3PiDIsynFnsF3A7UqjNBTOOOGAcSnPqDSBU2mbc6IMe1DPZQlrESV1LDloAhkRFsWxqcLwUJal7ZC8c9xTDtiKHUfqloIRzqi06q1AYDww5H04W3cdAfTm7xkYOr7Yz4vAcjtqBl6ovaIRhgLD8w27swGB0cCjGAhna7Z9c7c-ZEXYW_I4vh3j43pRlvansfozOvJCucQdjOIuWaGufAjSm0gV2VGxVrWOsp-wxfGAOqZLoWU8F2BI1Lw_KSZdYglVMWrw2nzAbtVafb2dFeWnRwIuNi1frSRRGAL7xHHnUMDZhuNbSR6LKVGuzBg2LDwVpW7Oh_lAOBcmhQ-LWcEIVKzjh2FHgNd4MOQpjzaTCrBAlVtyhlUHTQ-i0HJj3uP5GCY9pW4dP4wNcWS5q2S4WHc8GQ1O3XfDnLy0KLHL1Cq2LvzWYp53_uQc46Us1ilPntvkbQ-c4x37y7z9tjmKGQ-n8PjpDX7-wfO8bjLLb7_mIM-tVrjqy0sgwp-9fIrbD4zzZ4-cY60W84F7FxNSWpUjZ7d47TfOcVGFL71qLwcmS_zK_z7Jx87VuSVnePCthzixUuHl_-sUP10O-MKbD_HZ753hxj0lFseL3PeFZ_mF2SzvvXsff_Tt07z36PbO8t6umgHnlEBavGVLJInukBUY8w1TmeS4v2x4_90LWAf__eEl_u7I84RWublk-KWXzbO63eTjD53mgWcucuviCB-9ZQzPGDwR9o8X-eX5PK8rerxk_zgmNXQGxTeGv_z-eb7-4xVuXRzlvQsF_mVZODhZIowtr79qqJ2D8BCuni7zRzdP4FLzKyiffnyVUsbnV64b4Y03THN2vcpvPFsZUHvYiWoFqEWOkicdDRBgNGMYzno452jGSuwSFX_9Yplc4PGR75zlT881uNis0rSOP79pjIzv8aFvLvHnGxaObfH0eI5bF0d5-HQlBSghb7lujAsbDSrNkLFCrieXem47YrtpUVVyvsdbD41RC2O-_uOLvOraaW7KXsRZi1Ol2oh4_Q0zHFurpqvF8AfP13nzc-u87oYZfGP4nYdPc8le3gRnJGGkSxmTBL2BCHtLATlPOFuJWG0kcXnOT_C4TeGqbcSEVpkvBoxnvLbr7CkQRdqssIjwrWcvcc3sEHdfNck3nr5IO8eU4vp_d-8-3nXnIsdXtvn08Uvctn-EcxsNTm02KWR83nVFEaOJFjx0bJ3zGzXe_bK9mC6z9tHvryIIx1a2-cyFBnvzPmWvU4TtAyVPmM4YFgqGmZxQjSyV0GKyAnuLPpfqMbXIsbfoM5kziCq10NKIHF85U6URO971k3P82mKOjxwa4oH79_Dt57eJrOU3X7HIfzs0xNfumubgdJlHnltPOijwxLkK5y41CK3jfx7bTNPjHQ799x96jrd9_ilu__ISb5grMFHKcvXMEO-_9wBWHfccHGtnpptRxIcfPEMpZX815Qm_utkkdMpaJaQaxpythm0j7qtSMmBU2QhjzlRiTlctl9J9AP5s0We5GjGUMeR84UI1pqGdbI8ofC10_N6DS7zrjnnec_c-rCpPnL7EIxX41GMX-LkbZ_hXdy4Qq_K9k2t88LF1Xj-VIbIWp_Cb3zxN0YO6TWmwNH6wTjmxEfHZ9RgRePVPjLPdDLn3L55h08Hv3DzO62-a54YhQ-QcVoU_WY258_vnefOtc21HZVIm2VrFM4JD2E5TdTHKRryzQHMma4isQ6b-9IgqUMwYTlfjdsjZoWwSnCACC0a5fzzHJWN4aKPJauhYLGfINkLuLAc8V4v5Ri1xjxMoBwJYimAlfV0euCFruGATSLs3gOORsOYSv35zzqAi_KCZnM97hsWMcKZpmckIqxE8ZyEryk25pEbpVIqLXpoVth1sBh6eJDWGsWsnw6hESbFVy6iOBMJQ1keCP_yhLgwFLG2HSRncDhAhPRCxFQUulAM26jF155jJB5yuhD1IEu2tTejBQtJVS5Cea1cavzs92UKI8_mAamjZsA6T4iahp-C03d09eZ9GrDSdI3ZJ8NQD8FLgN5XzMCVfaEYO6zrVk6Zt3ropsk5O0anjfCVkLOcR20SVy570Dl5kQNq9-2_plCe30pOqPRWmrXtFI2SM4JmkXxMZL_FaviHXX_7ioB458oFh2yoN118uI-2K9pW6xc95QiOyaX1OX4H3QEibCCN2SSp8IuOx1YwZCgzbUdyVXdWemqDB-adWJWB3blHT_zoCHc37bYFLWrwhLln7IqBdMFo1YaSH8z4mBVk9dLB2dsWoaJKOsy9YzNefKJB2bF3IeFRjxfdM3_N62erQy1dydfSvlZ-P6GiJST1A5NKNFHTReiQ0XSNyjGVMymh33-8Nc3xrXVK64roKe7tzAi1v0OLy00BFBGKbMj4u9fuuO6nRaUVl9xIVFe3JP0g__Z5Sc-oU55QMnU0Z25FlrpiBRpx6rZZBcKzWIvYMZVhvxO1M984SfTCNWMn5prNzqxsadxc-pI2SgiJ16c4Ql5TOd3bC6C7TLoNDSe2ed2m_s_t9sVWmcj6BJxQ8Q5TSZN17fnr2KrUSLQ3LTN6_bAGhCa0SmC7JI20jlSQPpdN57TSWkBdpubB2R7CSmvWdG5WEbkFID3rslNlJVyImOVZqMdVmzIVKhG-SAmlfhKmsl5bzS2_WRxONuViPMO3nBqxQB2Ys61EPHZOt4oOutbRjDUuno2NZD5EkB68otkXu9qhiKp30aGtHPxWvXW21jHHXs6pJ5jhO6TAvDYQyXiJk01pirve96uDcdpOsb5jOmcRy9bSpGN83VCNHKetRMNIXDg_IGAHjWR9FmMr51EPLaMajFsa9OtZSD9klItf-LYSdaLS_Fql779J25MgFSei9FTp8X2hat6tVdQpnKyFKEu-U_V46yV-phMyWs6xUG0yXs1yshFRiN8BoJR3Ie4ZixqMZO7KecK4SspCWx_VwL9rHc3XXr-qADXw72CDZWe6ebuKwLgnIRJRG02Jt1xockOFWlJV6REaE8ZzHSM4ntEotsvj12LFeDZksZLlUi5guBhSbMRcbMa57AJqUyc8PJUmRWODsdsieUsClWphudO01etKbiO3TkN6SvF0rfwfUWp-vxhS85O9auv9P-6x7b9tpsZXC8zWLoBR9QyEw-KrKRjNJKowWAy7VI7KesH8oR2wdkXNphaghn_WI0gKmhlX2ljNUGxEboR08tj6Vb7vS3TralY_Qy5GtqtTiwX6lBbBU2VnZlmqIAtuxoxKnRVJKEirWYstMKYsRuFRPih1zJsmUV2PHSj0EDGM5j6lCwGolZDNyOzqnl9sSwy6l7fKCmzsGKcMAyvMFCjz7yiH87mebVjm91SDvCSNZj-Gc3y5hL4gwYkyyEcIznNtsUrO9KFH08nS4Xu7u_wuP_v_x838BRFRarEI8qToAAAAASUVORK5CYII".fromBase64Url().let { ByteString(it) },
                    "MIICRzCCAc2gAwIBAgIQZjjem0ED1YZfrJXIp0QagTAKBggqhkjOPQQDAzA2MQswCQYDVQQGDAJVVDEnMCUGA1UEAwweVXRvcGlhIFBsdW1iaW5nIFRFU1QgUmVhZGVyIENBMB4XDTI1MDcyNDIxMjcxMFoXDTMwMDcyNDIxMjcxMFowNjELMAkGA1UEBgwCVVQxJzAlBgNVBAMMHlV0b3BpYSBQbHVtYmluZyBURVNUIFJlYWRlciBDQTB2MBAGByqGSM49AgEGBSuBBAAiA2IABL6vQpn6zrHonK43hm_WED4i3W63IDDA0mtvIzvsBHSGl7YK0Tu7GiqDBNWXtDvxowB8upQv6Us4JVzqI5kzR4D142vmilOb-J9AhOCWxnqmEvqOTSVi_PX9r4DrDWVHsqOBnzCBnDAOBgNVHQ8BAf8EBAMCAQYwEgYDVR0TAQH_BAgwBgEB_wIBADA2BgNVHR8ELzAtMCugKaAnhiVodHRwczovL3JlYWRlci1jYS5leGFtcGxlLmNvbS9jcmwuY3JsMB0GA1UdDgQWBBQMJBLQ4z7QV6bb6Hg_NRlsprAzJTAfBgNVHSMEGDAWgBQMJBLQ4z7QV6bb6Hg_NRlsprAzJTAKBggqhkjOPQQDAwNoADBlAjEA4ZdSN9L3fLu2uuLbvctEhFap1myoYongcIlkFaiBnVftzc2U8Ziq9fk0bhzwUoYaAjBhZcjyGABTQYv2KH4-Nasqnjnw6PirUmutpPN15G4jzQ63hUp_X7xMfGSQ8Rd7ibU".fromBase64Url().let { X509Cert(it) },
                ),
                Triple(
                    "The Utopians",
                    null,
                    "MIIB4DCCAYagAwIBAgIQ6rXL1BAAeNzwRX2GH2BGqjAKBggqhkjOPQQDAjAhMR8wHQYDVQQDDBZUaGUgVXRvcGlhbnMgUmVhZGVyIENBMB4XDTI1MDcyNTE5NDQ1MloXDTMwMDcyNTE5NDQ1MlowITEfMB0GA1UEAwwWVGhlIFV0b3BpYW5zIFJlYWRlciBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABLqZ3qQWauWFQo0tbTvhnViU2kBJ4jKPkXxZuFtwlPyF-mYZzygtx5tReifmY9vQXxqi_QVwAAsX6sVZK9p42C6jgZ8wgZwwDgYDVR0PAQH_BAQDAgEGMBIGA1UdEwEB_wQIMAYBAf8CAQAwNgYDVR0fBC8wLTAroCmgJ4YlaHR0cHM6Ly9yZWFkZXItY2EuZXhhbXBsZS5jb20vY3JsLmNybDAdBgNVHQ4EFgQUG2vN6fsoBdTeXaUMbiX9MxtpldkwHwYDVR0jBBgwFoAUG2vN6fsoBdTeXaUMbiX9MxtpldkwCgYIKoZIzj0EAwIDSAAwRQIgWA7vaJVZgg8CebwCTxDgPu8w_2GvLVAHMa_RprUUSScCIQClOLGmxZFQLnKqT7Jy_EHXWM3oJUhyVeehOWauMMJyzQ".fromBase64Url().let { X509Cert(it) },
                ),
            )
            ) {
                try {
                    builtInReaderTrustManager.addX509Cert(
                        certificate = cert,
                        metadata = TrustMetadata(
                            displayName = displayName,
                            displayIcon = displayIcon,
                            privacyPolicyUrl = "https://apps.multipaz.org"
                        )
                    )
                } catch (_: TrustPointAlreadyExistsException) {
                    // Do nothing
                }
            }
        }
    }

    /**
     * Starts export documents via the W3C Digital Credentials API on the platform, if available.
     *
     * This should be called when the main wallet application UI is running.
     */
    fun startExportDocumentsToDigitalCredentials() {
        if (DigitalCredentials.Default.available) {
            val dc = DigitalCredentials.Default
            CoroutineScope(Dispatchers.IO).launch {
                dc.setSelectedProtocols(
                    settingsModel.dcApiProtocols.value
                )
                dc.startExportingCredentials(
                    documentStore = documentStore,
                    documentTypeRepository = documentTypeRepository,
                )
            }

            CoroutineScope(Dispatchers.IO).launch {
                settingsModel.dcApiProtocols.collect {
                    dc.setSelectedProtocols(it)
                }
            }
        }
    }

    /**
     * Handle a link (either a app link, universal link, or custom URL schema link).
     */
    fun handleUrl(url: String) {
        if (url.startsWith(OID4VCI_CREDENTIAL_OFFER_URL_SCHEME)
            || url.startsWith(HAIP_URL_SCHEME)) {
            val queryIndex = url.indexOf('?')
            if (queryIndex >= 0) {
                CoroutineScope(Dispatchers.Default).launch {
                    credentialOffers.send(url)
                }
            }
        } else if (url.startsWith(ProvisioningSupport.APP_LINK_BASE_URL)) {
            CoroutineScope(Dispatchers.Default).launch {
                provisioningSupport.processAppLinkInvocation(url)
            }
        }
    }

    companion object {
        private const val TAG = "App"

        // OID4VCI url scheme used for filtering OID4VCI Urls from all incoming URLs (deep links or QR)
        private const val OID4VCI_CREDENTIAL_OFFER_URL_SCHEME = "openid-credential-offer://"
        private const val HAIP_URL_SCHEME = "haip://"

        private var app: App? = null
        fun getInstance(): App {
            if (app == null) {
                app = App(platformPromptModel)
            }
            return app!!
        }

        private val testDocumentTableSpec = object: StorageTableSpec(
            name = "TestAppDocuments",
            supportExpiration = false,
            supportPartitions = false,
            schemaVersion = 1L,           // Bump every time incompatible changes are made
        ) {
            override suspend fun schemaUpgrade(oldTable: BaseStorageTable) {
                oldTable.deleteAll()
            }
        }

        private suspend fun initializeDocumentMetadata(
            metadata: AbstractDocumentMetadata,
            credentialDisplay: Display,
            issuerDisplay: Display
        ) {
            (metadata as DocumentMetadata).setMetadata(
                displayName = credentialDisplay.text,  // TODO: customize after provisioning?
                typeDisplayName = credentialDisplay.text,
                cardArt = credentialDisplay.logo
                    ?: ByteString(Res.readBytes("drawable/card_generic.png")),
                issuerLogo = issuerDisplay.logo,
                other = null
            )
        }
    }

    private lateinit var snackbarHostState: SnackbarHostState

    private val presentmentModel = PresentmentModel().apply {
        setPromptModel(promptModel)
    }

    @Composable
    @Preview
    fun Content(navController: NavHostController = rememberNavController()) {
        var isInitialized = remember { mutableStateOf<Boolean>(false) }
        if (!isInitialized.value) {
            CoroutineScope(Dispatchers.Main).launch {
                init()
                isInitialized.value = true
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Initializing...")
            }
            return
        }

        val context = LocalPlatformContext.current
        val imageLoader = remember {
            val engineFactory = platformHttpClientEngineFactory()
            val httpClient = HttpClient(engineFactory.create()) {
            }
            ImageLoader.Builder(context)
                .components {
                    add(KtorNetworkFetcherFactory(httpClient))
                }
                .build()
        }

        val backStackEntry by navController.currentBackStackEntryAsState()
        val routeWithoutArgs = backStackEntry?.destination?.route?.substringBefore('/')

        val currentDestination = appDestinations.find {
            it.route == routeWithoutArgs
        } ?: StartDestination

        LaunchedEffect(true) {
            while (true) {
                val credentialOffer = credentialOffers.receive()
                provisioningModel.launchOpenID4VCIProvisioning(
                    offerUri = credentialOffer,
                    clientPreferences = ProvisioningSupport.OPENID4VCI_CLIENT_PREFERENCES,
                    backend = provisioningSupport
                )
                navController.navigate(ProvisioningTestDestination.route)
            }
        }

        snackbarHostState = remember { SnackbarHostState() }
        AppTheme {
            // A surface container using the 'background' color from the theme
            Scaffold(
                topBar = {
                    AppBar(
                        currentDestination = currentDestination,
                        canNavigateBack = navController.previousBackStackEntry != null,
                        navigateUp = { navController.navigateUp() },
                        navigateToSettings = { navController.navigate(SettingsDestination.route) },
                        includeSettingsIcon = (currentDestination != SettingsDestination)
                    )
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { innerPadding ->

                PromptDialogs(promptModel)

                NavHost(
                    navController = navController,
                    startDestination = StartDestination.route,
                    modifier = Modifier
                        .fillMaxSize()
                        //.verticalScroll(rememberScrollState())
                        .padding(innerPadding)
                ) {
                    composable(route = StartDestination.route) {
                        StartScreen(
                            documentModel = documentModel,
                            onClickAbout = { navController.navigate(AboutDestination.route) },
                            onClickDocumentStore = { navController.navigate(DocumentStoreDestination.route) },
                            onClickTrustedIssuers = { navController.navigate(TrustedIssuersDestination.route) },
                            onClickTrustedVerifiers = { navController.navigate(TrustedVerifiersDestination.route) },
                            onClickSoftwareSecureArea = { navController.navigate(SoftwareSecureAreaDestination.route) },
                            onClickAndroidKeystoreSecureArea = { navController.navigate(AndroidKeystoreSecureAreaDestination.route) },
                            onClickCloudSecureArea = { navController.navigate(CloudSecureAreaDestination.route) },
                            onClickSecureEnclaveSecureArea = { navController.navigate(SecureEnclaveSecureAreaDestination.route) },
                            onClickPassphraseEntryField = { navController.navigate(PassphraseEntryFieldDestination.route) },
                            onClickPassphrasePrompt = { navController.navigate(PassphrasePromptDestination.route) },
                            onClickProvisioningTestField = { navController.navigate(ProvisioningTestDestination.route) },
                            onClickConsentSheetList = { navController.navigate(ConsentPromptDestination.route) },
                            onClickQrCodes = { navController.navigate(QrCodesDestination.route) },
                            onClickNfc = { navController.navigate(NfcDestination.route) },
                            onClickIsoMdocProximitySharing = { navController.navigate(IsoMdocProximitySharingDestination.route) },
                            onClickIsoMdocProximityReading = { navController.navigate(IsoMdocProximityReadingDestination.route) },
                            onClickDcRequest = { navController.navigate(DcRequestDestination.route) },
                            onClickMdocTransportMultiDeviceTesting = { navController.navigate(IsoMdocMultiDeviceTestingDestination.route) },
                            onClickCertificatesViewerExamples = { navController.navigate(CertificatesViewerExamplesDestination.route) },
                            onClickRichText = { navController.navigate(RichTextDestination.route) },
                            onClickNotifications = { navController.navigate(NotificationsDestination.route) },
                            onClickScreenLock = { navController.navigate(ScreenLockDestination.route) },
                            onClickCamera = { navController.navigate(CameraDestination.route) },
                            onClickFaceDetection = { navController.navigate(FaceDetectionDestination.route) },
                            onClickBarcodeScanning = { navController.navigate(BarcodeScanningDestination.route) },
                            onClickSelfieCheck = { navController.navigate(SelfieCheckScreenDestination.route) },
                            onClickFaceMatch = { navController.navigate(FaceMatchScreenDestination.route) },
                        )
                    }
                    composable(route = SettingsDestination.route) {
                        SettingsScreen(
                            app = this@App,
                            showToast = { message: String -> showToast(message) }
                        )
                    }
                    composable(route = AboutDestination.route) {
                        AboutScreen()
                    }
                    composable(route = DocumentStoreDestination.route) {
                        DocumentStoreScreen(
                            documentStore = documentStore,
                            documentModel = documentModel,
                            softwareSecureArea = softwareSecureArea,
                            settingsModel = settingsModel,
                            iacaKey = iacaKey,
                            iacaCert = iacaCert,
                            showToast = { message: String -> showToast(message) },
                            onViewDocument = { documentId ->
                                navController.navigate(DocumentViewerDestination.route + "/${documentId}")
                            }
                        )
                    }
                    composable(
                        route = DocumentViewerDestination.routeWithArgs,
                        arguments = DocumentViewerDestination.arguments
                    ) { backStackEntry ->
                        val documentId = backStackEntry.arguments?.getString(
                            DocumentViewerDestination.DOCUMENT_ID
                        )!!
                        DocumentViewerScreen(
                            documentModel = documentModel,
                            documentId = documentId,
                            showToast = ::showToast,
                            onViewCredential = { documentId, credentialId ->
                                navController.navigate(CredentialViewerDestination.route + "/${documentId}/${credentialId}")
                            }
                        )
                    }
                    composable(
                        route = CredentialViewerDestination.routeWithArgs,
                        arguments = CredentialViewerDestination.arguments
                    ) { backStackEntry ->
                        val documentId = backStackEntry.arguments?.getString(
                            CredentialViewerDestination.DOCUMENT_ID
                        )!!
                        val credentialId = backStackEntry.arguments?.getString(
                            CredentialViewerDestination.CREDENTIAL_ID
                        )!!
                        CredentialViewerScreen(
                            documentModel = documentModel,
                            documentId = documentId,
                            credentialId = credentialId,
                            showToast = ::showToast,
                            onViewCertificateChain = { encodedCertificateData: String ->
                                navController.navigate(CertificateViewerDestination.route + "/${encodedCertificateData}")
                            },
                            onViewCredentialClaims = { documentId, credentialId ->
                                navController.navigate(CredentialClaimsViewerDestination.route + "/${documentId}/${credentialId}")
                            }
                        )
                    }
                    composable(
                        route = CredentialClaimsViewerDestination.routeWithArgs,
                        arguments = CredentialClaimsViewerDestination.arguments
                    ) { backStackEntry ->
                        val documentId = backStackEntry.arguments?.getString(
                            CredentialViewerDestination.DOCUMENT_ID
                        )!!
                        val credentialId = backStackEntry.arguments?.getString(
                            CredentialViewerDestination.CREDENTIAL_ID
                        )!!
                        CredentialClaimsViewerScreen(
                            documentModel = documentModel,
                            documentTypeRepository = documentTypeRepository,
                            documentId = documentId,
                            credentialId = credentialId,
                            showToast = ::showToast,
                        )
                    }
                    composable(route = TrustedIssuersDestination.route) {
                        TrustManagerScreen(
                            compositeTrustManager = issuerTrustManager,
                            onViewTrustPoint = { trustPoint ->
                                navController.navigate(TrustPointViewerDestination.route +
                                        "/issuers/${trustPoint.certificate.subjectKeyIdentifier!!.toHex()}")
                            },
                            showToast = ::showToast
                        )
                    }
                    composable(route = TrustedVerifiersDestination.route) {
                        TrustManagerScreen(
                            compositeTrustManager = readerTrustManager,
                            onViewTrustPoint = { trustPoint ->
                                navController.navigate(TrustPointViewerDestination.route +
                                        "/readers/${trustPoint.certificate.subjectKeyIdentifier!!.toHex()}")
                            },
                            showToast = ::showToast
                        )
                    }
                    composable(
                        route = TrustPointViewerDestination.routeWithArgs,
                        arguments = TrustPointViewerDestination.arguments
                    ) { backStackEntry ->
                        val trustManagerId = backStackEntry.arguments?.getString(
                            TrustPointViewerDestination.TRUST_MANAGER_ID
                        )!!
                        val trustPointId = backStackEntry.arguments?.getString(
                            TrustPointViewerDestination.TRUST_POINT_ID
                        )!!
                        val trustManager = when (trustManagerId) {
                            "issuers" -> issuerTrustManager
                            "readers" -> readerTrustManager
                            else -> throw IllegalStateException()
                        }
                        TrustPointViewerScreen(
                            app = this@App,
                            trustManager = trustManager,
                            trustPointId = trustPointId,
                            showToast = ::showToast,
                        )
                    }
                    composable(route = SoftwareSecureAreaDestination.route) {
                        SoftwareSecureAreaScreen(
                            softwareSecureArea = softwareSecureArea,
                            promptModel = promptModel,
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = AndroidKeystoreSecureAreaDestination.route) {
                        AndroidKeystoreSecureAreaScreen(
                            promptModel = promptModel,
                            showToast = { message -> showToast(message) },
                            onViewCertificate = { encodedCertificateData ->
                                navController.navigate(CertificateViewerDestination.route + "/${encodedCertificateData}")
                            }
                        )
                    }
                    composable(route = SecureEnclaveSecureAreaDestination.route) {
                        SecureEnclaveSecureAreaScreen(showToast = { message -> showToast(message) })
                    }
                    composable(route = CloudSecureAreaDestination.route) {
                        CloudSecureAreaScreen(
                            app = this@App,
                            showToast = { message -> showToast(message) },
                            onViewCertificate = { encodedCertificateData ->
                                navController.navigate(CertificateViewerDestination.route + "/${encodedCertificateData}")
                            }
                        )
                    }
                    composable(route = PassphraseEntryFieldDestination.route) {
                        PassphraseEntryFieldScreen(showToast = { message -> showToast(message) })
                    }
                    composable(route = PassphrasePromptDestination.route) {
                        PassphrasePromptScreen(showToast = { message -> showToast(message) })
                    }
                    composable(route = ProvisioningTestDestination.route) {
                        ProvisioningTestScreen(
                            provisioningModel = provisioningModel,
                            provisioningSupport = provisioningSupport
                        )
                    }
                    composable(route = ConsentPromptDestination.route) {
                        ConsentPromptScreen(
                            imageLoader = imageLoader,
                            documentTypeRepository = documentTypeRepository,
                            secureAreaRepository = secureAreaRepository,
                            showToast = { message -> showToast(message) },
                        )
                    }
                    composable(route = QrCodesDestination.route) {
                        QrCodesScreen(
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = NfcDestination.route) {
                        NfcScreen(
                            promptModel = promptModel,
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = IsoMdocProximitySharingDestination.route) {
                        IsoMdocProximitySharingScreen(
                            presentmentSource = getPresentmentSource(),
                            presentmentModel = presentmentModel,
                            settingsModel = settingsModel,
                            promptModel = promptModel,
                            documentTypeRepository = documentTypeRepository,
                            imageLoader = imageLoader,
                            showToast = { message -> showToast(message) },
                        )
                    }
                    composable(route = IsoMdocProximityReadingDestination.route) {
                        IsoMdocProximityReadingScreen(
                            app = this@App,
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = DcRequestDestination.route) {
                        DcRequestScreen(
                            app = this@App,
                            showToast = { message -> showToast(message) },
                            showResponse = { vpToken: JsonObject?, deviceResponse: DataItem?, sessionTranscript: DataItem, nonce: ByteString? ->
                                val route = ShowResponseDestination.route +
                                        "/${vpToken?.let { Json.encodeToString(it) }?.encodeToByteArray()?.toBase64Url() ?: "_"}" +
                                        "/${deviceResponse?.let { Cbor.encode(it).toBase64Url() } ?: "_"}" +
                                        "/${Cbor.encode(sessionTranscript).toBase64Url()}" +
                                        "/${nonce?.let { nonce.toByteArray().toBase64Url() } ?: "_"}"
                                navController.navigate(route)
                            }
                        )
                    }
                    composable(
                        route = ShowResponseDestination.routeWithArgs,
                        arguments = ShowResponseDestination.arguments
                    ) { backStackEntry ->
                        val vpToken = backStackEntry.arguments?.getString(ShowResponseDestination.VP_TOKEN)
                            ?.let { if (it != "_") Json.decodeFromString<JsonObject>(it.fromBase64Url().decodeToString()) else null }
                        val deviceResponse = backStackEntry.arguments?.getString(ShowResponseDestination.DEVICE_RESPONSE)
                            ?.let { if (it != "_") Cbor.decode(it.fromBase64Url()) else null }
                        val sessionTranscript = backStackEntry.arguments!!.getString(ShowResponseDestination.SESSION_TRANSCRIPT)!!
                            .fromBase64Url().let { Cbor.decode(it) }
                        val nonce = backStackEntry.arguments?.getString(ShowResponseDestination.NONCE)
                            ?.let { if (it != "_") ByteString(it.fromBase64Url()) else null }
                        ShowResponseScreen(
                            vpToken = vpToken,
                            deviceResponse = deviceResponse,
                            sessionTranscript = sessionTranscript,
                            nonce = nonce,
                            issuerTrustManager = issuerTrustManager,
                            documentTypeRepository = documentTypeRepository,
                            zkSystemRepository = zkSystemRepository,
                            onViewCertChain = { certChain ->
                                val encodedCertificateData = Cbor.encode(certChain.toDataItem()).toBase64Url()
                                navController.navigate(CertificateViewerDestination.route + "/${encodedCertificateData}")
                            }
                        )
                    }
                    composable(route = IsoMdocMultiDeviceTestingDestination.route) {
                        IsoMdocMultiDeviceTestingScreen(
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = CertificatesViewerExamplesDestination.route) {
                        CertificateViewerExamplesScreen(
                            onViewCertificate = { encodedCertificateData ->
                                navController.navigate(CertificateViewerDestination.route + "/${encodedCertificateData}")
                            }
                        )
                    }
                    composable(
                        route = CertificateViewerDestination.routeWithArgs,
                        arguments = CertificateViewerDestination.arguments
                    ) { backStackEntry ->
                        val certData = backStackEntry.arguments?.getString(
                            CertificateViewerDestination.CERTIFICATE_DATA
                        )!!
                        CertificateScreen(certData)
                    }
                    composable(route = RichTextDestination.route) {
                        RichTextScreen()
                    }
                    composable(route = NotificationsDestination.route) {
                        NotificationsScreen(
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = ScreenLockDestination.route) {
                        ScreenLockScreen(
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = CameraDestination.route) {
                        CameraScreen(
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = FaceDetectionDestination.route) {
                        FaceDetectionScreen(
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = FaceMatchScreenDestination.route) {
                        FaceMatchScreen(
                            faceMatchLiteRtModel = faceMatchLiteRtModel,
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = SelfieCheckScreenDestination.route) {
                        SelfieCheckScreen(
                            showToast = { message -> showToast(message) }
                        )
                    }
                    composable(route = BarcodeScanningDestination.route) {
                        BarcodeScanningScreen(
                            showToast = { message -> showToast(message) }
                        )
                    }
                }
            }
        }
    }

    private fun showToast(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            when (snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "OK",
                duration = SnackbarDuration.Short,
            )) {
                SnackbarResult.Dismissed -> {
                }

                SnackbarResult.ActionPerformed -> {
                }
            }
        }
    }
}

/**
 * Composable that displays the topBar and displays back button if back navigation is possible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(
    currentDestination: Destination,
    canNavigateBack: Boolean,
    navigateUp: () -> Unit,
    navigateToSettings: () -> Unit,
    includeSettingsIcon: Boolean,
    modifier: Modifier = Modifier
) {
    val title = currentDestination.title?.let { stringResource(it) } ?: platformAppName
    TopAppBar(
        title = { Text(text = title) },
        colors = TopAppBarDefaults.mediumTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = modifier,
        navigationIcon = {
            if (canNavigateBack) {
                IconButton(onClick = navigateUp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.back_button)
                    )
                }
            }
        },
        actions = {
            if (includeSettingsIcon) {
                IconButton(onClick = navigateToSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null
                    )
                }
            }
        },
    )
}
