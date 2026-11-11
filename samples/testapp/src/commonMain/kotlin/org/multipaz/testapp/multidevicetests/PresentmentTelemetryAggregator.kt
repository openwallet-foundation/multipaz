package org.multipaz.testapp.multidevicetests

import kotlin.math.sqrt
import kotlin.time.Duration
import org.multipaz.mdoc.transport.MdocTransportStats
import org.multipaz.mdoc.transport.TransportWarningCode
import org.multipaz.presentment.PresentmentOutcome
import org.multipaz.presentment.PresentmentTelemetry

/**
 * Summary statistics (count, min, max, average, standard deviation) for a numeric metric.
 *
 * @property count number of recorded samples.
 * @property min minimum recorded value (0 if [count] == 0).
 * @property max maximum recorded value (0 if [count] == 0).
 * @property avg arithmetic mean of recorded values (0.0 if [count] == 0).
 * @property stdDev population standard deviation of recorded values (0.0 if [count] == 0).
 */
data class MetricSummary(
    val count: Int = 0,
    val min: Long = 0,
    val max: Long = 0,
    val avg: Double = 0.0,
    val stdDev: Double = 0.0,
) {
    companion object {
        /**
         * Computes a [MetricSummary] from a list of [Long] samples.
         */
        fun fromSamples(samples: List<Long>): MetricSummary {
            if (samples.isEmpty()) {
                return MetricSummary()
            }
            val count = samples.size
            val min = samples.min()
            val max = samples.max()
            val avg = samples.sum().toDouble() / count
            val variance = samples.sumOf { val d = it - avg; d * d } / count
            return MetricSummary(
                count = count,
                min = min,
                max = max,
                avg = avg,
                stdDev = sqrt(variance),
            )
        }
    }
}

/**
 * Summary of retry occurrences across a set of transactions.
 *
 * @property totalRetries total number of retries across all transactions.
 * @property maxRetriesInSingleTransaction highest number of retries observed in any single transaction.
 * @property transactionsWithRetries number of transactions that experienced at least one retry.
 */
data class RetrySummary(
    val totalRetries: Int = 0,
    val maxRetriesInSingleTransaction: Int = 0,
    val transactionsWithRetries: Int = 0,
) {
    companion object {
        /**
         * Computes a [RetrySummary] from a list of per-transaction retry counts.
         */
        fun fromCounts(counts: List<Int>): RetrySummary {
            if (counts.isEmpty()) {
                return RetrySummary()
            }
            return RetrySummary(
                totalRetries = counts.sum(),
                maxRetriesInSingleTransaction = counts.max(),
                transactionsWithRetries = counts.count { it > 0 },
            )
        }
    }
}

/**
 * Aggregated, PII-free telemetry across multiple presentment or transport sessions.
 *
 * @property numTransactionsTotal total number of recorded sessions.
 * @property numTransactionsSuccessful number of sessions with [PresentmentOutcome.SUCCESS].
 * @property outcomeCounts count of sessions by [PresentmentOutcome].
 * @property warningCounts count of sessions in which each [TransportWarningCode] occurred.
 * @property transactionTimeMsec summary of total transaction times in milliseconds.
 * @property scanningTimeMsec summary of transport scanning times in milliseconds.
 * @property connectingTimeMsec summary of transport connecting times in milliseconds.
 * @property requestSizeBytes summary of request payload sizes in bytes.
 * @property responseSizeBytes summary of response payload sizes in bytes.
 * @property bytesSent summary of transport bytes sent per session.
 * @property bytesReceived summary of transport bytes received per session.
 * @property bleNegotiatedMtu summary of negotiated BLE ATT MTU values.
 * @property bleScanRestarts summary of BLE scan restarts.
 * @property bleGattConnectRetries summary of BLE GATT connection retries.
 * @property bleMtuRequestRetries summary of BLE GATT MTU request retries.
 * @property bleServiceDiscoveryRetries summary of BLE GATT service discovery retries.
 * @property bleCharacteristicDiscoveryRetries summary of BLE GATT characteristic discovery retries.
 * @property bleCharacteristicReadRetries summary of BLE GATT characteristic read retries.
 * @property bleDescriptorWriteRetries summary of BLE GATT descriptor (CCCD) write retries.
 * @property bleL2capConnectRetries summary of BLE L2CAP socket connection retries.
 * @property nfcReTaps summary of NFC re-taps.
 */
