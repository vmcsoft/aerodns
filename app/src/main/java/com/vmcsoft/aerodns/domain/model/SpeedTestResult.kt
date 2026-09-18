package com.vmcsoft.aerodns.domain.model

data class SpeedTestResult(
    val server: DnsServer,
    val averageLatencyMs: Long,
    val isReachable: Boolean,
    val sampleCount: Int = 0,
    val testedAddress: String? = null,
    val testedProtocol: DnsProtocol = DnsProtocol.STANDARD,
    val attemptedSampleCount: Int = sampleCount,
    val plannedSampleCount: Int = 10,
    val timedOut: Boolean = false,
    val failureReason: String? = null
) {
    companion object {
        // Prefer more successful replies before comparing successful-sample latency.
        val ranking: Comparator<SpeedTestResult> = compareByDescending<SpeedTestResult> { it.sampleCount }
            .thenBy { it.averageLatencyMs }
    }
}
