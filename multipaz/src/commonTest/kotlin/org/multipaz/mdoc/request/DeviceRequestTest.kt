package org.multipaz.mdoc.request

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Nint
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseSign1
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.credential.KeyBoundMdocCredential
import org.multipaz.mdoc.credential.KeylessMdocCredential
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mdoc.zkp.ZkSystemParamValue
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.SimplePresentmentSource
import org.multipaz.presentment.mdocPresentment
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.Iso18013RequesterIdentity
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustManager
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger
import org.multipaz.util.fromHex
import org.multipaz.util.fromHexByteString
import org.multipaz.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DeviceRequestTest {
    private fun singleDocUseCaseInfo() = DeviceRequestInfo.fromValues(
        useCases = listOf(
            UseCase(
                mandatory = true,
                documentSets = listOf(DocumentSet(listOf(0))),
                purposeHints = emptyMap()
            )
        )
    )

    // Test against the test vector in Annex D of 18013-5:2021
    @Test
    fun testAgainstVector2021() = runTest {
        val encodedSessionTranscriptBytes =
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val sessionTranscript = Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor

        val deviceRequest = DeviceRequest.fromDataItem(
            Cbor.decode(TestVectors.ISO_18013_5_ANNEX_D_DEVICE_REQUEST.fromHex())
        )

        assertEquals("1.0", deviceRequest.version)
        assertEquals(1, deviceRequest.docRequests.size)
        assertNull(deviceRequest.deviceRequestInfo)

        // Verify we can't access readerAuthAll or readerAuth until verifyReaderAuthentication() is called
        assertEquals(
            "readerAuth not verified",
            assertFailsWith(IllegalStateException::class) {
                deviceRequest.docRequests[0].readerAuth
            }.message
        )

        deviceRequest.verifyReaderAuthentication(
            sessionTranscript = sessionTranscript,
            atTime = Instant.parse("2020-11-01T00:00:00Z"),
        )

        // Now we can access readerAuthAll and readerAuth
        val readerAuth = deviceRequest.docRequests[0].readerAuth
        assertNotNull(readerAuth)
        val readerCertChain = readerAuth.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]!!.asX509CertChain
        assertEquals(
            TestVectors.ISO_18013_5_ANNEX_D_READER_CERT.fromHexByteString(),
            readerCertChain.certificates[0].encoded
        )
        assertEquals(
            Algorithm.ES256.coseAlgorithmIdentifier!!,
            readerAuth.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )

        val docRequest = deviceRequest.docRequests.first()
        assertEquals(DrivingLicense.MDL_DOCTYPE, docRequest.docType)
        assertEquals(1, docRequest.nameSpaces.size)
        val mdlNamespace = docRequest.nameSpaces[DrivingLicense.MDL_NAMESPACE]!!
        assertEquals("{" +
                "family_name=true, " +
                "document_number=true, " +
                "driving_privileges=true, " +
                "issue_date=true, " +
                "expiry_date=true, " +
                "portrait=false" +
                "}",
            mdlNamespace.toString()
        )
    }

    @Test
    fun testAgainstMalformedReaderSignature() = runTest {
        val encodedSessionTranscriptBytes =
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val sessionTranscript = Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor

        // We know the COSE_Sign1 signature for reader authentication is at index 655 and
        // starts with 1f340006... Poison that so we can check whether signature verification
        // detects it...
        val encodedDeviceRequest = TestVectors.ISO_18013_5_ANNEX_D_DEVICE_REQUEST.fromHex()
        assertEquals(0x1f.toByte().toLong(), encodedDeviceRequest[655].toLong())
        encodedDeviceRequest[655] = 0x1e

        val deviceRequest = DeviceRequest.fromDataItem(
            Cbor.decode(encodedDeviceRequest)
        )

        assertEquals(
            "Error verifying reader authentication for DocRequest at index 0",
            assertFailsWith(SignatureVerificationException::class) {
                deviceRequest.verifyReaderAuthentication(
                    sessionTranscript = sessionTranscript,
                    atTime = Instant.parse("2020-11-01T00:00:00Z"),
                )
            }.message
        )

    }

    data class ReaderAuth(
        val readerRootKey: AsymmetricKey.X509Certified,
        val readerKey: AsymmetricKey.X509Certified,
    ) {
        companion object {
            suspend fun generateSoftware(): ReaderAuth {
                val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P384)
                val readerRootCert = MdocUtil.generateReaderRootCertificate(
                    readerRootKey = AsymmetricKey.anonymous(readerRootKey),
                    subject = X500Name.fromName("CN=TEST Reader Root,C=XG-US,ST=MA"),
                    serial = ASN1Integer(1),
                    validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    crlUrl = "http://www.example.com/issuer/crl"
                )
                val readerRootSigningKey = AsymmetricKey.X509CertifiedExplicit(
                    privateKey = readerRootKey,
                    certChain = X509CertChain(listOf(readerRootCert))
                )
                val readerKey = Crypto.createEcPrivateKey(EcCurve.P384)
                val readerCert = MdocUtil.generateReaderCertificate(
                    readerRootKey = readerRootSigningKey,
                    readerKey = readerKey.publicKey,
                    subject = X500Name.fromName("CN=TEST Reader Certificate,C=XG-US,ST=MA"),
                    dnsName = "localhost",
                    serial = ASN1Integer(1),
                    validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                )
                return ReaderAuth(
                    readerRootKey = readerRootSigningKey,
                    readerKey = AsymmetricKey.X509CertifiedExplicit(
                        privateKey = readerKey,
                        certChain = X509CertChain(listOf(readerCert, readerRootCert))
                    )
                )
            }

            suspend fun generateSecureArea(): ReaderAuth {
                val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P384)
                val readerRootCert = MdocUtil.generateReaderRootCertificate(
                    readerRootKey = AsymmetricKey.anonymous(readerRootKey),
                    subject = X500Name.fromName("CN=TEST Reader Root,C=XG-US,ST=MA"),
                    serial = ASN1Integer(1),
                    validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    crlUrl = "http://www.example.com/issuer/crl"
                )
                val readerRootSigningKey = AsymmetricKey.X509CertifiedExplicit(
                    privateKey = readerRootKey,
                    certChain = X509CertChain(listOf(readerRootCert))
                )
                val storage = EphemeralStorage()
                val readerKeySecureArea = SoftwareSecureArea.create(storage)
                val readerKeyInfo = readerKeySecureArea.createKey(
                    alias = null,
                    createKeySettings = CreateKeySettings()
                )
                val readerCert = MdocUtil.generateReaderCertificate(
                    readerRootKey = readerRootSigningKey,
                    readerKey = readerKeyInfo.ecPublicKey,
                    subject = X500Name.fromName("CN=TEST Reader Certificate,C=XG-US,ST=MA"),
                    dnsName = "localhost",
                    serial = ASN1Integer(1),
                    validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                )
                return ReaderAuth(
                    readerRootKey = readerRootSigningKey,
                    readerKey = AsymmetricKey.X509CertifiedSecureAreaBased(
                        secureArea = readerKeySecureArea,
                        certChain = X509CertChain(listOf(readerCert, readerRootCert)),
                        keyInfo = readerKeyInfo
                    )
                )
            }
        }
    }

    @Test
    fun readerAuthRoundTrip() = runTest {
        val ra = ReaderAuth.generateSoftware()
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = null,
                readerKey = ra.readerKey
            )
        }
        assertEquals("1.0", deviceRequest.version)
        val parsedDeviceRequest = DeviceRequest.fromDataItem(deviceRequest.toDataItem())

        // Verify we can't access readerAuthAll or readerAuth until verifyReaderAuthentication() is called
        assertEquals(
            "readerAuthAll not verified",
            assertFailsWith(IllegalStateException::class) {
                val size = parsedDeviceRequest.readerAuthAll.size
            }.message
        )
        assertEquals(
            "readerAuth not verified",
            assertFailsWith(IllegalStateException::class) {
                val unused = parsedDeviceRequest.docRequests[0].readerAuth
            }.message
        )

        parsedDeviceRequest.verifyReaderAuthentication(
            sessionTranscript = sessionTranscript
        )

        // Now we can access readerAuthAll and readerAuth
        assertEquals(0, parsedDeviceRequest.readerAuthAll.size)
        assertEquals(
            Algorithm.ES384.coseAlgorithmIdentifier!!,
            parsedDeviceRequest.docRequests[0].readerAuth!!.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )

        assertEquals(parsedDeviceRequest, deviceRequest)
    }

    @Test
    fun readerAuthRoundTripSecureArea() = runTest {
        val ra = ReaderAuth.generateSecureArea()
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = null,
                readerKey = ra.readerKey
            )
        }
        assertEquals("1.0", deviceRequest.version)
        val parsedDeviceRequest = DeviceRequest.fromDataItem(deviceRequest.toDataItem())

        // Verify we can't access readerAuthAll or readerAuth until verifyReaderAuthentication() is called
        assertEquals(
            "readerAuthAll not verified",
            assertFailsWith(IllegalStateException::class) {
                val size = parsedDeviceRequest.readerAuthAll.size
            }.message
        )
        assertEquals(
            "readerAuth not verified",
            assertFailsWith(IllegalStateException::class) {
                val unused = parsedDeviceRequest.docRequests[0].readerAuth
            }.message
        )

        parsedDeviceRequest.verifyReaderAuthentication(
            sessionTranscript = sessionTranscript
        )

        // Now we can access readerAuthAll and readerAuth
        assertEquals(0, parsedDeviceRequest.readerAuthAll.size)
        assertEquals(
            Algorithm.ES256.coseAlgorithmIdentifier!!,
            parsedDeviceRequest.docRequests[0].readerAuth!!.protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )

        assertEquals(parsedDeviceRequest, deviceRequest)
    }

    @Test
    fun readerAuthAllRoundTrip() = runTest {
        val ra = ReaderAuth.generateSoftware()
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = null,
            )
            addReaderAuthAll(readerKey = ra.readerKey)
        }
        assertEquals("1.1", deviceRequest.version)

        val parsedDeviceRequest = DeviceRequest.fromDataItem(deviceRequest.toDataItem())

        // Verify we can't access readerAuthAll or readerAuth until verifyReaderAuthentication() is called
        assertEquals(
            "readerAuthAll not verified",
            assertFailsWith(IllegalStateException::class) {
                val size = parsedDeviceRequest.readerAuthAll.size
            }.message
        )
        assertEquals(
            "readerAuth not verified",
            assertFailsWith(IllegalStateException::class) {
                val unused = parsedDeviceRequest.docRequests[0].readerAuth
            }.message
        )

        parsedDeviceRequest.verifyReaderAuthentication(
            sessionTranscript = sessionTranscript
        )

        // Now we can access readerAuthAll and readerAuth
        assertEquals(1, parsedDeviceRequest.readerAuthAll.size)
        assertNull(parsedDeviceRequest.docRequests[0].readerAuth)
        assertEquals(
            Algorithm.ES384.coseAlgorithmIdentifier!!,
            parsedDeviceRequest.readerAuthAll[0].protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )

        assertEquals(parsedDeviceRequest, deviceRequest)
    }

    @Test
    fun readerAuthAllSecureAreaRoundTrip() = runTest {
        val ra = ReaderAuth.generateSecureArea()
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = null,
            )
            addReaderAuthAll(readerKey = ra.readerKey)
        }
        assertEquals("1.1", deviceRequest.version)
        val parsedDeviceRequest = DeviceRequest.fromDataItem(deviceRequest.toDataItem())

        // Verify we can't access readerAuthAll or readerAuth until verifyReaderAuthentication() is called
        assertEquals(
            "readerAuthAll not verified",
            assertFailsWith(IllegalStateException::class) {
                val size = parsedDeviceRequest.readerAuthAll.size
            }.message
        )
        assertEquals(
            "readerAuth not verified",
            assertFailsWith(IllegalStateException::class) {
                val unused = parsedDeviceRequest.docRequests[0].readerAuth
            }.message
        )

        parsedDeviceRequest.verifyReaderAuthentication(
            sessionTranscript = sessionTranscript
        )

        // Now we can access readerAuthAll and readerAuth
        assertEquals(1, parsedDeviceRequest.readerAuthAll.size)
        assertNull(parsedDeviceRequest.docRequests[0].readerAuth)
        assertEquals(
            Algorithm.ES256.coseAlgorithmIdentifier!!,
            parsedDeviceRequest.readerAuthAll[0].protectedHeaders[Cose.COSE_LABEL_ALG.toCoseLabel]!!.asNumber.toInt()
        )

        assertEquals(parsedDeviceRequest, deviceRequest)
    }

    @Test
    fun docRequestInfoAlternativeDataElements() = runTest {
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(DrivingLicense.MDL_NAMESPACE, "age_over_18"),
                            alternativeElementSets = listOf(
                                listOf(
                                    ElementReference(DrivingLicense.MDL_NAMESPACE, "age_in_years"),
                                ),
                                listOf(
                                    ElementReference(DrivingLicense.MDL_NAMESPACE, "birth_date"),
                                ),
                            )
                        )
                    ),
                ),
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
            {
              "version": "1.1",
              "docRequests": [
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.iso.18013.5.1.mDL",
                    "nameSpaces": {
                      "org.iso.18013.5.1": {
                        "age_over_18": true,
                        "portrait": false
                      }
                    },
                    "requestInfo": {
                      "alternativeDataElements": [
                        {
                          "requestedElement": ["org.iso.18013.5.1", "age_over_18"],
                          "alternativeElementSets": [
                            [
                              ["org.iso.18013.5.1", "age_in_years"]
                            ],
                            [
                              ["org.iso.18013.5.1", "birth_date"]
                            ]
                          ]
                        }
                      ]
                    }
                  } >>)
                }
              ],
              "deviceRequestInfo": 24(<< {
                "useCases": [
                  {
                    "mandatory": true,
                    "documentSets": [
                      [0]
                    ]
                  }
                ]
              } >>)
            }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun docRequestInfoIssuerIdentifiers() = runTest {
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    issuerIdentifiers = listOf(
                        ByteString(1, 2, 3),
                        ByteString(4, 5, 6),
                    )
                ),
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
            {
              "version": "1.1",
              "docRequests": [
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.iso.18013.5.1.mDL",
                    "nameSpaces": {
                      "org.iso.18013.5.1": {
                        "age_over_18": true,
                        "portrait": false
                      }
                    },
                    "requestInfo": {
                      "issuerIdentifiers": [h'010203', h'040506']
                    }
                  } >>)
                }
              ],
              "deviceRequestInfo": 24(<< {
                "useCases": [
                  {
                    "mandatory": true,
                    "documentSets": [
                      [0]
                    ]
                  }
                ]
              } >>)
            }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun docRequestInfoUniqueDocSetRequiredSetToTrue() = runTest {
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    uniqueDocSetRequired = true
                ),
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
            {
              "version": "1.1",
              "docRequests": [
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.iso.18013.5.1.mDL",
                    "nameSpaces": {
                      "org.iso.18013.5.1": {
                        "age_over_18": true,
                        "portrait": false
                      }
                    },
                    "requestInfo": {
                      "uniqueDocSetRequired": true
                    }
                  } >>)
                }
              ],
              "deviceRequestInfo": 24(<< {
                "useCases": [
                  {
                    "mandatory": true,
                    "documentSets": [
                      [0]
                    ]
                  }
                ]
              } >>)
            }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun docRequestInfoUniqueDocSetRequiredSetToFalse() = runTest {
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    uniqueDocSetRequired = false
                ),
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
            {
              "version": "1.1",
              "docRequests": [
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.iso.18013.5.1.mDL",
                    "nameSpaces": {
                      "org.iso.18013.5.1": {
                        "age_over_18": true,
                        "portrait": false
                      }
                    },
                    "requestInfo": {
                      "uniqueDocSetRequired": false
                    }
                  } >>)
                }
              ],
              "deviceRequestInfo": 24(<< {
                "useCases": [
                  {
                    "mandatory": true,
                    "documentSets": [
                      [0]
                    ]
                  }
                ]
              } >>)
            }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun docRequestInfoMaximumResponseSizeSet() = runTest {
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    maximumResponseSize = 42L
                ),
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
            {
              "version": "1.1",
              "docRequests": [
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.iso.18013.5.1.mDL",
                    "nameSpaces": {
                      "org.iso.18013.5.1": {
                        "age_over_18": true,
                        "portrait": false
                      }
                    },
                    "requestInfo": {
                      "maximumResponseSize": 42
                    }
                  } >>)
                }
              ],
              "deviceRequestInfo": 24(<< {
                "useCases": [
                  {
                    "mandatory": true,
                    "documentSets": [
                      [0]
                    ]
                  }
                ]
              } >>)
            }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun docRequestInfoEncryptionParametersSet() = runTest {
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val recipientKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    docResponseEncryption = EncryptionParameters.fromValues(
                        recipientPublicKey = recipientKey.publicKey,
                    )
                ),
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
                {
                  "version": "1.1",
                  "docRequests": [
                    {
                      "itemsRequest": 24(<< {
                        "docType": "org.iso.18013.5.1.mDL",
                        "nameSpaces": {
                          "org.iso.18013.5.1": {
                            "age_over_18": true,
                            "portrait": false
                          }
                        },
                        "requestInfo": {
                          "docResponseEncryption": 24(<< {
                            "recipientPublicKey": {
                              1: 2,
                              -1: 1,
                              -2: h'${(recipientKey.publicKey as EcPublicKeyDoubleCoordinate).x.toHex()}',
                              -3: h'${(recipientKey.publicKey as EcPublicKeyDoubleCoordinate).y.toHex()}'
                            }
                          } >>)
                        }
                      } >>)
                    }
                  ],
                  "deviceRequestInfo": 24(<< {
                    "useCases": [
                      {
                        "mandatory": true,
                        "documentSets": [
                          [0]
                        ]
                      }
                    ]
                  } >>)
                }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun docRequestInfoZkRequest() = runTest {
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    zkRequest = ZkRequest(
                        systemSpecs = listOf(
                            // TODO: Need a builder for ZkSystemSpec
                            ZkSystemSpec(
                                "0",
                                "longfellow-zk"
                            ).apply {
                                addParam("circuit", "1234")
                                addParam("otherParam", 42)
                                addParam("yetAnotherParam", false)
                            },
                            ZkSystemSpec(
                                "1",
                                "other-system-zk"
                            ).apply {
                                addParam("foo", "bar")
                                addParam("flux-capacitor", false)
                                addParam("goes-to", 11)
                            }
                        ),
                        zkRequired = true
                    )
                ),
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
            {
              "version": "1.1",
              "docRequests": [
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.iso.18013.5.1.mDL",
                    "nameSpaces": {
                      "org.iso.18013.5.1": {
                        "age_over_18": true,
                        "portrait": false
                      }
                    },
                    "requestInfo": {
                      "zkRequest": {
                        "systemSpecs": [
                          {
                            "zkSystemId": "0",
                            "system": "longfellow-zk",
                            "params": {
                              "circuit": "1234",
                              "otherParam": 42,
                              "yetAnotherParam": false
                            }
                          },
                          {
                            "zkSystemId": "1",
                            "system": "other-system-zk",
                            "params": {
                              "foo": "bar",
                              "flux-capacitor": false,
                              "goes-to": 11
                            }
                          }
                        ],
                        "zkRequired": true
                      }
                    }
                  } >>)
                }
              ],
              "deviceRequestInfo": 24(<< {
                "useCases": [
                  {
                    "mandatory": true,
                    "documentSets": [
                      [0]
                    ]
                  }
                ]
              } >>)
            }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun deviceRequestInfoDocFormatAndDataElementIdentifierMapping() = runTest {
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf(
                    "_" to mapOf(
                        "sdjwtvc_given_name" to false,
                        "sdjwtvc_family_name" to false,
                        "sdjwtvc_degrees" to false,
                        "sdjwtvc_nationalities" to false,
                        "sdjwtvc_age_over_18" to false,
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add("given_name") },
                        "sdjwtvc_family_name" to buildJsonArray { add("family_name") },
                        "sdjwtvc_degrees" to buildJsonArray { add("degrees"); add(JsonNull); add("type") },
                        "sdjwtvc_nationalities" to buildJsonArray { add("nationalities"); add(1) },
                        "sdjwtvc_age_over_18" to buildJsonArray { add("age_equal_or_over"); add("18") },
                    )
                )
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
                {
                  "version": "1.1",
                  "docRequests": [
                    {
                      "itemsRequest": 24(<< {
                        "docType": "urn:eudi:pid:1",
                        "nameSpaces": {
                          "_": {
                            "sdjwtvc_given_name": false,
                            "sdjwtvc_family_name": false,
                            "sdjwtvc_degrees": false,
                            "sdjwtvc_nationalities": false,
                            "sdjwtvc_age_over_18": false
                          }
                        },
                        "requestInfo": {
                          "docFormat": "dc+sd-jwt",
                          "dataElementIdentifierMapping": {
                            "sdjwtvc_given_name": ["given_name"],
                            "sdjwtvc_family_name": ["family_name"],
                            "sdjwtvc_degrees": ["degrees", null, "type"],
                            "sdjwtvc_nationalities": ["nationalities", 1],
                            "sdjwtvc_age_over_18": ["age_equal_or_over", "18"]
                          }
                        }
                      } >>)
                    }
                  ],
                  "deviceRequestInfo": 24(<< {
                    "useCases": [
                      {
                        "mandatory": true,
                        "documentSets": [
                          [0]
                        ]
                      }
                    ]
                  } >>)
                }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun deviceRequestInfoAgeOverUseCase() = runTest {
        // This DeviceRequest asks for either an mDL, a PhotoID, or a EU PID with claims that
        // the holder is 18 years or older as well as their portrait image.
        //
        // TODO: link to this example in 18013-5 Second Edition
        //
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(
                            DocumentSet(listOf(0)),
                            DocumentSet(listOf(1)),
                            DocumentSet(listOf(2)),
                        ),
                        purposeHints = mapOf()
                    )
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = null,
            )
            addDocRequest(
                docType = PhotoID.PHOTO_ID_DOCTYPE,
                nameSpaces = mapOf(
                    PhotoID.ISO_23220_2_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = null,
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_DOCTYPE,
                nameSpaces = mapOf(
                    EUPersonalID.EUPID_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "picture" to false
                    )
                ),
                docRequestInfo = null,
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
            {
              "version": "1.1",
              "docRequests": [
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.iso.18013.5.1.mDL",
                    "nameSpaces": {
                      "org.iso.18013.5.1": {
                        "age_over_18": true,
                        "portrait": false
                      }
                    }
                  } >>)
                },
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.iso.23220.photoid.1",
                    "nameSpaces": {
                      "org.iso.23220.1": {
                        "age_over_18": true,
                        "portrait": false
                      }
                    }
                  } >>)
                },
                {
                  "itemsRequest": 24(<< {
                    "docType": "eu.europa.ec.eudi.pid.1",
                    "nameSpaces": {
                      "eu.europa.ec.eudi.pid.1": {
                        "age_over_18": true,
                        "picture": false
                      }
                    }
                  } >>)
                }
              ],
              "deviceRequestInfo": 24(<< {
                "useCases": [
                  {
                    "mandatory": true,
                    "documentSets": [
                      [0],
                      [1],
                      [2]
                    ]
                  }
                ]
              } >>)
            }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun deviceRequestInfoAgeOverUseCaseWithPurposeHints() = runTest {
        // This DeviceRequest asks for either an mDL, a PhotoID, or a EU PID with claims that
        // the holder is 18 years or older as well as their portrait image.
        //
        // TODO: link to this example in 18013-5 Second Edition
        //
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(
                            DocumentSet(listOf(0)),
                            DocumentSet(listOf(1)),
                            DocumentSet(listOf(2)),
                        ),
                        purposeHints = mapOf(
                            // Age verification, PurposeCode = 3 - see 18013-5 Second Edition
                            // clause 10.2.5 Additional device request info
                            "org.iso.jtc1.sc17" to 3
                        )
                    )
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = null,
            )
            addDocRequest(
                docType = PhotoID.PHOTO_ID_DOCTYPE,
                nameSpaces = mapOf(
                    PhotoID.ISO_23220_2_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = null,
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_DOCTYPE,
                nameSpaces = mapOf(
                    EUPersonalID.EUPID_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "picture" to false
                    )
                ),
                docRequestInfo = null,
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
            {
              "version": "1.1",
              "docRequests": [
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.iso.18013.5.1.mDL",
                    "nameSpaces": {
                      "org.iso.18013.5.1": {
                        "age_over_18": true,
                        "portrait": false
                      }
                    }
                  } >>)
                },
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.iso.23220.photoid.1",
                    "nameSpaces": {
                      "org.iso.23220.1": {
                        "age_over_18": true,
                        "portrait": false
                      }
                    }
                  } >>)
                },
                {
                  "itemsRequest": 24(<< {
                    "docType": "eu.europa.ec.eudi.pid.1",
                    "nameSpaces": {
                      "eu.europa.ec.eudi.pid.1": {
                        "age_over_18": true,
                        "picture": false
                      }
                    }
                  } >>)
                }
              ],
              "deviceRequestInfo": 24(<< {
                "useCases": [
                  {
                    "mandatory": true,
                    "documentSets": [
                      [0],
                      [1],
                      [2]
                    ],
                    "purposeHints": {
                      "org.iso.jtc1.sc17": 3
                    }
                  }
                ]
              } >>)
            }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun testDocRequestInfoExt() = runTest {
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    otherInfo = mapOf(
                        "com.example.foo" to Tstr("bar")
                    )
                ),
            )
        }
        // Only proprietary otherInfo in DocRequestInfo does not trigger version "1.1"
        assertEquals("1.0", deviceRequest.version)
        assertEquals(
            """
                {
                  "version": "1.0",
                  "docRequests": [
                    {
                      "itemsRequest": 24(<< {
                        "docType": "org.iso.18013.5.1.mDL",
                        "nameSpaces": {
                          "org.iso.18013.5.1": {
                            "age_over_18": true,
                            "portrait": false
                          }
                        },
                        "requestInfo": {
                          "com.example.foo": "bar"
                        }
                      } >>)
                    }
                  ]
                }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    @Test
    fun testDeviceRequestInfoExt() = runTest {
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0))),
                        purposeHints = emptyMap()
                    )
                ),
                otherInfo = mapOf(
                    "com.example.foobar" to buildCborArray { add(42); add(43) }
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "age_over_18" to true,
                        "portrait" to false
                    )
                ),
                docRequestInfo = null
            )
        }
        assertEquals("1.1", deviceRequest.version)
        assertEquals(
            """
                {
                  "version": "1.1",
                  "docRequests": [
                    {
                      "itemsRequest": 24(<< {
                        "docType": "org.iso.18013.5.1.mDL",
                        "nameSpaces": {
                          "org.iso.18013.5.1": {
                            "age_over_18": true,
                            "portrait": false
                          }
                        }
                      } >>)
                    }
                  ],
                  "deviceRequestInfo": 24(<< {
                    "useCases": [
                      {
                        "mandatory": true,
                        "documentSets": [
                          [0]
                        ]
                      }
                    ],
                    "com.example.foobar": [42, 43]
                  } >>)
                }
            """.trimIndent().trim(),
            Cbor.toDiagnostics(
                item =deviceRequest.toDataItem(),
                options = setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(DeviceRequest.fromDataItem(deviceRequest.toDataItem()), deviceRequest)
    }

    /**
     * iOS 26.0 has a bug where it puts `null` values in place of deviceRequestInfo and readerAuthAll (which isn't
     * allowed by 18013-5 Second Edition) but also sets version to "1.0". This test makes sure we ignore those
     * fields properly (warnings are logged).
     */
    @Test
    fun testAgainstIos26ReaderApi() {
        // This request was received from an iPhone 15 Pro running iOS 26.0 using the VerifierAPISample
        // available from https://developer.apple.com/documentation/proximityreader/checking-ids-with-the-verifier-api
        val deviceRequestBytes = "a46776657273696f6e63312e306b646f63526571756573747381a26a726561646572417574688443a10126a11821835907ad308207a93082072fa00302010202101777c779ee218c8cfe278f7322a36e44300a06082a8648ce3d040303307c3130302e06035504030c274170706c65204170706c69636174696f6e20496e746567726174696f6e2043412037202d20473131263024060355040b0c1d4170706c652043657274696669636174696f6e20417574686f7269747931133011060355040a0c0a4170706c6520496e632e310b3009060355040613025553301e170d3235313030323136333134395a170d3235313030343136333134385a3081ca31653063060a0992268993f22c6401010c55564b453247334b3536562e636f6d2e6578616d706c652e6170706c652d73616d706c65636f64652e76657269666965722d6170692d73616d706c652d646973706c61792d72657175657374564b453247334b3536563149304706035504030c403133396533396332333762343061663530663161663634366333663630393363346436356338303637323764616365326539633032313664323065343264623831163014060355040a0c0d4461766964205a65757468656e3059301306072a8648ce3d020106082a8648ce3d030107034200045aa47259f13b2ba2d4af525da4fc8cfaac9d730022b692f992b49635a12ae61669450606c9f57c6364e1ea7f2eef1a985e68dd6e15813145a93270b330fb4f96a38205423082053e300c0603551d130101ff04023000301f0603551d230418301680141a38fe7f80e56dc83716591e1b10f2fa302dafd83082011d0603551d2004820114308201103082010c06092a864886f7636405013081fe3081c306082b060105050702023081b60c81b352656c69616e6365206f6e207468697320636572746966696361746520627920616e7920706172747920617373756d657320616363657074616e6365206f6620746865207468656e206170706c696361626c65207374616e64617264207465726d7320616e6420636f6e646974696f6e73206f66207573652c20636572746966696361746520706f6c69637920616e642063657274696669636174696f6e2070726163746963652073746174656d656e74732e303606082b06010505070201162a68747470733a2f2f7777772e6170706c652e636f6d2f6365727469666963617465617574686f7269747930200603551d250101ff04163014060728818c5d05010606092a864886f763640415301d0603551d0e04160414e83c3b0d7e9d5aced7187dd14e89ebf1453a2267300e0603551d0f0101ff040403020780300f06092a864886f763640650040205003082037706092a864886f76364064e0482036830820364a04a304816156f72672e69736f2e31383031332e352e312e6d444c16166f72672e69736f2e32333232302e312e6a702e6d6e6316176f72672e69736f2e32333232302e70686f746f69642e31a18203143082031016276f72672e69736f2e31383031332e352e312e61616d76613a616b615f66616d696c795f6e616d65162a6f72672e69736f2e31383031332e352e312e61616d76613a616b615f66616d696c795f6e616d652e763216266f72672e69736f2e31383031332e352e312e61616d76613a616b615f676976656e5f6e616d6516296f72672e69736f2e31383031332e352e312e61616d76613a616b615f676976656e5f6e616d652e763216226f72672e69736f2e31383031332e352e312e61616d76613a616b615f737566666978162e6f72672e69736f2e31383031332e352e312e61616d76613a66616d696c795f6e616d655f7472756e636174696f6e162d6f72672e69736f2e31383031332e352e312e61616d76613a676976656e5f6e616d655f7472756e636174696f6e16236f72672e69736f2e31383031332e352e312e61616d76613a6e616d655f737566666978161e6f72672e69736f2e31383031332e352e313a6167655f696e5f7965617273161d6f72672e69736f2e31383031332e352e313a6167655f6f7665725f4e4e161d6f72672e69736f2e31383031332e352e313a66616d696c795f6e616d65161c6f72672e69736f2e31383031332e352e313a676976656e5f6e616d65161a6f72672e69736f2e31383031332e352e313a706f72747261697416246f72672e69736f2e32333232302e312e6a703a66756c6c5f6e616d655f756e69636f6465161b6f72672e69736f2e32333232302e312e6a703a706f727472616974161c6f72672e69736f2e32333232302e313a6167655f696e5f7965617273161b6f72672e69736f2e32333232302e313a6167655f6f7665725f4e4e16226f72672e69736f2e32333232302e313a66616d696c795f6e616d655f6c6174696e3116236f72672e69736f2e32333232302e313a66616d696c795f6e616d655f756e69636f646516216f72672e69736f2e32333232302e313a676976656e5f6e616d655f6c6174696e3116226f72672e69736f2e32333232302e313a676976656e5f6e616d655f756e69636f646516186f72672e69736f2e32333232302e313a706f727472616974300f06092a864886f76364065104020500300a06082a8648ce3d040303036800306502300e75111780bffc77384b40cf78fa0be22af04da134c16700be00ca7c3d7865108ab0d520ad16fb01c99140119c60c92b023100c679268553ea215173610d8df7640579fbf60cd83684feec3ed59fce864ab7581a53bce493045042c637ce4ed1137a785903203082031c308202a3a00302010202140945077ff346e881707ac440b4ae048788d59718300a06082a8648ce3d0403033067311b301906035504030c124170706c6520526f6f74204341202d20473331263024060355040b0c1d4170706c652043657274696669636174696f6e20417574686f7269747931133011060355040a0c0a4170706c6520496e632e310b3009060355040613025553301e170d3233303330313232313230375a170d3338303330333030303030305a307c3130302e06035504030c274170706c65204170706c69636174696f6e20496e746567726174696f6e2043412037202d20473131263024060355040b0c1d4170706c652043657274696669636174696f6e20417574686f7269747931133011060355040a0c0a4170706c6520496e632e310b30090603550406130255533076301006072a8648ce3d020106052b8104002203620004740692c5db79bd33ea8b26080ca783ce4fbe9afe993d4deb4286270b8776b30bf0535e43e00675c78f9f9ea1db0504e1242ff2fdbe548b0609b57a039c2e8958f4048ccdaf5902815090a4c2709bf120f9234ff37a835842a5633267b5d54c21a381fa3081f730120603551d130101ff040830060101ff020100301f0603551d23041830168014bbb0dea15833889aa48a99debebdebafdacb24ab304606082b06010505070101043a3038303606082b06010505073001862a687474703a2f2f6f6373702e6170706c652e636f6d2f6f63737030332d6170706c65726f6f746361673330370603551d1f0430302e302ca02aa0288626687474703a2f2f63726c2e6170706c652e636f6d2f6170706c65726f6f74636167332e63726c301d0603551d0e041604141a38fe7f80e56dc83716591e1b10f2fa302dafd8300e0603551d0f0101ff0404030201063010060a2a864886f7636406021f04020500300a06082a8648ce3d040303036700306402301267f36547ba5133c3e3037f65944b766b9ea80f08a7d5498b366c7523ab4b3dee2dea52eb4015892214c62a32b6a80d023029eea2a737ae204b51027ab8eca6b972d174a32d4a04d31b3440bc4dfcc9bde5172667090068c368f45e2f95df23ae8059024730820243308201c9a00302010202082dc5fc88d2c54b95300a06082a8648ce3d0403033067311b301906035504030c124170706c6520526f6f74204341202d20473331263024060355040b0c1d4170706c652043657274696669636174696f6e20417574686f7269747931133011060355040a0c0a4170706c6520496e632e310b3009060355040613025553301e170d3134303433303138313930365a170d3339303433303138313930365a3067311b301906035504030c124170706c6520526f6f74204341202d20473331263024060355040b0c1d4170706c652043657274696669636174696f6e20417574686f7269747931133011060355040a0c0a4170706c6520496e632e310b30090603550406130255533076301006072a8648ce3d020106052b810400220362000498e92f3d4072a4ed93227281131cdd1095f1c5a34e71dc1416d90ee5a6052a77647b5f4e38d3bb1c44b57ff51fb632625dc9e9845b4f304f115a00fd58580ca5f50f2c4d07471375da9797976f315ced2b9d7b203bd8b954d95e99a43a510a31a3423040301d0603551d0e04160414bbb0dea15833889aa48a99debebdebafdacb24ab300f0603551d130101ff040530030101ff300e0603551d0f0101ff040403020106300a06082a8648ce3d040303036800306502310083e9c1c4165e1a5d3418d9edeff46c0e00464bb8dfb24611c50ffde67a8ca1a66bcec203d49cf593c674b86adfaa231502306d668a10cad40dd44fcd8d433eb48a63a5336ee36dda17b7641fc85326f9886274390b175bcb51a80ce81803e7a2b228f6584024285443042fc5ea7ce21401ba442fefa5a90faf1d41eeed7d1b309f15b21c7d1717ad12562107703d371bdbc5a76ff7f8382498843612762441b0911b1b89d96c6974656d7352657175657374d818589fa367646f6354797065756f72672e69736f2e31383031332e352e312e6d444c6a6e616d65537061636573a1716f72672e69736f2e31383031332e352e31a268706f727472616974f46b6167655f6f7665725f3231f46b72657175657374496e666fa1783a636f6d2e6170706c652e696e746572707265742d77696c6c2d6e6f742d72657461696e2d696e74656e742d61732d646973706c61792d6f6e6c79f56d72656164657241757468416c6cf67164657669636552657175657374496e666ff6".fromHex()
        val deviceRequest = DeviceRequest.fromDataItem(Cbor.decode(deviceRequestBytes))
    }

    /**
     * Checks that DeviceRequestInfo serialization is preserved.
     *
     * This is important because this is part of ReaderAuthenticationAll.
     *
     * This was reported in https://github.com/openwallet-foundation/multipaz/issues/1724
     */
    @Test
    fun testDeviceRequestInfoSerialization() {
        fun buildDrForDri(dri: DataItem) = buildCborMap {
            put("version", "1.1")
            putCborArray("docRequests") {
                addCborMap {
                    putTaggedEncodedCbor(
                        "itemsRequest",
                        Cbor.encode(buildCborMap {
                            put("docType", "org.iso.18013.5.1.mDL")
                            putCborMap("nameSpaces") {
                                putCborMap("org.iso.18013.5.1") {
                                    put("age_over_18", true)
                                    put("portrait", true)
                                }
                            }
                        })
                    )
                }
            }
            putTaggedEncodedCbor(
                "deviceRequestInfo",
                Cbor.encode(dri)
            )
        }

        val dri1 = buildCborMap {
            putCborArray("useCases") {
                addCborMap {
                    put("mandatory", true)
                    putCborArray("documentSets") {
                        addCborArray {
                            add(0)
                        }
                    }
                }
            }
        }
        assertContentEquals(
            Cbor.encode(dri1),
            Cbor.encode(DeviceRequest.fromDataItem(buildDrForDri(dri1)).deviceRequestInfo!!.dataItem)
        )

        val dri2 = buildCborMap {
            putCborArray("useCases") {
                addCborMap {
                    putCborArray("documentSets") {
                        addCborArray {
                            add(0)
                        }
                    }
                    put("mandatory", true)
                }
            }
        }
        assertContentEquals(
            Cbor.encode(dri2),
            Cbor.encode(DeviceRequest.fromDataItem(buildDrForDri(dri2)).deviceRequestInfo!!.dataItem)
        )
    }

    /**
     * Tests that itemsRequest serialization is preserved.
     *
     * This is important because this is part of ReaderAuthentication.
     */
    @Test
    fun testItemsRequestSerializationPreserved() {
        val drRequest1 = buildCborMap {
            put("version", "1.0")
            putCborArray("docRequests") {
                addCborMap {
                    putTaggedEncodedCbor(
                        "itemsRequest",
                        Cbor.encode(buildCborMap {
                            put("docType", "org.iso.18013.5.1.mDL")
                            putCborMap("nameSpaces") {
                                putCborMap("org.iso.18013.5.1") {
                                    put("age_over_18", true)
                                    put("portrait", true)
                                }
                            }
                        })
                    )
                }
            }
        }
        assertContentEquals(
            Cbor.encode(drRequest1),
            Cbor.encode(DeviceRequest.fromDataItem(drRequest1).toDataItem())
        )

        val drRequest2 = buildCborMap {
            put("version", "1.0")
            putCborArray("docRequests") {
                addCborMap {
                    putTaggedEncodedCbor(
                        "itemsRequest",
                        Cbor.encode(buildCborMap {
                            putCborMap("nameSpaces") {
                                putCborMap("org.iso.18013.5.1") {
                                    put("portrait", true)
                                    put("age_over_18", true)
                                }
                            }
                            put("docType", "org.iso.18013.5.1.mDL")
                        })
                    )
                }
            }
        }
        assertContentEquals(
            Cbor.encode(drRequest2),
            Cbor.encode(DeviceRequest.fromDataItem(drRequest2).toDataItem())
        )
    }

    @Test
    fun testAgainst18013SecondEditionVector() {
        // This vector is ISO 18013-5 Second Edition clause D.4.1.3 Extended request examples
        val encodedDeviceRequest =
            "a36776657273696f6e63312e316b646f63526571756573747383a16c6974656d7352657175657374d8185902b2a367646f6354797065756f72672e69736f2e31383031332e352e312e6d444c6a6e616d65537061636573a2716f72672e69736f2e31383031332e352e31a768706f727472616974f56a62697274685f64617465f56a69737375655f64617465f56b6578706972795f64617465f56f646f63756d656e745f6e756d626572f5781d676976656e5f6e616d655f6e6174696f6e616c5f636861726163746572f5781e66616d696c795f6e616d655f6e6174696f6e616c5f636861726163746572f5776f72672e69736f2e31383031332e352e312e61616d7661a1781b646f6d65737469635f64726976696e675f70726976696c65676573f56b72657175657374496e666fa274756e69717565446f635365745265717569726564f577616c7465726e617469766544617461456c656d656e747383a270726571756573746564456c656d656e7482716f72672e69736f2e31383031332e352e31781d676976656e5f6e616d655f6e6174696f6e616c5f63686172616374657276616c7465726e6174697665456c656d656e7453657473818182716f72672e69736f2e31383031332e352e316a676976656e5f6e616d65a270726571756573746564456c656d656e7482716f72672e69736f2e31383031332e352e31781e66616d696c795f6e616d655f6e6174696f6e616c5f63686172616374657276616c7465726e6174697665456c656d656e7453657473818182716f72672e69736f2e31383031332e352e316c66616d696c792e5f6e616d65a270726571756573746564456c656d656e7482776f72672e69736f2e31383031332e352e312e61616d7661781b646f6d65737469635f64726976696e675f70726976696c6567657376616c7465726e6174697665456c656d656e7453657473818182716f72672e69736f2e31383031332e352e317264726976696e675f70726976696c65676573a16c6974656d7352657175657374d818590204a367646f6354797065756f72672e69736f2e31383031332e352e312e6d444c6a6e616d65537061636573a1716f72672e69736f2e31383031332e352e31a568706f727472616974f56a676976656e5f6e616d65f56b6167655f6f7665725f3138f56b66616d696c795f6e616d65f5747265736964656e745f706f7374616c5f636f6465f56b72657175657374496e666fa274756e69717565446f635365745265717569726564f577616c7465726e617469766544617461456c656d656e747382a270726571756573746564456c656d656e7482716f72672e69736f2e31383031332e352e316b6167655f6f7665725f313876616c7465726e6174697665456c656d656e7453657473828182716f72672e69736f2e31383031332e352e316c6167655f696e5f79656172738182716f72672e69736f2e31383031332e352e316a62697274685f64617465a270726571756573746564456c656d656e7482716f72672e69736f2e31383031332e352e31747265736964656e745f706f7374616c5f636f646576616c7465726e6174697665456c656d656e7453657473828282716f72672e69736f2e31383031332e352e316d7265736964656e745f6369747982716f72672e69736f2e31383031332e352e316e7265736964656e745f73746174658182716f72672e69736f2e31383031332e352e31707265736964656e745f61646472657373a16c6974656d7352657175657374d81859040da367646f6354797065756f72672e69736f2e32333232302e70686f746f49446a6e616d65537061636573a16f6f72672e69736f2e32333232302e31a668706f727472616974f5696c6173745f6e616d65f56a66697273745f6e616d65f56b6167655f6f7665725f3138f56d7265736964656e745f63697479f56e7265736964656e745f7374617465f56b72657175657374496e666fa274756e69717565446f635365745265717569726564f577616c7465726e617469766544617461456c656d656e747386a270726571756573746564456c656d656e74826f6f72672e69736f2e32333232302e316a66697273745f6e616d6576616c7465726e6174697665456c656d656e74536574738281826f6f72672e69736f2e32333232302e317166697273745f6e616d655f6c6174696e318182781a6f72672e69736f2e32333232302e6461746167726f7570732e3163646731a270726571756573746564456c656d656e74826f6f72672e69736f2e32333232302e31696c6173745f6e616d6576616c7465726e6174697665456c656d656e74536574738281826f6f72672e69736f2e32333232302e31706c6173745f6e616d655f6c6174696e318182781a6f72672e69736f2e32333232302e6461746167726f7570732e3163646731a270726571756573746564456c656d656e74826f6f72672e69736f2e32333232302e3168706f72747261697476616c7465726e6174697665456c656d656e7453657473818182781a6f72672e69736f2e32333232302e6461746167726f7570732e3163646732a270726571756573746564456c656d656e74826f6f72672e69736f2e32333232302e316d7265736964656e745f6369747976616c7465726e6174697665456c656d656e74536574738281826f6f72672e69736f2e32333232302e31747265736964656e745f706f7374616c5f636f646581826f6f72672e69736f2e32333232302e31707265736964656e745f61646472657373a270726571756573746564456c656d656e74826f6f72672e69736f2e32333232302e316e7265736964656e745f737461746576616c7465726e6174697665456c656d656e74536574738281826f6f72672e69736f2e32333232302e31747265736964656e745f706f7374616c5f636f646581826f6f72672e69736f2e32333232302e31707265736964656e745f61646472657373a270726571756573746564456c656d656e74826f6f72672e69736f2e32333232302e316b6167655f6f7665725f313876616c7465726e6174697665456c656d656e74536574738381826f6f72672e69736f2e32333232302e316c6167655f696e5f796561727381826f6f72672e69736f2e32333232302e316a62697274685f646174658182781a6f72672e69736f2e32333232302e6461746167726f7570732e31636467317164657669636552657175657374496e666fd8185887a168757365436173657382a3696d616e6461746f7279f46c646f63756d656e74536574738181006c707572706f736548696e7473a1716f72672e69736f2e6a7463312e7363313702a3696d616e6461746f7279f46c646f63756d656e745365747382810181026c707572706f736548696e7473a1716f72672e69736f2e6a7463312e7363313701".fromHex()
        val deviceRequest = DeviceRequest.fromDataItem(Cbor.decode(encodedDeviceRequest))
        assertEquals(deviceRequest.version, "1.1")
        assertEquals(deviceRequest.docRequests.size, 3)
        val dr = deviceRequest.docRequests

        // Check first DocRequest
        assertEquals("org.iso.18013.5.1.mDL", dr[0].docType)
        assertEquals(
            mapOf(
                "org.iso.18013.5.1" to mapOf(
                    "portrait" to true,
                    "birth_date" to true,
                    "issue_date" to true,
                    "expiry_date" to true,
                    "document_number" to true,
                    "given_name_national_character" to true,
                    "family_name_national_character" to true,
                ),
                "org.iso.18013.5.1.aamva" to mapOf(
                    "domestic_driving_privileges" to true,
                ),
            ),
            dr[0].nameSpaces
        )
        assertNotNull(dr[0].docRequestInfo)
        assertEquals(listOf(
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.18013.5.1", "given_name_national_character"),
                alternativeElementSets = listOf(
                    listOf(ElementReference("org.iso.18013.5.1", "given_name"))
                )
            ),
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.18013.5.1", "family_name_national_character"),
                alternativeElementSets = listOf(
                    // TODO: "family._name" is a typo in the test vector in WG10N2718, reported to the editor and
                    //  expected to be fixed in a later version
                    listOf(ElementReference("org.iso.18013.5.1", "family._name"))
                )
            ),
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.18013.5.1.aamva", "domestic_driving_privileges"),
                alternativeElementSets = listOf(
                    listOf(ElementReference("org.iso.18013.5.1", "driving_privileges"))
                )
            ),
        ), dr[0].docRequestInfo!!.alternativeDataElements)
        assertEquals(true, dr[0].docRequestInfo!!.uniqueDocSetRequired)
        assertEquals(emptyList(), dr[0].docRequestInfo!!.issuerIdentifiers)
        assertEquals(null, dr[0].docRequestInfo!!.maximumResponseSize)
        assertEquals(null, dr[0].docRequestInfo!!.zkRequest)
        assertEquals(emptyMap(), dr[0].docRequestInfo!!.otherInfo)

        // Check second DocRequest
        assertEquals("org.iso.18013.5.1.mDL", dr[1].docType)
        assertEquals(
            mapOf(
                "org.iso.18013.5.1" to mapOf(
                    "portrait" to true,
                    "given_name" to true,
                    "age_over_18" to true,
                    "family_name" to true,
                    "resident_postal_code" to true,
                ),
            ),
            dr[1].nameSpaces
        )
        assertNotNull(dr[1].docRequestInfo)
        assertEquals(listOf(
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.18013.5.1", "age_over_18"),
                alternativeElementSets = listOf(
                    listOf(ElementReference("org.iso.18013.5.1", "age_in_years")),
                    listOf(ElementReference("org.iso.18013.5.1", "birth_date")),
                )
            ),
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.18013.5.1", "resident_postal_code"),
                alternativeElementSets = listOf(
                    listOf(
                        ElementReference("org.iso.18013.5.1", "resident_city"),
                        ElementReference("org.iso.18013.5.1", "resident_state")
                    ),
                    listOf(ElementReference("org.iso.18013.5.1", "resident_address")),
                )
            ),
        ), dr[1].docRequestInfo!!.alternativeDataElements)
        assertEquals(true, dr[1].docRequestInfo!!.uniqueDocSetRequired)
        assertEquals(emptyList(), dr[1].docRequestInfo!!.issuerIdentifiers)
        assertEquals(null, dr[1].docRequestInfo!!.maximumResponseSize)
        assertEquals(null, dr[1].docRequestInfo!!.zkRequest)
        assertEquals(emptyMap(), dr[1].docRequestInfo!!.otherInfo)

        // Check third DocRequest
        assertEquals("org.iso.23220.photoID", dr[2].docType)
        assertEquals(
            mapOf(
                "org.iso.23220.1" to mapOf(
                    "portrait" to true,
                    "last_name" to true,
                    "first_name" to true,
                    "age_over_18" to true,
                    "resident_city" to true,
                    "resident_state" to true,
                ),
            ),
            dr[2].nameSpaces
        )
        assertNotNull(dr[2].docRequestInfo)
        assertEquals(listOf(
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.23220.1", "first_name"),
                alternativeElementSets = listOf(
                    listOf(ElementReference("org.iso.23220.1", "first_name_latin1")),
                    listOf(ElementReference("org.iso.23220.datagroups.1", "dg1")),
                )
            ),
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.23220.1", "last_name"),
                alternativeElementSets = listOf(
                    listOf(ElementReference("org.iso.23220.1", "last_name_latin1")),
                    listOf(ElementReference("org.iso.23220.datagroups.1", "dg1")),
                )
            ),
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.23220.1", "portrait"),
                alternativeElementSets = listOf(
                    listOf(ElementReference("org.iso.23220.datagroups.1", "dg2")),
                )
            ),
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.23220.1", "resident_city"),
                alternativeElementSets = listOf(
                    listOf(ElementReference("org.iso.23220.1", "resident_postal_code")),
                    listOf(ElementReference("org.iso.23220.1", "resident_address")),
                )
            ),
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.23220.1", "resident_state"),
                alternativeElementSets = listOf(
                    listOf(ElementReference("org.iso.23220.1", "resident_postal_code")),
                    listOf(ElementReference("org.iso.23220.1", "resident_address")),
                )
            ),
            AlternativeDataElementSet(
                requestedElement = ElementReference("org.iso.23220.1", "age_over_18"),
                alternativeElementSets = listOf(
                    listOf(ElementReference("org.iso.23220.1", "age_in_years")),
                    listOf(ElementReference("org.iso.23220.1", "birth_date")),
                    listOf(ElementReference("org.iso.23220.datagroups.1", "dg1")),
                )
            ),
        ), dr[2].docRequestInfo!!.alternativeDataElements)
        assertEquals(true, dr[2].docRequestInfo!!.uniqueDocSetRequired)
        assertEquals(emptyList(), dr[2].docRequestInfo!!.issuerIdentifiers)
        assertEquals(null, dr[2].docRequestInfo!!.maximumResponseSize)
        assertEquals(null, dr[2].docRequestInfo!!.zkRequest)
        assertEquals(emptyMap(), dr[2].docRequestInfo!!.otherInfo)

        // Check DeviceRequestInfo
        assertNotNull(deviceRequest.deviceRequestInfo)
        val dri = deviceRequest.deviceRequestInfo
        assertEquals(2, dri.useCases.size)
        assertEquals(false, dri.useCases[0].mandatory)
        assertEquals(listOf(DocumentSet(listOf(0))), dri.useCases[0].documentSets)
        assertEquals(mapOf("org.iso.jtc1.sc17" to 2), dri.useCases[0].purposeHints)
        assertEquals(false, dri.useCases[1].mandatory)
        assertEquals(listOf(DocumentSet(listOf(1)), DocumentSet(listOf(2))), dri.useCases[1].documentSets)
        assertEquals(mapOf("org.iso.jtc1.sc17" to 1), dri.useCases[1].purposeHints)
    }

    @Test
    fun testIsStructurallyEquivalent() = runTest {
        val transcript1 = buildCborArray { add("Session1") }
        val transcript2 = buildCborArray { add("Session2") }

        val req1 = DeviceRequest.Builder(transcript1)
            .addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                )
            )
            .build()

        val req2 = DeviceRequest.Builder(transcript2)
            .addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                )
            )
            .build()

        val req3 = DeviceRequest.Builder(transcript1)
            .addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false, "given_name" to false)
                )
            )
            .build()

        assertEquals(true, req1.isStructurallyEquivalent(req2))
        assertEquals(true, req2.isStructurallyEquivalent(req1))
        assertEquals(false, req1.isStructurallyEquivalent(req3))
    }

    @Test
    fun testIsStructurallyEquivalentWithDifferentReaderAuthCerts() = runTest {
        val transcript = buildCborArray { add("Session") }

        val caKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val key1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val key2 = Crypto.createEcPrivateKey(EcCurve.P256)
        val otherCaKey = Crypto.createEcPrivateKey(EcCurve.P256)

        val validFrom = Clock.System.now()
        val validUntil = Instant.fromEpochMilliseconds(
            validFrom.toEpochMilliseconds() + 30L * 24 * 60 * 60 * 1000
        )

        val caCert = X509Cert.Builder(
            publicKey = caKey.publicKey,
            signingKey = AsymmetricKey.anonymous(caKey, Algorithm.ES256),
            serialNumber = ASN1Integer(100),
            subject = X500Name.fromName("CN=Reader Root CA"),
            issuer = X500Name.fromName("CN=Reader Root CA"),
            validFrom = validFrom,
            validUntil = validUntil
        ).includeSubjectKeyIdentifier().build()

        // Single-use leaf keys issued by the same CA for the same reader subject
        val cert1 = X509Cert.Builder(
            publicKey = key1.publicKey,
            signingKey = AsymmetricKey.anonymous(caKey, Algorithm.ES256),
            serialNumber = ASN1Integer(1),
            subject = X500Name.fromName("CN=Reader 1"),
            issuer = caCert.subject,
            validFrom = validFrom,
            validUntil = validUntil
        ).setAuthorityKeyIdentifierToCertificate(caCert).build()

        val cert2 = X509Cert.Builder(
            publicKey = key2.publicKey,
            signingKey = AsymmetricKey.anonymous(caKey, Algorithm.ES256),
            serialNumber = ASN1Integer(2),
            subject = X500Name.fromName("CN=Reader 1"),
            issuer = caCert.subject,
            validFrom = validFrom,
            validUntil = validUntil
        ).setAuthorityKeyIdentifierToCertificate(caCert).build()

        // Unrelated reader certificate from a different CA / subject
        val otherCert = X509Cert.Builder(
            publicKey = key2.publicKey,
            signingKey = AsymmetricKey.anonymous(otherCaKey, Algorithm.ES256),
            serialNumber = ASN1Integer(3),
            subject = X500Name.fromName("CN=Reader 2"),
            issuer = X500Name.fromName("CN=Other Root CA"),
            validFrom = validFrom,
            validUntil = validUntil
        ).build()

        val readerKey1 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(cert1, caCert)), key1)
        val readerKey2 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(cert2, caCert)), key2)
        val otherReaderKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(otherCert)), key2)

        val req1 = DeviceRequest.Builder(transcript)
            .addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey1
            )
            .build()

        val req2 = DeviceRequest.Builder(transcript)
            .addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey2
            )
            .build()

        val reqOtherReader = DeviceRequest.Builder(transcript)
            .addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = otherReaderKey
            )
            .build()

        assertTrue(req1.isStructurallyEquivalent(req2))
        assertTrue(req2.isStructurallyEquivalent(req1))
        assertFalse(req1.isStructurallyEquivalent(reqOtherReader))
        assertFalse(reqOtherReader.isStructurallyEquivalent(req1))
    }

    @Test
    fun x5chain_excludesRootCertificate() = runTest {
        val ra = ReaderAuth.generateSoftware()
        // ra.readerKey has certChain of [readerCert, readerRootCert]
        assertEquals(2, ra.readerKey.certChain.certificates.size)
        val readerCert = ra.readerKey.certChain.certificates[0]
        val rootCert = ra.readerKey.certChain.certificates[1]

        val sessionTranscript = buildCborArray { add("SessionTranscript") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)
                ),
                readerKey = ra.readerKey
            )
            addReaderAuthAll(readerKey = ra.readerKey)
        }

        // Verify with raw CBOR that x5chain header parameter contains only readerCert (as Bstr, not array with root)
        val rawItem = deviceRequest.toDataItem()
        val docRequestsArray = rawItem["docRequests"].asArray
        val docRequestItem = docRequestsArray[0]
        val readerAuthItem = docRequestItem.getOrNull("readerAuth")
        assertNotNull(readerAuthItem)
        val readerAuthSign1 = CoseSign1.fromDataItem(readerAuthItem)
        val docRequestX5chain = readerAuthSign1.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
        assertNotNull(docRequestX5chain)
        // Since only 1 cert remains after root exclusion, RFC 9360 requires a single Bstr
        assertTrue(docRequestX5chain is Bstr)
        assertEquals(readerCert.encoded, ByteString(docRequestX5chain.asBstr))
        assertEquals(1, docRequestX5chain.asX509CertChain.certificates.size)
        assertEquals(readerCert, docRequestX5chain.asX509CertChain.certificates[0])

        val readerAuthAllArray = rawItem.getOrNull("readerAuthAll")?.asArray
        assertNotNull(readerAuthAllArray)
        val readerAuthAllSign1 = CoseSign1.fromDataItem(readerAuthAllArray[0])
        val readerAuthAllX5chain = readerAuthAllSign1.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]
        assertNotNull(readerAuthAllX5chain)
        assertTrue(readerAuthAllX5chain is Bstr)
        assertEquals(readerCert.encoded, ByteString(readerAuthAllX5chain.asBstr))
        assertEquals(1, readerAuthAllX5chain.asX509CertChain.certificates.size)
        assertEquals(readerCert, readerAuthAllX5chain.asX509CertChain.certificates[0])

        // Verify parsing and authentication verification
        val parsed = DeviceRequest.fromDataItem(rawItem)
        parsed.verifyReaderAuthentication(sessionTranscript)
        val identities = parsed.getRequesterIdentities()
        assertEquals(1, identities.size)
        assertEquals(1, identities[0].certChain.certificates.size)
        assertEquals(readerCert, identities[0].certChain.certificates[0])
    }

    @Test
    fun testIsSameAuthenticatedReader() = runTest {
        val transcript1 = buildCborArray { add("Session1") }
        val transcript2 = buildCborArray { add("Session2") }
        val validFrom = Clock.System.now()
        val validUntil = Instant.fromEpochMilliseconds(
            validFrom.toEpochMilliseconds() + 30L * 24 * 60 * 60 * 1000
        )

        val storage = EphemeralStorage()
        val secureAreaRepository = SecureAreaRepository.Builder().build()
        val documentStore = buildDocumentStore(storage, secureAreaRepository) {}
        val documentTypeRepository = DocumentTypeRepository()
        val trustManager = TrustManager(storage)

        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(rootKey, Algorithm.ES256),
            subject = X500Name.fromName("CN=Trusted Reader Root CA"),
            serial = ASN1Integer(100L),
            validFrom = validFrom,
            validUntil = validUntil,
            crlUrl = "https://example.com/crl",
        )
        trustManager.addX509Cert(
            certificate = rootCert,
            metadata = TrustMetadata(displayName = "Trusted Reader"),
        )

        val trustedSource = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            resolveTrustFn = { requester ->
                for (id in requester.requesterIdentities) {
                    val res = trustManager.verify(id.certChain.certificates)
                    if (res.isTrusted) {
                        return@SimplePresentmentSource TrustedRequesterIdentity(
                            identity = id,
                            trustMetadata = res.trustPoints.first().metadata
                        )
                    }
                }
                null
            },
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
        )
        val untrustedSource = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
        )

        val leafPriv1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val leafCert1 = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(rootCert)), rootKey),
            readerKey = leafPriv1.publicKey,
            subject = X500Name.fromName("CN=Reader A"),
            dnsName = null,
            serial = ASN1Integer(1L),
            validFrom = validFrom,
            validUntil = validUntil,
        )
        val leafPriv2 = Crypto.createEcPrivateKey(EcCurve.P256)
        val leafCert2 = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(rootCert)), rootKey),
            readerKey = leafPriv2.publicKey,
            subject = X500Name.fromName("CN=Reader A"),
            dnsName = null,
            serial = ASN1Integer(2L),
            validFrom = validFrom,
            validUntil = validUntil,
        )

        // Attacker signs a spoofed leaf certificate with the exact same subject, issuer, and AKI
        // as leafCert1, but using an attacker key instead of rootKey
        val attackerKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val spoofedLeafPriv = Crypto.createEcPrivateKey(EcCurve.P256)
        val spoofedLeafCert = X509Cert.Builder(
            publicKey = spoofedLeafPriv.publicKey,
            signingKey = AsymmetricKey.anonymous(attackerKey, Algorithm.ES256),
            serialNumber = ASN1Integer(3L),
            subject = leafCert1.subject,
            issuer = leafCert1.issuer,
            validFrom = validFrom,
            validUntil = validUntil,
        ).setAuthorityKeyIdentifierToCertificate(rootCert)
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()

        val readerKey1 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(leafCert1, rootCert)), leafPriv1)
        val readerKey2 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(leafCert2, rootCert)), leafPriv2)
        val spoofedReaderKey = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(spoofedLeafCert, rootCert)),
            spoofedLeafPriv
        )

        val req1 = buildDeviceRequest(transcript1) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey1,
            )
        }
        val req1SameCert = buildDeviceRequest(transcript2) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey1,
            )
        }
        val req2RotatedCert = buildDeviceRequest(transcript2) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey2,
            )
        }
        val reqSpoofedCert = buildDeviceRequest(transcript2) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = spoofedReaderKey,
            )
        }

        // 1. Identical leaf cert passes for both trusted and untrusted sources
        assertTrue(req1SameCert.isSameAuthenticatedReader(req1, trustedSource))
        assertTrue(req1SameCert.isSameAuthenticatedReader(req1, untrustedSource))

        // 2. Single-use leaf rotation (1-cert x5chain on the wire because root is excluded):
        //    - Passes when trusted by source.resolveTrust()
        //    - Fails when untrusted (no CA in x5chain or trust store to verify the rotated leaf)
        //    - Fails when the rotated leaf is spoofed by an attacker key
        assertTrue(req2RotatedCert.isSameAuthenticatedReader(req1, trustedSource))
        assertFalse(req2RotatedCert.isSameAuthenticatedReader(req1, untrustedSource))
        assertTrue(reqSpoofedCert.isStructurallyEquivalent(req1))
        assertFalse(reqSpoofedCert.isSameAuthenticatedReader(req1, trustedSource))

        // 3. Multi-certificate x5chain ([leaf, intermediateCA] on wire, root excluded):
        //    - Passes when both leaves are validly signed by intermediateCA
        //    - Fails when leaf2 is forged (not signed by intermediateCA)
        val intermediateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val intermediateCert = X509Cert.Builder(
            publicKey = intermediateKey.publicKey,
            signingKey = AsymmetricKey.anonymous(rootKey, Algorithm.ES256),
            serialNumber = ASN1Integer(50L),
            subject = X500Name.fromName("CN=Intermediate Reader CA"),
            issuer = rootCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ).includeSubjectKeyIdentifier()
            .setAuthorityKeyIdentifierToCertificate(rootCert)
            .setBasicConstraints(ca = true, pathLenConstraint = 0)
            .setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            .build()

        val multiLeaf1 = X509Cert.Builder(
            publicKey = leafPriv1.publicKey,
            signingKey = AsymmetricKey.anonymous(intermediateKey, Algorithm.ES256),
            serialNumber = ASN1Integer(51L),
            subject = X500Name.fromName("CN=Multi Reader"),
            issuer = intermediateCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ).setAuthorityKeyIdentifierToCertificate(intermediateCert)
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()

        val multiLeaf2 = X509Cert.Builder(
            publicKey = leafPriv2.publicKey,
            signingKey = AsymmetricKey.anonymous(intermediateKey, Algorithm.ES256),
            serialNumber = ASN1Integer(52L),
            subject = X500Name.fromName("CN=Multi Reader"),
            issuer = intermediateCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ).setAuthorityKeyIdentifierToCertificate(intermediateCert)
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()

        val forgedMultiLeaf = X509Cert.Builder(
            publicKey = spoofedLeafPriv.publicKey,
            signingKey = AsymmetricKey.anonymous(attackerKey, Algorithm.ES256),
            serialNumber = ASN1Integer(53L),
            subject = X500Name.fromName("CN=Multi Reader"),
            issuer = intermediateCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ).setAuthorityKeyIdentifierToCertificate(intermediateCert)
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()

        val multiKey1 = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(multiLeaf1, intermediateCert, rootCert)),
            leafPriv1
        )
        val multiKey2 = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(multiLeaf2, intermediateCert, rootCert)),
            leafPriv2
        )
        val forgedMultiKey = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(forgedMultiLeaf, intermediateCert, rootCert)),
            spoofedLeafPriv
        )

        val multiReq1 = buildDeviceRequest(transcript1) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = multiKey1,
            )
        }
        val multiReq2 = buildDeviceRequest(transcript2) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = multiKey2,
            )
        }
        val forgedMultiReq = buildDeviceRequest(transcript2) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = forgedMultiKey,
            )
        }

        assertTrue(multiReq2.isSameAuthenticatedReader(multiReq1, untrustedSource))
        assertTrue(forgedMultiReq.isStructurallyEquivalent(multiReq1))
        assertFalse(forgedMultiReq.isSameAuthenticatedReader(multiReq1, untrustedSource))

        // 4. Multi-DocRequest where docRequests[0] keeps the same cert and docRequests[1] rotates to a spoofed cert
        val multiDocReq1 = buildDeviceRequest(transcript1) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey1,
            )
            addDocRequest(
                docType = PhotoID.PHOTO_ID_DOCTYPE,
                nameSpaces = mapOf(PhotoID.PHOTO_ID_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey1,
            )
        }
        val multiDocReq2Valid = buildDeviceRequest(transcript2) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey1,
            )
            addDocRequest(
                docType = PhotoID.PHOTO_ID_DOCTYPE,
                nameSpaces = mapOf(PhotoID.PHOTO_ID_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey2,
            )
        }
        val multiDocReq2Spoofed = buildDeviceRequest(transcript2) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey1,
            )
            addDocRequest(
                docType = PhotoID.PHOTO_ID_DOCTYPE,
                nameSpaces = mapOf(PhotoID.PHOTO_ID_NAMESPACE to mapOf("family_name" to false)),
                readerKey = spoofedReaderKey,
            )
        }
        assertTrue(multiDocReq2Valid.isSameAuthenticatedReader(multiDocReq1, trustedSource))
        assertFalse(multiDocReq2Spoofed.isSameAuthenticatedReader(multiDocReq1, trustedSource))
    }

    @Test
    fun testIsStructurallyEquivalentAndSameReaderWithX5t() = runTest {
        val transcript1 = buildCborArray { add("Session1") }
        val transcript2 = buildCborArray { add("Session2") }
        val validFrom = Clock.System.now()
        val validUntil = validFrom + 30.days

        val storage = EphemeralStorage()
        val secureAreaRepository = SecureAreaRepository.Builder().build()
        val documentStore = buildDocumentStore(storage, secureAreaRepository) {}
        val documentTypeRepository = DocumentTypeRepository()
        val trustManager = TrustManager(storage)

        val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(rootKey, Algorithm.ES256),
            subject = X500Name.fromName("CN=Trusted Reader Root CA"),
            serial = ASN1Integer(100L),
            validFrom = validFrom,
            validUntil = validUntil,
            crlUrl = "https://example.com/crl",
        )
        trustManager.addX509Cert(
            certificate = rootCert,
            metadata = TrustMetadata(displayName = "Trusted Reader"),
        )

        val trustedSource = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            resolveTrustFn = { requester ->
                for (id in requester.requesterIdentities) {
                    val res = trustManager.verify(id.certChain.certificates)
                    if (res.isTrusted) {
                        return@SimplePresentmentSource TrustedRequesterIdentity(
                            identity = id,
                            trustMetadata = res.trustPoints.first().metadata
                        )
                    }
                }
                null
            },
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
        )
        val untrustedSource = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
        )

        val leafPriv1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val leafCert1 = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(rootCert)), rootKey),
            readerKey = leafPriv1.publicKey,
            subject = X500Name.fromName("CN=Reader Linux"),
            dnsName = null,
            serial = ASN1Integer(1L),
            validFrom = validFrom,
            validUntil = validUntil,
        )
        val leafPriv2 = Crypto.createEcPrivateKey(EcCurve.P256)
        val leafCert2 = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(rootCert)), rootKey),
            readerKey = leafPriv2.publicKey,
            subject = X500Name.fromName("CN=Reader Linux"),
            dnsName = null,
            serial = ASN1Integer(2L),
            validFrom = validFrom,
            validUntil = validUntil,
        )
        val otherRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val otherRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(otherRootKey, Algorithm.ES256),
            subject = X500Name.fromName("CN=Other Reader Root CA"),
            serial = ASN1Integer(200L),
            validFrom = validFrom,
            validUntil = validUntil,
            crlUrl = "https://example.com/other-crl",
        )
        val otherLeafPriv = Crypto.createEcPrivateKey(EcCurve.P256)
        val otherLeafCert = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(otherRootCert)), otherRootKey),
            readerKey = otherLeafPriv.publicKey,
            subject = X500Name.fromName("CN=Other Reader"),
            dnsName = null,
            serial = ASN1Integer(3L),
            validFrom = validFrom,
            validUntil = validUntil,
        )
        val attackerKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val spoofedLeafPriv = Crypto.createEcPrivateKey(EcCurve.P256)
        val spoofedLeafCert = X509Cert.Builder(
            publicKey = spoofedLeafPriv.publicKey,
            signingKey = AsymmetricKey.anonymous(attackerKey, Algorithm.ES256),
            serialNumber = ASN1Integer(4L),
            subject = leafCert1.subject,
            issuer = leafCert1.issuer,
            validFrom = validFrom,
            validUntil = validUntil,
        ).setAuthorityKeyIdentifierToCertificate(rootCert)
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()

        val readerKey1 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(leafCert1, rootCert)), leafPriv1)
        val readerKey2 = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(leafCert2, rootCert)), leafPriv2)
        val otherReaderKey = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(otherLeafCert, otherRootCert)),
            otherLeafPriv
        )
        val spoofedReaderKey = AsymmetricKey.X509CertifiedExplicit(
            X509CertChain(listOf(spoofedLeafCert, rootCert)),
            spoofedLeafPriv
        )

        val twoDocUseCaseInfo = DeviceRequestInfo.fromValues(
            useCases = listOf(
                UseCase(
                    mandatory = true,
                    documentSets = listOf(DocumentSet(listOf(0)), DocumentSet(listOf(1))),
                    purposeHints = emptyMap()
                )
            )
        )

        suspend fun buildV11ReaderAuthAllRequest(
            transcript: DataItem,
            readerKey: AsymmetricKey.X509Compatible,
        ): DeviceRequest = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = twoDocUseCaseInfo,
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("portrait" to false, "age_over_21" to false)
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(DrivingLicense.MDL_NAMESPACE, "age_over_21"),
                            alternativeElementSets = listOf(
                                listOf(ElementReference(DrivingLicense.MDL_NAMESPACE, "age_in_years")),
                                listOf(ElementReference(DrivingLicense.MDL_NAMESPACE, "birth_date")),
                            )
                        )
                    )
                )
            )
            addDocRequest(
                docType = PhotoID.PHOTO_ID_DOCTYPE,
                nameSpaces = mapOf(
                    PhotoID.ISO_23220_2_NAMESPACE to mapOf("portrait" to false, "age_over_21" to false)
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(PhotoID.ISO_23220_2_NAMESPACE, "age_over_21"),
                            alternativeElementSets = listOf(
                                listOf(ElementReference(PhotoID.ISO_23220_2_NAMESPACE, "age_in_years")),
                                listOf(ElementReference(PhotoID.ISO_23220_2_NAMESPACE, "birth_date")),
                            )
                        )
                    )
                )
            )
            addReaderAuthAll(readerKey)
        }

        // 1. Version 1.1 ReaderAuthAll with x5t in protected headers and rotated single-use leaf certs
        val reqAll1 = buildV11ReaderAuthAllRequest(transcript1, readerKey1)
        val reqAll1SameCert = buildV11ReaderAuthAllRequest(transcript2, readerKey1)
        val reqAll2Rotated = buildV11ReaderAuthAllRequest(transcript2, readerKey2)
        val reqAllOther = buildV11ReaderAuthAllRequest(transcript2, otherReaderKey)
        val reqAllSpoofed = buildV11ReaderAuthAllRequest(transcript2, spoofedReaderKey)

        val x5tLabel = Cose.COSE_LABEL_X5T.toCoseLabel
        assertEquals("1.1", reqAll1.version)
        assertEquals("1.1", reqAll2Rotated.version)
        val x5t1 = reqAll1.readerAuthAll.single().protectedHeaders[x5tLabel]
        val x5t2 = reqAll2Rotated.readerAuthAll.single().protectedHeaders[x5tLabel]
        assertEquals(leafCert1.toCoseX5t(), x5t1)
        assertEquals(leafCert2.toCoseX5t(), x5t2)
        assertNotEquals(x5t1, x5t2)

        assertTrue(reqAll1.isStructurallyEquivalent(reqAll2Rotated))
        assertTrue(reqAll2Rotated.isStructurallyEquivalent(reqAll1))
        assertFalse(reqAll1.isStructurallyEquivalent(reqAllOther))
        assertFalse(reqAllOther.isStructurallyEquivalent(reqAll1))

        assertTrue(reqAll1SameCert.isSameAuthenticatedReader(reqAll1, untrustedSource))
        assertTrue(reqAll2Rotated.isSameAuthenticatedReader(reqAll1, trustedSource))
        assertFalse(reqAll2Rotated.isSameAuthenticatedReader(reqAll1, untrustedSource))
        assertTrue(reqAllSpoofed.isStructurallyEquivalent(reqAll1))
        assertFalse(reqAllSpoofed.isSameAuthenticatedReader(reqAll1, trustedSource))

        // 2. Version 1.1 per-DocRequest readerAuth with x5t in protected headers and rotated single-use leaf certs
        val perDocV11Req1 = buildDeviceRequest(
            sessionTranscript = transcript1,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey1,
            )
        }
        val perDocV11Req2 = buildDeviceRequest(
            sessionTranscript = transcript2,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = readerKey2,
            )
        }
        val perDocV11Other = buildDeviceRequest(
            sessionTranscript = transcript2,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false)),
                readerKey = otherReaderKey,
            )
        }
        assertEquals(leafCert1.toCoseX5t(), perDocV11Req1.docRequests.single().readerAuth!!.protectedHeaders[x5tLabel])
        assertEquals(leafCert2.toCoseX5t(), perDocV11Req2.docRequests.single().readerAuth!!.protectedHeaders[x5tLabel])
        assertTrue(perDocV11Req1.isStructurallyEquivalent(perDocV11Req2))
        assertTrue(perDocV11Req2.isStructurallyEquivalent(perDocV11Req1))
        assertFalse(perDocV11Req1.isStructurallyEquivalent(perDocV11Other))
        assertTrue(perDocV11Req2.isSameAuthenticatedReader(perDocV11Req1, trustedSource))
        assertFalse(perDocV11Req2.isSameAuthenticatedReader(perDocV11Req1, untrustedSource))

        // 3. Mismatched x5t presence in protectedHeaders fails structural equivalence
        val origAuth = reqAll1.readerAuthAll.single()
        val authWithoutX5t = origAuth.copy(protectedHeaders = origAuth.protectedHeaders.minus(x5tLabel))
        val rawReqWithoutX5t = buildCborMap {
            put("version", reqAll1.version)
            put("docRequests", reqAll1.toDataItem()["docRequests"])
            put("deviceRequestInfo", reqAll1.toDataItem()["deviceRequestInfo"])
            putCborArray("readerAuthAll") {
                add(authWithoutX5t.toDataItem())
            }
        }
        val reqWithoutX5t = DeviceRequest.fromDataItem(rawReqWithoutX5t)
        assertFalse(reqAll1.isStructurallyEquivalent(reqWithoutX5t))
        assertFalse(reqWithoutX5t.isStructurallyEquivalent(reqAll1))

        // 4. Same leaf cert (no rotation) or missing x5chain with differing x5t fails structural equivalence
        val authSameLeafDifferentX5t = origAuth.copy(
            protectedHeaders = origAuth.protectedHeaders + (x5tLabel to leafCert2.toCoseX5t())
        )
        val rawReqSameLeafDifferentX5t = buildCborMap {
            put("version", reqAll1.version)
            put("docRequests", reqAll1.toDataItem()["docRequests"])
            put("deviceRequestInfo", reqAll1.toDataItem()["deviceRequestInfo"])
            putCborArray("readerAuthAll") {
                add(authSameLeafDifferentX5t.toDataItem())
            }
        }
        val reqSameLeafDifferentX5t = DeviceRequest.fromDataItem(rawReqSameLeafDifferentX5t)
        assertFalse(reqAll1.isStructurallyEquivalent(reqSameLeafDifferentX5t))
        assertFalse(reqSameLeafDifferentX5t.isStructurallyEquivalent(reqAll1))

        val x5chainLabel = Cose.COSE_LABEL_X5CHAIN.toCoseLabel
        val authNoChainX5t1 = origAuth.copy(unprotectedHeaders = origAuth.unprotectedHeaders.minus(x5chainLabel))
        val authNoChainX5t2 = authSameLeafDifferentX5t.copy(
            unprotectedHeaders = origAuth.unprotectedHeaders.minus(x5chainLabel)
        )
        val reqNoChainX5t1 = DeviceRequest.fromDataItem(
            buildCborMap {
                put("version", reqAll1.version)
                put("docRequests", reqAll1.toDataItem()["docRequests"])
                put("deviceRequestInfo", reqAll1.toDataItem()["deviceRequestInfo"])
                putCborArray("readerAuthAll") { add(authNoChainX5t1.toDataItem()) }
            }
        )
        val reqNoChainX5t2 = DeviceRequest.fromDataItem(
            buildCborMap {
                put("version", reqAll1.version)
                put("docRequests", reqAll1.toDataItem()["docRequests"])
                put("deviceRequestInfo", reqAll1.toDataItem()["deviceRequestInfo"])
                putCborArray("readerAuthAll") { add(authNoChainX5t2.toDataItem()) }
            }
        )
        assertFalse(reqNoChainX5t1.isStructurallyEquivalent(reqNoChainX5t2))
        assertFalse(reqNoChainX5t2.isStructurallyEquivalent(reqNoChainX5t1))
    }

    @Test
    fun testSecondEditionUseCasesMandatoryAndVersionValidation() = runTest {
        val transcript = buildCborArray { add("SessionTranscript") }

        // 1. "1.1" DeviceRequest without deviceRequestInfo throws in Builder and fromDataItem
        assertFailsWith<IllegalArgumentException> {
            buildDeviceRequest(sessionTranscript = transcript, version = "1.1") {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
                )
            }
        }
        val validV11 = buildDeviceRequest(
            sessionTranscript = transcript,
            version = "1.1",
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
            )
        }
        val rawWithoutInfo = buildCborMap {
            put("version", "1.1")
            put("docRequests", validV11.toDataItem()["docRequests"])
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceRequest.fromDataItem(rawWithoutInfo)
        }

        // 2. DeviceRequestInfo without "useCases" or with empty useCases throws; UseCase with empty documentSets throws
        assertFailsWith<IllegalArgumentException> {
            DeviceRequestInfo.fromValues(useCases = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceRequestInfo(buildCborMap { put("other", "value") })
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceRequestInfo(buildCborMap { putCborArray("useCases") {} })
        }
        assertFailsWith<IllegalArgumentException> {
            UseCase(mandatory = true, documentSets = emptyList(), purposeHints = emptyMap())
        }

        // 3. "1.1" DeviceRequest with out-of-range docRequestId or unreferenced DocRequest in useCases throws
        assertFailsWith<IllegalArgumentException> {
            buildDeviceRequest(
                sessionTranscript = transcript,
                deviceRequestInfo = DeviceRequestInfo.fromValues(
                    useCases = listOf(
                        UseCase(
                            mandatory = true,
                            documentSets = listOf(DocumentSet(listOf(0, 5))),
                            purposeHints = emptyMap()
                        )
                    )
                )
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            buildDeviceRequest(
                sessionTranscript = transcript,
                deviceRequestInfo = singleDocUseCaseInfo() // only covers DocRequest 0, leaves DocRequest 1 unreferenced
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
                )
                addDocRequest(
                    docType = PhotoID.PHOTO_ID_DOCTYPE,
                    nameSpaces = mapOf(PhotoID.PHOTO_ID_NAMESPACE to mapOf("given_name" to false))
                )
            }
        }

        // 4. DocRequestInfo(nonKeyBoundSupported = ...) round-trips and automatically selects version "1.1"
        for (flag in listOf(true, false)) {
            val req = buildDeviceRequest(
                sessionTranscript = transcript,
                deviceRequestInfo = singleDocUseCaseInfo()
            ) {
                addDocRequest(
                    docType = PhotoID.PHOTO_ID_DOCTYPE,
                    nameSpaces = mapOf(PhotoID.PHOTO_ID_NAMESPACE to mapOf("given_name" to false)),
                    docRequestInfo = DocRequestInfo(nonKeyBoundSupported = flag)
                )
            }
            assertEquals("1.1", req.version)
            val parsed = DeviceRequest.fromDataItem(Cbor.decode(Cbor.encode(req.toDataItem())))
            assertEquals(flag, parsed.docRequests.single().docRequestInfo?.nonKeyBoundSupported)
        }

        // 5. Empty DocRequestInfo() normalizes to null in Builder (keeps "1.0"), while raw empty "requestInfo": {} throws
        val normalizedV10 = buildDeviceRequest(sessionTranscript = transcript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo()
            )
        }
        assertEquals("1.0", normalizedV10.version)
        assertNull(normalizedV10.docRequests.single().docRequestInfo)

        val emptyItemsRequestBytes = Tagged(
            tagNumber = Tagged.ENCODED_CBOR,
            taggedItem = Bstr(
                Cbor.encode(
                    buildCborMap {
                        put("docType", DrivingLicense.MDL_DOCTYPE)
                        putCborMap("nameSpaces") {
                            putCborMap(DrivingLicense.MDL_NAMESPACE) {
                                put("given_name", false)
                            }
                        }
                        putCborMap("requestInfo") {}
                    }
                )
            )
        )
        assertFailsWith<IllegalArgumentException> {
            DeviceRequest.fromDataItem(
                buildCborMap {
                    put("version", "1.0")
                    putCborArray("docRequests") {
                        addCborMap {
                            put("itemsRequest", emptyItemsRequestBytes)
                        }
                    }
                }
            )
        }

        // 6. Explicit version = "1.0" combined with Second Edition features throws
        assertFailsWith<IllegalArgumentException> {
            buildDeviceRequest(
                sessionTranscript = transcript,
                version = "1.0",
                deviceRequestInfo = singleDocUseCaseInfo()
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            buildDeviceRequest(
                sessionTranscript = transcript,
                version = "1.0"
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                    docRequestInfo = DocRequestInfo(nonKeyBoundSupported = true)
                )
            }
        }
    }

    @Test
    fun testReaderSignatureX5tHeaderAndVerification() = runTest {
        val transcript = buildCborArray { add("SessionTranscript") }
        val now = Clock.System.now()
        val rootPriv = Crypto.createEcPrivateKey(EcCurve.P256)
        val rootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(rootPriv, Algorithm.ES256),
            subject = X500Name.fromName("CN=Reader Root"),
            serial = ASN1Integer(1L),
            validFrom = now,
            validUntil = now + 1.days,
            crlUrl = "https://example.com/crl",
        )
        val leafPriv = Crypto.createEcPrivateKey(EcCurve.P256)
        val leafCert = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(rootCert)), rootPriv),
            readerKey = leafPriv.publicKey,
            subject = X500Name.fromName("CN=Reader Leaf"),
            dnsName = null,
            serial = ASN1Integer(2L),
            validFrom = now,
            validUntil = now + 1.days,
        )
        val readerKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(leafCert, rootCert)),
            privateKey = leafPriv
        )
        val expectedX5t = buildCborArray {
            add(Algorithm.SHA256.coseAlgorithmIdentifier!!)
            add(Crypto.digest(Algorithm.SHA256, leafCert.encoded.toByteArray()))
        }

        // 1a. Version 1.0 ReaderAuth omits x5t (ISO/IEC 18013-5:2021 Clause 9.1.2.4)
        val perDocReq10 = buildDeviceRequest(sessionTranscript = transcript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                readerKey = readerKey
            )
        }
        assertEquals("1.0", perDocReq10.version)
        val readerAuth10 = perDocReq10.docRequests.single().readerAuth!!
        assertNull(readerAuth10.protectedHeaders[Cose.COSE_LABEL_X5T.toCoseLabel])
        val parsedPerDoc10 = DeviceRequest.fromDataItem(Cbor.decode(Cbor.encode(perDocReq10.toDataItem())))
        parsedPerDoc10.verifyReaderAuthentication(transcript)

        // 1b. Version 1.1 ReaderAuth includes x5t in protected header and verifies
        val perDocReq = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                readerKey = readerKey
            )
        }
        assertEquals("1.1", perDocReq.version)
        val readerAuth = perDocReq.docRequests.single().readerAuth!!
        assertEquals(expectedX5t, readerAuth.protectedHeaders[Cose.COSE_LABEL_X5T.toCoseLabel])
        val parsedPerDoc = DeviceRequest.fromDataItem(Cbor.decode(Cbor.encode(perDocReq.toDataItem())))
        parsedPerDoc.verifyReaderAuthentication(transcript)

        // 1c. Legacy DeviceRequestGenerator (version 1.0) omits x5t from protected header
        @Suppress("DEPRECATION")
        val legacyEncoded = DeviceRequestGenerator(Cbor.encode(transcript))
            .addDocumentRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                itemsToRequest = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                requestInfo = null,
                readerKey = leafPriv,
                signatureAlgorithm = Algorithm.ES256,
                readerKeyCertificateChain = X509CertChain(listOf(leafCert)),
            )
            .generate()
        val legacyParsed = DeviceRequest.fromDataItem(Cbor.decode(legacyEncoded))
        legacyParsed.verifyReaderAuthentication(transcript)
        assertNull(legacyParsed.docRequests.single().readerAuth!!.protectedHeaders[Cose.COSE_LABEL_X5T.toCoseLabel])

        // 2. ReaderAuthAll includes x5t in protected header and verifies
        val allReq = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
            )
            addReaderAuthAll(readerKey)
        }
        val readerAuthAll = allReq.readerAuthAll.single()
        assertEquals(expectedX5t, readerAuthAll.protectedHeaders[Cose.COSE_LABEL_X5T.toCoseLabel])
        assertTrue(
            Cbor.encode(readerAuthAll.toDataItem()).toHex().startsWith(
                "845829a201261822822f5820" + Crypto.digest(Algorithm.SHA256, leafCert.encoded.toByteArray()).toHex()
            )
        )
        val parsedAll = DeviceRequest.fromDataItem(Cbor.decode(Cbor.encode(allReq.toDataItem())))
        parsedAll.verifyReaderAuthentication(transcript)

        // 3. Wrong x5t digest, unsupported x5t hash alg (-43 = SHA-384), and duplicate protected+unprotected x5t fail
        val itemsRequestBytes = perDocReq.docRequests.single().toDataItem()["itemsRequest"]
        val readerAuthenticationBytes = Cbor.encode(
            Tagged(
                Tagged.ENCODED_CBOR,
                Bstr(
                    Cbor.encode(
                        buildCborArray {
                            add("ReaderAuthentication")
                            add(transcript)
                            add(itemsRequestBytes)
                        }
                    )
                )
            )
        )
        for ((badProtectedX5t, badUnprotectedX5t) in listOf(
            buildCborArray { add(-16); add(ByteArray(32) { 0x42 }) } to null,
            buildCborArray { add(-43); add(Crypto.digest(Algorithm.SHA384, leafCert.encoded.toByteArray())) } to null,
            expectedX5t to expectedX5t,
        )) {
            val badSig = Cose.coseSign1Sign(
                signingKey = readerKey,
                message = readerAuthenticationBytes,
                includeMessageInPayload = false,
                protectedHeaders = mapOf(
                    Cose.COSE_LABEL_ALG.toCoseLabel to Algorithm.ES256.coseAlgorithmIdentifier!!.let { Nint((-1 - it).toULong()) },
                    Cose.COSE_LABEL_X5T.toCoseLabel to badProtectedX5t,
                ),
                unprotectedHeaders = buildMap {
                    put(Cose.COSE_LABEL_X5CHAIN.toCoseLabel, X509CertChain(listOf(leafCert)).toDataItem())
                    if (badUnprotectedX5t != null) {
                        put(Cose.COSE_LABEL_X5T.toCoseLabel, badUnprotectedX5t)
                    }
                }
            )
            val badReq = DeviceRequest.fromDataItem(
                buildCborMap {
                    put("version", "1.0")
                    putCborArray("docRequests") {
                        addCborMap {
                            put("itemsRequest", itemsRequestBytes)
                            put("readerAuth", badSig.toDataItem())
                        }
                    }
                }
            )
            assertFailsWith<SignatureVerificationException> {
                badReq.verifyReaderAuthentication(transcript)
            }
        }
    }

    @Test
    fun nonKeyBoundSupportedExecutionAndDomainSeparation() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addDocumentType(UtopiaMovieTicket.getDocumentType())

        val sharedDomainDoc = harness.provisionKeylessMdoc(
            displayName = "Keyless Movie Ticket",
            docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
            data = mapOf(
                UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to listOf(
                    "ticket_id" to Tstr("99999")
                )
            ),
            domain = "shared_domain"
        )
        harness.provisionKeylessSdJwtVc(
            displayName = "Keyless SD-JWT Movie Ticket",
            vct = UtopiaMovieTicket.MOVIE_TICKET_VCT,
            data = listOf("ticket_id" to kotlinx.serialization.json.JsonPrimitive("88888")),
            domain = "shared_domain"
        )

        val sharedDomainSource = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            resolveTrustFn = { null },
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("shared_domain"),
            domainsKeylessMdoc = listOf("shared_domain"),
            domainsKeyBoundSdJwt = listOf("shared_domain"),
            domainsKeylessSdJwt = listOf("shared_domain")
        )
        val transcript = buildCborArray { add("test") }

        // 1. Without nonKeyBoundSupported = true, keyless credentials MUST NOT match even when sharing a domain
        val reqKeyBoundOnly = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                nameSpaces = mapOf(UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to mapOf("ticket_id" to false))
            )
        }
        assertFailsWith<Iso18015ResponseException> {
            reqKeyBoundOnly.execute(sharedDomainSource)
        }

        // 2. With nonKeyBoundSupported = true, keyless mdoc and keyless SD-JWT match
        val reqKeylessMdocAllowed = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                nameSpaces = mapOf(UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to mapOf("ticket_id" to false)),
                docRequestInfo = DocRequestInfo(nonKeyBoundSupported = true)
            )
        }
        val mdocMatchResult = reqKeylessMdocAllowed.execute(sharedDomainSource)
        assertEquals(1, mdocMatchResult.useCases.single().solutions.size)
        assertTrue(
            mdocMatchResult.useCases.single().solutions.single().matches.single().credential is KeylessMdocCredential
        )

        val reqKeylessSdJwtAllowed = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_VCT,
                nameSpaces = mapOf("_" to mapOf("c0" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "c0" to buildJsonArray { add("ticket_id") }
                    ),
                    nonKeyBoundSupported = true
                )
            )
        }
        val sdJwtMatchResult = reqKeylessSdJwtAllowed.execute(sharedDomainSource)
        assertEquals(1, sdJwtMatchResult.useCases.single().solutions.size)
        assertTrue(
            sdJwtMatchResult.useCases.single().solutions.single().matches.single().credential is KeylessSdJwtVcCredential
        )

        // 3. zkRequired = true never matches keyless mdoc; zkRequired = false falls back to non-ZK keyless mdoc
        val zkSpecs = listOf(ZkSystemSpec(id = "0", system = "longfellow-zk"))
        val reqZkRequired = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                nameSpaces = mapOf(UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to mapOf("ticket_id" to false)),
                docRequestInfo = DocRequestInfo(
                    nonKeyBoundSupported = true,
                    zkRequest = ZkRequest(systemSpecs = zkSpecs, zkRequired = true)
                )
            )
        }
        assertFailsWith<Iso18015ResponseException> {
            reqZkRequired.execute(sharedDomainSource)
        }

        val reqZkOptional = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                nameSpaces = mapOf(UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to mapOf("ticket_id" to false)),
                docRequestInfo = DocRequestInfo(
                    nonKeyBoundSupported = true,
                    zkRequest = ZkRequest(systemSpecs = zkSpecs, zkRequired = false)
                )
            )
        }
        assertEquals(1, reqZkOptional.execute(sharedDomainSource).useCases.single().solutions.size)

        // 4. Transaction data requests never match keyless credentials
        val reqWithTxData = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                nameSpaces = mapOf(
                    UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to mapOf("ticket_id" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf("org.multipaz.transaction.ping" to false)
                ),
                docRequestInfo = DocRequestInfo(
                    nonKeyBoundSupported = true,
                    transactionData = TransactionsInfo(
                        mapOf("org.multipaz.transaction.ping" to Bstr(byteArrayOf(1, 2, 3)))
                    )
                )
            )
        }
        assertFailsWith<Iso18015ResponseException> {
            reqWithTxData.execute(sharedDomainSource)
        }

        // 5. When a document has both KeyBoundMdocCredential and KeylessMdocCredential, key-bound is preferred
        UtopiaMovieTicket.getDocumentType().createMdocCredentialWithSampleData(
            document = sharedDomainDoc,
            secureArea = harness.softwareSecureArea,
            createKeySettings = CreateKeySettings(),
            dsKey = harness.dsKey,
            signedAt = harness.signedAt,
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            domain = "shared_domain"
        )
        val preferredMatch = reqKeylessMdocAllowed.execute(sharedDomainSource)
        assertTrue(
            preferredMatch.useCases.single().solutions.single().matches.single().credential is KeyBoundMdocCredential
        )

        // 6. mDL docType rejects nonKeyBoundSupported = true
        assertFailsWith<IllegalArgumentException> {
            buildDeviceRequest(sessionTranscript = transcript) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                    docRequestInfo = DocRequestInfo(nonKeyBoundSupported = true)
                )
            }
        }
    }

    @Test
    fun zkRequestAndSystemSpecSecondEditionValidation() = runTest {
        // 1. Empty systemSpecs is rejected
        assertFailsWith<IllegalArgumentException> {
            ZkRequest(systemSpecs = emptyList(), zkRequired = false)
        }

        // 2. ZkRequest omits "params" when empty and parses without "params"
        val noParamsSpec = ZkSystemSpec(id = "spec1", system = "test-zk")
        val zkReqNoParams = ZkRequest(systemSpecs = listOf(noParamsSpec), zkRequired = true)
        val encodedNoParams = Cbor.encode(zkReqNoParams.toDataItem())
        val rawMap = Cbor.decode(encodedNoParams)
        assertFalse(rawMap["systemSpecs"].asArray.single().hasKey("params"))
        val parsedNoParams = ZkRequest.fromDataItem(rawMap)
        assertEquals(listOf(noParamsSpec), parsedNoParams.systemSpecs)
        assertTrue(parsedNoParams.zkRequired)

        // 3. ZkSystemSpec supports list and map params and includes params in equality/hashCode
        val specWithNestedParams = ZkSystemSpec(id = "spec1", system = "test-zk").apply {
            addParam("curves", listOf(ZkSystemParamValue.StringValue("P-256"), ZkSystemParamValue.LongValue(1L)))
            addParam("config", mapOf("version" to ZkSystemParamValue.LongValue(2L)))
        }
        assertNotEquals(noParamsSpec, specWithNestedParams)
        val roundTripped = ZkRequest.fromDataItem(
            Cbor.decode(Cbor.encode(ZkRequest(listOf(specWithNestedParams), zkRequired = false).toDataItem()))
        ).systemSpecs.single()
        assertEquals(specWithNestedParams, roundTripped)
        assertEquals(specWithNestedParams.hashCode(), roundTripped.hashCode())

        // 4. Conflicting ZkSystemSpec definitions sharing the same zkSystemId across DocRequests are rejected
        val transcript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(Simple.NULL) }
        assertFailsWith<IllegalArgumentException> {
            buildDeviceRequest(
                sessionTranscript = transcript,
                deviceRequestInfo = DeviceRequestInfo.fromValues(
                    useCases = listOf(UseCase(true, listOf(DocumentSet(listOf(0, 1))), emptyMap()))
                )
            ) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("age_over_18" to false)),
                    docRequestInfo = DocRequestInfo(
                        zkRequest = ZkRequest(listOf(noParamsSpec), zkRequired = false)
                    )
                )
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("age_over_21" to false)),
                    docRequestInfo = DocRequestInfo(
                        zkRequest = ZkRequest(listOf(specWithNestedParams), zkRequired = false)
                    )
                )
            }
        }

        // 5. zkRequired = true fails execute() when no compatible ZK system is registered
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()
        val zkRequiredReq = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("age_over_18" to false)),
                docRequestInfo = DocRequestInfo(
                    zkRequest = ZkRequest(listOf(noParamsSpec), zkRequired = true)
                )
            )
        }
        assertFailsWith<Iso18015ResponseException> {
            zkRequiredReq.execute(presentmentSource = harness.presentmentSource)
        }
    }

    @Test
    fun encryptionParametersAndMaximumResponseSizeValidation() = runTest {
        val now = Clock.System.now()
        val key1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val key2 = Crypto.createEcPrivateKey(EcCurve.P256)

        // 1. Empty recipientCertificate array when key is present is rejected
        assertFailsWith<IllegalArgumentException> {
            EncryptionParameters(
                buildCborMap {
                    put("recipientPublicKey", key1.publicKey.toCoseKey().toDataItem())
                    putCborArray("recipientCertificate") {}
                }
            )
        }

        // 2. recipientPublicKey mismatching leaf recipientCertificate is rejected
        val certForKey2 = buildX509Cert(
            publicKey = key2.publicKey,
            signingKey = AsymmetricKey.anonymous(key2),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Recipient"),
            issuer = X500Name.fromName("CN=Recipient"),
            validFrom = now - 1.days,
            validUntil = now + 1.days
        ) {}
        assertFailsWith<IllegalArgumentException> {
            EncryptionParameters.fromValues(
                recipientPublicKey = key1.publicKey,
                recipientCertificates = listOf(certForKey2)
            )
        }

        // 3. Expired recipientCertificate fails execute()
        val expiredCertForKey1 = buildX509Cert(
            publicKey = key1.publicKey,
            signingKey = AsymmetricKey.anonymous(key1),
            serialNumber = ASN1Integer(2L),
            subject = X500Name.fromName("CN=Expired Recipient"),
            issuer = X500Name.fromName("CN=Expired Recipient"),
            validFrom = now - 10.days,
            validUntil = now - 1.days
        ) {}
        val expiredEncParams = EncryptionParameters.fromValues(
            recipientPublicKey = key1.publicKey,
            recipientCertificates = listOf(expiredCertForKey1)
        )
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()
        val transcript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val expiredEncReq = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(docResponseEncryption = expiredEncParams)
            )
        }
        assertFailsWith<Iso18015ResponseException> {
            expiredEncReq.execute(presentmentSource = harness.presentmentSource, atTime = now)
        }

        // 4. Negative or Nint maximumResponseSize is rejected
        assertFailsWith<IllegalArgumentException> {
            DocRequestInfo(maximumResponseSize = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            DocRequestInfo.fromDataItem(buildCborMap { put("maximumResponseSize", Nint(1UL)) })
        }

        // 5. Exceeding maximumResponseSize fails mdocPresentment without incrementing usage count
        val mdlCredBefore = harness.docMdl.getCertifiedCredentials().first()
        val usageBefore = mdlCredBefore.usageCount
        val tinyMaxSizeReq = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = singleDocUseCaseInfo()
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(maximumResponseSize = 10L)
            )
        }
        val err = assertFailsWith<Iso18015ResponseException> {
            mdocPresentment(
                deviceRequest = tinyMaxSizeReq,
                eReaderKey = null,
                sessionTranscript = transcript,
                source = harness.presentmentSource,
                keyAgreementPossible = emptyList(),
                requesterAppId = null,
                requesterOrigin = null,
                onDocumentsInFocus = {}
            )
        }
        assertTrue(err.message!!.contains("maximumResponseSize"))
        assertEquals(usageBefore, harness.docMdl.getCertifiedCredentials().first().usageCount)
    }

    @Test
    fun readerAuthCertValidationMultiIdentitiesAndUseCaseConstraints() = runTest {
        val now = Clock.System.now()
        val transcript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(4, 5, 6)) }

        suspend fun makeReaderCertifiedKey(label: String, validFrom: Instant, validUntil: Instant): AsymmetricKey.X509Certified {
            val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val rootCert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = AsymmetricKey.anonymous(rootKey),
                subject = X500Name.fromName("CN=$label Root,C=US"),
                serial = ASN1Integer(10L),
                validFrom = validFrom,
                validUntil = validUntil,
                crlUrl = null
            )
            val leafKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val leafCert = MdocUtil.generateReaderCertificate(
                readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                    certChain = X509CertChain(listOf(rootCert)),
                    privateKey = rootKey
                ),
                readerKey = leafKey.publicKey,
                subject = X500Name.fromName("CN=$label,C=US"),
                dnsName = null,
                serial = ASN1Integer(11L),
                validFrom = validFrom,
                validUntil = validUntil
            )
            return AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(leafCert, rootCert)),
                privateKey = leafKey
            )
        }

        // 1. Per-DocRequest expired reader certificate is rejected by verifyReaderAuthentication()
        val expiredReaderCertified = makeReaderCertifiedKey(
            label = "Expired Reader",
            validFrom = now - 20.days,
            validUntil = now - 5.days
        )
        val expiredReaderReq = buildDeviceRequest(sessionTranscript = transcript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                readerKey = expiredReaderCertified
            )
        }
        assertFailsWith<SignatureVerificationException> {
            expiredReaderReq.verifyReaderAuthentication(transcript, atTime = now)
        }

        // 2. getRequesterIdentities() returns all distinct DocRequest reader identities
        val readerA = makeReaderCertifiedKey("Reader A", now - 2.days, now + 30.days)
        val readerB = makeReaderCertifiedKey("Reader B", now - 2.days, now + 30.days)
        val multiReaderReq = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(UseCase(true, listOf(DocumentSet(listOf(0, 1))), emptyMap()))
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                readerKey = readerA
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_DOCTYPE,
                nameSpaces = mapOf(EUPersonalID.EUPID_NAMESPACE to mapOf("given_name" to false)),
                readerKey = readerB
            )
        }
        multiReaderReq.verifyReaderAuthentication(transcript, atTime = now)
        val identities = multiReaderReq.getRequesterIdentities()
        assertEquals(2, identities.size)
        assertEquals(
            setOf(
                X509CertChain(listOf(readerA.certChain.certificates.first())),
                X509CertChain(listOf(readerB.certChain.certificates.first()))
            ),
            identities.map { (it as Iso18013RequesterIdentity).certChain }.toSet()
        )

        // 3. v1.0 multi-DocRequest executes all DocRequests (and fails if second DocRequest cannot match)
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()
        val v10MultiReq = buildDeviceRequest(sessionTranscript = transcript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_DOCTYPE,
                nameSpaces = mapOf(EUPersonalID.EUPID_NAMESPACE to mapOf("given_name" to false))
            )
        }
        assertEquals("1.0", v10MultiReq.version)
        val v10Result = v10MultiReq.execute(presentmentSource = harness.presentmentSource)
        assertEquals(2, v10Result.useCases.size)

        val v10UnsatisfiedSecondReq = buildDeviceRequest(sessionTranscript = transcript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
            )
            addDocRequest(
                docType = "org.example.nonexistent.1",
                nameSpaces = mapOf("org.example.nonexistent" to mapOf("foo" to false))
            )
        }
        assertFailsWith<Iso18015ResponseException> {
            v10UnsatisfiedSecondReq.execute(presentmentSource = harness.presentmentSource)
        }

        // 4. All-optional UseCases with 0 matching credentials fails execute(), and selecting empty [] fails toPresentmentSelection()
        val allOptionalZeroMatch = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(UseCase(mandatory = false, documentSets = listOf(DocumentSet(listOf(0))), purposeHints = emptyMap()))
            )
        ) {
            addDocRequest(
                docType = "org.example.nonexistent.1",
                nameSpaces = mapOf("org.example.nonexistent" to mapOf("foo" to false))
            )
        }
        assertFailsWith<Iso18015ResponseException> {
            allOptionalZeroMatch.execute(presentmentSource = harness.presentmentSource)
        }

        val optionalSatisfied = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(UseCase(mandatory = false, documentSets = listOf(DocumentSet(listOf(0))), purposeHints = emptyMap()))
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false))
            )
        }.execute(presentmentSource = harness.presentmentSource)
        assertFailsWith<IllegalArgumentException> {
            optionalSatisfied.toPresentmentSelection(listOf(emptyList()))
        }

        // 5. PresentmentData prevents mixing solutions from different DocumentSet options
        val twoOptionsData = buildDeviceRequest(
            sessionTranscript = transcript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0)), DocumentSet(listOf(1))),
                        purposeHints = emptyMap()
                    )
                )
            )
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(uniqueDocSetRequired = false)
            )
            addDocRequest(
                docType = EUPersonalID.EUPID_DOCTYPE,
                nameSpaces = mapOf(EUPersonalID.EUPID_NAMESPACE to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(uniqueDocSetRequired = false)
            )
        }.execute(presentmentSource = harness.presentmentSource)
        assertTrue(twoOptionsData.useCases.single().solutions.size >= 2)
        val firstOptIdx = twoOptionsData.useCases.single().solutions.indexOfFirst { it.optionIndex == 0 }
        val secondOptIdx = twoOptionsData.useCases.single().solutions.indexOfFirst { it.optionIndex == 1 }
        assertFailsWith<IllegalArgumentException> {
            twoOptionsData.toPresentmentSelection(listOf(listOf(firstOptIdx, secondOptIdx)))
        }
    }

    @Test
    fun testVerifyReaderAuthenticationRejectsMalformedNegativeLengthCert() = runTest {
        // GHSA-4426-vj7r-m9qq: DeviceRequest with x5chain containing a malformed DER certificate
        // whose 4-byte length has the top bit set (30 06 04 84 ff ff ff fa) must fail with
        // SignatureVerificationException instead of looping until OutOfMemoryError.
        val malformedDeviceRequestHex =
            "a26776657273696f6e63312e306b646f63526571756573747381a26c6974656d735265717565" +
            "7374d818584ba267646f6354797065756f72672e69736f2e31383031332e352e312e6d444c6a" +
            "6e616d65537061636573a1716f72672e69736f2e31383031332e352e31a16b66616d696c795f" +
            "6e616d65f46a726561646572417574688443a10126a118214830060484fffffffaf658400000" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000"
        val request = DeviceRequest.fromDataItem(Cbor.decode(malformedDeviceRequestHex.fromHex()))
        assertFailsWith<SignatureVerificationException> {
            request.verifyReaderAuthentication(Cbor.decode("83f6f6f6".fromHex()))
        }
    }
}