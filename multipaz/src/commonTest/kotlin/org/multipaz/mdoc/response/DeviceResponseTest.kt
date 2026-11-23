package org.multipaz.mdoc.response

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseMac0
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.Hpke
import org.multipaz.crypto.HybridPublicKey
import org.multipaz.crypto.HybridSigning
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.credential.KeyBoundMdocCredential
import org.multipaz.mdoc.credential.KeylessMdocCredential
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.DeviceAuth
import org.multipaz.mdoc.devicesigned.buildDeviceNamespaces
import org.multipaz.mdoc.issuersigned.IssuerSignedItem
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.EncryptionParameters
import org.multipaz.mdoc.request.TransactionsInfo
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.ZkRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mdoc.zkp.ZkDocument
import org.multipaz.mdoc.zkp.ZkDocumentData
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.presentment.TransactionData
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64Url
import org.multipaz.util.zlibDeflate
import org.multipaz.util.zlibInflate
import org.multipaz.verification.VerificationUtil
import org.multipaz.webtoken.buildJwt
import kotlin.experimental.xor
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DeviceResponseTest {
    companion object {
        private const val TAG = "DeviceResponseTest"

        private fun deviceRequestInfoForIds(vararg ids: Int): DeviceRequestInfo =
            DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(ids.toList())),
                        purposeHints = emptyMap()
                    )
                )
            )

        private suspend fun transcriptAudience(sessionTranscript: DataItem): String =
            Crypto.digest(
                Algorithm.SHA256,
                Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(sessionTranscript))))
            ).toBase64Url()

        private fun findSubarrayOffset(haystack: ByteArray, needle: ByteArray): Int {
            require(needle.isNotEmpty() && needle.size <= haystack.size)
            outer@ for (i in 0..(haystack.size - needle.size)) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            error("Target byte sequence not found in encoded DeviceResponse")
        }
    }

    private lateinit var storage: Storage
    private lateinit var softwareSecureArea: SoftwareSecureArea
    private lateinit var secureAreaRepository: SecureAreaRepository

    private lateinit var mdlDocument: Document
    private lateinit var mdlCredentialSignature: KeyBoundMdocCredential
    private lateinit var mdlCredentialMac: KeyBoundMdocCredential
    private lateinit var mdlTimeSigned: Instant
    private lateinit var mdlTimeValidityBegin: Instant
    private lateinit var mdlTimeValidityEnd: Instant
    private lateinit var mdlTimeExpectedUpdate: Instant

    private lateinit var photoIdDocument: Document
    private lateinit var photoIdCredential: KeyBoundMdocCredential
    private lateinit var photoIdTimeSigned: Instant
    private lateinit var photoIdTimeValidityBegin: Instant
    private lateinit var photoIdTimeValidityEnd: Instant
    private lateinit var photoIdTimeExpectedUpdate: Instant

    private lateinit var documentStore: DocumentStore
    private lateinit var mdlDsKey: EcPrivateKey
    private lateinit var mdlDsCert: X509Cert

    private lateinit var euPidDsKey: EcPrivateKey
    private lateinit var euPidDocument: Document
    private lateinit var euPidCredential: KeyBoundSdJwtVcCredential
    private lateinit var euPidTimeSigned: Instant
    private lateinit var euPidTimeValidityBegin: Instant
    private lateinit var euPidTimeValidityEnd: Instant

    @BeforeTest
    fun setup() = runTest {
        testUtilSetupCryptoProvider()
        storage = EphemeralStorage()
        softwareSecureArea = SoftwareSecureArea.create(storage)
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(softwareSecureArea)
            .build()
    }

    private suspend fun provisionDocuments() {
        val randomProvider = Random(42)

        documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {
            setDocumentMetadataFactory(TestDocumentMetadata::create)
        }

        val iacaValidFrom = LocalDate.parse("2025-12-01").atStartOfDayIn(TimeZone.UTC)
        val iacaValidUntil = LocalDate.parse("2035-12-01").atStartOfDayIn(TimeZone.UTC)
        val iacaKey = Crypto.createEcPrivateKey(EcCurve.P384)
        val iacaCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.AnonymousExplicit(iacaKey),
            subject = X500Name.fromName("C=US,CN=IACA test key"),
            serial = ASN1Integer.fromRandom(128, random = randomProvider),
            validFrom = iacaValidFrom,
            validUntil = iacaValidUntil,
            issuerAltNameUrl = "https://github.com/openwallet-foundation/multipaz",
            crlUrl = "https://github.com/openwallet-foundation/multipaz/crl"
        )

        mdlDsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val mdlDsValidFrom = iacaValidFrom
        val mdlDsValidUntil = iacaValidUntil
        mdlDsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaKey),
            dsKey = mdlDsKey.publicKey,
            subject = X500Name.fromName("C=US,CN=mDL DS test key"),
            serial = ASN1Integer.fromRandom(128, random = randomProvider),
            validFrom = mdlDsValidFrom,
            validUntil = mdlDsValidUntil
        )
        mdlTimeSigned = LocalDate.parse("2025-12-01").atStartOfDayIn(TimeZone.UTC)
        mdlTimeValidityBegin = mdlTimeSigned
        mdlTimeValidityEnd = mdlTimeSigned + 30.days
        mdlTimeExpectedUpdate = mdlTimeSigned + 20.days

        mdlDocument = documentStore.createDocument()
        mdlCredentialSignature = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = mdlDocument,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(algorithm = Algorithm.ESP256),
            dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(mdlDsCert)), mdlDsKey),
            signedAt = mdlTimeSigned,
            validFrom = mdlTimeValidityBegin,
            validUntil = mdlTimeValidityEnd,
            expectedUpdate = mdlTimeExpectedUpdate,
            domain = "mdoc_sign",
            randomProvider = randomProvider
        )
        mdlCredentialMac = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = mdlDocument,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(algorithm = Algorithm.ECDH_P256),
            dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(mdlDsCert)), mdlDsKey),
            signedAt = mdlTimeSigned,
            validFrom = mdlTimeValidityBegin,
            validUntil = mdlTimeValidityEnd,
            expectedUpdate = mdlTimeExpectedUpdate,
            domain = "mdoc_mac",
            randomProvider = randomProvider
        )

        val photoIdDsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val photoIdDsValidFrom = iacaValidFrom
        val photoIdDsValidUntil = iacaValidUntil
        val photoIdDsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaKey),
            dsKey = photoIdDsKey.publicKey,
            subject = X500Name.fromName("C=US,CN=PhotoId DS test key"),
            serial = ASN1Integer.fromRandom(128, random = randomProvider),
            validFrom = photoIdDsValidFrom,
            validUntil = photoIdDsValidUntil
        )
        photoIdTimeSigned = LocalDate.parse("2025-12-01").atStartOfDayIn(TimeZone.UTC)
        photoIdTimeValidityBegin = photoIdTimeSigned
        photoIdTimeValidityEnd = photoIdTimeSigned + 30.days
        photoIdTimeExpectedUpdate = photoIdTimeSigned + 20.days

        photoIdDocument = documentStore.createDocument()
        photoIdCredential = PhotoID.getDocumentType().createMdocCredentialWithSampleData(
            document = photoIdDocument,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(algorithm = Algorithm.ESP256),
            dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(photoIdDsCert)), photoIdDsKey),
            signedAt = photoIdTimeSigned,
            validFrom = photoIdTimeValidityBegin,
            validUntil = photoIdTimeValidityEnd,
            expectedUpdate = photoIdTimeExpectedUpdate,
            domain = "mdoc_sign",
            randomProvider = randomProvider
        )

        euPidDsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val euPidDsValidFrom = iacaValidFrom
        val euPidDsValidUntil = iacaValidUntil
        val euPidDsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaCert)), iacaKey),
            dsKey = euPidDsKey.publicKey,
            subject = X500Name.fromName("C=US,CN=euPid DS test key"),
            serial = ASN1Integer.fromRandom(128, random = randomProvider),
            validFrom = euPidDsValidFrom,
            validUntil = euPidDsValidUntil
        )
        euPidTimeSigned = LocalDate.parse("2025-12-01").atStartOfDayIn(TimeZone.UTC)
        euPidTimeValidityBegin = euPidTimeSigned
        euPidTimeValidityEnd = euPidTimeSigned + 30.days

        euPidDocument = documentStore.createDocument()
        euPidCredential = EUPersonalID.getDocumentType().createKeyBoundSdJwtVcCredentialWithSampleData(
            document = euPidDocument,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(algorithm = Algorithm.ESP256),
            dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(euPidDsCert)), euPidDsKey),
            signedAt = euPidTimeSigned,
            validFrom = euPidTimeValidityBegin,
            validUntil = euPidTimeValidityEnd,
            domain = "sdjwtvc_sign",
            randomProvider = randomProvider
        )
    }

    private suspend fun createMdlCredentialWithAuthorizations(
        authorizedNamespaces: List<String> = emptyList(),
        authorizedDataElements: Map<String, List<String>> = emptyMap()
    ): KeyBoundMdocCredential {
        val document = documentStore.createDocument()
        return DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = document,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(algorithm = Algorithm.ESP256),
            dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(mdlDsCert)), mdlDsKey),
            signedAt = mdlTimeSigned,
            validFrom = mdlTimeValidityBegin,
            validUntil = mdlTimeValidityEnd,
            expectedUpdate = mdlTimeExpectedUpdate,
            domain = "mdoc_sign",
            randomProvider = Random(42),
            deviceKeyAuthorizedNamespaces = authorizedNamespaces,
            deviceKeyAuthorizedDataElements = authorizedDataElements
        )
    }

    // Test against the test vector in Annex D of 18013-5:2021
    @Test
    fun testAgainstVector2021() = runTest {
        val encodedSessionTranscriptBytes =
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val sessionTranscript = Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor

        val eReaderKey = EcPrivateKeyDoubleCoordinate(
            EcCurve.P256,
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_D.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_X.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_Y.fromHex()
        )

        val deviceResponse = DeviceResponse.fromDataItem(
            Cbor.decode(TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex())
        )
        assertEquals("1.0", deviceResponse.version)
        assertEquals(DeviceResponse.STATUS_OK, deviceResponse.status)
        assertEquals(
            "verify() not yet called",
            assertFailsWith(IllegalStateException::class) {
                val documents = deviceResponse.documents
            }.message
        )
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
            atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC)
        )
        assertEquals(1, deviceResponse.documents.size)

        val d = deviceResponse.documents.first()
        assertEquals(DrivingLicense.MDL_DOCTYPE, d.docType)
        assertEquals(
            """
                {
                  "org.iso.18013.5.1": [24(<< {
                    "digestID": 0,
                    "random": h'8798645b20ea200e19ffabac92624bee6aec63aceedecfb1b80077d22bfc20e9',
                    "elementIdentifier": "family_name",
                    "elementValue": "Doe"
                  } >>), 24(<< {
                    "digestID": 3,
                    "random": h'b23f627e8999c706df0c0a4ed98ad74af988af619b4bb078b89058553f44615d',
                    "elementIdentifier": "issue_date",
                    "elementValue": 1004("2019-10-20")
                  } >>), 24(<< {
                    "digestID": 4,
                    "random": h'c7ffa307e5de921e67ba5878094787e8807ac8e7b5b3932d2ce80f00f3e9abaf',
                    "elementIdentifier": "expiry_date",
                    "elementValue": 1004("2024-10-20")
                  } >>), 24(<< {
                    "digestID": 7,
                    "random": h'26052a42e5880557a806c1459af3fb7eb505d3781566329d0b604b845b5f9e68',
                    "elementIdentifier": "document_number",
                    "elementValue": "123456789"
                  } >>), 24(<< {
                    "digestID": 8,
                    "random": h'd094dad764a2eb9deb5210e9d899643efbd1d069cc311d3295516ca0b024412d',
                    "elementIdentifier": "portrait",
                    "elementValue": h'ffd8ffe000104a46494600010101009000900000ffdb004300130d0e110e0c13110f11151413171d301f1d1a1a1d3a2a2c2330453d4947443d43414c566d5d4c51685241435f82606871757b7c7b4a5c869085778f6d787b76ffdb0043011415151d191d381f1f38764f434f7676767676767676767676767676767676767676767676767676767676767676767676767676767676767676767676767676ffc00011080018006403012200021101031101ffc4001b00000301000301000000000000000000000005060401020307ffc400321000010303030205020309000000000000010203040005110612211331141551617122410781a1163542527391b2c1f1ffc4001501010100000000000000000000000000000001ffc4001a110101010003010000000000000000000000014111213161ffda000c03010002110311003f00a5bbde22da2329c7d692bc7d0d03f52cfb0ff75e7a7ef3e7709723a1d0dae146ddfbb3c039ce07ad2bd47a7e32dbb8dd1d52d6ef4b284f64a480067dfb51f87ffb95ff00eb9ff14d215de66af089ce44b7dbde9cb6890a2838eddf18078f7add62d411ef4db9b10a65d6b95a147381ea0d495b933275fe6bba75c114104a8ba410413e983dff004f5af5d34b4b4cde632d0bf1fd1592bdd91c6411f3934c2fa6af6b54975d106dcf4a65ae56e856001ebc03c7ce29dd9eef1ef10fc447dc9da76ad2aee93537a1ba7e4f70dd8eff0057c6dffb5e1a19854a83758e54528750946ec6704850cd037bceb08b6d7d2cc76d3317fc7b5cc04fb6707269c5c6e0c5b60ae549242123b0e493f602a075559e359970d98db89525456b51c951c8afa13ea8e98e3c596836783d5c63f5a61a99fdb7290875db4be88ab384bbbbbfc7183fdeaa633e8951db7da396dc48524fb1a8bd611a5aa2a2432f30ab420a7a6d3240c718cf031fa9ef4c9ad550205aa02951df4a1d6c8421b015b769db8c9229837ea2be8b1b0d39d0eba9c51484efdb8c0efd8d258daf3c449699f2edbd4584e7af9c64e3f96b9beb28d4ac40931e6478c8e76a24a825449501d867d2b1dcdebae99b9c752ae4ecd6dde4a179c1c1e460938f9149ef655e515c03919a289cb3dca278fb7bf177f4faa829dd8ce3f2ac9a7ecde490971fafd7dce15eed9b71c018c64fa514514b24e8e4f8c5c9b75c1e82579dc1233dfec08238f6add62d391acc1c5256a79e706d52d431c7a0145140b9fd149eb3a60dc5e88cbbc2da092411e9dc71f39a7766b447b344e847dcac9dcb5abba8d145061d43a6fcf1e65cf15d0e90231d3dd9cfe62995c6dcc5ca12a2c904a15f71dd27d451453e09d1a21450961cbb3ea8a956433b781f1ce33dfed54f0e2b50a2b71d84ed6db18028a28175f74fc6bda105c529a791c25c4f3c7a11f71586268f4a66b726e33de9ea6f1b52b181c760724e47b514520a5a28a283ffd9'
                  } >>), 24(<< {
                    "digestID": 9,
                    "random": h'4599f81beaa2b20bd0ffcc9aa03a6f985befab3f6beaffa41e6354cdb2ab2ce4',
                    "elementIdentifier": "driving_privileges",
                    "elementValue": [
                      {
                        "vehicle_category_code": "A",
                        "issue_date": 1004("2018-08-09"),
                        "expiry_date": 1004("2024-10-20")
                      },
                      {
                        "vehicle_category_code": "B",
                        "issue_date": 1004("2017-02-23"),
                        "expiry_date": 1004("2024-10-20")
                      }
                    ]
                  } >>)]
                }
            """.trimIndent(),
            Cbor.toDiagnostics(
                d.issuerNamespaces.toDataItem(),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )

        assertTrue(d.deviceAuth is DeviceAuth.Mac)
        assertEquals(
            "{}",
            Cbor.toDiagnostics(
                d.deviceNamespaces.toDataItem(),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(0, d.deviceNamespaces.data.size)

        // Check the [mso] and [issuerCertChain] can be accessed
        assertEquals(DrivingLicense.MDL_DOCTYPE, d.mso.docType)
        assertEquals(1, d.issuerCertChain.certificates.size)
        assertEquals(
            """
                -----BEGIN CERTIFICATE-----
                MIIB7zCCAZWgAwIBAgIUPEQW7teE87QT5I9W8HWr+m2H64QwCgYIKoZIzj0EAwIwIzEUMBIGA1UE
                AwwLdXRvcGlhIGlhY2ExCzAJBgNVBAYTAlVTMB4XDTIwMTAwMTAwMDAwMFoXDTIxMTAwMTAwMDAw
                MFowITESMBAGA1UEAwwJdXRvcGlhIGRzMQswCQYDVQQGEwJVUzBZMBMGByqGSM49AgEGCCqGSM49
                AwEHA0IABKznq3NA5dlkjFpyqab1Z0XHqtQ2oDpD7+p3tfp7iPAZfVfYmD4bN9OlOfTViDZeOMu/
                W5TWjFR7W8hzHc0vFGujgagwgaUwHgYDVR0SBBcwFYETZXhhbXBsZUBleGFtcGxlLmNvbTAcBgNV
                HR8EFTATMBGgD6ANggtleGFtcGxlLmNvbTAdBgNVHQ4EFgQUFOKQF6bDViH/x6aGt7ctsGzRI1Ew
                HwYDVR0jBBgwFoAUVPojg6BMKODZMHkiYcgMSIHSwAswDgYDVR0PAQH/BAQDAgeAMBUGA1UdJQEB
                /wQLMAkGByiBjF0FAQIwCgYIKoZIzj0EAwIDSAAwRQIhAJdxerkBZ0DI17zapJSmLAU7vezOE4PB
                rKcq0I28BMuyAiA7rYWcE6Y8bRrWfYFNQ+JCXK+Q1CJCLASo7gMEwNOmjQ==
                -----END CERTIFICATE-----
            """.trimIndent(),
            d.issuerCertChain.certificates[0].toPem().replace("\r\n", "\n").trim()
        )
    }

    @Test
    fun emptyResponse() = runTest {
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {}
        assertEquals(
            """
                {
                  "version": "1.0",
                  "status": 0
                }
            """.trimIndent(),
            Cbor.toDiagnostics(deviceResponse.toDataItem(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
        assertEquals("1.0", deviceResponse.version)

        val deviceResponse2 = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_GENERAL_ERROR,
        ) {}
        assertEquals(
            """
                {
                  "version": "1.0",
                  "status": 10
                }
            """.trimIndent(),
            Cbor.toDiagnostics(deviceResponse2.toDataItem(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
    }

    @Test
    fun simpleDocumentEcdsa() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = mdlCredentialSignature,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "age_over_18",
                        intentToRetain = false
                    ),
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    ),
                )
            )
        }
        assertEquals("1.0", deviceResponse.version)

        // Check we can call verify() on an instance we created ourselves
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )

        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())

        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        drParsed.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )
        assertEquals(drParsed, deviceResponse)

        // Check that we're putting -7 (ES256) in as the COSE algorithm, not -9 (ESP256)
        val deviceSignature = drParsed.documents[0].deviceAuth as DeviceAuth.Signature
        assertEquals(
            Algorithm.ES256.coseAlgorithmIdentifier,
            deviceSignature.signature.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )

        // Bunch of negative tests to ensure we check signatures and other things correctly.
        checkPoisonIssueSignature(encodedDeviceResponse, drParsed.documents[0].issuerAuth.signature, sessionTranscript)
        checkPoisonDeviceSignature(encodedDeviceResponse, deviceSignature.signature.signature, sessionTranscript)
        checkPoisonIssuerSignedItem(
            encodedDeviceResponse,
            drParsed.documents[0].issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["given_name"]!!.random.toByteArray(),
            sessionTranscript
        )
        checkChangedDoctype(encodedDeviceResponse, sessionTranscript)
        checkDeviceSignatureByOtherKey(encodedDeviceResponse, sessionTranscript)
        checkDeviceAuthenticationWithOtherDoctype(encodedDeviceResponse, sessionTranscript)
        checkDeviceKeyWithInconsistentKeyType(encodedDeviceResponse)
        checkReencodedIssuerSignedItem(encodedDeviceResponse, sessionTranscript)
        checkTruncatedDigest(encodedDeviceResponse, sessionTranscript)
        checkMissingDigestId(encodedDeviceResponse, sessionTranscript)
        checkSha1DigestAlgorithm(encodedDeviceResponse)
    }

    private suspend fun checkPoisonIssueSignature(
        encodedDeviceResponse: ByteArray,
        sigBytes: ByteArray,
        sessionTranscript: DataItem
    ) {
        val dr = encodedDeviceResponse.copyOf()
        val offset = findSubarrayOffset(dr, sigBytes)
        dr[offset + 16] = dr[offset + 16].xor(0xff.toByte())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(dr))
        assertEquals(
            "Signature verification failed",
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = mdlTimeValidityBegin
                )
            }.cause!!.message
        )
    }

    private suspend fun checkPoisonDeviceSignature(
        encodedDeviceResponse: ByteArray,
        sigBytes: ByteArray,
        sessionTranscript: DataItem
    ) {
        val dr = encodedDeviceResponse.copyOf()
        val offset = findSubarrayOffset(dr, sigBytes)
        dr[offset + 16] = dr[offset + 16].xor(0xff.toByte())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(dr))
        assertEquals(
            "Signature verification failed",
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = mdlTimeValidityBegin
                )
            }.cause!!.message
        )
    }

    private suspend fun checkPoisonIssuerSignedItem(
        encodedDeviceResponse: ByteArray,
        randomBytes: ByteArray,
        sessionTranscript: DataItem
    ) {
        val dr = encodedDeviceResponse.copyOf()
        val offset = findSubarrayOffset(dr, randomBytes)
        dr[offset + 8] = dr[offset + 8].xor(0xff.toByte())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(dr))
        assertEquals(
            "Digest mismatch for data element given_name in org.iso.18013.5.1",
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = mdlTimeValidityBegin
                )
            }.message
        )
    }

    private suspend fun checkChangedDoctype(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem
    ) {
        val dr = encodedDeviceResponse.copyOf()
        // By inspection we know the docType in DeviceResponse is at offset 42. Poison it and
        // check that we catch if it doesn't match with the one in the MSO.
        assertEquals('o'.code.toByte(), dr[42])
        dr[42] = 'O'.code.toByte()
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(dr))
        assertEquals(
            "Mismatch between docType in document and MSO",
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = mdlTimeValidityBegin
                )
            }.message
        )
    }

    private suspend fun assertVerifyFails(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem,
        expectedMessage: String
    ) {
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        assertEquals(
            expectedMessage,
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = mdlTimeValidityBegin
                )
            }.message
        )
    }

    private fun firstDocument(deviceResponse: DataItem): CborMap =
        deviceResponse["documents"].asArray[0] as CborMap

    // Signs DeviceAuthentication for the given docType with the given key and puts the result
    // in place of the device signature of the first document.
    private suspend fun replaceDeviceSignature(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem,
        docType: String,
        signingKey: AsymmetricKey
    ): ByteArray {
        val deviceResponse = Cbor.decode(encodedDeviceResponse)
        val deviceSigned = firstDocument(deviceResponse)["deviceSigned"]
        val deviceAuth = deviceSigned["deviceAuth"] as CborMap
        val deviceSignature = deviceAuth["deviceSignature"].asCoseSign1
        val deviceAuthentication = buildCborArray {
            add("DeviceAuthentication")
            add(sessionTranscript)
            add(docType)
            add(deviceSigned["nameSpaces"])
        }
        deviceAuth.items[Tstr("deviceSignature")] = Cose.coseSign1Sign(
            signingKey = signingKey,
            message = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(deviceAuthentication)))),
            includeMessageInPayload = false,
            protectedHeaders = deviceSignature.protectedHeaders,
            unprotectedHeaders = deviceSignature.unprotectedHeaders
        ).toDataItem()
        return Cbor.encode(deviceResponse)
    }

    private suspend fun checkDeviceSignatureByOtherKey(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem
    ) {
        // Signing again with the device key gives a response which verifies...
        val deviceKey = AsymmetricKey.anonymous(mdlCredentialSignature.secureArea, mdlCredentialSignature.alias)
        DeviceResponse.fromDataItem(Cbor.decode(
            replaceDeviceSignature(encodedDeviceResponse, sessionTranscript, DrivingLicense.MDL_DOCTYPE, deviceKey)
        )).verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )

        // ... but the same signature made with a key other than the one in the MSO is rejected.
        val otherKey = AsymmetricKey.anonymous(Crypto.createEcPrivateKey(EcCurve.P256))
        assertVerifyFails(
            replaceDeviceSignature(encodedDeviceResponse, sessionTranscript, DrivingLicense.MDL_DOCTYPE, otherKey),
            sessionTranscript,
            "Device authentication signature failed to verify"
        )
    }

    private suspend fun checkDeviceAuthenticationWithOtherDoctype(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem
    ) {
        // A signature by the device key over DeviceAuthentication naming another docType than
        // the one of the document is rejected.
        val deviceKey = AsymmetricKey.anonymous(mdlCredentialSignature.secureArea, mdlCredentialSignature.alias)
        assertVerifyFails(
            replaceDeviceSignature(encodedDeviceResponse, sessionTranscript, PhotoID.PHOTO_ID_DOCTYPE, deviceKey),
            sessionTranscript,
            "Device authentication signature failed to verify"
        )
    }

    // Applies the given change to the MSO of the first document and signs the MSO again with
    // the document signing key, so the change is the only difference.
    private suspend fun changeMso(
        encodedDeviceResponse: ByteArray,
        change: suspend (CborMap) -> Unit
    ): ByteArray {
        val deviceResponse = Cbor.decode(encodedDeviceResponse)
        val issuerSigned = firstDocument(deviceResponse)["issuerSigned"] as CborMap
        val issuerAuth = issuerSigned["issuerAuth"].asCoseSign1
        val mso = Cbor.decode(Cbor.decode(issuerAuth.payload!!).asTagged.asBstr) as CborMap
        change(mso)
        issuerSigned.items[Tstr("issuerAuth")] = Cose.coseSign1Sign(
            signingKey = AsymmetricKey.anonymous(mdlDsKey),
            message = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso)))),
            includeMessageInPayload = true,
            protectedHeaders = issuerAuth.protectedHeaders,
            unprotectedHeaders = issuerAuth.unprotectedHeaders
        ).toDataItem()
        return Cbor.encode(deviceResponse)
    }

    private fun givenNameDigestId(encodedDeviceResponse: ByteArray): DataItem {
        val issuerSigned = firstDocument(Cbor.decode(encodedDeviceResponse))["issuerSigned"]
        return issuerSigned["nameSpaces"][DrivingLicense.MDL_NAMESPACE].asArray
            .map { it.asTaggedEncodedCbor }
            .first { it["elementIdentifier"].asTstr == "given_name" }["digestID"]
    }

    private suspend fun checkDeviceKeyWithInconsistentKeyType(encodedDeviceResponse: ByteArray) {
        // RFC 9053 section 7.1 requires the key type and the curve of a key to be consistent.
        // Mark the P-256 device key in the MSO as an OKP key and check it's rejected.
        val dr = changeMso(encodedDeviceResponse) { mso ->
            val deviceKey = mso["deviceKeyInfo"]["deviceKey"] as CborMap
            deviceKey.items[Cose.COSE_KEY_KTY.toDataItem()] = Cose.COSE_KEY_TYPE_OKP.toDataItem()
        }
        assertEquals(
            "Unsupported curve P256",
            assertFailsWith(IllegalArgumentException::class) {
                DeviceResponse.fromDataItem(Cbor.decode(dr))
            }.message
        )
    }

    private suspend fun checkReencodedIssuerSignedItem(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem
    ) {
        // Write the digestID in the IssuerSignedItem for given_name in four bytes instead of the
        // shortest form. The item decodes to the same values but its bytes, and so its digest,
        // are no longer the ones in the MSO.
        val deviceResponse = Cbor.decode(encodedDeviceResponse)
        val issuerSigned = firstDocument(deviceResponse)["issuerSigned"]
        val issuerSignedItems = issuerSigned["nameSpaces"][DrivingLicense.MDL_NAMESPACE] as CborArray
        val index = issuerSignedItems.items.indexOfFirst {
            it.asTaggedEncodedCbor["elementIdentifier"].asTstr == "given_name"
        }
        val issuerSignedItem = issuerSignedItems.items[index].asTaggedEncodedCbor as CborMap
        assertContentEquals(issuerSignedItems.items[index].asTagged.asBstr, Cbor.encode(issuerSignedItem))
        val digestId = issuerSignedItem["digestID"].asNumber
        issuerSignedItem.items[Tstr("digestID")] = RawCbor(
            byteArrayOf(0x1a) + ByteArray(4) { (digestId shr (24 - 8 * it)).toByte() }
        )
        issuerSignedItems.items[index] = Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(issuerSignedItem)))
        assertVerifyFails(
            Cbor.encode(deviceResponse),
            sessionTranscript,
            "Digest mismatch for data element given_name in org.iso.18013.5.1"
        )
    }

    private suspend fun checkTruncatedDigest(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem
    ) {
        // Signing the unchanged MSO again gives a response which verifies...
        DeviceResponse.fromDataItem(Cbor.decode(changeMso(encodedDeviceResponse) {})).verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )

        // ... but cutting the digest for given_name to its first 20 bytes is caught.
        val digestId = givenNameDigestId(encodedDeviceResponse)
        assertVerifyFails(
            changeMso(encodedDeviceResponse) { mso ->
                val digests = mso["valueDigests"][DrivingLicense.MDL_NAMESPACE] as CborMap
                digests.items[digestId] = Bstr(digests[digestId].asBstr.copyOf(20))
            },
            sessionTranscript,
            "Invalid MSO: MSO digest for digestID ${digestId.asNumber} in namespace 'org.iso.18013.5.1' has length 20 bytes, expected 32 bytes for SHA256"
        )
    }

    private suspend fun checkMissingDigestId(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem
    ) {
        // An MSO without the digest ID of a disclosed data element is caught.
        val digestId = givenNameDigestId(encodedDeviceResponse)
        assertVerifyFails(
            changeMso(encodedDeviceResponse) { mso ->
                (mso["valueDigests"][DrivingLicense.MDL_NAMESPACE] as CborMap).items.remove(digestId)
            },
            sessionTranscript,
            "No digests ID for data element given_name in org.iso.18013.5.1"
        )
    }

    private suspend fun checkSha1DigestAlgorithm(encodedDeviceResponse: ByteArray) {
        // An MSO using SHA-1 is rejected, even with correct SHA-1 digests for the disclosed
        // data elements.
        val issuerSigned = firstDocument(Cbor.decode(encodedDeviceResponse))["issuerSigned"]
        val issuerSignedItems = issuerSigned["nameSpaces"][DrivingLicense.MDL_NAMESPACE].asArray
        val dr = changeMso(encodedDeviceResponse) { mso ->
            mso.items[Tstr("digestAlgorithm")] = Tstr("SHA-1")
            val digests = mso["valueDigests"][DrivingLicense.MDL_NAMESPACE] as CborMap
            for (issuerSignedItemBytes in issuerSignedItems) {
                digests.items[issuerSignedItemBytes.asTaggedEncodedCbor["digestID"]] =
                    Bstr(Crypto.digest(Algorithm.INSECURE_SHA1, Cbor.encode(issuerSignedItemBytes)))
            }
        }
        assertEquals(
            "Unsupported digest algorithm SHA-1",
            assertFailsWith(IllegalArgumentException::class) {
                DeviceResponse.fromDataItem(Cbor.decode(dr))
            }.message
        )
    }

    // The SessionTranscript for OpenID4VP according to OpenID4VP 1.0 Appendix B.2.6.
    private suspend fun openId4VpSessionTranscript(
        handover: String,
        handoverInfo: DataItem
    ): DataItem {
        val handoverInfoHash = Crypto.digest(Algorithm.SHA256, Cbor.encode(handoverInfo))
        return buildCborArray {
            add(Simple.NULL) // DeviceEngagementBytes
            add(Simple.NULL) // EReaderKeyBytes
            addCborArray {
                add(handover)
                add(handoverInfoHash)
            }
        }
    }

    // OpenID4VP 1.0 B.2.6.1, invocation via redirects.
    private suspend fun redirectSessionTranscript(
        audience: String,
        nonce: String,
        jwkThumbprint: DataItem,
        responseUri: String
    ) = openId4VpSessionTranscript(
        handover = "OpenID4VPHandover",
        handoverInfo = buildCborArray {
            add(audience)
            add(nonce)
            add(jwkThumbprint)
            add(responseUri)
        }
    )

    // OpenID4VP 1.0 B.2.6.2, invocation via the Digital Credentials API.
    private suspend fun dcApiSessionTranscript(
        origin: String,
        nonce: String,
        jwkThumbprint: DataItem
    ) = openId4VpSessionTranscript(
        handover = "OpenID4VPDCAPIHandover",
        handoverInfo = buildCborArray {
            add(origin)
            add(nonce)
            add(jwkThumbprint)
        }
    )

    private suspend fun encodedResponseFor(sessionTranscript: DataItem): ByteArray {
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = mdlCredentialSignature,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                )
            )
        }
        return Cbor.encode(deviceResponse.toDataItem())
    }

    @Test
    fun openId4VpHandovers() = runTest {
        provisionDocuments()
        val audience = "x509_san_dns:verifier.example"
        val responseUri = "https://verifier.example/response"
        val origin = "https://verifier.example"
        val nonce = "mG3v7Rz0qLw5Hc1TfYb8Nd"
        val thumbprint = Bstr(
            Crypto.createEcPrivateKey(EcCurve.P256).publicKey.toJwkThumbprint(Algorithm.SHA256).toByteArray()
        )
        val otherThumbprint = Bstr(
            Crypto.createEcPrivateKey(EcCurve.P256).publicKey.toJwkThumbprint(Algorithm.SHA256).toByteArray()
        )
        val redirect = redirectSessionTranscript(audience, nonce, thumbprint, responseUri)
        val dcApi = dcApiSessionTranscript(origin, nonce, thumbprint)

        // A response made for the SessionTranscript the verifier expects verifies...
        for (sessionTranscript in listOf(redirect, dcApi)) {
            DeviceResponse.fromDataItem(Cbor.decode(encodedResponseFor(sessionTranscript))).verify(
                sessionTranscript = sessionTranscript,
                atTime = mdlTimeValidityBegin
            )
        }

        // ... and one made for a SessionTranscript with one value changed is rejected.
        val mismatches = listOf(
            redirect to redirectSessionTranscript(audience, "other-nonce", thumbprint, responseUri),
            redirect to redirectSessionTranscript(audience, nonce, thumbprint, "https://verifier.example/other"),
            redirect to redirectSessionTranscript(audience, nonce, otherThumbprint, responseUri),
            redirect to redirectSessionTranscript(audience, nonce, Simple.NULL, responseUri),
            redirect to dcApiSessionTranscript(origin, nonce, thumbprint),
            dcApi to redirectSessionTranscript(audience, nonce, thumbprint, responseUri),
            dcApi to dcApiSessionTranscript("origin:$origin", nonce, thumbprint),
            dcApi to dcApiSessionTranscript("https://other.example", nonce, thumbprint),
        )
        for ((verifierSessionTranscript, responseSessionTranscript) in mismatches) {
            assertVerifyFails(
                encodedResponseFor(responseSessionTranscript),
                verifierSessionTranscript,
                "Device authentication signature failed to verify"
            )
        }
    }

    @Test
    fun simpleDocumentMac() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)

        // addDocument() should fail if eReaderKey isn't passed
        assertEquals(
            "Trying to add a document with MACing but eReaderKey not specified",
            assertFailsWith(IllegalStateException::class) {
                val deviceResponse = buildDeviceResponse(
                    sessionTranscript = sessionTranscript,
                    status = DeviceResponse.STATUS_OK,
                ) {
                    addDocument(
                        credential = mdlCredentialMac,
                        requestedClaims = listOf(
                            MdocRequestedClaim(
                                docType = DrivingLicense.MDL_DOCTYPE,
                                namespaceName = DrivingLicense.MDL_NAMESPACE,
                                dataElementName =  "age_over_18",
                                intentToRetain = false
                            ),
                        )
                    )
                }
            }.message
        )
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            eReaderKey = eReaderKey.publicKey,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = mdlCredentialMac,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName =  "age_over_18",
                        intentToRetain = false
                    ),
                )
            )
        }
        assertEquals("1.0", deviceResponse.version)

        // Check we can call verify() on an instance we created ourselves
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
            atTime = mdlTimeValidityBegin
        )

        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())

        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))

        // verify() should fail if eReaderKey isn't passed
        assertEquals(
            "Device authentication is MAC but eReaderKey was not set",
            assertFailsWith(IllegalArgumentException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = mdlTimeValidityBegin
                )
            }.message
        )
        drParsed.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
            atTime = mdlTimeValidityBegin
        )
        assertEquals(drParsed, deviceResponse)

        checkPoisonDeviceMac(
            encodedDeviceResponse,
            (drParsed.documents[0].deviceAuth as DeviceAuth.Mac).mac.tag,
            sessionTranscript,
            eReaderKey
        )
    }

    private suspend fun checkPoisonDeviceMac(
        encodedDeviceResponse: ByteArray,
        macBytes: ByteArray,
        sessionTranscript: DataItem,
        eReaderKey: EcPrivateKey
    ) {
        val dr = encodedDeviceResponse.copyOf()
        Logger.iCbor(TAG, "dr", dr)
        val offset = findSubarrayOffset(dr, macBytes)
        dr[offset + 16] = dr[offset + 16].xor(0xff.toByte())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(dr))
        assertEquals(
            "Device authentication MAC failed to verify",
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                    atTime = mdlTimeValidityBegin
                )
            }.message
        )
    }

    @Test
    fun simpleDocumentEcdsaTimesNotValid() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = mdlCredentialSignature,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "age_over_18",
                        intentToRetain = false
                    ),
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    ),
                )
            )
        }
        assertEquals("1.0", deviceResponse.version)
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        assertEquals(
            "MSO is not yet valid",
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = mdlTimeValidityBegin - 10.seconds
                )
            }.message
        )
        assertEquals(
            "MSO is not valid anymore",
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = mdlTimeValidityEnd + 10.seconds
                )
            }.message
        )
    }

    @Test
    fun responseWithTwoDocuments() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = mdlCredentialSignature,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "age_over_18",
                        intentToRetain = false
                    ),
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    ),
                )
            )
            addDocument(
                credential = photoIdCredential,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = PhotoID.ISO_23220_2_NAMESPACE,
                        dataElementName = "family_name",
                        intentToRetain = false
                    ),
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = PhotoID.ISO_23220_2_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    ),
                )
            )
        }
        assertEquals("1.0", deviceResponse.version)
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        drParsed.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )
        assertEquals(drParsed, deviceResponse)

        assertEquals(2, drParsed.documents.size)
        assertEquals(DrivingLicense.MDL_DOCTYPE, drParsed.documents[0].docType)
        assertEquals(
            """
                {
                  "org.iso.18013.5.1": [24(<< {
                    "digestID": 20,
                    "random": h'a8617f13f8a21673a1200718d435e7cb',
                    "elementIdentifier": "age_over_18",
                    "elementValue": true
                  } >>), 24(<< {
                    "digestID": 35,
                    "random": h'4979e7b3d99311fe288479d603befbfe',
                    "elementIdentifier": "given_name",
                    "elementValue": "Erika"
                  } >>)]
                }
            """.trimIndent(),
            Cbor.toDiagnostics(
                drParsed.documents[0].issuerNamespaces.toDataItem(),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(PhotoID.PHOTO_ID_DOCTYPE, drParsed.documents[1].docType)
        assertEquals(
            """
                {
                  "org.iso.23220.1": [24(<< {
                    "digestID": 29,
                    "random": h'6b48169d22735cace8eb8879a69be443',
                    "elementIdentifier": "family_name",
                    "elementValue": "Mustermann"
                  } >>), 24(<< {
                    "digestID": 38,
                    "random": h'8bb7547c4b90bf20da946c40048bcc0b',
                    "elementIdentifier": "given_name",
                    "elementValue": "Erika"
                  } >>)]
                }
            """.trimIndent(),
            Cbor.toDiagnostics(
                drParsed.documents[1].issuerNamespaces.toDataItem(),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )
    }

    @Test
    fun deviceResponseDocumentErrors() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_GENERAL_ERROR,
        ) {
            addDocumentError(mapOf(
                DrivingLicense.MDL_DOCTYPE to DeviceResponse.ERROR_CODE_DATA_NOT_RETURNED
            ))
            // Negative numbers are application specific.
            addDocumentError(mapOf(
                PhotoID.PHOTO_ID_DOCTYPE to -42
            ))
        }
        assertEquals("1.0", deviceResponse.version)
        assertEquals(
            """
                {
                  "version": "1.0",
                  "status": 10,
                  "documentErrors": [
                    {
                      "org.iso.18013.5.1.mDL": 0
                    },
                    {
                      "org.iso.23220.photoid.1": -42
                    }
                  ]
                }
            """.trimIndent(),
            Cbor.toDiagnostics(deviceResponse.toDataItem(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
    }

    @Test
    fun responseWithDataElementErrors() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = mdlCredentialSignature,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "age_over_18",
                        intentToRetain = false
                    ),
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    ),
                ),
                errors = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "family_name" to DeviceResponse.ERROR_CODE_DATA_NOT_RETURNED,
                        "birth_date" to -43
                    )
                )
            )
        }
        assertEquals("1.0", deviceResponse.version)
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        drParsed.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )
        assertEquals(drParsed, deviceResponse)

        assertEquals(1, drParsed.documents.size)
        assertEquals(DrivingLicense.MDL_DOCTYPE, drParsed.documents[0].docType)
        assertEquals(
            """
                {
                  "org.iso.18013.5.1": [24(<< {
                    "digestID": 20,
                    "random": h'a8617f13f8a21673a1200718d435e7cb',
                    "elementIdentifier": "age_over_18",
                    "elementValue": true
                  } >>), 24(<< {
                    "digestID": 35,
                    "random": h'4979e7b3d99311fe288479d603befbfe',
                    "elementIdentifier": "given_name",
                    "elementValue": "Erika"
                  } >>)]
                }
            """.trimIndent(),
            Cbor.toDiagnostics(
                drParsed.documents[0].issuerNamespaces.toDataItem(),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(
            mapOf(
                DrivingLicense.MDL_NAMESPACE to mapOf(
                    "family_name" to DeviceResponse.ERROR_CODE_DATA_NOT_RETURNED,
                    "birth_date" to -43
                )
            ),
            drParsed.documents[0].errors
        )
    }

    @Test
    fun zkDocuments() = runTest {
        provisionDocuments()
        val zkTimestamp = LocalDate.parse("2025-12-01").atStartOfDayIn(TimeZone.UTC)
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addZkDocument(
                ZkDocument(
                    documentData = ZkDocumentData(
                        zkSystemSpecId = "id1",
                        docType = DrivingLicense.MDL_DOCTYPE,
                        timestamp = zkTimestamp,
                        issuerSigned = mapOf(
                            DrivingLicense.MDL_NAMESPACE to mapOf(
                                "age_over_18" to true.toDataItem(),
                                "issuing_authority" to "Elbonia DMV".toDataItem()
                            )
                        ),
                        deviceSigned = mapOf(),
                        msoX5chain = null
                    ),
                    proof = ByteString(1, 2, 3)
                )
            )
        }
        assertEquals("1.0", deviceResponse.version)
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        assertEquals(drParsed, deviceResponse)
        assertEquals(
            """
                {
                  "version": "1.0",
                  "status": 0,
                  "zkDocuments": [
                    {
                      "proof": h'010203',
                      "documentData": 24(<< {
                        "zkSystemId": "id1",
                        "docType": "org.iso.18013.5.1.mDL",
                        "timestamp": 0("2025-12-01T00:00:00Z"),
                        "issuerSigned": {
                          "org.iso.18013.5.1": [
                            {
                              "elementIdentifier": "age_over_18",
                              "elementValue": true
                            },
                            {
                              "elementIdentifier": "issuing_authority",
                              "elementValue": "Elbonia DMV"
                            }
                          ]
                        }
                      } >>)
                    }
                  ]
                }
            """.trimIndent(),
            Cbor.toDiagnostics(
                encodedDeviceResponse,
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )
    }

    @Test
    fun encryptedDocuments() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }

        val encryptionKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val encryptionParameters = EncryptionParameters.fromValues(
            recipientPublicKey = encryptionKey.publicKey,
        )

        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addEncryptedDocuments(
                encryptionParameters = encryptionParameters,
                docRequestIds = listOf(1)
            ) {
                addDocument(
                    credential = mdlCredentialSignature,
                    requestedClaims = listOf(
                        MdocRequestedClaim(
                            docType = DrivingLicense.MDL_DOCTYPE,
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "age_over_18",
                            intentToRetain = false
                        ),
                        MdocRequestedClaim(
                            docType = DrivingLicense.MDL_DOCTYPE,
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "given_name",
                            intentToRetain = false
                        ),
                    ),
                )
            }
        }
        assertEquals("1.0", deviceResponse.version)
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        assertEquals(drParsed, deviceResponse)

        drParsed.verify(sessionTranscript)
        assertEquals(0, drParsed.documents.size)
        assertEquals(0, drParsed.zkDocuments.size)
        assertEquals(1, drParsed.encryptedDocuments.size)

        // Check that EncryptedDocuments.decrypt() verifies the decrypted documents
        assertEquals(
            "MSO is not yet valid",
            assertFailsWith(IllegalStateException::class) {
                val encDocs = drParsed.encryptedDocuments[0].decrypt(
                    recipientPrivateKey = AsymmetricKey.AnonymousExplicit(encryptionKey),
                    encryptionParameters = encryptionParameters,
                    sessionTranscript = sessionTranscript,
                    atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC)
                )
            }.cause!!.message
        )

        val encDocs = drParsed.encryptedDocuments[0].decrypt(
            recipientPrivateKey = AsymmetricKey.AnonymousExplicit(encryptionKey),
            encryptionParameters = encryptionParameters,
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )
        assertEquals(1, encDocs.documents.size)
        assertEquals(0, encDocs.zkDocuments.size)
        assertEquals(0, encDocs.otherDocuments.size)

        assertEquals(DrivingLicense.MDL_DOCTYPE, encDocs.documents[0].docType)
        assertEquals(
            """
                {
                  "org.iso.18013.5.1": [24(<< {
                    "digestID": 20,
                    "random": h'a8617f13f8a21673a1200718d435e7cb',
                    "elementIdentifier": "age_over_18",
                    "elementValue": true
                  } >>), 24(<< {
                    "digestID": 35,
                    "random": h'4979e7b3d99311fe288479d603befbfe',
                    "elementIdentifier": "given_name",
                    "elementValue": "Erika"
                  } >>)]
                }
            """.trimIndent(),
            Cbor.toDiagnostics(
                encDocs.documents[0].issuerNamespaces.toDataItem(),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun encryptedDocumentsWithOtherDocument() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }

        val encryptionKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val encryptionParameters = EncryptionParameters.fromValues(
            recipientPublicKey = encryptionKey.publicKey,
        )

        val sdJwtVc = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val encSessionTranscript = EncryptedDocuments.buildSessionTranscript(
            originalSessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters
        )
        val encSessionTranscriptBytes = Tagged(
            tagNumber = Tagged.ENCODED_CBOR,
            taggedItem = Bstr(Cbor.encode(encSessionTranscript))
        )
        val compactSerialization = sdJwtVc
            .filter(
                pathsToInclude = listOf(
                    buildJsonArray { add(JsonPrimitive("given_name")) },
                    buildJsonArray { add(JsonPrimitive("family_name")) },
                    buildJsonArray { add(JsonPrimitive("place_of_birth")); add(JsonPrimitive("country")) }
                )
            )
            .present(
            signingKey = AsymmetricKey.AnonymousSecureAreaBased(
                alias = euPidCredential.alias,
                secureArea = euPidCredential.secureArea,
                keyInfo = euPidCredential.secureArea.getKeyInfo(euPidCredential.alias),
            ),
            nonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(encSessionTranscriptBytes)).toBase64Url(),
            audience = Crypto.digest(Algorithm.SHA256, Cbor.encode(encSessionTranscriptBytes)).toBase64Url(),
            creationTime = euPidTimeSigned,
        ).compactSerialization

        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addEncryptedDocuments(
                encryptionParameters = encryptionParameters,
                docRequestIds = listOf(1)
            ) {
                addOtherDocument(
                    OtherDocument(
                        docFormat = "dc+sd-jwt",
                        data = ByteString(compactSerialization.encodeToByteArray().zlibDeflate())
                    )
                )
            }
        }
        assertEquals("1.0", deviceResponse.version)
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        assertEquals(drParsed, deviceResponse)

        drParsed.verify(sessionTranscript)
        assertEquals(0, drParsed.documents.size)
        assertEquals(0, drParsed.zkDocuments.size)
        assertEquals(1, drParsed.encryptedDocuments.size)

        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
        }

        // Check that EncryptedDocuments.decrypt() verifies the decrypted documents
        assertEquals(
            "Failed verification of creationTime",
            assertFailsWith(IllegalStateException::class) {
                val encDocs = drParsed.encryptedDocuments[0].decrypt(
                    recipientPrivateKey = AsymmetricKey.AnonymousExplicit(encryptionKey),
                    encryptionParameters = encryptionParameters,
                    sessionTranscript = sessionTranscript,
                    atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
                    deviceRequest = deviceRequest,
                )
            }.cause!!.message
        )

        val encDocs = drParsed.encryptedDocuments[0].decrypt(
            recipientPrivateKey = AsymmetricKey.AnonymousExplicit(encryptionKey),
            encryptionParameters = encryptionParameters,
            sessionTranscript = sessionTranscript,
            atTime = euPidTimeSigned,
            deviceRequest = deviceRequest,
        )
        assertEquals(0, encDocs.documents.size)
        assertEquals(0, encDocs.zkDocuments.size)
        assertEquals(1, encDocs.otherDocuments.size)

        assertEquals("dc+sd-jwt", encDocs.otherDocuments[0].docFormat)
        val sdJwtKbCompactSerialization = encDocs.otherDocuments[0].data.toByteArray().zlibInflate().decodeToString()
        val processedPayload = SdJwtKb.fromCompactSerialization(sdJwtKbCompactSerialization)
            .verify(
                issuerKey = euPidDsKey.publicKey,
                checkNonce = { nonce -> true },
                checkAudience = { aud -> true },
                checkCreationTime = { creationTime -> true },
                transactionData = emptyList()
            )
        val kbKeyJwk = euPidCredential.secureArea.getKeyInfo(euPidCredential.alias).publicKey.toJwk()
        assertEquals(
            """
                {
                  "iss": "https://example-issuer.com",
                  "vct": "urn:eudi:pid:1",
                  "iat": 1764547200,
                  "nbf": 1764547200,
                  "exp": 1767139200,
                  "cnf": {
                    "jwk": {
                      "crv": "P-256",
                      "kty": "EC",
                      "x": "${kbKeyJwk["x"]!!.jsonPrimitive.content}",
                      "y": "${kbKeyJwk["y"]!!.jsonPrimitive.content}"
                    }
                  },
                  "family_name": "Mustermann",
                  "given_name": "Erika",
                  "place_of_birth": {
                    "country": "ZZ"
                  }
                }
            """.trimIndent(),
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }.encodeToString(processedPayload)
        )
    }

    @Test
    fun otherDocuments() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }

        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addOtherDocument(
                otherDocument = OtherDocument(
                    docFormat = "xyz123-abc",
                    data = ByteString(byteArrayOf(1, 2, 3).zlibDeflate())
                )
            )
        }
        assertEquals("1.0", deviceResponse.version)
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        assertEquals(drParsed, deviceResponse)

        assertEquals(1, drParsed.otherDocuments.size)
        assertEquals("xyz123-abc", drParsed.otherDocuments[0].docFormat)
        assertContentEquals(byteArrayOf(1, 2, 3), drParsed.otherDocuments[0].data.toByteArray().zlibInflate())
    }

    private suspend fun arbitraryAudienceOtherDocument(
        sessionTranscript: DataItem,
        audience: String = "arbitrary-reader.example"
    ): OtherDocument {
        val taggedTranscript = Tagged(
            tagNumber = Tagged.ENCODED_CBOR,
            taggedItem = Bstr(Cbor.encode(sessionTranscript))
        )
        val sdJwt = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val presentation = sdJwt.present(
            signingKey = AsymmetricKey.AnonymousSecureAreaBased(
                alias = euPidCredential.alias,
                secureArea = euPidCredential.secureArea,
                keyInfo = euPidCredential.secureArea.getKeyInfo(euPidCredential.alias),
            ),
            nonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(taggedTranscript)).toBase64Url(),
            audience = audience,
            creationTime = euPidTimeSigned,
        ).compactSerialization
        return OtherDocument(
            docFormat = "dc+sd-jwt",
            data = ByteString(presentation.encodeToByteArray().zlibDeflate())
        )
    }

    private suspend fun otherDocumentAudienceFixture(): Pair<DeviceResponse, DataItem> {
        provisionDocuments()
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val otherDocument = arbitraryAudienceOtherDocument(sessionTranscript)
        val response = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addOtherDocument(otherDocument)
        }
        return Pair(DeviceResponse.fromDataItem(Cbor.decode(Cbor.encode(response.toDataItem()))), sessionTranscript)
    }

    @Test
    fun otherDocumentArbitraryAudienceRejectedWithoutRequest() = runTest {
        val (response, sessionTranscript) = otherDocumentAudienceFixture()
        val error = assertFailsWith<IllegalStateException> {
            response.verify(sessionTranscript = sessionTranscript, atTime = euPidTimeSigned)
        }
        assertEquals("Failed verification of audience", error.message)
    }

    private fun unsignedOtherDocumentRequest(sessionTranscript: DataItem) =
        buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0),
        ) {
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
        }

    @Test
    fun otherDocumentArbitraryAudienceRejectedWithRequest() = runTest {
        val (response, sessionTranscript) = otherDocumentAudienceFixture()
        val request = unsignedOtherDocumentRequest(sessionTranscript)
        val error = assertFailsWith<IllegalStateException> {
            response.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = request,
                documentTypeRepository = DocumentTypeRepository(),
                atTime = euPidTimeSigned
            )
        }
        assertEquals("Failed verification of audience", error.message)
    }

    @Test
    fun sdJwtExpiredFloatExpIsRejected() = runTest {
        provisionDocuments()
        val original = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val sdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.X509CertifiedExplicit(original.x5c!!, euPidDsKey),
            kbKey = null,
            claims = buildJsonObject { put("given_name", "Synthetic") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.invalid")
                put("vct", EUPersonalID.EUPID_VCT)
                put("nbf", (euPidTimeSigned - 2.days).epochSeconds)
                put("exp", (euPidTimeSigned - 1.days).epochSeconds.toDouble() + 0.5)
            }
        )
        assertEquals(
            Instant.fromEpochMilliseconds((euPidTimeSigned - 1.days).toEpochMilliseconds() + 500),
            sdJwt.validUntil
        )
        val error = assertFailsWith<IllegalStateException> {
            VerificationUtil.verifySdJwtPresentation(
                compactSerialization = sdJwt.compactSerialization,
                nonce = "unused",
                documentTypeRepository = null,
                transactionData = emptyList(),
                now = euPidTimeSigned,
                expectedAudience = null,
                keyBindingRequired = false
            )
        }
        assertEquals("SD-JWT is not valid anymore", error.message)
    }

    @Test
    fun verifyMdocDeviceResponseRejectsWithoutRequest() = runTest {
        val (response, sessionTranscript) = otherDocumentAudienceFixture()
        val error = assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = euPidTimeSigned,
                deviceResponse = response.toDataItem(),
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = null,
                request = null
            )
        }
        assertEquals("Failed verification of audience", error.message)
    }

    @Test
    fun verifyMdocDeviceResponseRejectsDifferentRequestedAudience() = runTest {
        val (response, sessionTranscript) = otherDocumentAudienceFixture()
        val error = assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = euPidTimeSigned,
                deviceResponse = response.toDataItem(),
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                documentTypeRepository = DocumentTypeRepository(),
                zkSystemRepository = null,
                request = unsignedOtherDocumentRequest(sessionTranscript)
            )
        }
        assertEquals("Failed verification of audience", error.message)
    }

    @Test
    fun encryptedOtherDocumentValidatesAudienceAgainstRequest() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val recipientKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val encryptionParameters = EncryptionParameters.fromValues(recipientKey.publicKey)
        val encryptedSessionTranscript = EncryptedDocuments.buildSessionTranscript(
            originalSessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters
        )
        val otherDocument = arbitraryAudienceOtherDocument(encryptedSessionTranscript)
        val encryptedBuilder = EncryptedDocuments.Builder(
            sessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters,
            docRequestIds = listOf(0)
        )
        encryptedBuilder.addOtherDocument(otherDocument)
        val encrypted = encryptedBuilder.build()
        val request = unsignedOtherDocumentRequest(sessionTranscript)

        val errorWithoutRequest = assertFailsWith<IllegalStateException> {
            encrypted.decrypt(
                recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
                encryptionParameters = encryptionParameters,
                sessionTranscript = sessionTranscript,
                atTime = euPidTimeSigned
            )
        }
        assertEquals("Failed verification of audience", errorWithoutRequest.cause?.message)

        val errorWithRequest = assertFailsWith<IllegalStateException> {
            encrypted.decrypt(
                recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
                encryptionParameters = encryptionParameters,
                sessionTranscript = sessionTranscript,
                atTime = euPidTimeSigned,
                deviceRequest = request
            )
        }
        assertEquals("Failed verification of audience", errorWithRequest.cause?.message)
    }

    @Test
    fun sdJwtFutureFloatNbfIsRejected() = runTest {
        provisionDocuments()
        val original = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val sdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.X509CertifiedExplicit(original.x5c!!, euPidDsKey),
            kbKey = null,
            claims = buildJsonObject { put("given_name", "Synthetic") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.invalid")
                put("vct", EUPersonalID.EUPID_VCT)
                put("nbf", (euPidTimeSigned + 1.days).epochSeconds.toDouble() + 0.25)
                put("exp", (euPidTimeSigned + 2.days).epochSeconds)
            }
        )
        assertEquals(
            Instant.fromEpochMilliseconds((euPidTimeSigned + 1.days).toEpochMilliseconds() + 250),
            sdJwt.validFrom
        )
        val error = assertFailsWith<IllegalStateException> {
            VerificationUtil.verifySdJwtPresentation(
                compactSerialization = sdJwt.compactSerialization,
                nonce = "unused",
                documentTypeRepository = null,
                transactionData = emptyList(),
                now = euPidTimeSigned,
                expectedAudience = null,
                keyBindingRequired = false
            )
        }
        assertEquals("SD-JWT is not yet valid", error.message)
    }

    @Test
    fun sdJwtExpiredIntegerExpIsRejected() = runTest {
        provisionDocuments()
        val original = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val sdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.X509CertifiedExplicit(original.x5c!!, euPidDsKey),
            kbKey = null,
            claims = buildJsonObject { put("given_name", "Synthetic") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.invalid")
                put("vct", EUPersonalID.EUPID_VCT)
                put("nbf", (euPidTimeSigned - 2.days).epochSeconds)
                put("exp", (euPidTimeSigned - 1.days).epochSeconds)
            }
        )
        val error = assertFailsWith<IllegalStateException> {
            VerificationUtil.verifySdJwtPresentation(
                compactSerialization = sdJwt.compactSerialization,
                nonce = "unused",
                documentTypeRepository = null,
                transactionData = emptyList(),
                now = euPidTimeSigned,
                expectedAudience = null,
                keyBindingRequired = false
            )
        }
        assertEquals("SD-JWT is not valid anymore", error.message)
    }

    @Test
    fun sdJwtFutureIntegerNbfIsRejected() = runTest {
        provisionDocuments()
        val original = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val sdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.X509CertifiedExplicit(original.x5c!!, euPidDsKey),
            kbKey = null,
            claims = buildJsonObject { put("given_name", "Synthetic") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.invalid")
                put("vct", EUPersonalID.EUPID_VCT)
                put("nbf", (euPidTimeSigned + 1.days).epochSeconds)
                put("exp", (euPidTimeSigned + 2.days).epochSeconds)
            }
        )
        val error = assertFailsWith<IllegalStateException> {
            VerificationUtil.verifySdJwtPresentation(
                compactSerialization = sdJwt.compactSerialization,
                nonce = "unused",
                documentTypeRepository = null,
                transactionData = emptyList(),
                now = euPidTimeSigned,
                expectedAudience = null,
                keyBindingRequired = false
            )
        }
        assertEquals("SD-JWT is not yet valid", error.message)
    }

    private object TestTxType : TransactionType<String>(
        displayName = "Test Transaction",
        identifier = "com.example.test_tx",
        kbJwtResponseClaimName = "test_tx_response",
    ) {
        override fun serializeIso18013Request(payload: String): DataItem = buildCborMap {
            put("nonce", payload)
        }

        override fun parseIso18013Request(dataItem: DataItem): String =
            dataItem.asMap[Tstr("nonce")]!!.asTstr
    }

    @Test
    fun verifyRejectsTransactionRequestWhenRepositoryIsNull() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val otherDocument = arbitraryAudienceOtherDocument(
            sessionTranscript,
            audience = transcriptAudience(sessionTranscript)
        )
        val response = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addOtherDocument(otherDocument)
        }
        val requestWithTx = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0),
        ) {
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf(
                    "_" to mapOf("sdjwtvc_given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TestTxType.identifier to false)
                ),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    ),
                    transactionData = TransactionsInfo(
                        data = mapOf(
                            TestTxType.identifier to TestTxType.serializeIso18013Request("tx-nonce")
                        )
                    )
                )
            )
        }
        val error = assertFailsWith<IllegalStateException> {
            response.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = requestWithTx,
                documentTypeRepository = null,
                atTime = euPidTimeSigned
            )
        }
        assertEquals(
            "documentTypeRepository is required when DeviceRequest contains transaction data",
            error.message
        )
    }

    @Test
    fun otherDocumentRejectsOutOfRangeDocRequestIdUnderReaderAuthAll() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.AnonymousExplicit(readerRootKey),
            subject = X500Name.fromName("CN=Test Reader Root"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = euPidTimeValidityBegin,
            validUntil = euPidTimeValidityEnd,
            crlUrl = "https://verifier.example.com/crl"
        )
        val readerKeyPrivate = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(readerRootCert)),
                privateKey = readerRootKey
            ),
            readerKey = readerKeyPrivate.publicKey,
            subject = X500Name.fromName("CN=Test Reader"),
            dnsName = null,
            serial = ASN1Integer.fromRandom(128),
            validFrom = euPidTimeValidityBegin,
            validUntil = euPidTimeValidityEnd,
            extensions = emptyList()
        )
        val readerKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(readerCert, readerRootCert)),
            privateKey = readerKeyPrivate
        )
        val request = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf(
                    "_" to mapOf("sdjwtvc_given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TestTxType.identifier to false)
                ),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    ),
                    transactionData = TransactionsInfo(
                        data = mapOf(
                            TestTxType.identifier to TestTxType.serializeIso18013Request("tx-nonce")
                        )
                    )
                )
            )
            addReaderAuthAll(readerKey)
        }
        val taggedTranscript = Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(sessionTranscript)))
        val nonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(taggedTranscript)).toBase64Url()
        val sdJwt = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val sdHash = Crypto.digest(sdJwt.digestAlg, sdJwt.compactSerialization.encodeToByteArray()).toBase64Url()
        val kbSigningKey = AsymmetricKey.AnonymousSecureAreaBased(
            alias = euPidCredential.alias,
            secureArea = euPidCredential.secureArea,
            keyInfo = euPidCredential.secureArea.getKeyInfo(euPidCredential.alias),
        )
        val kbJwt = buildJwt(
            type = "kb+jwt",
            key = kbSigningKey,
            creationTime = euPidTimeSigned
        ) {
            put("nonce", nonce)
            put("aud", nonce)
            put("sd_hash", sdHash)
            putJsonObject(TestTxType.kbJwtResponseClaimName) {
                put("doc_request_id", 99)
            }
        }
        val otherDocument = OtherDocument(
            docFormat = "dc+sd-jwt",
            data = ByteString((sdJwt.compactSerialization + kbJwt).encodeToByteArray().zlibDeflate())
        )
        val response = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addOtherDocument(otherDocument)
        }
        val repo = DocumentTypeRepository().apply { addTransactionType(TestTxType) }
        val error = assertFailsWith<IllegalStateException> {
            response.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = request,
                documentTypeRepository = repo,
                atTime = euPidTimeSigned
            )
        }
        assertEquals("Invalid 'doc_request_id' value 99", error.message)

        // Also verify that an in-range doc_request_id pointing to an mso_mdoc DocRequest (index 0)
        // is rejected rather than bypassing transaction verification.
        val kbJwtIndex0 = buildJwt(
            type = "kb+jwt",
            key = kbSigningKey,
            creationTime = euPidTimeSigned
        ) {
            put("nonce", nonce)
            put("aud", nonce)
            put("sd_hash", sdHash)
            putJsonObject(TestTxType.kbJwtResponseClaimName) {
                put("doc_request_id", 0)
            }
        }
        val responseIndex0 = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addOtherDocument(
                OtherDocument(
                    docFormat = "dc+sd-jwt",
                    data = ByteString((sdJwt.compactSerialization + kbJwtIndex0).encodeToByteArray().zlibDeflate())
                )
            )
        }
        val errorIndex0 = assertFailsWith<IllegalStateException> {
            responseIndex0.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = request,
                documentTypeRepository = repo,
                atTime = euPidTimeSigned
            )
        }
        assertEquals("DocRequest at index 0 is not a dc+sd-jwt request", errorIndex0.message)
    }

    @Test
    fun otherDocumentDuplicateVctUnderReaderAuthAllWithoutTransactionsPasses() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.AnonymousExplicit(readerRootKey),
            subject = X500Name.fromName("CN=Test Reader Root"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = euPidTimeValidityBegin,
            validUntil = euPidTimeValidityEnd,
            crlUrl = "https://verifier.example.com/crl"
        )
        val readerKeyPrivate = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(readerRootCert)),
                privateKey = readerRootKey
            ),
            readerKey = readerKeyPrivate.publicKey,
            subject = X500Name.fromName("CN=Test Reader"),
            dnsName = null,
            serial = ASN1Integer.fromRandom(128),
            validFrom = euPidTimeValidityBegin,
            validUntil = euPidTimeValidityEnd,
            extensions = emptyList()
        )
        val readerKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(readerCert, readerRootCert)),
            privateKey = readerKeyPrivate
        )
        val request = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_family_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_family_name" to buildJsonArray { add(JsonPrimitive("family_name")) }
                    )
                )
            )
            addReaderAuthAll(readerKey)
        }
        val expectedAudience = transcriptAudience(sessionTranscript)
        val otherDocument = arbitraryAudienceOtherDocument(sessionTranscript, audience = expectedAudience)
        val response = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addOtherDocument(otherDocument)
        }
        response.verify(
            sessionTranscript = sessionTranscript,
            deviceRequest = request,
            atTime = euPidTimeSigned
        )
        assertEquals(expectedAudience, response.otherDocuments.single().expectedAudience)

        // Verify that a subsequent failed verification resets expectedAudience to null
        val otherSessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(9, 9, 9))
        }
        assertFailsWith<IllegalStateException> {
            response.verify(
                sessionTranscript = otherSessionTranscript,
                deviceRequest = null,
                atTime = euPidTimeSigned
            )
        }
        assertEquals(null, response.otherDocuments.single().expectedAudience)
    }

    @Test
    fun otherDocumentVctFallbackRejectsDefaultMdocDocFormat() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val otherDocument = arbitraryAudienceOtherDocument(
            sessionTranscript,
            audience = transcriptAudience(sessionTranscript)
        )
        val response = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addOtherDocument(otherDocument)
        }
        val requestWithDefaultDocFormat = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = null,
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
        }
        val error = assertFailsWith<IllegalStateException> {
            response.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = requestWithDefaultDocFormat,
                atTime = euPidTimeSigned
            )
        }
        assertEquals("Cannot resolve DocRequest for otherDocument", error.message)
    }

    @Test
    fun encryptedOtherDocumentRejectsOutOfRangeOrMismatchedDocRequestId() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val recipientKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val encryptionParameters = EncryptionParameters.fromValues(recipientKey.publicKey)
        val encryptedSessionTranscript = EncryptedDocuments.buildSessionTranscript(
            originalSessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters
        )
        val otherDocument = arbitraryAudienceOtherDocument(
            encryptedSessionTranscript,
            audience = transcriptAudience(encryptedSessionTranscript)
        )
        val outOfRangeEncrypted = EncryptedDocuments.Builder(
            sessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters,
            docRequestIds = listOf(5)
        ).apply { addOtherDocument(otherDocument) }.build()

        val request = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
        }
        val outOfRangeError = assertFailsWith<IllegalStateException> {
            outOfRangeEncrypted.decrypt(
                recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
                encryptionParameters = encryptionParameters,
                sessionTranscript = sessionTranscript,
                atTime = euPidTimeSigned,
                deviceRequest = request
            )
        }
        assertEquals("Invalid docRequestId 5 for DeviceRequest", outOfRangeError.message)

        val mismatchedEncrypted = EncryptedDocuments.Builder(
            sessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters,
            docRequestIds = listOf(0)
        ).apply { addOtherDocument(otherDocument) }.build()
        val mismatchedError = assertFailsWith<IllegalStateException> {
            mismatchedEncrypted.decrypt(
                recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
                encryptionParameters = encryptionParameters,
                sessionTranscript = sessionTranscript,
                atTime = euPidTimeSigned,
                deviceRequest = request
            )
        }
        assertEquals("DocRequest at index 0 is not a dc+sd-jwt request", mismatchedError.cause?.message)

        // Verify that a credential selected via otherVcts (where wire vct != docRequest.docType)
        // is accepted by decrypt() when docRequestId points to the matching dc+sd-jwt request,
        // and rejected if EncryptionParameters do not match docResponseEncryption.
        val otherRecipientKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val otherEncryptionParameters = EncryptionParameters.fromValues(otherRecipientKey.publicKey)
        val requestWithOtherVctAndEncryption = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = "urn:eudi:pid:base:1",
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    docResponseEncryption = encryptionParameters,
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
            addDocRequest(
                docType = "urn:eudi:pid:base:1",
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    docResponseEncryption = otherEncryptionParameters,
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
        }
        val validOtherVctEncrypted = EncryptedDocuments.Builder(
            sessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters,
            docRequestIds = listOf(0)
        ).apply { addOtherDocument(otherDocument) }.build()

        // Without a verifier-configured otherVcts alias in documentTypeRepository, mismatched vct is rejected.
        val unapprovedOtherVctError = assertFailsWith<IllegalStateException> {
            validOtherVctEncrypted.decrypt(
                recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
                encryptionParameters = encryptionParameters,
                sessionTranscript = sessionTranscript,
                atTime = euPidTimeSigned,
                deviceRequest = requestWithOtherVctAndEncryption
            )
        }
        assertEquals(
            "OtherDocument vct '${EUPersonalID.EUPID_VCT}' does not match DocRequest docType 'urn:eudi:pid:base:1'",
            unapprovedOtherVctError.cause?.message
        )

        val aliasRepo = DocumentTypeRepository().apply {
            addDocumentType(
                DocumentType.Builder("EU PID")
                    .addJsonDocumentType(
                        type = EUPersonalID.EUPID_VCT,
                        keyBound = true,
                        otherVcts = listOf("urn:eudi:pid:base:1"),
                    )
                    .build()
            )
        }
        val decrypted = validOtherVctEncrypted.decrypt(
            recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
            encryptionParameters = encryptionParameters,
            sessionTranscript = sessionTranscript,
            atTime = euPidTimeSigned,
            deviceRequest = requestWithOtherVctAndEncryption,
            documentTypeRepository = aliasRepo,
        )
        assertEquals(1, decrypted.otherDocuments.size)
        assertEquals(transcriptAudience(encryptedSessionTranscript), decrypted.otherDocuments.single().expectedAudience)

        val wrongEncryptionParamsDocRequestId = EncryptedDocuments.Builder(
            sessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters,
            docRequestIds = listOf(1)
        ).apply { addOtherDocument(otherDocument) }.build()
        val wrongEncryptionParamsError = assertFailsWith<IllegalStateException> {
            wrongEncryptionParamsDocRequestId.decrypt(
                recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
                encryptionParameters = encryptionParameters,
                sessionTranscript = sessionTranscript,
                atTime = euPidTimeSigned,
                deviceRequest = requestWithOtherVctAndEncryption
            )
        }
        assertEquals(
            "EncryptionParameters do not match DocRequest at index 1",
            wrongEncryptionParamsError.message
        )
    }

    @Test
    fun sdJwtRejectsExactExpirationAndMalformedNumericDate() = runTest {
        provisionDocuments()
        val original = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val exactExpSdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.X509CertifiedExplicit(original.x5c!!, euPidDsKey),
            kbKey = null,
            claims = buildJsonObject { put("given_name", "Synthetic") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.invalid")
                put("vct", EUPersonalID.EUPID_VCT)
                put("nbf", (euPidTimeSigned - 1.days).epochSeconds)
                put("exp", euPidTimeSigned.epochSeconds)
            }
        )
        val exactExpError = assertFailsWith<IllegalStateException> {
            VerificationUtil.verifySdJwtPresentation(
                compactSerialization = exactExpSdJwt.compactSerialization,
                nonce = "unused",
                documentTypeRepository = null,
                transactionData = emptyList(),
                now = euPidTimeSigned,
                expectedAudience = null,
                keyBindingRequired = false
            )
        }
        assertEquals("SD-JWT is not valid anymore", exactExpError.message)

        val nullExpSdJwt = SdJwt.create(
            issuerKey = AsymmetricKey.X509CertifiedExplicit(original.x5c!!, euPidDsKey),
            kbKey = null,
            claims = buildJsonObject { put("given_name", "Synthetic") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example.invalid")
                put("vct", EUPersonalID.EUPID_VCT)
                put("nbf", (euPidTimeSigned - 1.days).epochSeconds)
                put("exp", JsonNull)
            }
        )
        assertEquals(
            "Invalid 'exp' claim: expected JSON number, got null",
            assertFailsWith<IllegalStateException> { nullExpSdJwt.validUntil }.message
        )
    }

    @Test
    fun deviceSignedAuthorizedByNamespace() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val credential = createMdlCredentialWithAuthorizations(
            authorizedNamespaces = listOf("com.example.device")
        )
        val deviceNamespaces = buildDeviceNamespaces {
            addNamespace("com.example.device") {
                addDataElement("device_element_1", "foo".toDataItem())
                addDataElement("device_element_2", 42.toDataItem())
            }
        }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = credential,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                ),
                deviceNamespaces = deviceNamespaces
            )
        }
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        drParsed.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )
        assertEquals(drParsed, deviceResponse)

        @Suppress("DEPRECATION")
        val legacyDoc = DeviceResponseParser(
            encodedDeviceResponse,
            Cbor.encode(sessionTranscript)
        ).parse().documents.single()
        assertTrue(legacyDoc.deviceSignedAuthenticated)
        assertEquals("foo", legacyDoc.getDeviceEntryString("com.example.device", "device_element_1"))
        assertEquals(42L, legacyDoc.getDeviceEntryNumber("com.example.device", "device_element_2"))
    }

    @Test
    fun deviceSignedAuthorizedByDataElements() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val credential = createMdlCredentialWithAuthorizations(
            authorizedDataElements = mapOf(
                "com.example.device" to listOf("device_element_1", "device_element_2")
            )
        )
        val deviceNamespaces = buildDeviceNamespaces {
            addNamespace("com.example.device") {
                addDataElement("device_element_1", "foo".toDataItem())
            }
        }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = credential,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                ),
                deviceNamespaces = deviceNamespaces
            )
        }
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )
        @Suppress("DEPRECATION")
        val legacyDoc = DeviceResponseParser(
            Cbor.encode(deviceResponse.toDataItem()),
            Cbor.encode(sessionTranscript)
        ).parse().documents.single()
        assertTrue(legacyDoc.deviceSignedAuthenticated)
        assertEquals("foo", legacyDoc.getDeviceEntryString("com.example.device", "device_element_1"))
    }

    @Test
    fun deviceSignedUnauthorizedNoKeyAuthorizations() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val deviceNamespaces = buildDeviceNamespaces {
            addNamespace("com.example.device") {
                addDataElement("device_element_1", "foo".toDataItem())
            }
        }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = mdlCredentialSignature,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                ),
                deviceNamespaces = deviceNamespaces
            )
        }
        val error = assertFailsWith(IllegalStateException::class) {
            deviceResponse.verify(
                sessionTranscript = sessionTranscript,
                atTime = mdlTimeValidityBegin
            )
        }
        assertEquals(
            "Device-signed data element 'device_element_1' in namespace 'com.example.device' is not authorized by MSO",
            error.message
        )
        @Suppress("DEPRECATION")
        val legacyDoc = DeviceResponseParser(
            Cbor.encode(deviceResponse.toDataItem()),
            Cbor.encode(sessionTranscript)
        ).parse().documents.single()
        assertFalse(legacyDoc.deviceSignedAuthenticated)
        assertTrue(legacyDoc.deviceNamespaces.isEmpty())
    }

    @Test
    fun deviceSignedUnauthorizedNamespace() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val credential = createMdlCredentialWithAuthorizations(
            authorizedNamespaces = listOf("com.example.other")
        )
        val deviceNamespaces = buildDeviceNamespaces {
            addNamespace("com.example.device") {
                addDataElement("device_element_1", "foo".toDataItem())
            }
        }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = credential,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                ),
                deviceNamespaces = deviceNamespaces
            )
        }
        val error = assertFailsWith(IllegalStateException::class) {
            deviceResponse.verify(
                sessionTranscript = sessionTranscript,
                atTime = mdlTimeValidityBegin
            )
        }
        assertEquals(
            "Device-signed data element 'device_element_1' in namespace 'com.example.device' is not authorized by MSO",
            error.message
        )
        @Suppress("DEPRECATION")
        val legacyDoc = DeviceResponseParser(
            Cbor.encode(deviceResponse.toDataItem()),
            Cbor.encode(sessionTranscript)
        ).parse().documents.single()
        assertFalse(legacyDoc.deviceSignedAuthenticated)
        assertTrue(legacyDoc.deviceNamespaces.isEmpty())
    }

    @Test
    fun deviceSignedUnauthorizedDataElement() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val credential = createMdlCredentialWithAuthorizations(
            authorizedDataElements = mapOf(
                "com.example.device" to listOf("device_element_1")
            )
        )
        val deviceNamespaces = buildDeviceNamespaces {
            addNamespace("com.example.device") {
                addDataElement("device_element_2", "bar".toDataItem())
            }
        }
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = credential,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                ),
                deviceNamespaces = deviceNamespaces
            )
        }
        val error = assertFailsWith(IllegalStateException::class) {
            deviceResponse.verify(
                sessionTranscript = sessionTranscript,
                atTime = mdlTimeValidityBegin
            )
        }
        assertEquals(
            "Device-signed data element 'device_element_2' in namespace 'com.example.device' is not authorized by MSO",
            error.message
        )
        @Suppress("DEPRECATION")
        val legacyDoc = DeviceResponseParser(
            Cbor.encode(deviceResponse.toDataItem()),
            Cbor.encode(sessionTranscript)
        ).parse().documents.single()
        assertFalse(legacyDoc.deviceSignedAuthenticated)
        assertTrue(legacyDoc.deviceNamespaces.isEmpty())
    }

    @Test
    fun msoSignedBeforeDsCertNotBefore() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = mdlDocument,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(algorithm = Algorithm.ESP256),
            dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(mdlDsCert)), mdlDsKey),
            signedAt = mdlDsCert.validityNotBefore - 1.days,
            validFrom = mdlDsCert.validityNotBefore,
            validUntil = mdlDsCert.validityNotBefore + 30.days,
            domain = "mdoc_sign_before"
        )
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = credential,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                )
            )
        }
        val error = assertFailsWith(IllegalStateException::class) {
            deviceResponse.verify(
                sessionTranscript = sessionTranscript,
                atTime = mdlDsCert.validityNotBefore
            )
        }
        assertEquals("MSO signed date is outside DS certificate validity period", error.message)
    }

    @Test
    fun msoSignedAfterDsCertNotAfter() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = mdlDocument,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(algorithm = Algorithm.ESP256),
            dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(mdlDsCert)), mdlDsKey),
            signedAt = mdlDsCert.validityNotAfter + 1.days,
            validFrom = mdlDsCert.validityNotAfter - 10.days,
            validUntil = mdlDsCert.validityNotAfter + 30.days,
            domain = "mdoc_sign_after"
        )
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = credential,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                )
            )
        }
        val error = assertFailsWith(IllegalStateException::class) {
            deviceResponse.verify(
                sessionTranscript = sessionTranscript,
                atTime = mdlDsCert.validityNotAfter
            )
        }
        assertEquals("MSO signed date is outside DS certificate validity period", error.message)
    }

    @Test
    fun msoValidUntilAfterDsCertNotAfter() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val credential = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = mdlDocument,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(algorithm = Algorithm.ESP256),
            dsKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(mdlDsCert)), mdlDsKey),
            signedAt = mdlDsCert.validityNotBefore,
            validFrom = mdlDsCert.validityNotBefore,
            validUntil = mdlDsCert.validityNotAfter + 1.days,
            domain = "mdoc_valid_until_after"
        )
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = credential,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                )
            )
        }
        // Allowed by default (rejectIfValidUntilAfterNotAfter = false)
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlDsCert.validityNotBefore,
            rejectIfValidUntilAfterNotAfter = false
        )

        // Rejected when rejectIfValidUntilAfterNotAfter = true
        val error = assertFailsWith(IllegalStateException::class) {
            deviceResponse.verify(
                sessionTranscript = sessionTranscript,
                atTime = mdlDsCert.validityNotBefore,
                rejectIfValidUntilAfterNotAfter = true
            )
        }
        assertEquals("MSO validUntil is after DS certificate validity period", error.message)
    }

    @Test
    fun otherDocumentRejectsUnrequestedVctUnlessVerifierAliasConfigured() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val requestedBaseVct = "urn:example:pid:base:1"

        // 1. Non-transaction path: request for requestedBaseVct, presented EUPID_VCT
        val nonTxRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0),
        ) {
            addDocRequest(
                docType = requestedBaseVct,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
        }
        val expectedAud = transcriptAudience(sessionTranscript)
        val nonTxOtherDoc = arbitraryAudienceOtherDocument(sessionTranscript, audience = expectedAud)
        val nonTxResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addOtherDocument(nonTxOtherDoc)
        }

        // Rejected without DocumentTypeRepository
        val noRepoError = assertFailsWith<IllegalStateException> {
            nonTxResponse.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = nonTxRequest,
                documentTypeRepository = null,
                atTime = euPidTimeSigned,
            )
        }
        assertEquals("Cannot resolve DocRequest for otherDocument", noRepoError.message)
        assertEquals(null, nonTxResponse.otherDocuments.single().expectedAudience)

        // Rejected when DocumentTypeRepository does not declare requestedBaseVct in otherVcts
        val repoWithoutAlias = DocumentTypeRepository().apply {
            addTransactionType(TestTxType)
            addDocumentType(
                DocumentType.Builder("EU PID")
                    .addJsonDocumentType(type = EUPersonalID.EUPID_VCT, keyBound = true)
                    .build()
            )
        }
        assertFailsWith<IllegalStateException> {
            nonTxResponse.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = nonTxRequest,
                documentTypeRepository = repoWithoutAlias,
                atTime = euPidTimeSigned,
            )
        }

        // Accepted when DocumentTypeRepository declares requestedBaseVct in otherVcts of EUPID_VCT
        val repoWithAlias = DocumentTypeRepository().apply {
            addTransactionType(TestTxType)
            addDocumentType(
                DocumentType.Builder("EU PID")
                    .addJsonDocumentType(
                        type = EUPersonalID.EUPID_VCT,
                        keyBound = true,
                        otherVcts = listOf(requestedBaseVct),
                    )
                    .build()
            )
        }
        nonTxResponse.verify(
            sessionTranscript = sessionTranscript,
            deviceRequest = nonTxRequest,
            documentTypeRepository = repoWithAlias,
            atTime = euPidTimeSigned,
        )
        assertEquals(expectedAud, nonTxResponse.otherDocuments.single().expectedAudience)

        val verifiedPresentations = VerificationUtil.verifyMdocDeviceResponse(
            now = euPidTimeSigned,
            deviceResponse = nonTxResponse.toDataItem(),
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            documentTypeRepository = repoWithAlias,
            zkSystemRepository = null,
            request = nonTxRequest,
        )
        assertEquals(1, verifiedPresentations.size)

        // 2. Transaction path: request index 0 asks for requestedBaseVct with TestTxType
        val txRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0),
        ) {
            addDocRequest(
                docType = requestedBaseVct,
                nameSpaces = mapOf(
                    "_" to mapOf("sdjwtvc_given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TestTxType.identifier to false),
                ),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    ),
                    transactionData = TransactionsInfo(
                        data = mapOf(
                            TestTxType.identifier to TestTxType.serializeIso18013Request("tx-nonce")
                        )
                    )
                )
            )
        }
        val taggedTranscript = Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(sessionTranscript)))
        val nonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(taggedTranscript)).toBase64Url()
        val sdJwt = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val sdHash = Crypto.digest(sdJwt.digestAlg, sdJwt.compactSerialization.encodeToByteArray()).toBase64Url()
        val kbSigningKey = AsymmetricKey.AnonymousSecureAreaBased(
            alias = euPidCredential.alias,
            secureArea = euPidCredential.secureArea,
            keyInfo = euPidCredential.secureArea.getKeyInfo(euPidCredential.alias),
        )
        val txKbJwt = buildJwt(
            type = "kb+jwt",
            key = kbSigningKey,
            creationTime = euPidTimeSigned,
        ) {
            put("nonce", nonce)
            put("aud", nonce)
            put("sd_hash", sdHash)
            putJsonObject(TestTxType.kbJwtResponseClaimName) {
                put("doc_request_id", 0)
            }
        }
        val txResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addOtherDocument(
                OtherDocument(
                    docFormat = "dc+sd-jwt",
                    data = ByteString((sdJwt.compactSerialization + txKbJwt).encodeToByteArray().zlibDeflate()),
                )
            )
        }

        // Rejected on transaction path without verifier alias
        val txNoAliasError = assertFailsWith<IllegalStateException> {
            txResponse.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = txRequest,
                documentTypeRepository = repoWithoutAlias,
                atTime = euPidTimeSigned,
            )
        }
        assertEquals(
            "OtherDocument vct '${EUPersonalID.EUPID_VCT}' does not match DocRequest at index 0",
            txNoAliasError.message,
        )

        // Accepted on transaction path with verifier alias
        txResponse.verify(
            sessionTranscript = sessionTranscript,
            deviceRequest = txRequest,
            documentTypeRepository = repoWithAlias,
            atTime = euPidTimeSigned,
        )
        assertEquals(nonce, txResponse.otherDocuments.single().expectedAudience)

        // Still rejected even with alias when another dc+sd-jwt DocRequest explicitly requests EUPID_VCT
        val txRequestWithExplicitSubtype = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = requestedBaseVct,
                nameSpaces = mapOf(
                    "_" to mapOf("sdjwtvc_given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TestTxType.identifier to false),
                ),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    ),
                    transactionData = TransactionsInfo(
                        data = mapOf(
                            TestTxType.identifier to TestTxType.serializeIso18013Request("tx-nonce")
                        )
                    )
                )
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
        }
        val txMisboundError = assertFailsWith<IllegalStateException> {
            txResponse.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = txRequestWithExplicitSubtype,
                documentTypeRepository = repoWithAlias,
                atTime = euPidTimeSigned,
            )
        }
        assertEquals(
            "OtherDocument vct '${EUPersonalID.EUPID_VCT}' does not match DocRequest at index 0",
            txMisboundError.message,
        )
        assertEquals(null, txResponse.otherDocuments.single().expectedAudience)
    }

    @Test
    fun docRequestIdsRoundTripAndValidation() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val encryptionKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val encryptionParameters = EncryptionParameters.fromValues(encryptionKey.publicKey)

        val zkDoc = ZkDocument(
            documentData = ZkDocumentData(
                zkSystemSpecId = "spec1",
                docType = DrivingLicense.MDL_DOCTYPE,
                timestamp = mdlTimeSigned,
                issuerSigned = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("age_over_18" to true.toDataItem())),
                deviceSigned = emptyMap(),
                msoX5chain = X509CertChain(listOf(mdlDsCert))
            ),
            proof = ByteString(1, 2, 3),
            docRequestIds = listOf(1, 3),
        )
        val otherDoc = OtherDocument(
            docFormat = "dc+sd-jwt",
            data = ByteString(byteArrayOf(4, 5, 6)),
            docRequestIds = listOf(2),
        )

        val response = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addDocument(
                credential = mdlCredentialSignature,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                ),
                docRequestIds = listOf(0, 2),
            )
            addZkDocument(zkDoc)
            addEncryptedDocuments(
                encryptionParameters = encryptionParameters,
                docRequestIds = listOf(0, 1),
            ) {
                addOtherDocument(otherDoc)
            }
            addOtherDocument(otherDoc)
        }

        // DeviceResponse version always defaults to "1.0" per ISO/IEC 18013-5 Second Edition Clause 10.3.2
        assertEquals("1.0", response.version)
        val dataItem = response.toDataItem()
        assertEquals(listOf(0L, 2L), dataItem["documents"].asArray[0]["docRequestIds"].asArray.map { it.asNumber })
        assertEquals(listOf(1L, 3L), dataItem["zkDocuments"].asArray[0]["docRequestIds"].asArray.map { it.asNumber })
        assertEquals(listOf(0L, 1L), dataItem["encryptedDocuments"].asArray[0]["docRequestIds"].asArray.map { it.asNumber })
        assertFalse(dataItem["encryptedDocuments"].asArray[0].hasKey("docRequestID"))
        assertEquals(listOf(2L), dataItem["otherDocuments"].asArray[0]["docRequestIds"].asArray.map { it.asNumber })

        val parsed = DeviceResponse.fromDataItem(Cbor.decode(Cbor.encode(dataItem)))
        assertEquals(response, parsed)
        assertEquals(listOf(1, 3), parsed.zkDocuments[0].docRequestIds)
        assertEquals(listOf(0, 1), parsed.encryptedDocuments[0].docRequestIds)
        assertEquals(listOf(2), parsed.otherDocuments[0].docRequestIds)

        // Constructor validation rejects empty or negative docRequestIds
        assertFailsWith<IllegalArgumentException> {
            OtherDocument("dc+sd-jwt", ByteString(1), docRequestIds = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            OtherDocument("dc+sd-jwt", ByteString(1), docRequestIds = listOf(-1))
        }
        assertFailsWith<IllegalArgumentException> {
            zkDoc.copy(docRequestIds = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            EncryptedDocuments(ByteString(1), ByteString(2), emptyList())
        }

        // CBOR parser rejects empty array, negative integer, non-integer, and legacy "docRequestID"
        for (invalidIds in listOf(
            buildCborArray {},
            buildCborArray { add(-1) },
            buildCborArray { add("0") },
        )) {
            assertFailsWith<IllegalArgumentException> {
                OtherDocument.fromDataItem(buildCborMap {
                    put("docFormat", "dc+sd-jwt")
                    put("data", byteArrayOf(1))
                    put("docRequestIds", invalidIds)
                })
            }
            assertFailsWith<IllegalArgumentException> {
                EncryptedDocuments.fromDataItem(buildCborMap {
                    put("enc", byteArrayOf(1))
                    put("cipherText", byteArrayOf(2))
                    put("docRequestIds", invalidIds)
                })
            }
        }
        assertFailsWith<IllegalArgumentException> {
            EncryptedDocuments.fromDataItem(buildCborMap {
                put("enc", byteArrayOf(1))
                put("cipherText", byteArrayOf(2))
                put("docRequestID", 0)
            })
        }
    }

    @Test
    fun docRequestIdsDisambiguatesMultipleRequestsAndSupportsSharedPresentation() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }

        // 1. Two dc+sd-jwt DocRequests for the same VCT signed with distinct per-request readerAuth keys
        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.AnonymousExplicit(readerRootKey),
            subject = X500Name.fromName("CN=Test Reader Root"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = euPidTimeValidityBegin,
            validUntil = euPidTimeValidityEnd,
            crlUrl = "https://verifier.example.com/crl"
        )
        suspend fun createReaderKey(cn: String): AsymmetricKey.X509Certified {
            val priv = Crypto.createEcPrivateKey(EcCurve.P256)
            val cert = MdocUtil.generateReaderCertificate(
                readerRootKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(readerRootCert)), readerRootKey),
                readerKey = priv.publicKey,
                subject = X500Name.fromName("CN=$cn"),
                dnsName = null,
                serial = ASN1Integer.fromRandom(128),
                validFrom = euPidTimeValidityBegin,
                validUntil = euPidTimeValidityEnd,
                extensions = emptyList()
            )
            return AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(cert, readerRootCert)), priv)
        }
        val readerKey0 = createReaderKey("Reader 0")
        val readerKey1 = createReaderKey("Reader 1")

        val mdlAki = ByteString(mdlDsCert.authorityKeyIdentifier!!)
        val wrongAki = ByteString(ByteArray(mdlAki.size) { 0x55 })

        val twoPidRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    issuerIdentifiers = listOf(wrongAki),
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                ),
                readerKey = readerKey0
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_family_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    issuerIdentifiers = listOf(mdlAki),
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_family_name" to buildJsonArray { add(JsonPrimitive("family_name")) }
                    )
                ),
                readerKey = readerKey1
            )
        }
        val expectedPidAudience = transcriptAudience(sessionTranscript)
        val otherDocWithoutIds = arbitraryAudienceOtherDocument(sessionTranscript, audience = expectedPidAudience)

        // Without docRequestIds, fallback resolves index 0 whose issuerIdentifiers does not match
        val ambiguousResponse = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addOtherDocument(otherDocWithoutIds)
        }
        assertFailsWith<IllegalStateException> {
            ambiguousResponse.verify(sessionTranscript, deviceRequest = twoPidRequest, atTime = euPidTimeSigned)
        }

        // With docRequestIds = [1], verification resolves DocRequest 1 and succeeds
        val disambiguatedResponse = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addOtherDocument(otherDocWithoutIds.copy(docRequestIds = listOf(1)))
        }
        disambiguatedResponse.verify(sessionTranscript, deviceRequest = twoPidRequest, atTime = euPidTimeSigned)
        assertEquals(expectedPidAudience, disambiguatedResponse.otherDocuments.single().expectedAudience)

        // Pointing docRequestIds to index 0 or [0, 1] fails because DocRequest 0 requires wrongAki
        assertFailsWith<IllegalStateException> {
            buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
                addOtherDocument(otherDocWithoutIds.copy(docRequestIds = listOf(0)))
            }.verify(sessionTranscript, deviceRequest = twoPidRequest, atTime = euPidTimeSigned)
        }
        assertFailsWith<IllegalStateException> {
            buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
                addOtherDocument(otherDocWithoutIds.copy(docRequestIds = listOf(0, 1)))
            }.verify(sessionTranscript, deviceRequest = twoPidRequest, atTime = euPidTimeSigned)
        }

        // When both DocRequests allow the issuer (even with distinct per-request readerAuth keys),
        // docRequestIds = [0, 1] succeeds under DIS Annex H.2 shared SessionTranscriptBytes audience
        val sharedPidRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    issuerIdentifiers = listOf(mdlAki),
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                ),
                readerKey = readerKey0
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_family_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    issuerIdentifiers = listOf(mdlAki),
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_family_name" to buildJsonArray { add(JsonPrimitive("family_name")) }
                    )
                ),
                readerKey = readerKey1
            )
        }
        buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addOtherDocument(otherDocWithoutIds.copy(docRequestIds = listOf(0, 1)))
        }.verify(sessionTranscript, deviceRequest = sharedPidRequest, atTime = euPidTimeSigned)

        // 2. Two mso_mdoc DocRequests with the same docType: disambiguating AKI and satisfying [0, 1] with one document
        val twoMdlDiffAkiRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(wrongAki))
            )
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(mdlAki))
            )
        }
        // Without docRequestIds, fallback picks index 0 (wrong AKI) and fails; with docRequestIds = [1], it succeeds!
        val mdlGivenNameClaim = MdocRequestedClaim(
            docType = DrivingLicense.MDL_DOCTYPE,
            namespaceName = DrivingLicense.MDL_NAMESPACE,
            dataElementName = "given_name",
            intentToRetain = false
        )
        val mdlFamilyNameClaim = MdocRequestedClaim(
            docType = DrivingLicense.MDL_DOCTYPE,
            namespaceName = DrivingLicense.MDL_NAMESPACE,
            dataElementName = "family_name",
            intentToRetain = false
        )
        val mdlDocReq1Response = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addDocument(
                credential = mdlCredentialSignature,
                requestedClaims = listOf(mdlGivenNameClaim),
                docRequestIds = listOf(1)
            )
        }
        mdlDocReq1Response.verify(sessionTranscript, deviceRequest = twoMdlDiffAkiRequest, atTime = mdlTimeValidityBegin)

        // Claiming [0, 1] fails when DocRequest 0's issuerIdentifiers do not match
        assertFailsWith<IllegalStateException> {
            buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
                addDocument(
                    credential = mdlCredentialSignature,
                    requestedClaims = listOf(mdlGivenNameClaim),
                    docRequestIds = listOf(0, 1)
                )
            }.verify(sessionTranscript, deviceRequest = twoMdlDiffAkiRequest, atTime = mdlTimeValidityBegin)
        }

        // Duplicate IDs [1, 1] are rejected
        assertFailsWith<IllegalStateException> {
            buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
                addDocument(
                    credential = mdlCredentialSignature,
                    requestedClaims = listOf(mdlGivenNameClaim),
                    docRequestIds = listOf(1, 1)
                )
            }.verify(sessionTranscript, deviceRequest = twoMdlDiffAkiRequest, atTime = mdlTimeValidityBegin)
        }

        // When both DocRequest 0 and DocRequest 1 match, a single MdocDocument with docRequestIds = [0, 1] succeeds
        val twoMdlCompatibleRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(mdlAki))
            )
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                docRequestInfo = DocRequestInfo(issuerIdentifiers = listOf(mdlAki))
            )
        }
        val sharedMdlResponse = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addDocument(
                credential = mdlCredentialSignature,
                requestedClaims = listOf(mdlGivenNameClaim, mdlFamilyNameClaim),
                docRequestIds = listOf(0, 1)
            )
        }
        sharedMdlResponse.verify(sessionTranscript, deviceRequest = twoMdlCompatibleRequest, atTime = mdlTimeValidityBegin)
    }

    @Test
    fun docRequestIdsCrossCheckedAgainstSignedTransactions() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val txRepo = DocumentTypeRepository().apply {
            addTransactionType(TestTxType)
        }
        val mdlGivenNameClaim = MdocRequestedClaim(
            docType = DrivingLicense.MDL_DOCTYPE,
            namespaceName = DrivingLicense.MDL_NAMESPACE,
            dataElementName = "given_name",
            intentToRetain = false
        )

        // 1. Mdoc: signed transaction binds to docRequestId = 0, outer docRequestIds claims [1] -> rejected
        val mdlWithTxAuth = createMdlCredentialWithAuthorizations(
            authorizedNamespaces = listOf(ISO_18013_TRANSACTION_DATA_NAMESPACE)
        )
        val twoMdlTxRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TestTxType.identifier to false),
                ),
                docRequestInfo = DocRequestInfo(
                    transactionData = TransactionsInfo(
                        mapOf(TestTxType.identifier to TestTxType.serializeIso18013Request("tx-0"))
                    )
                )
            )
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TestTxType.identifier to false),
                ),
                docRequestInfo = DocRequestInfo(
                    transactionData = TransactionsInfo(
                        mapOf(TestTxType.identifier to TestTxType.serializeIso18013Request("tx-1"))
                    )
                )
            )
        }
        val txData0 = twoMdlTxRequest.docRequests[0].getTransactionData(txRepo).single()
        val txElements0 = txData0.generateMdocResponseElements(mdlWithTxAuth, null, docRequestId = 0)
        val devNamespaces0 = buildDeviceNamespaces {
            addNamespace(ISO_18013_TRANSACTION_DATA_NAMESPACE) {
                addDataElement(
                    TestTxType.identifier,
                    buildCborMap {
                        for ((k, v) in txElements0) {
                            put(k, v)
                        }
                    }
                )
            }
        }
        // Matching outer docRequestIds = [0] succeeds
        buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addDocument(
                credential = mdlWithTxAuth,
                requestedClaims = listOf(mdlGivenNameClaim),
                deviceNamespaces = devNamespaces0,
                docRequestIds = listOf(0)
            )
        }.verify(
            sessionTranscript = sessionTranscript,
            deviceRequest = twoMdlTxRequest,
            documentTypeRepository = txRepo,
            atTime = mdlTimeValidityBegin
        )
        // Mismatched outer docRequestIds = [1] is rejected
        val mdocMismatchError = assertFailsWith<IllegalStateException> {
            buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
                addDocument(
                    credential = mdlWithTxAuth,
                    requestedClaims = listOf(mdlGivenNameClaim),
                    deviceNamespaces = devNamespaces0,
                    docRequestIds = listOf(1)
                )
            }.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = twoMdlTxRequest,
                documentTypeRepository = txRepo,
                atTime = mdlTimeValidityBegin
            )
        }
        assertEquals(
            "Signed transaction 'docRequestId' 0 is not in document docRequestIds [1]",
            mdocMismatchError.message
        )

        // 2. SD-JWT: signed KB-JWT binds to doc_request_id = 0, outer docRequestIds claims [1] -> rejected
        val twoPidTxRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1),
        ) {
            for (i in 0..1) {
                addDocRequest(
                    docType = EUPersonalID.EUPID_VCT,
                    nameSpaces = mapOf(
                        "_" to mapOf("sdjwtvc_given_name" to false),
                        ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TestTxType.identifier to false),
                    ),
                    docRequestInfo = DocRequestInfo(
                        docFormat = "dc+sd-jwt",
                        dataElementIdentifierMapping = mapOf(
                            "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                        ),
                        transactionData = TransactionsInfo(
                            mapOf(TestTxType.identifier to TestTxType.serializeIso18013Request("pid-tx-$i"))
                        )
                    )
                )
            }
        }
        val pidTx0 = twoPidTxRequest.docRequests[0].getTransactionData(txRepo).single()
        val pidTx0Claims = pidTx0.generateSdJwtResponseClaims(euPidCredential, null, docRequestId = 0)
        val taggedTranscript = Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(sessionTranscript)))
        val nonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(taggedTranscript)).toBase64Url()
        val sdJwt = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val sdJwtKb0 = sdJwt.present(
            signingKey = AsymmetricKey.AnonymousSecureAreaBased(
                alias = euPidCredential.alias,
                secureArea = euPidCredential.secureArea,
                keyInfo = euPidCredential.secureArea.getKeyInfo(euPidCredential.alias)
            ),
            nonce = nonce,
            audience = nonce,
            creationTime = euPidTimeSigned
        ) {
            putJsonObject(TestTxType.kbJwtResponseClaimName) {
                for ((k, v) in pidTx0Claims) {
                    put(k, v)
                }
            }
        }
        val sdJwtBytes = ByteString(sdJwtKb0.compactSerialization.encodeToByteArray().zlibDeflate())

        // Matching outer docRequestIds = [0] succeeds
        buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addOtherDocument(OtherDocument("dc+sd-jwt", sdJwtBytes, docRequestIds = listOf(0)))
        }.verify(
            sessionTranscript = sessionTranscript,
            deviceRequest = twoPidTxRequest,
            documentTypeRepository = txRepo,
            atTime = euPidTimeSigned
        )
        // Mismatched outer docRequestIds = [1] is rejected
        val sdJwtMismatchError = assertFailsWith<IllegalStateException> {
            buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
                addOtherDocument(OtherDocument("dc+sd-jwt", sdJwtBytes, docRequestIds = listOf(1)))
            }.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = twoPidTxRequest,
                documentTypeRepository = txRepo,
                atTime = euPidTimeSigned
            )
        }
        assertEquals(
            "Signed transaction 'doc_request_id' 0 is not in document docRequestIds [1]",
            sdJwtMismatchError.message
        )
    }

    @Test
    fun encryptedDocumentsMultiDocRequestIdsSubsetAndZkValidation() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val recipientKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val encryptionParameters = EncryptionParameters.fromValues(recipientKey.publicKey)
        val encSessionTranscript = EncryptedDocuments.buildSessionTranscript(
            originalSessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters
        )
        val otherDoc1 = arbitraryAudienceOtherDocument(
            encSessionTranscript,
            audience = transcriptAudience(encSessionTranscript)
        ).copy(docRequestIds = listOf(1))
        val zkSpec = ZkSystemSpec(id = "zk_spec_1", system = "mock_zk")
        val zkDoc2 = ZkDocument(
            documentData = ZkDocumentData(
                zkSystemSpecId = zkSpec.id,
                docType = DrivingLicense.MDL_DOCTYPE,
                timestamp = mdlTimeSigned,
                issuerSigned = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("age_over_18" to true.toDataItem())),
                deviceSigned = emptyMap(),
                msoX5chain = X509CertChain(listOf(mdlDsCert))
            ),
            proof = ByteString(9, 8, 7),
            docRequestIds = listOf(2),
        )

        val multiEncryptedRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1, 2),
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(docResponseEncryption = encryptionParameters)
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("sdjwtvc_given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    docResponseEncryption = encryptionParameters,
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("age_over_18" to false)),
                docRequestInfo = DocRequestInfo(
                    docResponseEncryption = encryptionParameters,
                    zkRequest = ZkRequest(systemSpecs = listOf(zkSpec), zkRequired = true)
                )
            )
        }

        // Single EncryptedDocuments carrying mdoc (0), OtherDocument (1), and ZkDocument (2)
        val combinedResponse = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addEncryptedDocuments(
                encryptionParameters = encryptionParameters,
                docRequestIds = listOf(0, 1, 2)
            ) {
                addDocument(
                    credential = mdlCredentialSignature,
                    requestedClaims = listOf(
                        MdocRequestedClaim(
                            docType = DrivingLicense.MDL_DOCTYPE,
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "given_name",
                            intentToRetain = false
                        )
                    ),
                    docRequestIds = listOf(0)
                )
                addOtherDocument(otherDoc1)
                addZkDocument(zkDoc2)
            }
        }
        combinedResponse.verify(sessionTranscript, deviceRequest = multiEncryptedRequest, atTime = mdlTimeValidityBegin)
        val decrypted = combinedResponse.encryptedDocuments.single().decrypt(
            recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
            encryptionParameters = encryptionParameters,
            sessionTranscript = sessionTranscript,
            atTime = euPidTimeSigned,
            deviceRequest = multiEncryptedRequest
        )
        assertEquals(listOf(0), decrypted.documents.single().docRequestIds)
        assertEquals(listOf(1), decrypted.otherDocuments.single().docRequestIds)
        assertEquals(listOf(2), decrypted.zkDocuments.single().docRequestIds)

        // Inner docRequestIds outside outer EncryptedDocuments.docRequestIds is rejected by decrypt()
        val subsetViolation = EncryptedDocuments.Builder(
            sessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters,
            docRequestIds = listOf(0)
        ).apply {
            addOtherDocument(otherDoc1) // inner docRequestIds = [1], outer = [0]
        }.build()
        val subsetError = assertFailsWith<IllegalStateException> {
            subsetViolation.decrypt(
                recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
                encryptionParameters = encryptionParameters,
                sessionTranscript = sessionTranscript,
                atTime = euPidTimeSigned,
                deviceRequest = multiEncryptedRequest
            )
        }
        assertEquals(
            "Decrypted document docRequestIds [1] is not a subset of EncryptedDocuments docRequestIds [0]",
            subsetError.cause?.message
        )

        // ZkDocument inside EncryptedDocuments pointing to non-ZK DocRequest 0 is rejected by decrypt()
        val badZkEncrypted = EncryptedDocuments.Builder(
            sessionTranscript = sessionTranscript,
            encryptionParameters = encryptionParameters,
            docRequestIds = listOf(0)
        ).apply {
            addZkDocument(zkDoc2.copy(docRequestIds = listOf(0)))
        }.build()
        assertFailsWith<IllegalStateException> {
            badZkEncrypted.decrypt(
                recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
                encryptionParameters = encryptionParameters,
                sessionTranscript = sessionTranscript,
                atTime = mdlTimeValidityBegin,
                deviceRequest = multiEncryptedRequest
            )
        }
    }

    @Test
    fun encryptedDocumentsHandoverAndHpkeInfoMatchesSecondEdition() = runTest {
        provisionDocuments()
        val originalSessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte()))
        }
        val recipientKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val encryptionParameters = EncryptionParameters.fromValues(recipientKey.publicKey)

        // Verify Clause 12.6.4 handover structure:
        // OriginalSessionTranscriptHash = SHA-256(#6.24(bstr .cbor originalSessionTranscript))
        // EncryptedDocumentsHandover = ["encryptedDocuments", OriginalSessionTranscriptHash, EncryptionParametersBytes]
        // encSessionTranscript = [null, null, EncryptedDocumentsHandover]
        val expectedOriginalTranscriptHash = Crypto.digest(
            Algorithm.SHA256,
            Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(originalSessionTranscript))))
        )
        val expectedEncryptionParametersBytes = Tagged(
            Tagged.ENCODED_CBOR,
            Bstr(Cbor.encode(encryptionParameters.dataItem))
        )
        val encSessionTranscript = EncryptedDocuments.buildSessionTranscript(
            originalSessionTranscript = originalSessionTranscript,
            encryptionParameters = encryptionParameters
        )
        assertEquals(Simple.NULL, encSessionTranscript.asArray[0])
        assertEquals(Simple.NULL, encSessionTranscript.asArray[1])
        val handover = encSessionTranscript.asArray[2].asArray
        assertEquals(3, handover.size)
        assertEquals("encryptedDocuments", handover[0].asTstr)
        assertContentEquals(expectedOriginalTranscriptHash, handover[1].asBstr)
        assertEquals(expectedEncryptionParametersBytes, handover[2])

        // Verify Clause 12.7.1 HPKE info parameter = SessionTranscriptBytes = #6.24(bstr .cbor encSessionTranscript)
        val response = buildDeviceResponse(originalSessionTranscript, DeviceResponse.STATUS_OK) {
            addEncryptedDocuments(
                encryptionParameters = encryptionParameters,
                docRequestIds = listOf(0)
            ) {
                addDocument(
                    credential = mdlCredentialSignature,
                    requestedClaims = listOf(
                        MdocRequestedClaim(
                            docType = DrivingLicense.MDL_DOCTYPE,
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "given_name",
                            intentToRetain = false
                        )
                    ),
                    docRequestIds = listOf(0)
                )
            }
        }
        val encryptedDoc = response.encryptedDocuments.single()
        val expectedHpkeInfo = Cbor.encode(
            Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(encSessionTranscript)))
        )
        val manualPlaintext = Hpke.getDecrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPrivateKey = AsymmetricKey.anonymous(recipientKey),
            encapsulatedKey = encryptedDoc.enc.toByteArray(),
            info = expectedHpkeInfo
        ).decrypt(
            ciphertext = encryptedDoc.ciphertext.toByteArray(),
            aad = byteArrayOf()
        )
        val plaintextDocs = EncryptedDocumentsPlaintext.fromDataItem(Cbor.decode(manualPlaintext))
        assertEquals(1, plaintextDocs.documents.size)
        // Inner mdoc deviceAuth must verify against encSessionTranscript (not originalSessionTranscript)
        plaintextDocs.documents.single().verify(
            sessionTranscript = encSessionTranscript,
            eReaderKey = null,
            transactionData = emptyList(),
            atTime = mdlTimeValidityBegin
        )
        assertFailsWith<IllegalStateException> {
            plaintextDocs.documents.single().verify(
                sessionTranscript = originalSessionTranscript,
                eReaderKey = null,
                transactionData = emptyList(),
                atTime = mdlTimeValidityBegin
            )
        }
    }

    @Test
    fun issuerAuthX5tHeaderAndVerification() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val response = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addDocument(
                credential = mdlCredentialSignature,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = DrivingLicense.MDL_DOCTYPE,
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "given_name",
                        intentToRetain = false
                    )
                )
            )
        }
        response.verify(sessionTranscript = sessionTranscript, atTime = mdlTimeValidityBegin)
        val validDoc = response.documents.single()
        val expectedX5t = buildCborArray {
            add(Algorithm.SHA256.coseAlgorithmIdentifier!!)
            add(Crypto.digest(Algorithm.SHA256, mdlDsCert.encoded.toByteArray()))
        }
        assertEquals(expectedX5t, validDoc.issuerAuth.protectedHeaders[Cose.COSE_LABEL_X5T.toCoseLabel])
        assertFalse(mdlCredentialSignature.validate(now = mdlTimeValidityBegin).hasErrors)

        // Re-sign MSO with wrong x5t digest, unsupported x5t hash alg (-43 = SHA-384), and duplicate x5t
        val dsCertifiedKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(mdlDsCert)), mdlDsKey)
        val msoPayload = validDoc.issuerAuth.payload!!
        for ((badProtectedX5t, badUnprotectedX5t) in listOf(
            buildCborArray { add(-16); add(ByteArray(32) { 0x11 }) } to null,
            buildCborArray { add(-43); add(Crypto.digest(Algorithm.SHA384, mdlDsCert.encoded.toByteArray())) } to null,
            expectedX5t to expectedX5t,
        )) {
            val badIssuerAuth = Cose.coseSign1Sign(
                signingKey = dsCertifiedKey,
                message = msoPayload,
                includeMessageInPayload = true,
                protectedHeaders = mapOf(
                    Cose.COSE_LABEL_ALG.toCoseLabel to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem(),
                    Cose.COSE_LABEL_X5T.toCoseLabel to badProtectedX5t,
                ),
                unprotectedHeaders = buildMap {
                    put(Cose.COSE_LABEL_X5CHAIN.toCoseLabel, X509CertChain(listOf(mdlDsCert)).toDataItem())
                    if (badUnprotectedX5t != null) {
                        put(Cose.COSE_LABEL_X5T.toCoseLabel, badUnprotectedX5t)
                    }
                }
            )
            val tamperedResponseItem = buildCborMap {
                put("version", "1.0")
                put("status", 0)
                putCborArray("documents") {
                    addCborMap {
                        put("docType", validDoc.docType)
                        putCborMap("issuerSigned") {
                            put("nameSpaces", validDoc.issuerNamespaces.toDataItem())
                            put("issuerAuth", badIssuerAuth.toDataItem())
                        }
                        putCborMap("deviceSigned") {
                            putCborMap("deviceAuth") {
                                put("deviceSignature", (validDoc.deviceAuth as DeviceAuth.Signature).signature.toDataItem())
                            }
                            put("nameSpaces", Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(validDoc.deviceNamespaces.toDataItem()))))
                        }
                    }
                }
            }
            val tamperedParsed = DeviceResponse.fromDataItem(tamperedResponseItem)
            assertFailsWith<IllegalStateException> {
                tamperedParsed.verify(sessionTranscript = sessionTranscript, atTime = mdlTimeValidityBegin)
            }

            // Also verify MdocCredential.validate() fails with an x5t error when issuerProvidedData carries bad x5t
            val badIssuerProvidedData = Cbor.encode(
                buildCborMap {
                    put("nameSpaces", validDoc.issuerNamespaces.toDataItem())
                    put("issuerAuth", badIssuerAuth.toDataItem())
                }
            )
            val validationResult = mdlCredentialSignature.validate(
                issuerProvidedAuthenticationData = ByteString(badIssuerProvidedData),
                now = mdlTimeValidityBegin
            )
            assertTrue(validationResult.hasErrors)
            assertTrue(validationResult.errors.any { it.message.contains("x5t") })
        }
    }

    @Test
    fun keylessMdocDocumentVerificationAndAntiStripping() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val dsCertifiedKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(mdlDsCert)), mdlDsKey)

        val movieDoc = documentStore.createDocument()
        val keylessMovieCred = UtopiaMovieTicket.getDocumentType().createKeylessMdocCredentialWithSampleData(
            document = movieDoc,
            dsKey = dsCertifiedKey,
            signedAt = mdlTimeSigned,
            validFrom = mdlTimeValidityBegin,
            validUntil = mdlTimeValidityEnd,
            domain = "mdoc_keyless"
        )

        val response = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addDocument(
                credential = keylessMovieCred,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                        namespaceName = UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE,
                        dataElementName = "ticket_id",
                        intentToRetain = false
                    )
                ),
                docRequestIds = listOf(0)
            )
        }
        val encoded = Cbor.encode(response.toDataItem())
        val rawDocItem = Cbor.decode(encoded)["documents"][0]
        assertFalse(rawDocItem.hasKey("deviceSigned"))

        // 1. Default standalone verify() requires key binding -> rejects keyless mdoc
        val parsedDefault = DeviceResponse.fromDataItem(Cbor.decode(encoded))
        assertFailsWith<IllegalStateException> {
            parsedDefault.verify(sessionTranscript = sessionTranscript, atTime = mdlTimeValidityBegin)
        }

        // 2. Standalone verifySingleDoc(keyBindingRequired = false) succeeds and deviceAuth is null
        val parsedAllowKeyless = DeviceResponse.fromDataItem(Cbor.decode(encoded))
        parsedAllowKeyless.verifySingleDoc(
            sessionTranscript = sessionTranscript,
            transactionData = emptyList(),
            atTime = mdlTimeValidityBegin,
            keyBindingRequired = false
        )
        assertNull(parsedAllowKeyless.documents.single().deviceAuth)

        // 3. DeviceRequest without nonKeyBoundSupported = true (or v1.0) rejects keyless mdoc
        val reqWithoutNonKeyBound = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0)
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                nameSpaces = mapOf(UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to mapOf("ticket_id" to false)),
                docRequestInfo = DocRequestInfo(nonKeyBoundSupported = false)
            )
        }
        assertFailsWith<IllegalStateException> {
            DeviceResponse.fromDataItem(Cbor.decode(encoded)).verify(
                sessionTranscript = sessionTranscript,
                atTime = mdlTimeValidityBegin,
                deviceRequest = reqWithoutNonKeyBound
            )
        }

        // 4. DeviceRequest v1.1 with nonKeyBoundSupported = true accepts keyless mdoc AND key-bound mdoc
        val reqWithNonKeyBound = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0)
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                nameSpaces = mapOf(UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to mapOf("ticket_id" to false)),
                docRequestInfo = DocRequestInfo(nonKeyBoundSupported = true)
            )
        }
        val parsedWithReq = DeviceResponse.fromDataItem(Cbor.decode(encoded))
        parsedWithReq.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin,
            deviceRequest = reqWithNonKeyBound
        )
        assertNull(parsedWithReq.documents.single().deviceAuth)

        val keyBoundPhotoIdReq = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0)
        ) {
            addDocRequest(
                docType = PhotoID.PHOTO_ID_DOCTYPE,
                nameSpaces = mapOf(PhotoID.ISO_23220_2_NAMESPACE to mapOf("family_name" to false)),
                docRequestInfo = DocRequestInfo(nonKeyBoundSupported = true)
            )
        }
        val keyBoundPhotoIdResp = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addDocument(
                credential = photoIdCredential,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = PhotoID.PHOTO_ID_DOCTYPE,
                        namespaceName = PhotoID.ISO_23220_2_NAMESPACE,
                        dataElementName = "family_name",
                        intentToRetain = false
                    )
                ),
                docRequestIds = listOf(0)
            )
        }
        val verifiedKeyBoundPhotoIdResp = DeviceResponse.fromDataItem(
            Cbor.decode(Cbor.encode(keyBoundPhotoIdResp.toDataItem()))
        )
        verifiedKeyBoundPhotoIdResp.verify(
            sessionTranscript = sessionTranscript,
            atTime = photoIdTimeValidityBegin,
            deviceRequest = keyBoundPhotoIdReq
        )

        // 5. EncryptedDocuments with keyless mdoc verifies when nonKeyBoundSupported = true
        val recipientKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val encParams = EncryptionParameters.fromValues(
            recipientPublicKey = recipientKey.publicKey
        )
        val reqWithNonKeyBoundEnc = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0)
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                nameSpaces = mapOf(UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to mapOf("ticket_id" to false)),
                docRequestInfo = DocRequestInfo(
                    nonKeyBoundSupported = true,
                    docResponseEncryption = encParams
                )
            )
        }
        val encResp = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addEncryptedDocuments(encryptionParameters = encParams, docRequestIds = listOf(0)) {
                addDocument(
                    credential = keylessMovieCred,
                    requestedClaims = listOf(
                        MdocRequestedClaim(
                            docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                            namespaceName = UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE,
                            dataElementName = "ticket_id",
                            intentToRetain = false
                        )
                    ),
                    docRequestIds = listOf(0)
                )
            }
        }
        val parsedEnc = DeviceResponse.fromDataItem(Cbor.decode(Cbor.encode(encResp.toDataItem())))
        parsedEnc.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin,
            deviceRequest = reqWithNonKeyBoundEnc
        )
        val decryptedPt = parsedEnc.encryptedDocuments.single().decrypt(
            recipientPrivateKey = AsymmetricKey.anonymous(recipientKey),
            encryptionParameters = encParams,
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin,
            deviceRequest = reqWithNonKeyBoundEnc
        )
        assertEquals(1, decryptedPt.documents.size)
        assertNull(decryptedPt.documents.single().deviceAuth)

        // 6. Anti-stripping security check: stripping deviceSigned from a key-bound MSO MUST fail
        // even when keyBindingRequired = false / nonKeyBoundSupported = true!
        val validKeyBoundDoc = verifiedKeyBoundPhotoIdResp.documents.single()
        val strippedDeviceSignedCbor = buildCborMap {
            put("version", "1.1")
            put("status", 0)
            putCborArray("documents") {
                addCborMap {
                    put("docType", validKeyBoundDoc.docType)
                    putCborMap("issuerSigned") {
                        put("nameSpaces", validKeyBoundDoc.issuerNamespaces.toDataItem())
                        put("issuerAuth", validKeyBoundDoc.issuerAuth.toDataItem())
                    }
                    putCborArray("docRequestIds") { add(0) }
                }
            }
        }
        val strippedParsed = DeviceResponse.fromDataItem(strippedDeviceSignedCbor)
        assertFailsWith<IllegalStateException> {
            strippedParsed.verifySingleDoc(
                sessionTranscript = sessionTranscript,
                transactionData = emptyList(),
                atTime = photoIdTimeValidityBegin,
                keyBindingRequired = false
            )
        }
        assertFailsWith<IllegalStateException> {
            strippedParsed.verify(
                sessionTranscript = sessionTranscript,
                atTime = photoIdTimeValidityBegin,
                deviceRequest = keyBoundPhotoIdReq
            )
        }
    }

    @Test
    fun keylessOtherDocumentSdJwtVerificationAndAntiStripping() = runTest {
        provisionDocuments()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val dsCertifiedKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(mdlDsCert)), mdlDsKey)

        val movieDoc = documentStore.createDocument()
        val keylessSdJwtCred = UtopiaMovieTicket.getDocumentType().createKeylessSdJwtVcCredentialWithSampleData(
            document = movieDoc,
            dsKey = dsCertifiedKey,
            signedAt = mdlTimeSigned,
            validFrom = mdlTimeValidityBegin,
            validUntil = mdlTimeValidityEnd,
            domain = "sdjwt_keyless"
        )
        val filteredKeylessPresentation = SdJwt.fromCompactSerialization(
            keylessSdJwtCred.issuerProvidedData.decodeToString()
        ).filter { path, _ ->
            path.first().jsonPrimitive.content == "ticket_id"
        }.compactSerialization

        val response = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addOtherDocument(
                OtherDocument(
                    docFormat = "dc+sd-jwt",
                    data = ByteString(filteredKeylessPresentation.encodeToByteArray().zlibDeflate()),
                    docRequestIds = listOf(0)
                )
            )
        }
        val encoded = Cbor.encode(response.toDataItem())
        val decompressedJwt = response.otherDocuments.single().data.toByteArray().zlibInflate().decodeToString()
        assertTrue(decompressedJwt.endsWith("~"))

        // 1. Default standalone verify() requires key binding -> rejects keyless SD-JWT
        assertFailsWith<IllegalStateException> {
            DeviceResponse.fromDataItem(Cbor.decode(encoded)).verify(
                sessionTranscript = sessionTranscript,
                atTime = mdlTimeValidityBegin
            )
        }

        // 2. Standalone OtherDocument.verify(keyBindingRequired = false) succeeds
        val parsedAllowKeyless = DeviceResponse.fromDataItem(Cbor.decode(encoded))
        parsedAllowKeyless.otherDocuments.single().verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            transactionData = emptyList(),
            atTime = mdlTimeValidityBegin,
            keyBindingRequired = false
        )
        assertEquals(1, parsedAllowKeyless.otherDocuments.size)

        // 3. DeviceRequest without nonKeyBoundSupported = true rejects keyless SD-JWT
        val reqWithoutNonKeyBound = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0)
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_VCT,
                nameSpaces = mapOf("_" to mapOf("c0" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf("c0" to buildJsonArray { add(JsonPrimitive("ticket_id")) }),
                    nonKeyBoundSupported = false
                )
            )
        }
        assertFailsWith<IllegalStateException> {
            DeviceResponse.fromDataItem(Cbor.decode(encoded)).verify(
                sessionTranscript = sessionTranscript,
                atTime = mdlTimeValidityBegin,
                deviceRequest = reqWithoutNonKeyBound
            )
        }

        // 4. DeviceRequest v1.1 with nonKeyBoundSupported = true accepts keyless SD-JWT
        val reqWithNonKeyBound = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0)
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_VCT,
                nameSpaces = mapOf("_" to mapOf("c0" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf("c0" to buildJsonArray { add(JsonPrimitive("ticket_id")) }),
                    nonKeyBoundSupported = true
                )
            )
        }
        val parsedWithReq = DeviceResponse.fromDataItem(Cbor.decode(encoded))
        parsedWithReq.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin,
            deviceRequest = reqWithNonKeyBound
        )
        assertEquals(1, parsedWithReq.otherDocuments.size)

        // 5. Anti-stripping security check: key-bound SD-JWT (with cnf) stripped of its KB-JWT MUST fail
        // even when keyBindingRequired = false or nonKeyBoundSupported = true!
        val keyBoundSdJwt = SdJwt.fromCompactSerialization(euPidCredential.issuerProvidedData.decodeToString())
        val strippedPresentation = keyBoundSdJwt.filter { _, _ -> true }.compactSerialization
        assertTrue(strippedPresentation.endsWith("~"))
        val strippedResponse = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
            addOtherDocument(
                OtherDocument(
                    docFormat = "dc+sd-jwt",
                    data = ByteString(strippedPresentation.encodeToByteArray().zlibDeflate()),
                    docRequestIds = listOf(0)
                )
            )
        }
        val strippedEncoded = Cbor.encode(strippedResponse.toDataItem())
        assertFailsWith<IllegalStateException> {
            DeviceResponse.fromDataItem(Cbor.decode(strippedEncoded)).otherDocuments.single().verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                transactionData = emptyList(),
                atTime = euPidTimeValidityBegin,
                keyBindingRequired = false
            )
        }
        val euPidReqNonKeyBound = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0)
        ) {
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("c0" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf("c0" to buildJsonArray { add(JsonPrimitive("given_name")) }),
                    nonKeyBoundSupported = true
                )
            )
        }
        assertFailsWith<IllegalStateException> {
            DeviceResponse.fromDataItem(Cbor.decode(strippedEncoded)).verify(
                sessionTranscript = sessionTranscript,
                atTime = euPidTimeValidityBegin,
                deviceRequest = euPidReqNonKeyBound
            )
        }
    }

    @Test
    fun nonEcKeysIssuerReaderAndDeviceAuthMdoc() = runTest {
        provisionDocuments()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validFrom = now - 1.days
        val validUntil = now + 30.days
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(7, 8, 9)) }
        val eReaderPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey = eReaderPrivateKey.publicKey
        val eReaderAsymmetricKey = AsymmetricKey.anonymous(eReaderPrivateKey)

        for ((rootAlg, leafAlg, deviceAlg) in listOf(
            Triple(Algorithm.ML_DSA_65, Algorithm.ML_DSA_44, Algorithm.ML_DSA_44),
            Triple(Algorithm.ML_DSA_87, Algorithm.ML_DSA_65, Algorithm.ML_DSA_65),
            Triple(Algorithm.RS256_2048, Algorithm.RS256_2048, Algorithm.RS256_2048),
            Triple(Algorithm.RS256_2048, Algorithm.PS256_2048, Algorithm.PS256_2048),
        )) {
            val iacaEphemeral = AsymmetricKey.ephemeral(rootAlg) as AsymmetricKey.AnonymousExplicit
            val iacaCert = MdocUtil.generateIacaCertificate(
                iacaKey = iacaEphemeral,
                subject = X500Name.fromName("CN=Non-EC TEST IACA,C=US"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil,
                issuerAltNameUrl = "https://issuer.example.com",
                crlUrl = "https://issuer.example.com/crl"
            )
            val iacaCertified = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(iacaCert)),
                privateKey = iacaEphemeral.privateKey,
                algorithm = rootAlg
            )

            val dsEphemeral = AsymmetricKey.ephemeral(leafAlg) as AsymmetricKey.AnonymousExplicit
            val dsCert = MdocUtil.generateDsCertificate(
                iacaKey = iacaCertified,
                dsKey = dsEphemeral.publicKey,
                subject = X500Name.fromName("CN=Non-EC TEST DS,C=US"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil
            )
            val dsCertified = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(dsCert, iacaCert)),
                privateKey = dsEphemeral.privateKey,
                algorithm = leafAlg
            )

            val readerRootEphemeral = AsymmetricKey.ephemeral(rootAlg) as AsymmetricKey.AnonymousExplicit
            val readerRootCert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = readerRootEphemeral,
                subject = X500Name.fromName("CN=Non-EC TEST Reader Root,C=US"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil,
                crlUrl = "https://reader.example.com/crl"
            )
            val readerRootCertified = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(readerRootCert)),
                privateKey = readerRootEphemeral.privateKey,
                algorithm = rootAlg
            )
            val readerKeyInfo = softwareSecureArea.createKey(
                alias = null,
                createKeySettings = CreateKeySettings(algorithm = leafAlg)
            )
            val readerCert = MdocUtil.generateReaderCertificate(
                readerRootKey = readerRootCertified,
                readerKey = readerKeyInfo.publicKey,
                subject = X500Name.fromName("CN=Non-EC TEST Reader,C=US"),
                dnsName = "reader.example.com",
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil
            )
            val readerCertified = AsymmetricKey.X509CertifiedSecureAreaBased(
                secureArea = softwareSecureArea,
                certChain = X509CertChain(listOf(readerCert, readerRootCert)),
                keyInfo = readerKeyInfo
            )

            // Provision mDL with non-EC issuer DS key and non-EC deviceKey
            val doc = documentStore.createDocument()
            val cred = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
                document = doc,
                secureArea = softwareSecureArea,
                createKeySettings = CreateKeySettings(algorithm = deviceAlg),
                dsKey = dsCertified,
                signedAt = validFrom,
                validFrom = validFrom,
                validUntil = validUntil,
                expectedUpdate = null,
                domain = "mdoc_non_ec"
            )
            val validation = cred.validate(now = now)
            assertFalse(validation.hasErrors, "Unexpected validation errors for $deviceAlg: ${validation.errors}")
            assertEquals(cred.getAttestation().publicKey, cred.mso.deviceKey)
            assertEquals(cred.mso, MobileSecurityObject.fromDataItem(cred.mso.toDataItem()))

            // Reader authentication (both per-doc readerAuth and multi-doc readerAuthAll)
            val request = buildDeviceRequest(
                sessionTranscript = sessionTranscript,
                deviceRequestInfo = deviceRequestInfoForIds(0)
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false, "age_over_21" to false)),
                    docRequestInfo = DocRequestInfo(),
                    readerKey = readerCertified
                )
                addReaderAuthAll(readerKey = readerCertified)
            }
            val parsedRequest = DeviceRequest.fromDataItem(Cbor.decode(Cbor.encode(request.toDataItem())))
            parsedRequest.verifyReaderAuthentication(sessionTranscript)
            assertEquals(
                leafAlg.coseAlgorithmIdentifier!!.toLong(),
                parsedRequest.docRequests.single().readerAuth!!.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber
            )
            assertEquals(
                leafAlg.coseAlgorithmIdentifier!!.toLong(),
                parsedRequest.readerAuthAll.single().protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber
            )

            // Holder presentation: even with eReaderKey supplied and forceSignature = false,
            // non-EC deviceKey automatically uses DeviceAuth.Signature (not ECDH MAC).
            val response = buildDeviceResponse(
                sessionTranscript = sessionTranscript,
                status = DeviceResponse.STATUS_OK,
                eReaderKey = eReaderKey
            ) {
                addDocument(
                    credential = cred,
                    requestedClaims = listOf(
                        MdocRequestedClaim(
                            docType = DrivingLicense.MDL_DOCTYPE,
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "given_name",
                            intentToRetain = false
                        ),
                        MdocRequestedClaim(
                            docType = DrivingLicense.MDL_DOCTYPE,
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "age_over_21",
                            intentToRetain = false
                        ),
                    ),
                    docRequestIds = listOf(0)
                )
            }
            val encodedResponse = Cbor.encode(response.toDataItem())
            val parsedResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedResponse))
            parsedResponse.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderAsymmetricKey,
                atTime = now,
                deviceRequest = parsedRequest
            )
            val verifiedDoc = parsedResponse.documents.single()
            val deviceSig = assertIs<DeviceAuth.Signature>(verifiedDoc.deviceAuth)
            assertEquals(
                deviceAlg.coseAlgorithmIdentifier!!.toLong(),
                deviceSig.signature.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber
            )
            assertEquals(
                leafAlg.coseAlgorithmIdentifier!!.toLong(),
                verifiedDoc.issuerAuth.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber
            )
            verifiedDoc.issuerCertChain.validate(now)

            // Also verify MdocDocument.fromNamespaces with explicit non-EC signing key
            val directSigningKey = AsymmetricKey.AnonymousSecureAreaBased(
                alias = cred.alias,
                secureArea = softwareSecureArea,
                keyInfo = softwareSecureArea.getKeyInfo(cred.alias)
            )
            val fromNamespacesDoc = MdocDocument.fromNamespaces(
                issuerNamespaces = verifiedDoc.issuerNamespaces,
                issuerAuth = verifiedDoc.issuerAuth,
                docType = DrivingLicense.MDL_DOCTYPE,
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                deviceNamespaces = buildDeviceNamespaces {},
                deviceKey = directSigningKey
            )
            fromNamespacesDoc.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                transactionData = emptyList(),
                atTime = now
            )

            // Negative 1: tampered deviceSignature must fail verification
            val rawSigBytes = deviceSig.signature.signature.copyOf()
            rawSigBytes[0] = rawSigBytes[0] xor 0x01
            val tamperedSig = CoseSign1(
                protectedHeaders = deviceSig.signature.protectedHeaders,
                unprotectedHeaders = deviceSig.signature.unprotectedHeaders,
                signature = rawSigBytes,
                payload = deviceSig.signature.payload
            )
            val tamperedSigItem = buildCborMap {
                put("version", "1.1")
                put("status", 0)
                putCborArray("documents") {
                    addCborMap {
                        put("docType", verifiedDoc.docType)
                        putCborMap("issuerSigned") {
                            put("nameSpaces", verifiedDoc.issuerNamespaces.toDataItem())
                            put("issuerAuth", verifiedDoc.issuerAuth.toDataItem())
                        }
                        putCborMap("deviceSigned") {
                            putCborMap("deviceAuth") {
                                put("deviceSignature", tamperedSig.toDataItem())
                            }
                            put("nameSpaces", Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(verifiedDoc.deviceNamespaces.toDataItem()))))
                        }
                    }
                }
            }
            val tamperedErr = assertFailsWith<IllegalStateException> {
                DeviceResponse.fromDataItem(tamperedSigItem).verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = eReaderAsymmetricKey,
                    atTime = now
                )
            }
            assertIs<SignatureVerificationException>(tamperedErr.cause)

            // Negative 2: deviceMac with non-EC MSO deviceKey must fail cleanly with IllegalStateException
            val forgedMac = CoseMac0(
                protectedHeaders = mapOf(Cose.COSE_LABEL_ALG.toCoseLabel to Algorithm.HMAC_SHA256.coseAlgorithmIdentifier!!.toDataItem()),
                unprotectedHeaders = emptyMap(),
                tag = ByteArray(32),
                payload = null
            )
            val forgedMacItem = buildCborMap {
                put("version", "1.1")
                put("status", 0)
                putCborArray("documents") {
                    addCborMap {
                        put("docType", verifiedDoc.docType)
                        putCborMap("issuerSigned") {
                            put("nameSpaces", verifiedDoc.issuerNamespaces.toDataItem())
                            put("issuerAuth", verifiedDoc.issuerAuth.toDataItem())
                        }
                        putCborMap("deviceSigned") {
                            putCborMap("deviceAuth") {
                                put("deviceMac", forgedMac.toDataItem())
                            }
                            put("nameSpaces", Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(verifiedDoc.deviceNamespaces.toDataItem()))))
                        }
                    }
                }
            }
            assertFailsWith<IllegalStateException> {
                DeviceResponse.fromDataItem(forgedMacItem).verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = eReaderAsymmetricKey,
                    atTime = now
                )
            }
        }
    }

    @Test
    fun nonEcKeysSdJwtVcInDeviceResponse() = runTest {
        provisionDocuments()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validFrom = now - 1.days
        val validUntil = now + 30.days
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(4, 5, 6)) }

        for ((certAlg, signingAlg) in listOf(
            Pair(Algorithm.ML_DSA_44, Algorithm.ML_DSA_44),
            Pair(Algorithm.RS256_2048, Algorithm.RS256_2048),
            Pair(Algorithm.RS256_2048, Algorithm.PS256_2048),
        )) {
            val issuerRoot = AsymmetricKey.ephemeral(certAlg) as AsymmetricKey.AnonymousExplicit
            val issuerRootCert = MdocUtil.generateIacaCertificate(
                iacaKey = issuerRoot,
                subject = X500Name.fromName("CN=Non-EC PID Root,C=UT"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil,
                issuerAltNameUrl = "https://pid-issuer.example.com",
                crlUrl = "https://pid-issuer.example.com/crl"
            )
            val issuerDs = AsymmetricKey.ephemeral(signingAlg) as AsymmetricKey.AnonymousExplicit
            val issuerDsCert = MdocUtil.generateDsCertificate(
                iacaKey = AsymmetricKey.X509CertifiedExplicit(
                    certChain = X509CertChain(listOf(issuerRootCert)),
                    privateKey = issuerRoot.privateKey,
                    algorithm = certAlg
                ),
                dsKey = issuerDs.publicKey,
                subject = X500Name.fromName("CN=Non-EC PID DS,C=UT"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil
            )
            val dsCertified = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(issuerDsCert, issuerRootCert)),
                privateKey = issuerDs.privateKey,
                algorithm = signingAlg
            )

            val doc = documentStore.createDocument()
            val pidCred = EUPersonalID.getDocumentType().createKeyBoundSdJwtVcCredentialWithSampleData(
                document = doc,
                secureArea = softwareSecureArea,
                createKeySettings = CreateKeySettings(algorithm = signingAlg),
                dsKey = dsCertified,
                signedAt = validFrom,
                validFrom = validFrom,
                validUntil = validUntil,
                domain = "sdjwt_non_ec"
            )

            val readerRoot = AsymmetricKey.ephemeral(certAlg) as AsymmetricKey.AnonymousExplicit
            val readerRootCert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = readerRoot,
                subject = X500Name.fromName("CN=Non-EC SD-JWT Reader Root,C=UT"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil,
                crlUrl = "https://reader.example.com/crl"
            )
            val readerLeaf = AsymmetricKey.ephemeral(signingAlg) as AsymmetricKey.AnonymousExplicit
            val readerCert = MdocUtil.generateReaderCertificate(
                readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                    certChain = X509CertChain(listOf(readerRootCert)),
                    privateKey = readerRoot.privateKey,
                    algorithm = certAlg
                ),
                readerKey = readerLeaf.publicKey,
                subject = X500Name.fromName("CN=Non-EC SD-JWT Reader,C=UT"),
                dnsName = "reader.example.com",
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil
            )
            val readerCertified = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(readerCert, readerRootCert)),
                privateKey = readerLeaf.privateKey,
                algorithm = signingAlg
            )

            val req = buildDeviceRequest(
                sessionTranscript = sessionTranscript,
                deviceRequestInfo = deviceRequestInfoForIds(0)
            ) {
                addDocRequest(
                    docType = EUPersonalID.EUPID_VCT,
                    nameSpaces = mapOf("_" to mapOf("c0" to false)),
                    docRequestInfo = DocRequestInfo(
                        docFormat = "dc+sd-jwt",
                        dataElementIdentifierMapping = mapOf("c0" to buildJsonArray { add(JsonPrimitive("given_name")) })
                    ),
                    readerKey = readerCertified
                )
            }
            req.verifyReaderAuthentication(sessionTranscript)

            val sessionTranscriptBytes = Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(Cbor.encode(sessionTranscript))
            )
            val expectedNonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(sessionTranscriptBytes)).toBase64Url()
            val filteredSdJwt = SdJwt.fromCompactSerialization(pidCred.issuerProvidedData.decodeToString())
                .filter { path, _ -> path.first().jsonPrimitive.content == "given_name" }
            val kbSigningKey = AsymmetricKey.AnonymousSecureAreaBased(
                alias = pidCred.alias,
                secureArea = softwareSecureArea,
                keyInfo = softwareSecureArea.getKeyInfo(pidCred.alias)
            )
            val sdJwtKb = filteredSdJwt.present(
                signingKey = kbSigningKey,
                nonce = expectedNonce,
                audience = expectedNonce,
                creationTime = now
            )

            val response = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
                addOtherDocument(
                    OtherDocument(
                        docFormat = "dc+sd-jwt",
                        data = ByteString(sdJwtKb.compactSerialization.encodeToByteArray().zlibDeflate()),
                        docRequestIds = listOf(0)
                    )
                )
            }
            val parsed = DeviceResponse.fromDataItem(Cbor.decode(Cbor.encode(response.toDataItem())))
            parsed.verify(
                sessionTranscript = sessionTranscript,
                atTime = now,
                deviceRequest = req
            )
            assertEquals(1, parsed.otherDocuments.size)
        }
    }

    @Test
    fun secondEditionMsoZkDocumentDataAndMdocVerificationChecks() = runTest {
        provisionDocuments()
        val now = Clock.System.now().let { Instant.fromEpochSeconds(it.epochSeconds) }

        // 1. ZkDocumentData omits empty issuerSigned/deviceSigned and rejects present-but-empty maps/arrays
        val zkDataIssuerOnly = ZkDocumentData(
            zkSystemSpecId = "spec1",
            docType = DrivingLicense.MDL_DOCTYPE,
            timestamp = now,
            issuerSigned = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("age_over_18" to Simple.TRUE)),
            deviceSigned = emptyMap(),
            msoX5chain = X509CertChain(listOf(mdlDsCert))
        )
        val encodedZkData = Cbor.decode(Cbor.encode(zkDataIssuerOnly.toDataItem()))
        assertTrue(encodedZkData.hasKey("issuerSigned"))
        assertFalse(encodedZkData.hasKey("deviceSigned"))
        assertEquals(zkDataIssuerOnly, ZkDocumentData.fromDataItem(encodedZkData))

        assertFailsWith<IllegalArgumentException> {
            ZkDocumentData.fromDataItem(
                buildCborMap {
                    put("zkSystemId", "spec1")
                    put("docType", DrivingLicense.MDL_DOCTYPE)
                    put("timestamp", now.toDataItemDateTimeString())
                    putCborMap("issuerSigned") {}
                }
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ZkDocumentData.fromDataItem(
                buildCborMap {
                    put("zkSystemId", "spec1")
                    put("docType", DrivingLicense.MDL_DOCTYPE)
                    put("timestamp", now.toDataItemDateTimeString())
                    putCborMap("deviceSigned") {
                        putCborArray(DrivingLicense.MDL_NAMESPACE) {}
                    }
                }
            )
        }

        // 2. MobileSecurityObject and IssuerSignedItem reject DigestID > 2^31 - 1, and keyAuthorizations rejects empty/overlapping entries
        val deviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val tooLargeDigestMso = MobileSecurityObject(
            version = "1.0",
            docType = DrivingLicense.MDL_DOCTYPE,
            signedAt = now,
            validFrom = now,
            validUntil = now + 30.days,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = mapOf(
                DrivingLicense.MDL_NAMESPACE to mapOf(0x8000_0000L to ByteString(ByteArray(32)))
            ),
            deviceKey = deviceKey.publicKey
        )
        assertTrue(tooLargeDigestMso.validate(now = null).hasErrors)
        assertFailsWith<IllegalArgumentException> {
            MobileSecurityObject.fromDataItem(tooLargeDigestMso.toDataItem())
        }
        assertTrue(
            IssuerSignedItem.fromValues(
                digestId = 0x8000_0000L,
                random = ByteString(ByteArray(16)),
                dataElementIdentifier = "given_name",
                dataElementValue = "Erika".toDataItem()
            ).validate().hasErrors
        )
        val overlappingKeyAuthMso = MobileSecurityObject(
            version = "1.0",
            docType = DrivingLicense.MDL_DOCTYPE,
            signedAt = now,
            validFrom = now,
            validUntil = now + 30.days,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = mapOf(
                DrivingLicense.MDL_NAMESPACE to mapOf(0L to ByteString(ByteArray(32)))
            ),
            deviceKey = deviceKey.publicKey,
            deviceKeyAuthorizedNamespaces = listOf("org.example.ns"),
            deviceKeyAuthorizedDataElements = mapOf("org.example.ns" to listOf("elem1"))
        )
        assertTrue(overlappingKeyAuthMso.validate(now = null).hasErrors)
        assertFailsWith<IllegalArgumentException> {
            MobileSecurityObject.fromDataItem(overlappingKeyAuthMso.toDataItem())
        }
        val emptyKeyAuthCbor = buildCborMap {
            put("version", "1.0")
            put("digestAlgorithm", "SHA-256")
            put("docType", DrivingLicense.MDL_DOCTYPE)
            putCborMap("valueDigests") {
                putCborMap(DrivingLicense.MDL_NAMESPACE) {
                    put(0L, ByteArray(32))
                }
            }
            putCborMap("deviceKeyInfo") {
                put("deviceKey", deviceKey.publicKey.toCoseKey().toDataItem())
                putCborMap("keyAuthorizations") {}
            }
            putCborMap("validityInfo") {
                put("signed", now.toDataItemDateTimeString())
                put("validFrom", now.toDataItemDateTimeString())
                put("validUntil", (now + 30.days).toDataItemDateTimeString())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            MobileSecurityObject.fromDataItem(emptyKeyAuthCbor)
        }

        // 3. MdocDocument.verify() enforces DS DIGITAL_SIGNATURE KeyUsage, mDL DS EKU, issuing_country, and expiry_date_after_validuntil
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(7, 8, 9)) }
        suspend fun buildSignedMdlDoc(
            dsSigningKey: AsymmetricKey.X509Certified,
            elements: Map<String, DataItem>,
            validUntil: Instant = now + 30.days,
        ): MdocDocument {
            val issuerNs = buildIssuerNamespaces {
                addNamespace(DrivingLicense.MDL_NAMESPACE) {
                    for ((k, v) in elements) {
                        addDataElement(k, v)
                    }
                }
            }
            val mso = MobileSecurityObject(
                version = "1.0",
                docType = DrivingLicense.MDL_DOCTYPE,
                signedAt = now - 1.days,
                validFrom = now - 1.days,
                validUntil = validUntil,
                expectedUpdate = null,
                digestAlgorithm = Algorithm.SHA256,
                valueDigests = issuerNs.getValueDigests(Algorithm.SHA256),
                deviceKey = deviceKey.publicKey
            )
            val taggedMso = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(mso.toDataItem()))))
            val issuerAuth = Cose.coseSign1Sign(
                signingKey = dsSigningKey,
                message = taggedMso,
                includeMessageInPayload = true,
                protectedHeaders = mapOf(
                    Cose.COSE_LABEL_ALG.toCoseLabel to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
                ),
                unprotectedHeaders = mapOf(
                    Cose.COSE_LABEL_X5CHAIN.toCoseLabel to dsSigningKey.certChain.toDataItem()
                )
            )
            val deviceNs = buildDeviceNamespaces {}
            val deviceAuthenticationBytes = Cbor.encode(
                Tagged(
                    Tagged.ENCODED_CBOR,
                    Bstr(
                        Cbor.encode(
                            buildCborArray {
                                add("DeviceAuthentication")
                                add(sessionTranscript)
                                add(DrivingLicense.MDL_DOCTYPE)
                                add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(deviceNs.toDataItem()))))
                            }
                        )
                    )
                )
            )
            val deviceSig = Cose.coseSign1Sign(
                signingKey = AsymmetricKey.anonymous(deviceKey, Algorithm.ES256),
                message = deviceAuthenticationBytes,
                includeMessageInPayload = false,
                protectedHeaders = mapOf(
                    Cose.COSE_LABEL_ALG.toCoseLabel to Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
                ),
                unprotectedHeaders = emptyMap()
            )
            return MdocDocument(
                docType = DrivingLicense.MDL_DOCTYPE,
                issuerAuth = issuerAuth,
                issuerNamespaces = issuerNs,
                deviceAuth = DeviceAuth.Signature(deviceSig),
                deviceNamespaces = deviceNs,
                errors = emptyMap()
            )
        }

        // 3a. DS cert without mDL DS EKU fails for mDL
        val dsNoEkuKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsNoEkuCert = buildX509Cert(
            publicKey = dsNoEkuKey.publicKey,
            signingKey = AsymmetricKey.anonymous(dsNoEkuKey),
            serialNumber = ASN1Integer(99L),
            subject = X500Name.fromName("CN=No EKU DS,C=US"),
            issuer = X500Name.fromName("CN=No EKU DS,C=US"),
            validFrom = now - 2.days,
            validUntil = now + 60.days
        ) {
            setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
        }
        val docNoEku = buildSignedMdlDoc(
            dsSigningKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(dsNoEkuCert)), dsNoEkuKey),
            elements = mapOf("given_name" to "Erika".toDataItem())
        )
        assertFailsWith<IllegalStateException> {
            docNoEku.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                transactionData = emptyList(),
                atTime = now
            )
        }

        // 3b. mDL issuing_country mismatching DS certificate countryName fails
        val iacaUsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val iacaUsCert = MdocUtil.generateIacaCertificate(
            iacaKey = AsymmetricKey.anonymous(iacaUsKey),
            subject = X500Name.fromName("CN=US IACA,C=US"),
            serial = ASN1Integer(100L),
            validFrom = now - 2.days,
            validUntil = now + 365.days,
            issuerAltNameUrl = null,
            crlUrl = null
        )
        val dsUsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val dsUsCert = MdocUtil.generateDsCertificate(
            iacaKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(iacaUsCert)), iacaUsKey),
            dsKey = dsUsKey.publicKey,
            subject = X500Name.fromName("CN=US DS,C=US,ST=US-CA"),
            serial = ASN1Integer(101L),
            validFrom = now - 2.days,
            validUntil = now + 365.days
        )
        val dsUsCertified = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(dsUsCert, iacaUsCert)),
            privateKey = dsUsKey
        )
        val docWrongCountry = buildSignedMdlDoc(
            dsSigningKey = dsUsCertified,
            elements = mapOf("issuing_country" to "DE".toDataItem())
        )
        assertFailsWith<IllegalStateException> {
            docWrongCountry.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                transactionData = emptyList(),
                atTime = now
            )
        }

        // 3c. mDL issuing_jurisdiction mismatching DS certificate stateOrProvinceName fails
        val docWrongState = buildSignedMdlDoc(
            dsSigningKey = dsUsCertified,
            elements = mapOf(
                "issuing_country" to "US".toDataItem(),
                "issuing_jurisdiction" to "US-NY".toDataItem()
            )
        )
        assertFailsWith<IllegalStateException> {
            docWrongState.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                transactionData = emptyList(),
                atTime = now
            )
        }

        // 3d. expiry_date_after_validuntil = false or with expiry_date <= validUntil fails; valid case passes
        val docFalseFlag = buildSignedMdlDoc(
            dsSigningKey = dsUsCertified,
            elements = mapOf(
                "issuing_country" to "US".toDataItem(),
                "issuing_jurisdiction" to "US-CA".toDataItem(),
                "expiry_date" to LocalDate.parse("2035-01-01").toDataItemFullDate(),
                "expiry_date_after_validuntil" to Simple.FALSE
            )
        )
        assertFailsWith<IllegalStateException> {
            docFalseFlag.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                transactionData = emptyList(),
                atTime = now
            )
        }

        val docExpiryBeforeMso = buildSignedMdlDoc(
            dsSigningKey = dsUsCertified,
            elements = mapOf(
                "issuing_country" to "US".toDataItem(),
                "issuing_jurisdiction" to "US-CA".toDataItem(),
                "expiry_date" to (now + 5.days).toDataItemDateTimeString(),
                "expiry_date_after_validuntil" to Simple.TRUE
            ),
            validUntil = now + 30.days
        )
        assertFailsWith<IllegalStateException> {
            docExpiryBeforeMso.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                transactionData = emptyList(),
                atTime = now
            )
        }

        val docValidAfterValidUntil = buildSignedMdlDoc(
            dsSigningKey = dsUsCertified,
            elements = mapOf(
                "issuing_country" to "US".toDataItem(),
                "issuing_jurisdiction" to "US-CA".toDataItem(),
                "expiry_date" to (now + 90.days).toDataItemDateTimeString(),
                "expiry_date_after_validuntil" to Simple.TRUE
            ),
            validUntil = now + 30.days
        )
        docValidAfterValidUntil.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            transactionData = emptyList(),
            atTime = now
        )

        // 4. VerificationUtil.verifyMdocDeviceResponse rejects empty DeviceResponse when all UseCases are optional
        val optionalOnlyReq = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(UseCase(mandatory = false, documentSets = listOf(DocumentSet(listOf(0))), purposeHints = emptyMap()))
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
            )
        }
        val emptyResp = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {}
        assertFailsWith<IllegalStateException> {
            VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = emptyResp.toDataItem(),
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                documentTypeRepository = null,
                zkSystemRepository = null,
                request = optionalOnlyReq
            )
        }
    }

    @Test
    fun hybridCompositeSignaturesIssuerReaderAndDeviceAuthMdoc() = runTest {
        provisionDocuments()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validFrom = now - 1.days
        val validUntil = now + 30.days
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(7, 8, 9)) }
        val encodedSessionTranscript = Cbor.encode(sessionTranscript)
        val eReaderPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey = eReaderPrivateKey.publicKey
        val eReaderAsymmetricKey = AsymmetricKey.anonymous(eReaderPrivateKey)

        fun isAlgorithmSupported(alg: Algorithm): Boolean = when {
            alg.isHybridSigning -> alg in Crypto.supportedHybridSigningAlgorithms
            alg.curve != null -> alg.curve in Crypto.supportedCurves
            else -> true
        }

        val testTuples = listOf(
            Triple(Algorithm.ML_DSA_65_ES256, Algorithm.ML_DSA_44_ES256, Algorithm.ML_DSA_44_ES256),
            Triple(Algorithm.ML_DSA_87_ES384, Algorithm.ML_DSA_65_ES256, Algorithm.ML_DSA_65_ES384),
            Triple(Algorithm.ML_DSA_65_ES384, Algorithm.ML_DSA_87_ES384, Algorithm.ML_DSA_87_ES384),
            Triple(Algorithm.ML_DSA_65_ES256, Algorithm.ML_DSA_44_ED25519, Algorithm.ML_DSA_44_ED25519),
            // Transitional mixed PKI chains (classical EC root <-> hybrid leaf and vice versa)
            Triple(Algorithm.ESP384, Algorithm.ML_DSA_65_ES256, Algorithm.ML_DSA_65_ES256),
            Triple(Algorithm.ML_DSA_87_ES384, Algorithm.ESP256, Algorithm.ML_DSA_65_ES256),
        ).filter { (rootAlg, leafAlg, deviceAlg) ->
            isAlgorithmSupported(rootAlg) && isAlgorithmSupported(leafAlg) && isAlgorithmSupported(deviceAlg)
        }
        if (Crypto.supportedHybridSigningAlgorithms.isNotEmpty()) {
            assertTrue(testTuples.isNotEmpty())
        }

        for ((rootAlg, leafAlg, deviceAlg) in testTuples) {
            val iacaEphemeral = AsymmetricKey.ephemeral(rootAlg) as AsymmetricKey.AnonymousExplicit
            val iacaCert = MdocUtil.generateIacaCertificate(
                iacaKey = iacaEphemeral,
                subject = X500Name.fromName("CN=Hybrid TEST IACA,C=US"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil,
                issuerAltNameUrl = "https://issuer.example.com",
                crlUrl = "https://issuer.example.com/crl"
            )
            val iacaCertified = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(iacaCert)),
                privateKey = iacaEphemeral.privateKey,
                algorithm = rootAlg
            )

            val dsEphemeral = AsymmetricKey.ephemeral(leafAlg) as AsymmetricKey.AnonymousExplicit
            val dsCert = MdocUtil.generateDsCertificate(
                iacaKey = iacaCertified,
                dsKey = dsEphemeral.publicKey,
                subject = X500Name.fromName("CN=Hybrid TEST DS,C=US"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil
            )
            val dsCertified = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(dsCert, iacaCert)),
                privateKey = dsEphemeral.privateKey,
                algorithm = leafAlg
            )

            val readerRootEphemeral = AsymmetricKey.ephemeral(rootAlg) as AsymmetricKey.AnonymousExplicit
            val readerRootCert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = readerRootEphemeral,
                subject = X500Name.fromName("CN=Hybrid TEST Reader Root,C=US"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil,
                crlUrl = "https://reader.example.com/crl"
            )
            val readerRootCertified = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(readerRootCert)),
                privateKey = readerRootEphemeral.privateKey,
                algorithm = rootAlg
            )
            val readerKeyInfo = softwareSecureArea.createKey(
                alias = null,
                createKeySettings = CreateKeySettings(algorithm = leafAlg)
            )
            val readerCert = MdocUtil.generateReaderCertificate(
                readerRootKey = readerRootCertified,
                readerKey = readerKeyInfo.publicKey,
                subject = X500Name.fromName("CN=Hybrid TEST Reader,C=US"),
                dnsName = "reader.example.com",
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil
            )
            val readerCertified = AsymmetricKey.X509CertifiedSecureAreaBased(
                secureArea = softwareSecureArea,
                certChain = X509CertChain(listOf(readerCert, readerRootCert)),
                keyInfo = readerKeyInfo
            )

            val customNs = "org.example.custom"
            val doc = documentStore.createDocument()
            val cred = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
                document = doc,
                secureArea = softwareSecureArea,
                createKeySettings = CreateKeySettings(algorithm = deviceAlg),
                dsKey = dsCertified,
                signedAt = validFrom,
                validFrom = validFrom,
                validUntil = validUntil,
                expectedUpdate = null,
                domain = "mdoc_hybrid",
                deviceKeyAuthorizedNamespaces = listOf(customNs)
            )
            val validation = cred.validate(now = now)
            assertFalse(validation.hasErrors, "Unexpected validation errors for $deviceAlg: ${validation.errors}")
            assertEquals(cred.getAttestation().publicKey, cred.mso.deviceKey)
            val msoDeviceKey = assertIs<HybridPublicKey>(cred.mso.deviceKey)
            assertEquals(deviceAlg, msoDeviceKey.algorithm)
            val coseDeviceKey = msoDeviceKey.toCoseKey()
            assertEquals(Cose.COSE_KEY_TYPE_AKP.toDataItem(), coseDeviceKey.keyType)
            assertEquals(
                deviceAlg.coseAlgorithmIdentifier!!.toDataItem(),
                coseDeviceKey.labels[Cose.COSE_KEY_ALG.toCoseLabel]
            )
            assertEquals(cred.mso, MobileSecurityObject.fromDataItem(cred.mso.toDataItem()))

            val request = buildDeviceRequest(
                sessionTranscript = sessionTranscript,
                deviceRequestInfo = deviceRequestInfoForIds(0)
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false, "age_over_21" to false)),
                    docRequestInfo = DocRequestInfo(),
                    readerKey = readerCertified
                )
                addReaderAuthAll(readerKey = readerCertified)
            }
            val parsedRequest = DeviceRequest.fromDataItem(Cbor.decode(Cbor.encode(request.toDataItem())))
            parsedRequest.verifyReaderAuthentication(sessionTranscript = sessionTranscript, atTime = now)

            val expectedLeafAlg = leafAlg.curve?.defaultSigningAlgorithm ?: leafAlg
            val response = buildDeviceResponse(
                sessionTranscript = sessionTranscript,
                status = DeviceResponse.STATUS_OK,
                eReaderKey = eReaderKey
            ) {
                addDocument(
                    credential = cred,
                    requestedClaims = listOf(
                        MdocRequestedClaim(
                            docType = DrivingLicense.MDL_DOCTYPE,
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "given_name",
                            intentToRetain = false
                        ),
                        MdocRequestedClaim(
                            docType = DrivingLicense.MDL_DOCTYPE,
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "age_over_21",
                            intentToRetain = false
                        ),
                    ),
                    deviceNamespaces = buildDeviceNamespaces {
                        addNamespace(customNs) {
                            addDataElement("custom_flag", Simple.TRUE)
                        }
                    },
                    docRequestIds = listOf(0)
                )
            }
            val encodedResponse = Cbor.encode(response.toDataItem())

            // 1. Verify via DeviceResponse.verify(...)
            val parsedResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedResponse))
            parsedResponse.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderAsymmetricKey,
                atTime = now,
                deviceRequest = parsedRequest
            )
            val verifiedDoc = parsedResponse.documents.single()
            val deviceSig = assertIs<DeviceAuth.Signature>(verifiedDoc.deviceAuth)
            assertEquals(
                deviceAlg.coseAlgorithmIdentifier!!.toLong(),
                deviceSig.signature.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber
            )
            assertEquals(
                expectedLeafAlg.coseAlgorithmIdentifier!!.toLong(),
                verifiedDoc.issuerAuth.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber
            )
            assertEquals(
                Simple.TRUE,
                verifiedDoc.deviceNamespaces.data[customNs]?.get("custom_flag")
            )
            verifiedDoc.issuerCertChain.validate(validateAt = now)

            // 2. Verify via DeviceResponse.verifySingleDoc(...)
            DeviceResponse.fromDataItem(Cbor.decode(encodedResponse)).verifySingleDoc(
                sessionTranscript = sessionTranscript,
                transactionData = emptyList(),
                atTime = now
            )

            // 3. Verify via VerificationUtil.verifyMdocDeviceResponse(...)
            val verifiedPresentations = VerificationUtil.verifyMdocDeviceResponse(
                now = now,
                deviceResponse = Cbor.decode(encodedResponse),
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderAsymmetricKey,
                documentTypeRepository = null,
                zkSystemRepository = null,
                request = parsedRequest
            )
            assertEquals(1, verifiedPresentations.size)

            // 4. Verify via legacy DeviceResponseParser
            @Suppress("DEPRECATION")
            val legacyDoc = DeviceResponseParser(encodedResponse, encodedSessionTranscript)
                .parse().documents.single()
            assertTrue(legacyDoc.issuerSignedAuthenticated)
            assertTrue(legacyDoc.deviceSignedAuthenticated)
            assertTrue(legacyDoc.deviceSignedAuthenticatedViaSignature)
            assertEquals(cred.mso.deviceKey, legacyDoc.deviceKey)
            assertTrue(legacyDoc.getDeviceEntryBoolean(customNs, "custom_flag"))

            // 5. Verify MdocDocument.fromNamespaces with explicit hybrid SecureArea signing key
            val directSigningKey = AsymmetricKey.AnonymousSecureAreaBased(
                alias = cred.alias,
                secureArea = softwareSecureArea,
                keyInfo = softwareSecureArea.getKeyInfo(cred.alias)
            )
            val fromNamespacesDoc = MdocDocument.fromNamespaces(
                issuerNamespaces = verifiedDoc.issuerNamespaces,
                issuerAuth = verifiedDoc.issuerAuth,
                docType = DrivingLicense.MDL_DOCTYPE,
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                deviceNamespaces = verifiedDoc.deviceNamespaces,
                deviceKey = directSigningKey
            )
            fromNamespacesDoc.verify(
                sessionTranscript = sessionTranscript,
                eReaderKey = null,
                transactionData = emptyList(),
                atTime = now
            )

            // Negative 1 & 2: tampered deviceSignature in ML-DSA half (byte 0) and traditional half (last byte)
            for (flipIndex in listOf(0, deviceSig.signature.signature.lastIndex)) {
                val rawSigBytes = deviceSig.signature.signature.copyOf()
                rawSigBytes[flipIndex] = rawSigBytes[flipIndex] xor 0x01
                val tamperedSig = CoseSign1(
                    protectedHeaders = deviceSig.signature.protectedHeaders,
                    unprotectedHeaders = deviceSig.signature.unprotectedHeaders,
                    signature = rawSigBytes,
                    payload = deviceSig.signature.payload
                )
                val tamperedSigItem = buildCborMap {
                    put("version", "1.1")
                    put("status", 0)
                    putCborArray("documents") {
                        addCborMap {
                            put("docType", verifiedDoc.docType)
                            putCborMap("issuerSigned") {
                                put("nameSpaces", verifiedDoc.issuerNamespaces.toDataItem())
                                put("issuerAuth", verifiedDoc.issuerAuth.toDataItem())
                            }
                            putCborMap("deviceSigned") {
                                putCborMap("deviceAuth") {
                                    put("deviceSignature", tamperedSig.toDataItem())
                                }
                                put("nameSpaces", Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(verifiedDoc.deviceNamespaces.toDataItem()))))
                            }
                        }
                    }
                }
                val tamperedEncoded = Cbor.encode(tamperedSigItem)
                val tamperedErr = assertFailsWith<IllegalStateException> {
                    DeviceResponse.fromDataItem(Cbor.decode(tamperedEncoded)).verify(
                        sessionTranscript = sessionTranscript,
                        eReaderKey = eReaderAsymmetricKey,
                        atTime = now
                    )
                }
                assertIs<SignatureVerificationException>(tamperedErr.cause)
                @Suppress("DEPRECATION")
                val legacyTampered = DeviceResponseParser(tamperedEncoded, encodedSessionTranscript)
                    .parse().documents.single()
                assertFalse(legacyTampered.deviceSignedAuthenticated)
            }

            // Negative 3: tampered issuerAuth (traditional half / last byte)
            val rawIssuerSig = verifiedDoc.issuerAuth.signature.copyOf()
            rawIssuerSig[rawIssuerSig.lastIndex] = rawIssuerSig[rawIssuerSig.lastIndex] xor 0x01
            val tamperedIssuerAuth = CoseSign1(
                protectedHeaders = verifiedDoc.issuerAuth.protectedHeaders,
                unprotectedHeaders = verifiedDoc.issuerAuth.unprotectedHeaders,
                signature = rawIssuerSig,
                payload = verifiedDoc.issuerAuth.payload
            )
            val tamperedIssuerItem = buildCborMap {
                put("version", "1.0")
                put("status", 0)
                putCborArray("documents") {
                    addCborMap {
                        put("docType", verifiedDoc.docType)
                        putCborMap("issuerSigned") {
                            put("nameSpaces", verifiedDoc.issuerNamespaces.toDataItem())
                            put("issuerAuth", tamperedIssuerAuth.toDataItem())
                        }
                        putCborMap("deviceSigned") {
                            putCborMap("deviceAuth") {
                                put("deviceSignature", deviceSig.signature.toDataItem())
                            }
                            put("nameSpaces", Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(verifiedDoc.deviceNamespaces.toDataItem()))))
                        }
                    }
                }
            }
            val tamperedIssuerEncoded = Cbor.encode(tamperedIssuerItem)
            val issuerTamperedErr = assertFailsWith<IllegalStateException> {
                DeviceResponse.fromDataItem(Cbor.decode(tamperedIssuerEncoded)).verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = eReaderAsymmetricKey,
                    atTime = now
                )
            }
            assertIs<SignatureVerificationException>(issuerTamperedErr.cause)
            @Suppress("DEPRECATION")
            val legacyBadIssuer = DeviceResponseParser(tamperedIssuerEncoded, encodedSessionTranscript)
                .parse().documents.single()
            assertFalse(legacyBadIssuer.issuerSignedAuthenticated)

            // Negative 4: forged deviceMac with hybrid MSO deviceKey must fail cleanly
            val forgedMac = CoseMac0(
                protectedHeaders = mapOf(Cose.COSE_LABEL_ALG.toCoseLabel to Algorithm.HMAC_SHA256.coseAlgorithmIdentifier!!.toDataItem()),
                unprotectedHeaders = emptyMap(),
                tag = ByteArray(32),
                payload = null
            )
            val forgedMacItem = buildCborMap {
                put("version", "1.1")
                put("status", 0)
                putCborArray("documents") {
                    addCborMap {
                        put("docType", verifiedDoc.docType)
                        putCborMap("issuerSigned") {
                            put("nameSpaces", verifiedDoc.issuerNamespaces.toDataItem())
                            put("issuerAuth", verifiedDoc.issuerAuth.toDataItem())
                        }
                        putCborMap("deviceSigned") {
                            putCborMap("deviceAuth") {
                                put("deviceMac", forgedMac.toDataItem())
                            }
                            put("nameSpaces", Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(verifiedDoc.deviceNamespaces.toDataItem()))))
                        }
                    }
                }
            }
            assertFailsWith<IllegalStateException> {
                DeviceResponse.fromDataItem(forgedMacItem).verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = eReaderAsymmetricKey,
                    atTime = now
                )
            }
        }
    }

    @Test
    fun hybridCompositeSignaturesEncryptedAndSdJwtDeviceResponse() = runTest {
        if (Crypto.supportedHybridSigningAlgorithms.isEmpty()) {
            return@runTest
        }
        provisionDocuments()
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validFrom = now - 1.days
        val validUntil = now + 30.days
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(4, 5, 6)) }

        suspend fun createHybridIssuerDs(alg: Algorithm): AsymmetricKey.X509CertifiedExplicit {
            val issuerRoot = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
            val issuerRootCert = MdocUtil.generateIacaCertificate(
                iacaKey = issuerRoot,
                subject = X500Name.fromName("CN=Hybrid PID Root $alg,C=UT"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil,
                issuerAltNameUrl = "https://pid-issuer.example.com",
                crlUrl = "https://pid-issuer.example.com/crl"
            )
            val issuerDs = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
            val issuerDsCert = MdocUtil.generateDsCertificate(
                iacaKey = AsymmetricKey.X509CertifiedExplicit(
                    certChain = X509CertChain(listOf(issuerRootCert)),
                    privateKey = issuerRoot.privateKey,
                    algorithm = alg
                ),
                dsKey = issuerDs.publicKey,
                subject = X500Name.fromName("CN=Hybrid PID DS $alg,C=UT"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil
            )
            return AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(issuerDsCert, issuerRootCert)),
                privateKey = issuerDs.privateKey,
                algorithm = alg
            )
        }

        suspend fun createHybridReaderKey(alg: Algorithm): AsymmetricKey.X509CertifiedExplicit {
            val readerRoot = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
            val readerRootCert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = readerRoot,
                subject = X500Name.fromName("CN=Hybrid Reader Root $alg,C=UT"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil,
                crlUrl = "https://reader.example.com/crl"
            )
            val readerLeaf = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
            val readerCert = MdocUtil.generateReaderCertificate(
                readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                    certChain = X509CertChain(listOf(readerRootCert)),
                    privateKey = readerRoot.privateKey,
                    algorithm = alg
                ),
                readerKey = readerLeaf.publicKey,
                subject = X500Name.fromName("CN=Hybrid Reader $alg,C=UT"),
                dnsName = "reader.example.com",
                serial = ASN1Integer.fromRandom(128),
                validFrom = validFrom,
                validUntil = validUntil
            )
            return AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(readerCert, readerRootCert)),
                privateKey = readerLeaf.privateKey,
                algorithm = alg
            )
        }

        // Part 1: OtherDocument (dc+sd-jwt, Annex H.2) across all supported hybrid composite algorithms
        var sdJwtTestedCount = 0
        for (alg in HybridSigning.specs.map { it.algorithm }) {
            if (alg !in Crypto.supportedHybridSigningAlgorithms) {
                continue
            }
            sdJwtTestedCount++
            val dsCertified = createHybridIssuerDs(alg)
            val readerCertified = createHybridReaderKey(alg)

            val doc = documentStore.createDocument()
            val pidCred = EUPersonalID.getDocumentType().createKeyBoundSdJwtVcCredentialWithSampleData(
                document = doc,
                secureArea = softwareSecureArea,
                createKeySettings = CreateKeySettings(algorithm = alg),
                dsKey = dsCertified,
                signedAt = validFrom,
                validFrom = validFrom,
                validUntil = validUntil,
                domain = "sdjwt_hybrid"
            )
            val issuedSdJwt = SdJwt.fromCompactSerialization(pidCred.issuerProvidedData.decodeToString())
            val kbPubKey = assertIs<HybridPublicKey>(issuedSdJwt.kbKey)
            assertEquals(alg, kbPubKey.algorithm)

            val req = buildDeviceRequest(
                sessionTranscript = sessionTranscript,
                deviceRequestInfo = deviceRequestInfoForIds(0)
            ) {
                addDocRequest(
                    docType = EUPersonalID.EUPID_VCT,
                    nameSpaces = mapOf("_" to mapOf("c0" to false)),
                    docRequestInfo = DocRequestInfo(
                        docFormat = "dc+sd-jwt",
                        dataElementIdentifierMapping = mapOf("c0" to buildJsonArray { add(JsonPrimitive("given_name")) })
                    ),
                    readerKey = readerCertified
                )
            }
            req.verifyReaderAuthentication(sessionTranscript = sessionTranscript, atTime = now)

            val expectedNonce = transcriptAudience(sessionTranscript)
            val filteredSdJwt = issuedSdJwt.filter { path, _ -> path.first().jsonPrimitive.content == "given_name" }
            val kbSigningKey = AsymmetricKey.AnonymousSecureAreaBased(
                alias = pidCred.alias,
                secureArea = softwareSecureArea,
                keyInfo = softwareSecureArea.getKeyInfo(pidCred.alias)
            )
            val sdJwtKb = filteredSdJwt.present(
                signingKey = kbSigningKey,
                nonce = expectedNonce,
                audience = expectedNonce,
                creationTime = now
            )

            val response = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
                addOtherDocument(
                    OtherDocument(
                        docFormat = "dc+sd-jwt",
                        data = ByteString(sdJwtKb.compactSerialization.encodeToByteArray().zlibDeflate()),
                        docRequestIds = listOf(0)
                    )
                )
            }
            val parsed = DeviceResponse.fromDataItem(Cbor.decode(Cbor.encode(response.toDataItem())))
            parsed.verify(
                sessionTranscript = sessionTranscript,
                atTime = now,
                deviceRequest = req
            )
            assertEquals(1, parsed.otherDocuments.size)

            // Tamper traditional half (last byte of KB-JWT signature) -> must fail verification
            val tildeParts = sdJwtKb.compactSerialization.split("~").toMutableList()
            val kbJwtParts = tildeParts.last().split(".").toMutableList()
            val sigBytes = kbJwtParts[2].fromBase64Url()
            sigBytes[sigBytes.lastIndex] = sigBytes[sigBytes.lastIndex] xor 0x01
            kbJwtParts[2] = sigBytes.toBase64Url()
            tildeParts[tildeParts.lastIndex] = kbJwtParts.joinToString(".")
            val tamperedSdJwtResponse = buildDeviceResponse(sessionTranscript, DeviceResponse.STATUS_OK) {
                addOtherDocument(
                    OtherDocument(
                        docFormat = "dc+sd-jwt",
                        data = ByteString(tildeParts.joinToString("~").encodeToByteArray().zlibDeflate()),
                        docRequestIds = listOf(0)
                    )
                )
            }
            val tamperedParsed = DeviceResponse.fromDataItem(Cbor.decode(Cbor.encode(tamperedSdJwtResponse.toDataItem())))
            assertFailsWith<SignatureVerificationException> {
                tamperedParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = now,
                    deviceRequest = req
                )
            }
        }
        assertEquals(Crypto.supportedHybridSigningAlgorithms.size, sdJwtTestedCount)

        // Part 2: EncryptedDocuments (hybrid Reader Root CA certifying P-256 recipient cert,
        // encrypted mDL + encrypted dc+sd-jwt) + unencrypted KeylessMdocCredential signed with hybrid DS
        val alg = Crypto.supportedHybridSigningAlgorithms.first()
        val dsCertified = createHybridIssuerDs(alg)

        val readerRootEph = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
        val readerRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = readerRootEph,
            subject = X500Name.fromName("CN=Hybrid Enc Reader Root,C=UT"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = validFrom,
            validUntil = validUntil,
            crlUrl = "https://reader.example.com/crl"
        )
        val readerRootCertified = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(readerRootCert)),
            privateKey = readerRootEph.privateKey,
            algorithm = alg
        )
        val readerLeafEph = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
        val readerLeafCert = MdocUtil.generateReaderCertificate(
            readerRootKey = readerRootCertified,
            readerKey = readerLeafEph.publicKey,
            subject = X500Name.fromName("CN=Hybrid Enc Reader,C=UT"),
            dnsName = "reader.example.com",
            serial = ASN1Integer.fromRandom(128),
            validFrom = validFrom,
            validUntil = validUntil
        )
        val readerKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(readerLeafCert, readerRootCert)),
            privateKey = readerLeafEph.privateKey,
            algorithm = alg
        )

        val recipientKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val recipientCert = MdocUtil.generateReaderCertificate(
            readerRootKey = readerRootCertified,
            readerKey = recipientKey.publicKey,
            subject = X500Name.fromName("CN=HPKE Recipient signed by Hybrid CA,C=UT"),
            dnsName = "recipient.example.com",
            serial = ASN1Integer.fromRandom(128),
            validFrom = validFrom,
            validUntil = validUntil
        )
        assertEquals(alg, recipientCert.signatureAlgorithm)
        val encParams = EncryptionParameters.fromValues(
            recipientPublicKey = recipientKey.publicKey,
            recipientCertificates = listOf(recipientCert, readerRootCert)
        )
        encParams.validateCertificateChain(atTime = now)

        val mdlDoc = documentStore.createDocument()
        val hybridMdlCred = DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
            document = mdlDoc,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(algorithm = alg),
            dsKey = dsCertified,
            signedAt = validFrom,
            validFrom = validFrom,
            validUntil = validUntil,
            domain = "mdoc_hybrid_enc"
        )
        val pidDoc = documentStore.createDocument()
        val hybridPidCred = EUPersonalID.getDocumentType().createKeyBoundSdJwtVcCredentialWithSampleData(
            document = pidDoc,
            secureArea = softwareSecureArea,
            createKeySettings = CreateKeySettings(algorithm = alg),
            dsKey = dsCertified,
            signedAt = validFrom,
            validFrom = validFrom,
            validUntil = validUntil,
            domain = "sdjwt_hybrid_enc"
        )
        val movieDoc = documentStore.createDocument()
        val keylessMovieCred = UtopiaMovieTicket.getDocumentType().createKeylessMdocCredentialWithSampleData(
            document = movieDoc,
            dsKey = dsCertified,
            signedAt = validFrom,
            validFrom = validFrom,
            validUntil = validUntil,
            domain = "mdoc_keyless_hybrid"
        )

        val combinedReq = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = deviceRequestInfoForIds(0, 1, 2)
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(docResponseEncryption = encParams)
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("c0" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf("c0" to buildJsonArray { add(JsonPrimitive("given_name")) }),
                    docResponseEncryption = encParams
                )
            )
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                nameSpaces = mapOf(UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to mapOf("ticket_id" to false)),
                docRequestInfo = DocRequestInfo(nonKeyBoundSupported = true)
            )
            addReaderAuthAll(readerKey = readerKey)
        }
        val parsedReq = DeviceRequest.fromDataItem(Cbor.decode(Cbor.encode(combinedReq.toDataItem())))
        parsedReq.verifyReaderAuthentication(sessionTranscript = sessionTranscript, atTime = now)

        val encSessionTranscript = EncryptedDocuments.buildSessionTranscript(
            originalSessionTranscript = sessionTranscript,
            encryptionParameters = encParams
        )
        val encAudience = transcriptAudience(encSessionTranscript)
        val pidSdJwtKb = SdJwt.fromCompactSerialization(hybridPidCred.issuerProvidedData.decodeToString())
            .filter { path, _ -> path.first().jsonPrimitive.content == "given_name" }
            .present(
                signingKey = AsymmetricKey.AnonymousSecureAreaBased(
                    alias = hybridPidCred.alias,
                    secureArea = softwareSecureArea,
                    keyInfo = softwareSecureArea.getKeyInfo(hybridPidCred.alias)
                ),
                nonce = encAudience,
                audience = encAudience,
                creationTime = now
            )

        val combinedResp = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK
        ) {
            addEncryptedDocuments(
                encryptionParameters = encParams,
                docRequestIds = listOf(0, 1)
            ) {
                addDocument(
                    credential = hybridMdlCred,
                    requestedClaims = listOf(
                        MdocRequestedClaim(
                            docType = DrivingLicense.MDL_DOCTYPE,
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "given_name",
                            intentToRetain = false
                        )
                    ),
                    docRequestIds = listOf(0)
                )
                addOtherDocument(
                    OtherDocument(
                        docFormat = "dc+sd-jwt",
                        data = ByteString(pidSdJwtKb.compactSerialization.encodeToByteArray().zlibDeflate()),
                        docRequestIds = listOf(1)
                    )
                )
            }
            addDocument(
                credential = keylessMovieCred,
                requestedClaims = listOf(
                    MdocRequestedClaim(
                        docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                        namespaceName = UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE,
                        dataElementName = "ticket_id",
                        intentToRetain = false
                    )
                ),
                docRequestIds = listOf(2)
            )
        }
        val parsedResp = DeviceResponse.fromDataItem(Cbor.decode(Cbor.encode(combinedResp.toDataItem())))
        parsedResp.verify(
            sessionTranscript = sessionTranscript,
            atTime = now,
            deviceRequest = parsedReq
        )
        assertEquals(1, parsedResp.documents.size)
        assertNull(parsedResp.documents.single().deviceAuth)
        assertEquals(listOf(2), parsedResp.documents.single().docRequestIds)
        assertEquals(1, parsedResp.encryptedDocuments.size)

        val decrypted = parsedResp.encryptedDocuments.single().decrypt(
            recipientPrivateKey = AsymmetricKey.AnonymousExplicit(recipientKey, Algorithm.ECDH_P256),
            encryptionParameters = encParams,
            sessionTranscript = sessionTranscript,
            atTime = now,
            deviceRequest = parsedReq
        )
        assertEquals(1, decrypted.documents.size)
        val decryptedMdl = decrypted.documents.single()
        assertIs<DeviceAuth.Signature>(decryptedMdl.deviceAuth)
        val decryptedDeviceKey = assertIs<HybridPublicKey>(decryptedMdl.mso.deviceKey)
        assertEquals(alg, decryptedDeviceKey.algorithm)
        assertEquals(listOf(0), decryptedMdl.docRequestIds)
        assertEquals(1, decrypted.otherDocuments.size)
        assertEquals(listOf(1), decrypted.otherDocuments.single().docRequestIds)
    }
}
