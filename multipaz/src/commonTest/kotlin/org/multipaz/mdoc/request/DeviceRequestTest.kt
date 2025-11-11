package org.multipaz.mdoc.request

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cose.Cose
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.fromHex
import org.multipaz.util.fromHexByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeviceRequestTest {


    // Test against the test vector in Annex D of 18013-5:2021
    @Test
    fun testAgainstVector2021() {
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

        deviceRequest.verifyReaderAuthentication(sessionTranscript)

        // Now we can access readerAuthAll and readerAuth
        val readerAuth = deviceRequest.docRequests[0].readerAuth
        assertNotNull(readerAuth)
        val readerCertChain = readerAuth.unprotectedHeaders[Cose.COSE_LABEL_X5CHAIN.toCoseLabel]!!.asX509CertChain
        assertEquals(
            TestVectors.ISO_18013_5_ANNEX_D_READER_CERT.fromHexByteString(),
            readerCertChain.certificates[0].encoded
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

    // Test against the test vector in Annex D of 18013-5:2021
    @Test
    fun testAgainstMalformedReaderSignature() {
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
                deviceRequest.verifyReaderAuthentication(sessionTranscript)
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
                    readerKey = readerKeyInfo.publicKey,
                    subject = X500Name.fromName("CN=TEST Reader Certificate,C=XG-US,ST=MA"),
                    serial = ASN1Integer(1),
                    validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                )
                return ReaderAuth(
                    readerRootKey = readerRootSigningKey,
                    readerKey = AsymmetricKey.X509CertifiedSecureAreaBased(
                        secureArea = readerKeySecureArea,
                        alias = readerKeyInfo.alias,
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
        val unused = parsedDeviceRequest.docRequests[0].readerAuth

        assertEquals(parsedDeviceRequest, deviceRequest)
    }

    @Test
    fun readerAuthRoundTripSecureArea() = runTest {
        val ra = ReaderAuth.generateSecureArea()
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequestSuspend(
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
        val unused = parsedDeviceRequest.docRequests[0].readerAuth

        assertEquals(parsedDeviceRequest, deviceRequest)
    }

    @Test
    fun readerAuthAllRoundTrip() = runTest {
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

        assertEquals(parsedDeviceRequest, deviceRequest)
    }

    @Test
    fun readerAuthAllSecureAreaRoundTrip() = runTest {
        val ra = ReaderAuth.generateSecureArea()
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequestSuspend(
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

        assertEquals(parsedDeviceRequest, deviceRequest)
    }

    @Test
    fun docRequestInfoAlternativeDataElements() = runTest {
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
    fun docRequestInfoIssuerIdentifiers() = runTest {
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
    fun docRequestInfoUniqueDocSetRequiredSetToTrue() = runTest {
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
    fun docRequestInfoUniqueDocSetRequiredSetToFalse() = runTest {
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
    fun docRequestInfoMaximumResponseSizeSet() = runTest {
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
    fun docRequestInfoZkRequest() = runTest {
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
    fun deviceRequestInfoAgeOverUseCase() = runTest {
        // This DeviceRequest asks for either an mDL, a PhotoID, or a EU PID with claims that
        // the holder is 18 years or older as well as their portrait image.
        //
        // TODO: link to this example in 18013-5 Second Edition
        //
        val sessionTranscript = buildCborArray { add("Doesn't matter") }
        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = DeviceRequestInfo(
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
                    "docType": "org.iso.23220.photoID.1",
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
              "deviceRequestInfo": {
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
              }
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
            deviceRequestInfo = DeviceRequestInfo(
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
                    "docType": "org.iso.23220.photoID.1",
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
              "deviceRequestInfo": {
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
              }
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
                    maximumResponseSize = 16384,
                    otherInfo = mapOf(
                        "com.example.foo" to Tstr("bar")
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
                          "maximumResponseSize": 16384,
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
            deviceRequestInfo = DeviceRequestInfo(
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
                  "deviceRequestInfo": {
                    "com.example.foobar": [42, 43]
                  }
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
}