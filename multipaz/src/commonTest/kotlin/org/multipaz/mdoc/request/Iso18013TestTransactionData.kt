package org.multipaz.mdoc.request

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.credential.Credential
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.mdocPresentment

class Iso18013TestTransactionData {

    private object TxA : TransactionType<String>(
        displayName = "Transaction A",
        identifier = "com.example.txA",
    ) {
        override fun serializeIso18013Request(payload: String): DataItem = buildCborMap {
            put("nonce", payload)
        }

        override fun parseIso18013Request(dataItem: DataItem): String =
            dataItem.asMap[Tstr("nonce")]!!.asTstr

        override suspend fun generateMdocResponseElements(
            transactionData: TransactionData<String>,
            credential: Credential,
            userInput: TransactionUserInput?,
            docRequestId: Int?
        ): Map<String, DataItem> = buildMap {
            putAll(super.generateMdocResponseElements(transactionData, credential, userInput, docRequestId))
            put("status", Tstr("A_OK"))
        }

        override suspend fun verifyMdocResponse(
            transactionData: TransactionData<String>,
            responseElements: Map<String, DataItem>
        ) {
            super.verifyMdocResponse(transactionData, responseElements)
            check(responseElements["status"]?.asTstr == "A_OK")
        }
    }

    private object TxB : TransactionType<String>(
        displayName = "Transaction B",
        identifier = "com.example.txB",
    ) {
        override fun serializeIso18013Request(payload: String): DataItem = buildCborMap {
            put("nonce", payload)
        }

        override fun parseIso18013Request(dataItem: DataItem): String =
            dataItem.asMap[Tstr("nonce")]!!.asTstr

        override suspend fun generateMdocResponseElements(
            transactionData: TransactionData<String>,
            credential: Credential,
            userInput: TransactionUserInput?,
            docRequestId: Int?
        ): Map<String, DataItem> = buildMap {
            putAll(super.generateMdocResponseElements(transactionData, credential, userInput, docRequestId))
            put("status", Tstr("B_OK"))
        }

        override suspend fun verifyMdocResponse(
            transactionData: TransactionData<String>,
            responseElements: Map<String, DataItem>
        ) {
            super.verifyMdocResponse(transactionData, responseElements)
            check(responseElements["status"]?.asTstr == "B_OK")
        }
    }

