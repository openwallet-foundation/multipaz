package org.multipaz.mdoc.response

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Simple
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.DeviceAuth
import org.multipaz.mdoc.request.EncryptionParameters
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mdoc.zkp.ZkDocument
import org.multipaz.mdoc.zkp.ZkDocumentData
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.fromHex
import kotlin.experimental.xor
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DeviceResponseTest {
    companion object {
        private const val TAG = "DeviceResponseTest"
    }

    private lateinit var storage: Storage
    private lateinit var softwareSecureArea: SoftwareSecureArea
    private lateinit var secureAreaRepository: SecureAreaRepository

    private lateinit var mdlDocument: Document
    private lateinit var mdlCredentialSignature: MdocCredential
    private lateinit var mdlCredentialMac: MdocCredential
    private lateinit var mdlTimeSigned: Instant
    private lateinit var mdlTimeValidityBegin: Instant
    private lateinit var mdlTimeValidityEnd: Instant
    private lateinit var mdlTimeExpectedUpdate: Instant


    private lateinit var photoIdDocument: Document
    private lateinit var photoIdCredential: MdocCredential
    private lateinit var photoIdTimeSigned: Instant
    private lateinit var photoIdTimeValidityBegin: Instant
    private lateinit var photoIdTimeValidityEnd: Instant
    private lateinit var photoIdTimeExpectedUpdate: Instant

    @BeforeTest
    fun setup() = runBlocking {
        storage = EphemeralStorage()
        softwareSecureArea = SoftwareSecureArea.create(storage)
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(softwareSecureArea)
            .build()
    }

    private suspend fun provisionDocuments() {
        val randomProvider = Random(42)

        val documentStore = buildDocumentStore(
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

        val mdlDsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val mdlDsValidFrom = iacaValidFrom
        val mdlDsValidUntil = iacaValidUntil
        val mdlDsCert = MdocUtil.generateDsCertificate(
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
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "age_over_18",
                        intentToRetain = false
                    ),
                    MdocRequestedClaim(
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
        drParsed.verify(
            sessionTranscript = sessionTranscript,
            atTime = mdlTimeValidityBegin
        )
        assertEquals(drParsed, deviceResponse)

        // Check that we're putting -7 (ES256) in as the COSE algorithm, not -9 (ESP256)
        val ecdsaSignature = drParsed.documents[0].deviceAuth as DeviceAuth.Ecdsa
        assertEquals(
            Algorithm.ES256.coseAlgorithmIdentifier,
            ecdsaSignature.signature.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )

        // Bunch of negative tests to ensure we check signatures and other things correctly.
        checkPoisonIssueSignature(encodedDeviceResponse, sessionTranscript)
        checkPoisonDeviceSignature(encodedDeviceResponse, sessionTranscript)
        checkPoisonIssuerSignedItem(encodedDeviceResponse, sessionTranscript)
        checkChangedDoctype(encodedDeviceResponse, sessionTranscript)
    }

    private suspend fun checkPoisonIssueSignature(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem
    ) {
        val dr = encodedDeviceResponse.copyOf()
        // By inspection we know offset 2916 always contain the issuer signature.
        dr[2916] = dr[2916].xor(0xff.toByte())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(dr))
        assertEquals(
            "Signature on MSO failed to verify",
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
        sessionTranscript: DataItem
    ) {
        val dr = encodedDeviceResponse.copyOf()
        // By inspection we know offset 3232 always contain the device auth EC signature. Poison it.
        dr[3232] = dr[3232].xor(0xff.toByte())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(dr))
        assertEquals(
            "Device authentication signature failed to verify",
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
        sessionTranscript: DataItem
    ) {
        val dr = encodedDeviceResponse.copyOf()
        // By inspection we know the offset 3071 is always the random for IssuerSignedItem
        // for the disclosed value for given_name. Poison it.
        dr[3071] = dr[3071].xor(0xff.toByte())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(dr))
        assertEquals(
            "Digest mismatch for data element given_name in org.iso.18013.5.1",
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = mdlTimeValidityBegin
                )
            }.cause!!.message
        )
    }

    private suspend fun checkChangedDoctype(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem
    ) {
        val dr = encodedDeviceResponse.copyOf()
        // By inspection we know the docType in DeviceResponse is at offset 42. Poison it and
        // check that we catch it it doesn't match with the one in the MSO.
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
            }.cause!!.message
        )
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
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName =  "age_over_18",
                        intentToRetain = false
                    ),
                )
            )
        }
        assertEquals("1.0", deviceResponse.version)
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())

        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))

        // verify() should fail if eReaderKey isn't passed
        assertEquals(
            "Error verifying document 0 in DeviceResponse",
            assertFailsWith(IllegalStateException::class) {
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

        checkPoisonDeviceMac(encodedDeviceResponse, sessionTranscript, eReaderKey)
    }

    private suspend fun checkPoisonDeviceMac(
        encodedDeviceResponse: ByteArray,
        sessionTranscript: DataItem,
        eReaderKey: EcPrivateKey
    ) {
        val dr = encodedDeviceResponse.copyOf()
        // By inspection we know the offset 3088 always contain the device auth EC MAC. Poison it.
        dr[3088] = dr[3088].xor(0xff.toByte())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(dr))
        assertEquals(
            "Device authentication MAC failed to verify",
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
                    atTime = mdlTimeValidityBegin
                )
            }.cause!!.message
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
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "age_over_18",
                        intentToRetain = false
                    ),
                    MdocRequestedClaim(
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
            }.cause!!.message
        )
        assertEquals(
            "MSO is not valid anymore",
            assertFailsWith(IllegalStateException::class) {
                drParsed.verify(
                    sessionTranscript = sessionTranscript,
                    atTime = mdlTimeValidityEnd + 10.seconds
                )
            }.cause!!.message
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
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "age_over_18",
                        intentToRetain = false
                    ),
                    MdocRequestedClaim(
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
                        namespaceName = PhotoID.ISO_23220_2_NAMESPACE,
                        dataElementName = "family_name",
                        intentToRetain = false
                    ),
                    MdocRequestedClaim(
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
                    "digestID": 48,
                    "random": h'7a5dc224bebb1e4403c308f97052d8fe',
                    "elementIdentifier": "age_over_18",
                    "elementValue": true
                  } >>), 24(<< {
                    "digestID": 25,
                    "random": h'eb0f39cfd0c6668ae37453b8efecfbcd',
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
                    "digestID": 25,
                    "random": h'e4e4c755d0ae57496bdb42b9c9679178',
                    "elementIdentifier": "family_name",
                    "elementValue": "Mustermann"
                  } >>), 24(<< {
                    "digestID": 17,
                    "random": h'4e2b0087715aae6d958c6a14e03a4eeb',
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
                      "org.iso.23220.photoID.1": -42
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
                        namespaceName = DrivingLicense.MDL_NAMESPACE,
                        dataElementName = "age_over_18",
                        intentToRetain = false
                    ),
                    MdocRequestedClaim(
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
                    "digestID": 48,
                    "random": h'7a5dc224bebb1e4403c308f97052d8fe',
                    "elementIdentifier": "age_over_18",
                    "elementValue": true
                  } >>), 24(<< {
                    "digestID": 25,
                    "random": h'eb0f39cfd0c6668ae37453b8efecfbcd',
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
        assertEquals("1.1", deviceResponse.version)
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        assertEquals(drParsed, deviceResponse)
        assertEquals(
            """
                {
                  "version": "1.1",
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
                        },
                        "deviceSigned": {}
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
        val encryptionParameters = EncryptionParameters(
            recipientPublicKey = encryptionKey.publicKey,
        )

        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addEncryptedDocuments(
                encryptionParameters = encryptionParameters,
                docRequestId = 1
            ) {
                addDocument(
                    credential = mdlCredentialSignature,
                    requestedClaims = listOf(
                        MdocRequestedClaim(
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "age_over_18",
                            intentToRetain = false
                        ),
                        MdocRequestedClaim(
                            namespaceName = DrivingLicense.MDL_NAMESPACE,
                            dataElementName = "given_name",
                            intentToRetain = false
                        ),
                    ),
                )
            }
        }
        assertEquals("1.1", deviceResponse.version)
        val encodedDeviceResponse = Cbor.encode(deviceResponse.toDataItem())
        val drParsed = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        assertEquals(drParsed, deviceResponse)

        drParsed.verify(sessionTranscript)
        assertEquals(0, drParsed.documents.size)
        assertEquals(0, drParsed.zkDocuments.size)
        assertEquals(1, drParsed.encryptedDocuments.size)

        // Check that EncryptedDocuments.encrypt() verifies the decrypted documents
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

        assertEquals(DrivingLicense.MDL_DOCTYPE, encDocs.documents[0].docType)
        assertEquals(
            """
                {
                  "org.iso.18013.5.1": [24(<< {
                    "digestID": 48,
                    "random": h'7a5dc224bebb1e4403c308f97052d8fe',
                    "elementIdentifier": "age_over_18",
                    "elementValue": true
                  } >>), 24(<< {
                    "digestID": 25,
                    "random": h'eb0f39cfd0c6668ae37453b8efecfbcd',
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
}
