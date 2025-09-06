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
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.toCoseLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.fromHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_READER_CERT.fromHex(),
            readerCertChain.certificates[0].encodedCertificate
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
        val readerRootKey: EcPrivateKey,
        val readerRootCert: X509Cert,
        val readerKey: EcPrivateKey,
        val readerCert: X509Cert,
    ) {
        companion object {
            fun generate(): ReaderAuth {
                val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P384)
                val readerRootCert = MdocUtil.generateReaderRootCertificate(
                    readerRootKey = readerRootKey,
                    subject = X500Name.fromName("CN=TEST Reader Root,C=XG-US,ST=MA"),
                    serial = ASN1Integer(1),
                    validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    crlUrl = "http://www.example.com/issuer/crl"
                )
                val readerKey = Crypto.createEcPrivateKey(EcCurve.P384)
                val readerCert = MdocUtil.generateReaderCertificate(
                    readerRootCert = readerRootCert,
                    readerRootKey = readerRootKey,
                    readerKey = readerKey.publicKey,
                    subject = X500Name.fromName("CN=TEST Reader Certificate,C=XG-US,ST=MA"),
                    serial = ASN1Integer(1),
                    validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                )
                return ReaderAuth(
                    readerRootKey = readerRootKey,
                    readerRootCert = readerRootCert,
                    readerKey = readerKey,
                    readerCert = readerCert
                )
            }
        }
    }

    data class ReaderAuthSecureArea(
        val readerRootKey: EcPrivateKey,
        val readerRootCert: X509Cert,
        val readerKeySecureArea: SecureArea,
        val readerKeyAlias: String,
        val readerCert: X509Cert,
    ) {
        companion object {
            suspend fun generate(): ReaderAuthSecureArea {
                val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P384)
                val readerRootCert = MdocUtil.generateReaderRootCertificate(
                    readerRootKey = readerRootKey,
                    subject = X500Name.fromName("CN=TEST Reader Root,C=XG-US,ST=MA"),
                    serial = ASN1Integer(1),
                    validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    crlUrl = "http://www.example.com/issuer/crl"
                )
                val storage = EphemeralStorage()
                val readerKeySecureArea = SoftwareSecureArea.create(storage)
                val readerKeyInfo = readerKeySecureArea.createKey(
                    alias = null,
                    createKeySettings = CreateKeySettings()
                )
                val readerCert = MdocUtil.generateReaderCertificate(
                    readerRootCert = readerRootCert,
                    readerRootKey = readerRootKey,
                    readerKey = readerKeyInfo.publicKey,
                    subject = X500Name.fromName("CN=TEST Reader Certificate,C=XG-US,ST=MA"),
                    serial = ASN1Integer(1),
                    validFrom = LocalDateTime(2024, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                    validUntil = LocalDateTime(2029, 1, 1, 0, 0, 0, 0).toInstant(TimeZone.UTC),
                )
                return ReaderAuthSecureArea(
                    readerRootKey = readerRootKey,
                    readerRootCert = readerRootCert,
                    readerKeySecureArea = readerKeySecureArea,
                    readerKeyAlias = readerKeyInfo.alias,
                    readerCert = readerCert
                )
            }
        }
    }

    @Test
    fun readerAuthRoundTrip() {
        val ra = ReaderAuth.generate()
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
                readerKey = ra.readerKey,
                signatureAlgorithm = Algorithm.ESP256,
                readerKeyCertificateChain = X509CertChain(listOf(ra.readerCert, ra.readerRootCert))
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
    fun readerAuthSecureAreaRoundTrip() = runTest {
        val ra = ReaderAuthSecureArea.generate()
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
                readerKeySecureArea = ra.readerKeySecureArea,
                readerKeyAlias = ra.readerKeyAlias,
                readerKeyCertificateChain = X509CertChain(listOf(ra.readerCert, ra.readerRootCert)),
                keyUnlockData = null
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
    fun readerAuthAllRoundTrip() {
        val ra = ReaderAuth.generate()
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
            addReaderAuthAll(
                readerKey = ra.readerKey,
                signatureAlgorithm = Algorithm.ESP256,
                readerKeyCertificateChain = X509CertChain(listOf(ra.readerCert, ra.readerRootCert))
            )
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
        val ra = ReaderAuthSecureArea.generate()
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
            addReaderAuthAll(
                readerKeySecureArea = ra.readerKeySecureArea,
                readerKeyAlias = ra.readerKeyAlias,
                readerKeyCertificateChain = X509CertChain(listOf(ra.readerCert, ra.readerRootCert)),
                keyUnlockData = null
            )
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
    fun docRequestInfoAlternativeDataElements() {
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
    fun docRequestInfoIssuerIdentifiers() {
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
    fun docRequestInfoUniqueDocSetRequiredSetToTrue() {
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
    fun docRequestInfoUniqueDocSetRequiredSetToFalse() {
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
    fun docRequestInfoMaximumResponseSizeSet() {
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
    fun docRequestInfoZkRequest() {
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
                            "id": "0",
                            "system": "longfellow-zk",
                            "params": {
                              "circuit": "1234",
                              "otherParam": 42,
                              "yetAnotherParam": false
                            }
                          },
                          {
                            "id": "1",
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
    fun deviceRequestInfoAgeOverUseCase() {
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
    fun deviceRequestInfoAgeOverUseCaseWithPurposeHints() {
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
    fun testDocRequestInfoExt() {
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
    fun testDeviceRequestInfoExt() {
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
}