data class AggregatedPresentmentTelemetry(
    val numTransactionsTotal: Int = 0,
    val numTransactionsSuccessful: Int = 0,
    val outcomeCounts: Map<PresentmentOutcome, Int> = emptyMap(),
    val warningCounts: Map<TransportWarningCode, Int> = emptyMap(),
    val transactionTimeMsec: MetricSummary = MetricSummary(),
    val scanningTimeMsec: MetricSummary = MetricSummary(),
    val connectingTimeMsec: MetricSummary = MetricSummary(),
    val requestSizeBytes: MetricSummary = MetricSummary(),
    val responseSizeBytes: MetricSummary = MetricSummary(),
    val bytesSent: MetricSummary = MetricSummary(),
    val bytesReceived: MetricSummary = MetricSummary(),
    val bleNegotiatedMtu: MetricSummary = MetricSummary(),
    val bleScanRestarts: RetrySummary = RetrySummary(),
    val bleGattConnectRetries: RetrySummary = RetrySummary(),
    val bleMtuRequestRetries: RetrySummary = RetrySummary(),
    val bleServiceDiscoveryRetries: RetrySummary = RetrySummary(),
    val bleCharacteristicDiscoveryRetries: RetrySummary = RetrySummary(),
    val bleCharacteristicReadRetries: RetrySummary = RetrySummary(),
    val bleDescriptorWriteRetries: RetrySummary = RetrySummary(),
    val bleL2capConnectRetries: RetrySummary = RetrySummary(),
    val nfcReTaps: RetrySummary = RetrySummary(),
)

/**
 * Accumulates [PresentmentTelemetry] and/or [MdocTransportStats] records and computes an
 * [AggregatedPresentmentTelemetry] summary.
 */
class PresentmentTelemetryAggregator {
    private var numTransactionsTotal = 0
    private var numTransactionsSuccessful = 0
    private val outcomeCounts = mutableMapOf<PresentmentOutcome, Int>()
    private val warningCounts = mutableMapOf<TransportWarningCode, Int>()

    private val transactionTimeSamples = mutableListOf<Long>()
    private val scanningTimeSamples = mutableListOf<Long>()
    private val connectingTimeSamples = mutableListOf<Long>()
    private val requestSizeSamples = mutableListOf<Long>()
    private val responseSizeSamples = mutableListOf<Long>()
    private val bytesSentSamples = mutableListOf<Long>()
    private val bytesReceivedSamples = mutableListOf<Long>()
    private val bleNegotiatedMtuSamples = mutableListOf<Long>()

    private val bleScanRestartCounts = mutableListOf<Int>()
    private val bleGattConnectRetryCounts = mutableListOf<Int>()
    private val bleMtuRequestRetryCounts = mutableListOf<Int>()
    private val bleServiceDiscoveryRetryCounts = mutableListOf<Int>()
    private val bleCharacteristicDiscoveryRetryCounts = mutableListOf<Int>()
    private val bleCharacteristicReadRetryCounts = mutableListOf<Int>()
    private val bleDescriptorWriteRetryCounts = mutableListOf<Int>()
    private val bleL2capConnectRetryCounts = mutableListOf<Int>()
    private val nfcReTapCounts = mutableListOf<Int>()

    /**
     * Resets all accumulated telemetry in this aggregator.
     */
    fun reset() {
        numTransactionsTotal = 0
        numTransactionsSuccessful = 0
        outcomeCounts.clear()
        warningCounts.clear()
        transactionTimeSamples.clear()
        scanningTimeSamples.clear()
        connectingTimeSamples.clear()
        requestSizeSamples.clear()
        responseSizeSamples.clear()
        bytesSentSamples.clear()
        bytesReceivedSamples.clear()
        bleNegotiatedMtuSamples.clear()
        bleScanRestartCounts.clear()
        bleGattConnectRetryCounts.clear()
        bleMtuRequestRetryCounts.clear()
        bleServiceDiscoveryRetryCounts.clear()
        bleCharacteristicDiscoveryRetryCounts.clear()
        bleCharacteristicReadRetryCounts.clear()
        bleDescriptorWriteRetryCounts.clear()
        bleL2capConnectRetryCounts.clear()
        nfcReTapCounts.clear()
    }

    /**
     * Adds a [PresentmentTelemetry] record to this aggregator.
     */
    fun add(telemetry: PresentmentTelemetry) {
        numTransactionsTotal += 1
        if (telemetry.outcome == PresentmentOutcome.SUCCESS) {
            numTransactionsSuccessful += 1
        }
        outcomeCounts[telemetry.outcome] = (outcomeCounts[telemetry.outcome] ?: 0) + 1

        val distinctWarningCodes = (telemetry.warnings + (telemetry.transportStats?.warnings ?: emptyList()))
            .map { it.code }
            .distinct()
        for (code in distinctWarningCodes) {
            warningCounts[code] = (warningCounts[code] ?: 0) + 1
        }

        telemetry.durationTotal?.let { transactionTimeSamples.add(it.inWholeMilliseconds) }
        telemetry.requestSizeBytes?.let { requestSizeSamples.add(it.toLong()) }
        telemetry.responseSizeBytes?.let { responseSizeSamples.add(it.toLong()) }
        nfcReTapCounts.add(telemetry.numNfcReTaps)

        telemetry.transportStats?.let { stats ->
            recordTransportMetrics(stats)
        }
    }

