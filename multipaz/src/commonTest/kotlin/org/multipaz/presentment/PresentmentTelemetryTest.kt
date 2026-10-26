package org.multipaz.presentment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import kotlin.time.Duration.Companion.milliseconds

class PresentmentTelemetryTest {

    @Test
    fun testMdocTransportStatsCborRoundTrip() {
        val stats = MdocTransportStats(
            transportType = MdocTransportType.NFC_CCE_MDOC_PERIPHERAL_SERVER_MODE,
            role = MdocRole.MDOC,
            scanningTime = 120.milliseconds,
            connectingTime = 340.milliseconds,
            bytesSent = 2048,
            bytesReceived = 512,
            numMessagesSent = 2,
            numMessagesReceived = 2,
            bleUsingL2cap = false,
            bleL2capPsmInEngagement = false,
            bleNegotiatedMtu = 515,
            bleMtuInferredFromWrite = true,
            bleNumScanRestarts = 1,
            bleNumGattConnectRetries = 2,
            bleNumServiceDiscoveryRetries = 1,
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
    fun testPresentmentTelemetryCborRoundTrip() {
        val transportStats = MdocTransportStats(
            transportType = MdocTransportType.MDOC_PERIPHERAL_SERVER_MODE,
            role = MdocRole.MDOC,
            connectingTime = 180.milliseconds,
            bytesSent = 4096,
            bytesReceived = 256,
            numMessagesSent = 1,
            numMessagesReceived = 1,
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
