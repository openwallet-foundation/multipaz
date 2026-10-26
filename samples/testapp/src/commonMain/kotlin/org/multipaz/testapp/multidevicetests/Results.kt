package org.multipaz.testapp.multidevicetests

data class Results(
    val plan: Plan = Plan.ALL_TESTS,
    val currentTest: Test? = null,
    val numIterationsTotal: Int = 0,
    val numIterationsCompleted: Int = 0,
    val numIterationsSuccessful: Int = 0,
    val failedIterations: List<Int> = listOf(),
    val transactionTime: Timing = Timing(),
    val scanningTime: Timing = Timing(),
    val holderTelemetry: AggregatedPresentmentTelemetry? = null,
    val readerTelemetry: AggregatedPresentmentTelemetry? = null,
)