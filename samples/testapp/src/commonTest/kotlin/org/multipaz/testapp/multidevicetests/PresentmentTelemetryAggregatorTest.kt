package org.multipaz.testapp.multidevicetests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import org.multipaz.mdoc.engagement.EngagementType
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransportStats
import org.multipaz.mdoc.transport.MdocTransportType
import org.multipaz.mdoc.transport.TransportWarning
import org.multipaz.mdoc.transport.TransportWarningCode
import org.multipaz.presentment.PresentmentOutcome
import org.multipaz.presentment.PresentmentProtocol
import org.multipaz.presentment.PresentmentTelemetry

class PresentmentTelemetryAggregatorTest {

    @Test
    fun testPresentmentTelemetryAggregator() {
        val aggregator = PresentmentTelemetryAggregator()

        aggregator.add(
            PresentmentTelemetry(
                protocol = PresentmentProtocol.ISO_18013_5_PROXIMITY,
                engagementType = EngagementType.QR_CODE,
                outcome = PresentmentOutcome.SUCCESS,
                requestSizeBytes = 200,
                responseSizeBytes = 3000,
                numRequestsServed = 1,
                numDocumentsPresented = 1,
                numNfcReTaps = 0,
                durationTotal = 1000.milliseconds,
                durationEngagementToConnected = 200.milliseconds,
                durationResponseTransmission = 150.milliseconds,
                transportStats = MdocTransportStats(
                    transportType = MdocTransportType.MDOC_PERIPHERAL_SERVER_MODE,
                    role = MdocRole.MDOC,
                    connectingTime = 200.milliseconds,
                    bleNumGattConnectRetries = 1,
                    warnings = listOf(
                        TransportWarning(TransportWarningCode.BLE_MTU_INFERRED_FROM_WRITE),
                        TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CHUNK_SIZE),
                        TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CHUNK_SIZE),
                    )
                )
            )
        )

        aggregator.add(
            PresentmentTelemetry(
                protocol = PresentmentProtocol.ISO_18013_5_PROXIMITY,
                engagementType = EngagementType.NFC_STATIC_HANDOVER,
                outcome = PresentmentOutcome.TRANSPORT_ERROR,
                numNfcReTaps = 1,
                durationTotal = 500.milliseconds,
                warnings = listOf(TransportWarning(TransportWarningCode.NFC_DISCONNECTED_WAITING_FOR_RETAP)),
                transportStats = MdocTransportStats(
                    transportType = MdocTransportType.NFC_CCE_NFC_ONLY,
                    role = MdocRole.MDOC
                )
            )
        )

        val aggregated = aggregator.aggregate()
        assertEquals(2, aggregated.numTransactionsTotal)
        assertEquals(1, aggregated.numTransactionsSuccessful)
        assertEquals(1, aggregated.outcomeCounts[PresentmentOutcome.SUCCESS])
        assertEquals(1, aggregated.outcomeCounts[PresentmentOutcome.TRANSPORT_ERROR])
        assertEquals(1, aggregated.warningCounts[TransportWarningCode.BLE_MTU_INFERRED_FROM_WRITE])
        assertEquals(1, aggregated.warningCounts[TransportWarningCode.BLE_UNEXPECTED_CHUNK_SIZE])
        assertEquals(1, aggregated.warningCounts[TransportWarningCode.NFC_DISCONNECTED_WAITING_FOR_RETAP])

        val gattRetry = aggregated.bleGattConnectRetries
        assertEquals(1, gattRetry.transactionsWithRetries)
        assertEquals(1, gattRetry.totalRetries)
        assertEquals(1, gattRetry.maxRetriesInSingleTransaction)

        val nfcReTaps = aggregated.nfcReTaps
        assertEquals(1, nfcReTaps.transactionsWithRetries)
        assertEquals(1, nfcReTaps.totalRetries)

        val durationTotal = aggregated.transactionTimeMsec
        assertEquals(2, durationTotal.count)
        assertEquals(500L, durationTotal.min)
        assertEquals(1000L, durationTotal.max)
        assertEquals(750.0, durationTotal.avg)
    }
}
