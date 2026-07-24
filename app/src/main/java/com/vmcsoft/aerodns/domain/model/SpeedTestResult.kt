package com.vmcsoft.aerodns.domain.model

data class SpeedTestResult(
    val server: DnsServer,
    val averageLatencyMs: Long,
    val isReachable: Boolean,
    val sampleCount: Int = 0,
    val testedAddress: String? = null
)