    @Test
    fun alternativeTransactions_preferA_onlyASupported() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addTransactionType(TxA)
        harness.documentTypeRepository.addTransactionType(TxB)
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("David")
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_18013_TRANSACTION_DATA_NAMESPACE to listOf(TxA.identifier)
            )
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val txAData = TxA.serializeIso18013Request("nonceA")
        val txBData = TxB.serializeIso18013Request("nonceB")
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TxA.identifier to false)
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(
                                ISO_18013_TRANSACTION_DATA_NAMESPACE,
                                TxA.identifier
                            ),
                            alternativeElementSets = listOf(
                                listOf(
                                    ElementReference(
                                        ISO_18013_TRANSACTION_DATA_NAMESPACE,
                                        TxB.identifier
                                    )
                                )
                            )
                        )
                    ),
                    transactionData = TransactionsInfo(
                        data = mapOf(
                            TxA.identifier to txAData,
                            TxB.identifier to txBData
                        )
                    )
                )
            )
        }

        // Query execution check
        val queryResult = deviceRequest.execute(presentmentSource = harness.presentmentSource)
        val match = queryResult.credentialSets[0].options[0].members[0].matches[0]
        assertEquals(1, match.transactionData.size)
        assertEquals(TxA, match.transactionData[0].type)

        // End-to-end presentment and verification check
        val creationTime = Clock.System.now()
        val isoResponse = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = harness.presentmentSource,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = "https://example.com",
            creationTime = creationTime,
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {}
        )
        val deviceResponse = isoResponse.deviceResponse
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            deviceRequest = deviceRequest,
            documentTypeRepository = harness.documentTypeRepository,
            atTime = creationTime
        )
        assertEquals(DeviceResponse.STATUS_OK, deviceResponse.status)
        val mdocDoc = deviceResponse.documents[0]
        val txNamespaceData = mdocDoc.deviceNamespaces.data[ISO_18013_TRANSACTION_DATA_NAMESPACE]
        assertNotNull(txNamespaceData)
        assertTrue(txNamespaceData.containsKey(TxA.identifier))
        assertFalse(txNamespaceData.containsKey(TxB.identifier))
        assertEquals(1, mdocDoc.transactionData.size)
        assertEquals(TxA, mdocDoc.transactionData[0].type)
    }

    @Test
    fun alternativeTransactions_preferA_onlyBSupported() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addTransactionType(TxA)
        harness.documentTypeRepository.addTransactionType(TxB)
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("David")
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_18013_TRANSACTION_DATA_NAMESPACE to listOf(TxB.identifier)
            )
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val txAData = TxA.serializeIso18013Request("nonceA")
        val txBData = TxB.serializeIso18013Request("nonceB")
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TxA.identifier to false)
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(
                                ISO_18013_TRANSACTION_DATA_NAMESPACE,
                                TxA.identifier
                            ),
                            alternativeElementSets = listOf(
                                listOf(
                                    ElementReference(
                                        ISO_18013_TRANSACTION_DATA_NAMESPACE,
                                        TxB.identifier
                                    )
                                )
                            )
                        )
                    ),
                    transactionData = TransactionsInfo(
                        data = mapOf(
                            TxA.identifier to txAData,
                            TxB.identifier to txBData
                        )
                    )
                )
            )
        }

        // Query execution check
        val queryResult = deviceRequest.execute(presentmentSource = harness.presentmentSource)
        val match = queryResult.credentialSets[0].options[0].members[0].matches[0]
        assertEquals(1, match.transactionData.size)
        assertEquals(TxB, match.transactionData[0].type)

        // End-to-end presentment and verification check
        val creationTime = Clock.System.now()
        val isoResponse = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = harness.presentmentSource,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = "https://example.com",
            creationTime = creationTime,
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {}
        )
        val deviceResponse = isoResponse.deviceResponse
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            deviceRequest = deviceRequest,
            documentTypeRepository = harness.documentTypeRepository,
            atTime = creationTime
        )
        assertEquals(DeviceResponse.STATUS_OK, deviceResponse.status)
        val mdocDoc = deviceResponse.documents[0]
        val txNamespaceData = mdocDoc.deviceNamespaces.data[ISO_18013_TRANSACTION_DATA_NAMESPACE]
        assertNotNull(txNamespaceData)
        assertTrue(txNamespaceData.containsKey(TxB.identifier))
        assertFalse(txNamespaceData.containsKey(TxA.identifier))
        assertEquals(1, mdocDoc.transactionData.size)
        assertEquals(TxB, mdocDoc.transactionData[0].type)
    }

    @Test
    fun alternativeTransactions_preferA_bothSupported() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addTransactionType(TxA)
        harness.documentTypeRepository.addTransactionType(TxB)
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("David")
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_18013_TRANSACTION_DATA_NAMESPACE to listOf(TxA.identifier, TxB.identifier)
            )
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val txAData = TxA.serializeIso18013Request("nonceA")
        val txBData = TxB.serializeIso18013Request("nonceB")
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TxA.identifier to false)
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(
                                ISO_18013_TRANSACTION_DATA_NAMESPACE,
                                TxA.identifier
                            ),
                            alternativeElementSets = listOf(
                                listOf(
                                    ElementReference(
                                        ISO_18013_TRANSACTION_DATA_NAMESPACE,
                                        TxB.identifier
                                    )
                                )
                            )
                        )
                    ),
                    transactionData = TransactionsInfo(
                        data = mapOf(
                            TxA.identifier to txAData,
                            TxB.identifier to txBData
                        )
                    )
                )
            )
        }

        // Query execution check: Option 0 (TxA) must be preferred over Option 1 (TxB)
        val queryResult = deviceRequest.execute(presentmentSource = harness.presentmentSource)
        val match = queryResult.credentialSets[0].options[0].members[0].matches[0]
        assertEquals(1, match.transactionData.size)
        assertEquals(TxA, match.transactionData[0].type)

        // End-to-end presentment and verification check
        val creationTime = Clock.System.now()
        val isoResponse = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = harness.presentmentSource,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = "https://example.com",
            creationTime = creationTime,
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {}
        )
        val deviceResponse = isoResponse.deviceResponse
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            deviceRequest = deviceRequest,
            documentTypeRepository = harness.documentTypeRepository,
            atTime = creationTime
        )
        assertEquals(DeviceResponse.STATUS_OK, deviceResponse.status)
        val mdocDoc = deviceResponse.documents[0]
        val txNamespaceData = mdocDoc.deviceNamespaces.data[ISO_18013_TRANSACTION_DATA_NAMESPACE]
        assertNotNull(txNamespaceData)
        assertTrue(txNamespaceData.containsKey(TxA.identifier))
        assertFalse(txNamespaceData.containsKey(TxB.identifier))
        assertEquals(1, mdocDoc.transactionData.size)
        assertEquals(TxA, mdocDoc.transactionData[0].type)
    }

    @Test
    fun alternativeTransactions_preferA_neitherSupported() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addTransactionType(TxA)
        harness.documentTypeRepository.addTransactionType(TxB)
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("David")
                )
            ),
            keyAuthorizedDataElements = emptyMap()
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val txAData = TxA.serializeIso18013Request("nonceA")
        val txBData = TxB.serializeIso18013Request("nonceB")
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TxA.identifier to false)
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(
                                ISO_18013_TRANSACTION_DATA_NAMESPACE,
                                TxA.identifier
                            ),
                            alternativeElementSets = listOf(
                                listOf(
                                    ElementReference(
                                        ISO_18013_TRANSACTION_DATA_NAMESPACE,
                                        TxB.identifier
                                    )
                                )
                            )
                        )
                    ),
                    transactionData = TransactionsInfo(
                        data = mapOf(
                            TxA.identifier to txAData,
                            TxB.identifier to txBData
                        )
                    )
                )
            )
        }

        val exception = assertFailsWith(Iso18015ResponseException::class) {
            deviceRequest.execute(presentmentSource = harness.presentmentSource)
        }
        assertEquals(
            "No matching credentials for first DocRequest: transaction com.example.txA is not applicable",
            exception.message
        )
    }

    @Test
    fun alternativeTransactions_fallbackToNonTransactionDataElement() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addTransactionType(TxA)
        // Setup without transaction authorization, but with given_name and family_name
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "family_name" to Tstr("Mustermann"),
                    "given_name" to Tstr("David")
                )
            ),
            keyAuthorizedDataElements = emptyMap()
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val txAData = TxA.serializeIso18013Request("nonceA")
        // Request TxA with fallback to given_name
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TxA.identifier to false)
                ),
                docRequestInfo = DocRequestInfo(
                    alternativeDataElements = listOf(
                        AlternativeDataElementSet(
                            requestedElement = ElementReference(
                                ISO_18013_TRANSACTION_DATA_NAMESPACE,
                                TxA.identifier
                            ),
                            alternativeElementSets = listOf(
                                listOf(
                                    ElementReference(
                                        DrivingLicense.MDL_NAMESPACE,
                                        "given_name"
                                    )
                                )
                            )
                        )
                    ),
                    transactionData = TransactionsInfo(
                        data = mapOf(TxA.identifier to txAData)
                    )
                )
            )
        }

        // Query execution check: fallback to given_name succeeded without transactions
        val queryResult = deviceRequest.execute(presentmentSource = harness.presentmentSource)
        val match = queryResult.credentialSets[0].options[0].members[0].matches[0]
        assertTrue(match.transactionData.isEmpty())

        // End-to-end presentment and verification check
        val creationTime = Clock.System.now()
        val isoResponse = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = harness.presentmentSource,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = "https://example.com",
            creationTime = creationTime,
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {}
        )
        val deviceResponse = isoResponse.deviceResponse
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            deviceRequest = deviceRequest,
            documentTypeRepository = harness.documentTypeRepository,
            atTime = creationTime
        )
        assertEquals(DeviceResponse.STATUS_OK, deviceResponse.status)
        val mdocDoc = deviceResponse.documents[0]
        val txNamespaceData = mdocDoc.deviceNamespaces.data[ISO_18013_TRANSACTION_DATA_NAMESPACE]
        assertTrue(txNamespaceData.isNullOrEmpty())
        assertTrue(mdocDoc.transactionData.isEmpty())
    }

    @Test
    fun extraTransactionPayloadInRequestInfoNotRequestedByDataElements() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.documentTypeRepository.addTransactionType(TxA)
        harness.documentTypeRepository.addTransactionType(TxB)
        // Wallet authorizes both TxA and TxB
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to Tstr("David")
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_18013_TRANSACTION_DATA_NAMESPACE to listOf(TxA.identifier, TxB.identifier)
            )
        )

        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val txAData = TxA.serializeIso18013Request("nonceA")
        val txBData = TxB.serializeIso18013Request("nonceB")

        // Verifier requests ONLY TxA in nameSpaces, but sends BOTH TxA and TxB in requestInfo.transactionData
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ISO_18013_TRANSACTION_DATA_NAMESPACE to mapOf(TxA.identifier to false)
                ),
                docRequestInfo = DocRequestInfo(
                    transactionData = TransactionsInfo(
                        data = mapOf(
                            TxA.identifier to txAData,
                            TxB.identifier to txBData
                        )
                    )
                )
            )
        }

        // Query execution check: only TxA is included
        val queryResult = deviceRequest.execute(presentmentSource = harness.presentmentSource)
        val match = queryResult.credentialSets[0].options[0].members[0].matches[0]
        assertEquals(1, match.transactionData.size)
        assertEquals(TxA, match.transactionData[0].type)

        // End-to-end presentment and verification check
        val creationTime = Clock.System.now()
        val isoResponse = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = harness.presentmentSource,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = "https://example.com",
            creationTime = creationTime,
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {}
        )
        val deviceResponse = isoResponse.deviceResponse
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            deviceRequest = deviceRequest,
            documentTypeRepository = harness.documentTypeRepository,
            atTime = creationTime
        )
        assertEquals(DeviceResponse.STATUS_OK, deviceResponse.status)
        val mdocDoc = deviceResponse.documents[0]
        val txNamespaceData = mdocDoc.deviceNamespaces.data[ISO_18013_TRANSACTION_DATA_NAMESPACE]
        assertNotNull(txNamespaceData)
        assertTrue(txNamespaceData.containsKey(TxA.identifier))
        assertFalse(txNamespaceData.containsKey(TxB.identifier))
        assertEquals(1, mdocDoc.transactionData.size)
        assertEquals(TxA, mdocDoc.transactionData[0].type)
    }
}
