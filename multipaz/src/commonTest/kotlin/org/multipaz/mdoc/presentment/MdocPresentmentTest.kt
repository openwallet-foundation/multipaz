package org.multipaz.mdoc.presentment

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.SampleData
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.EncryptionParameters
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.presentment.ConsentData
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.mdocPresentment
import org.multipaz.presentment.prettyPrint
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.truncateToWholeSeconds
import org.multipaz.util.zlibInflate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class MdocPresentmentTest {

    @Test
    fun testClearNamesAndEncryptedPortrait() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }

        val encryptionKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Clock.System.now()
        val encryptionKeyCertification = buildX509Cert(
            publicKey = encryptionKey.publicKey,
            signingKey = AsymmetricKey.anonymous(encryptionKey, encryptionKey.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=Encrypted Document Receiver"),
            issuer = X500Name.fromName("CN=Encrypted Document Receiver"),
            validFrom = (now - 1.hours).truncateToWholeSeconds(),
            validUntil = (now + 1.hours).truncateToWholeSeconds()
        ) {
            includeSubjectKeyIdentifier()
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            setBasicConstraints(true, null)
        }
        harness.readerTrustManager.addX509Cert(
            certificate = encryptionKeyCertification,
            metadata = TrustMetadata(
                displayName = "Encrypted Document Receiver"
            )
        )
        val encryptionParameters = EncryptionParameters.fromValues(
            recipientPublicKey = encryptionKey.publicKey,
            recipientCertificates = listOf(encryptionKeyCertification)
        )

        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = DeviceRequestInfo(useCases = listOf(UseCase(
                mandatory = true,
                documentSets = listOf(DocumentSet(docRequestIds = listOf(0, 1))),
                purposeHints = mapOf()
            )))
        ) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "given_name" to false,
                        "family_name" to false
                    )
                )
            )
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf(
                        "portrait" to false,
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    docResponseEncryption = encryptionParameters
                )
            )
        }
        val (dr, _) = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = harness.presentmentSource,
            keyAgreementPossible = listOf(),
            requesterAppId = null,
            requesterOrigin = "https://verifier.example.com",
            preselectedDocuments = listOf(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {}
        )

        dr.verify(sessionTranscript = sessionTranscript)
        assertEquals(1, dr.documents.size)
        assertEquals(1, dr.documents[0].issuerNamespaces.data.size)
        assertEquals(2, dr.documents[0].issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!.size)
        assertEquals(
            "Erika",
            dr.documents[0].issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["given_name"]!!.dataElementValue.asTstr
        )
        assertEquals(
            "Mustermann",
            dr.documents[0].issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["family_name"]!!.dataElementValue.asTstr
        )

        assertEquals(1, dr.encryptedDocuments.size)
        val decryptedDocuments = dr.encryptedDocuments[0].decrypt(
            recipientPrivateKey = AsymmetricKey.AnonymousExplicit(encryptionKey, Algorithm.ECDH_P256),
            encryptionParameters = encryptionParameters,
            sessionTranscript = sessionTranscript
        )
        assertEquals(1, decryptedDocuments.documents.size)
        assertEquals(1, decryptedDocuments.documents[0].issuerNamespaces.data.size)
        assertEquals(1, decryptedDocuments.documents[0].issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!.size)
        assertContentEquals(
            SampleData.PORTRAIT_BASE64URL.fromBase64Url(),
            decryptedDocuments.documents[0].issuerNamespaces.data[DrivingLicense.MDL_NAMESPACE]!!["portrait"]!!.dataElementValue.asBstr
        )

        // Check that converting to ConsentData includes the Encrypted Receiver...
        val cd = ConsentData.fromCredentialQueryResult(
            credentialQueryResult = deviceRequest.execute(
                presentmentSource = harness.presentmentSource,
            ),
            source = harness.presentmentSource
        )
        assertEquals(
            """
                useCases:
                  useCase:
                    optional: false
                      solution:
                        credential:
                          encryptionRequested: false
                          encryptionTargetTrustMetadata: null
                          match:
                            credential:
                              type: MdocCredential
                              docId: mDL
                              claims:
                                claim:
                                  nameSpace: org.iso.18013.5.1
                                  dataElement: given_name
                                  displayName: Given names
                                  value: Erika
                                claim:
                                  nameSpace: org.iso.18013.5.1
                                  dataElement: family_name
                                  displayName: Family name
                                  value: Mustermann
                        credential:
                          encryptionRequested: true
                          encryptionTargetTrustMetadata: TrustMetadata(displayName=Encrypted Document Receiver, displayIcon=null, displayIconUrl=null, privacyPolicyUrl=null, disclaimer=null, testOnly=false, extensions={})
                          match:
                            credential:
                              type: MdocCredential
                              docId: mDL
                              claims:
                                claim:
                                  nameSpace: org.iso.18013.5.1
                                  dataElement: portrait
                                  displayName: Photo of holder
                                  value: Image (5318 bytes)
            """.trimIndent().trim(),
            cd.prettyPrint().trim()
        )
    }

    enum class ReaderAuthType {
        NONE,
        READER_AUTH,
        READER_AUTH_ALL,
        READER_AUTH_AND_READER_AUTH_ALL
    }

    @Test fun testSdJwtVcWithoutReaderAuth() = runTest { testSdJwtVc(ReaderAuthType.NONE) }
    @Test fun testSdJwtVcWithReaderAuth() = runTest { testSdJwtVc(ReaderAuthType.READER_AUTH) }
    @Test fun testSdJwtVcWithReaderAuthAll() = runTest { testSdJwtVc(ReaderAuthType.READER_AUTH_ALL) }
    @Test fun testSdJwtVcWithReaderAuthAndReaderAuthAll() = runTest { testSdJwtVc(ReaderAuthType.READER_AUTH_AND_READER_AUTH_ALL) }

    suspend fun testSdJwtVc(readerAuthType: ReaderAuthType) {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }

        val readerKeyPrivate = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = harness.readerRootKey,
            readerKey = readerKeyPrivate.publicKey,
            subject = X500Name.fromName("CN=Reader Key"),
            dnsName = null,
            serial = ASN1Integer.fromRandom(128),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            extensions = emptyList()
        )
        val readerKey = AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(readerCert) + harness.readerRootKey.certChain.certificates),
            privateKey = readerKeyPrivate
        )

        val deviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
        ) {
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf(
                    "_" to mapOf(
                        "sdjwtvc_given_name" to false,
                        "sdjwtvc_family_name" to false
                    )
                ),
                docRequestInfo = DocRequestInfo(
                    docFormat = "sd-jwt+kb",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_given_name" to buildJsonArray { add("given_name") },
                        "sdjwtvc_family_name" to buildJsonArray { add("family_name") },
                    )
                ),
                readerKey =
                    if (readerAuthType == ReaderAuthType.READER_AUTH || readerAuthType == ReaderAuthType.READER_AUTH_AND_READER_AUTH_ALL) {
                        readerKey
                    } else {
                        null
                    }
            )
            if (readerAuthType == ReaderAuthType.READER_AUTH_ALL || readerAuthType == ReaderAuthType.READER_AUTH_AND_READER_AUTH_ALL) {
                addReaderAuthAll(readerKey)
            }
        }
        val creationTime = Clock.System.now()
        val (dr, _) = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = harness.presentmentSource,
            keyAgreementPossible = listOf(),
            requesterAppId = null,
            requesterOrigin = "https://verifier.example.com",
            creationTime = creationTime,
            preselectedDocuments = listOf(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {}
        )

        dr.verify(sessionTranscript = sessionTranscript)
        assertEquals(0, dr.documents.size)

        assertEquals(1, dr.otherDocuments.size)
        assertEquals("sd-jwt+kb", dr.otherDocuments[0].docFormat)
        val decompressedData = dr.otherDocuments[0].data.toByteArray().zlibInflate()
        val sdJwtKb = SdJwtKb.fromCompactSerialization(decompressedData.decodeToString())
        val sessionTranscriptBytes = Tagged(
            tagNumber = Tagged.ENCODED_CBOR,
            taggedItem = Bstr(Cbor.encode(sessionTranscript))
        )
        val expectedNonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(sessionTranscriptBytes)).toBase64Url()

        val readerAuthExpectedAudience = deviceRequest.docRequests[0].readerAuth?.let {
            Crypto.digest(
                algorithm = Algorithm.SHA256,
                message = Cbor.encode(
                    Tagged(
                        tagNumber = Tagged.ENCODED_CBOR,
                        taggedItem = Bstr(Cbor.encode(it.toDataItem()))
                    )
                )
            )
        }?.toBase64Url()
        val readerAuthAllExpectedAudience = if (deviceRequest.readerAuthAll.isNotEmpty()) {
            deviceRequest.readerAuthAll.let {
                val listOfReaderAuthAll = buildCborArray {
                    deviceRequest.readerAuthAll.forEach {
                        add(it.toDataItem())
                    }
                }
                Crypto.digest(
                    algorithm = Algorithm.SHA256,
                    message = Cbor.encode(
                        Tagged(
                            tagNumber = Tagged.ENCODED_CBOR,
                            taggedItem = Bstr(Cbor.encode(listOfReaderAuthAll))
                        )
                    )
                )
            }.toBase64Url()
        } else {
            null
        }

        val processedPayload = sdJwtKb.verify(
            issuerKey = harness.dsKey.publicKey,
            checkNonce = { nonce ->
                expectedNonce == nonce
            },
            checkAudience = { aud ->
                when (readerAuthType) {
                    ReaderAuthType.NONE -> aud == "none"
                    ReaderAuthType.READER_AUTH -> aud == readerAuthExpectedAudience
                    ReaderAuthType.READER_AUTH_ALL, ReaderAuthType.READER_AUTH_AND_READER_AUTH_ALL -> {
                        aud == readerAuthAllExpectedAudience
                    }
                }
            },
            checkCreationTime = { creationTimeInSdJwtKb ->
                (creationTimeInSdJwtKb - creationTime).absoluteValue.inWholeSeconds < 5
            }
        )
        assertEquals("Erika", processedPayload["given_name"]?.jsonPrimitive?.content)
        assertEquals("Mustermann", processedPayload["family_name"]?.jsonPrimitive?.content)
        assertNull(processedPayload["portrait"]?.jsonPrimitive?.content)
    }
}