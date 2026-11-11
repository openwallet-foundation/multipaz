package org.multipaz.presentment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.mdoc.engagement.EngagementType
import org.multipaz.mdoc.nfc.MdocNfcEngagementException
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.MdocTransportStats
import org.multipaz.mdoc.transport.MdocTransportType
import org.multipaz.mdoc.transport.TransportWarning
import org.multipaz.mdoc.transport.TransportWarningCode
import org.multipaz.mdoc.transport.fromDataItem
import org.multipaz.mdoc.transport.toDataItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class PresentmentTelemetryTest {

    @Test
    fun testMdocTransportStatsCborRoundTrip() {
        val stats = MdocTransportStats(
            transportType = MdocTransportType.NFC_CCE_MDOC_PERIPHERAL_SERVER_MODE,
            role = MdocRole.MDOC,
            bytesSent = 2048,
            bytesReceived = 512,
            numMessagesSent = 2,
            numMessagesReceived = 2,
            bleScanningTime = 120.milliseconds,
            bleConnectingTime = 340.milliseconds,
            bleUsingL2cap = false,
            bleL2capPsmInEngagement = false,
            bleNegotiatedMtu = 515,
            bleMtuInferredFromWrite = true,
            bleNumScanRestarts = 1,
            bleNumGattConnectRetries = 2,
            bleNumMtuRequestRetries = 1,
            bleNumServiceDiscoveryRetries = 1,
            bleNumCharacteristicDiscoveryRetries = 1,
            bleNumCharacteristicReadRetries = 1,
            bleNumDescriptorWriteRetries = 1,
            bleNumL2capConnectRetries = 0,
            bleNumChunksSent = 5,
            bleNumChunksReceived = 2,
            nfcHybridNumSentViaNfc = 1,
            nfcHybridNumSentViaTransport = 2,
            nfcHybridNumReceivedFirstOnNfc = 1,
            nfcHybridNumReceivedFirstOnTransport = 1,
            nfcDisconnectedDuringTransaction = true,
            warnings = listOf(
                TransportWarning(
                    code = TransportWarningCode.BLE_MTU_INFERRED_FROM_WRITE
                ),
                TransportWarning(
                    code = TransportWarningCode.NFC_DISCONNECTED_WAITING_FOR_RETAP
                )
            ),
        )

        val cborItem = stats.toDataItem()
        val decodedFromCbor = MdocTransportStats.fromDataItem(cborItem)
        assertEquals(stats, decodedFromCbor)
    }

    @Test
    fun testMdocTransportStatsMinimalCborCompatibility() {
        val minimalStats = MdocTransportStats(
            transportType = MdocTransportType.NFC_DATA_TRANSFER_MODE,
            role = MdocRole.MDOC,
            bytesSent = 1024,
            bytesReceived = 256,
            numMessagesSent = 1,
            numMessagesReceived = 1,
        )
        val cborItem = minimalStats.toDataItem()
        assertFalse(cborItem.hasKey("bleUsingL2cap"))
        assertFalse(cborItem.hasKey("bleNumScanRestarts"))
        assertFalse(cborItem.hasKey("nfcHybridNumSentViaNfc"))
        assertFalse(cborItem.hasKey("nfcDisconnectedDuringTransaction"))

        val decoded = MdocTransportStats.fromDataItem(cborItem)
        assertEquals(minimalStats, decoded)
        assertNull(decoded.bleScanningTime)
        assertNull(decoded.bleConnectingTime)
        assertNull(decoded.bleUsingL2cap)
        assertNull(decoded.bleNumScanRestarts)
        assertNull(decoded.bleNumGattConnectRetries)
        assertNull(decoded.bleNumMtuRequestRetries)
        assertNull(decoded.bleNumServiceDiscoveryRetries)
        assertNull(decoded.bleNumCharacteristicDiscoveryRetries)
        assertNull(decoded.bleNumCharacteristicReadRetries)
        assertNull(decoded.bleNumDescriptorWriteRetries)
        assertNull(decoded.bleNumL2capConnectRetries)
        assertNull(decoded.nfcHybridNumSentViaNfc)
        assertNull(decoded.nfcDisconnectedDuringTransaction)

        // Also verify decoding a hand-crafted older CBOR map that omits newly added fields
        val legacyCbor = buildCborMap {
            put("transportType", MdocTransportType.MDOC_CENTRAL_CLIENT_MODE.name)
            put("role", MdocRole.MDOC.name)
            put("bytesSent", 512L)
            put("bytesReceived", 128L)
            put("numMessagesSent", 1L)
            put("numMessagesReceived", 1L)
            putCborArray("warnings") {}
            put("bleUsingL2cap", true)
        }
        val decodedLegacy = MdocTransportStats.fromDataItem(legacyCbor)
        assertEquals(MdocTransportType.MDOC_CENTRAL_CLIENT_MODE, decodedLegacy.transportType)
        assertEquals(true, decodedLegacy.bleUsingL2cap)
        assertNull(decodedLegacy.bleNumMtuRequestRetries)
        assertNull(decodedLegacy.nfcDisconnectedDuringTransaction)
    }

    @Test
    fun testPresentmentTelemetryCborRoundTrip() {
        val transportStats = MdocTransportStats(
            transportType = MdocTransportType.MDOC_PERIPHERAL_SERVER_MODE,
            role = MdocRole.MDOC,
            bytesSent = 4096,
            bytesReceived = 256,
            numMessagesSent = 1,
            numMessagesReceived = 1,
            bleConnectingTime = 180.milliseconds,
            bleNegotiatedMtu = 515,
            bleNumChunksSent = 9,
            bleNumChunksReceived = 1
        )
        val telemetry = PresentmentTelemetry(
            protocol = PresentmentProtocol.ISO_18013_5_PROXIMITY,
            engagementType = EngagementType.QR_CODE,
            outcome = PresentmentOutcome.SUCCESS,
            warnings = listOf(
                TransportWarning(
                    code = TransportWarningCode.BLE_UNEXPECTED_CHUNK_SIZE
                )
            ),
            requesterInTrustList = true,
            requestSizeBytes = 256,
            responseSizeBytes = 4096,
            numRequestsServed = 1,
            numDocumentsPresented = 1,
            numNfcReTaps = 0,
            durationEngagementToConnected = 180.milliseconds,
            durationConnectedToRequestReceived = 45.milliseconds,
            durationUserConsentAndAuth = 1200.milliseconds,
            durationResponseGeneration = 85.milliseconds,
            durationResponseTransmission = 110.milliseconds,
            durationTotal = 1620.milliseconds,
            transportStats = transportStats
        )

        val cborItem = telemetry.toDataItem()
        val decodedFromCbor = PresentmentTelemetry.fromDataItem(cborItem)
        assertEquals(telemetry, decodedFromCbor)
    }

    @Test
    fun testClassifyPresentmentException() = runTest {
        assertEquals(
            PresentmentOutcome.CANCELED_BY_USER,
            classifyPresentmentException(PresentmentCanceledException("canceled"))
        )
        assertEquals(
            PresentmentOutcome.CANCELED_BY_USER,
            classifyPresentmentException(CancellationException("canceled"))
        )
        val timeoutException = runCatching {
            withTimeout(1.milliseconds) {
                delay(100.milliseconds)
            }
        }.exceptionOrNull()!!
        assertEquals(
            PresentmentOutcome.CONNECTION_TIMEOUT,
            classifyPresentmentException(timeoutException)
        )
        assertEquals(
            PresentmentOutcome.CANNOT_SATISFY_REQUEST,
            classifyPresentmentException(PresentmentCannotSatisfyRequestException("no match", IllegalStateException()))
        )
        assertEquals(
            PresentmentOutcome.AUTHENTICATION_FAILED,
            classifyPresentmentException(AuthenticationFailedException("auth failed"))
        )
        assertEquals(
            PresentmentOutcome.AUTHENTICATION_FAILED,
            classifyPresentmentException(
                CancellationException("Canceled", AuthenticationFailedException("auth failed"))
            )
        )
        assertEquals(
            PresentmentOutcome.FACE_NOT_MATCHED,
            classifyPresentmentException(FaceNotMatchedException("face not matched"))
        )
        assertEquals(
            PresentmentOutcome.NFC_ENGAGEMENT_FAILED,
            classifyPresentmentException(MdocNfcEngagementException("engagement failed"))
        )
        assertEquals(
            PresentmentOutcome.TRANSPORT_ERROR,
            classifyPresentmentException(Iso18013PresentmentNfcDisconnectedException("removed"))
        )
        assertEquals(
            PresentmentOutcome.REQUEST_TIMEOUT,
            classifyPresentmentException(Iso18013PresentmentTimeoutException("timeout"))
        )
        assertEquals(
            PresentmentOutcome.TRANSPORT_ERROR,
            classifyPresentmentException(MdocTransportException("transport error"))
        )
        assertEquals(
            PresentmentOutcome.TRANSPORT_ERROR,
            classifyPresentmentException(MdocTransportClosedException("closed"))
        )
        assertEquals(
            PresentmentOutcome.TRANSPORT_ERROR,
            classifyPresentmentException(
                CancellationException("Transport FAILED", MdocTransportException("Transport FAILED"))
            )
        )
        assertEquals(
            PresentmentOutcome.TRANSPORT_ERROR,
            classifyPresentmentException(
                IllegalStateException("Unexpected resultingTransport is null"),
                waitingForConnection = true
            )
        )
    }
}