    /**
     * Adds a standalone [MdocTransportStats] record (e.g., from reader-side or multi-device transport testing)
     * to this aggregator.
     */
    fun addTransportStats(
        stats: MdocTransportStats,
        transactionTime: Duration? = null,
        outcome: PresentmentOutcome = PresentmentOutcome.SUCCESS,
    ) {
        numTransactionsTotal += 1
        if (outcome == PresentmentOutcome.SUCCESS) {
            numTransactionsSuccessful += 1
        }
        outcomeCounts[outcome] = (outcomeCounts[outcome] ?: 0) + 1
        for (code in stats.warnings.map { it.code }.distinct()) {
            warningCounts[code] = (warningCounts[code] ?: 0) + 1
        }
        transactionTime?.let { transactionTimeSamples.add(it.inWholeMilliseconds) }
        recordTransportMetrics(stats)
    }

    private fun recordTransportMetrics(stats: MdocTransportStats) {
        stats.bleScanningTime?.let { scanningTimeSamples.add(it.inWholeMilliseconds) }
        stats.bleConnectingTime?.let { connectingTimeSamples.add(it.inWholeMilliseconds) }
        if (stats.bytesSent > 0) {
            bytesSentSamples.add(stats.bytesSent)
        }
        if (stats.bytesReceived > 0) {
            bytesReceivedSamples.add(stats.bytesReceived)
        }
        stats.bleNegotiatedMtu?.let { bleNegotiatedMtuSamples.add(it.toLong()) }
        stats.bleNumScanRestarts?.let { bleScanRestartCounts.add(it) }
        stats.bleNumGattConnectRetries?.let { bleGattConnectRetryCounts.add(it) }
        stats.bleNumMtuRequestRetries?.let { bleMtuRequestRetryCounts.add(it) }
        stats.bleNumServiceDiscoveryRetries?.let { bleServiceDiscoveryRetryCounts.add(it) }
        stats.bleNumCharacteristicDiscoveryRetries?.let { bleCharacteristicDiscoveryRetryCounts.add(it) }
        stats.bleNumCharacteristicReadRetries?.let { bleCharacteristicReadRetryCounts.add(it) }
        stats.bleNumDescriptorWriteRetries?.let { bleDescriptorWriteRetryCounts.add(it) }
        stats.bleNumL2capConnectRetries?.let { bleL2capConnectRetryCounts.add(it) }
    }

    /**
     * Computes an immutable [AggregatedPresentmentTelemetry] snapshot from all records added so far.
     */
    fun aggregate(): AggregatedPresentmentTelemetry = AggregatedPresentmentTelemetry(
        numTransactionsTotal = numTransactionsTotal,
        numTransactionsSuccessful = numTransactionsSuccessful,
        outcomeCounts = outcomeCounts.toMap(),
        warningCounts = warningCounts.toMap(),
        transactionTimeMsec = MetricSummary.fromSamples(transactionTimeSamples),
        scanningTimeMsec = MetricSummary.fromSamples(scanningTimeSamples),
        connectingTimeMsec = MetricSummary.fromSamples(connectingTimeSamples),
        requestSizeBytes = MetricSummary.fromSamples(requestSizeSamples),
        responseSizeBytes = MetricSummary.fromSamples(responseSizeSamples),
        bytesSent = MetricSummary.fromSamples(bytesSentSamples),
        bytesReceived = MetricSummary.fromSamples(bytesReceivedSamples),
        bleNegotiatedMtu = MetricSummary.fromSamples(bleNegotiatedMtuSamples),
        bleScanRestarts = RetrySummary.fromCounts(bleScanRestartCounts),
        bleGattConnectRetries = RetrySummary.fromCounts(bleGattConnectRetryCounts),
        bleMtuRequestRetries = RetrySummary.fromCounts(bleMtuRequestRetryCounts),
        bleServiceDiscoveryRetries = RetrySummary.fromCounts(bleServiceDiscoveryRetryCounts),
        bleCharacteristicDiscoveryRetries = RetrySummary.fromCounts(bleCharacteristicDiscoveryRetryCounts),
        bleCharacteristicReadRetries = RetrySummary.fromCounts(bleCharacteristicReadRetryCounts),
        bleDescriptorWriteRetries = RetrySummary.fromCounts(bleDescriptorWriteRetryCounts),
        bleL2capConnectRetries = RetrySummary.fromCounts(bleL2capConnectRetryCounts),
        nfcReTaps = RetrySummary.fromCounts(nfcReTapCounts),
    )
}
