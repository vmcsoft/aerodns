package com.vmcsoft.aerodns.domain.usecase

import android.util.Log
import com.vmcsoft.aerodns.data.vpn.NetworkPinger
import com.vmcsoft.aerodns.data.vpn.PingResult
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.NetworkIpStack
import com.vmcsoft.aerodns.domain.model.SpeedTestResult
import com.vmcsoft.aerodns.domain.model.buildDnsConnectionConfig
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import com.vmcsoft.aerodns.domain.repository.NetworkCapabilitiesRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class RunSpeedTestUseCase @Inject constructor(
    private val dnsRepository: DnsRepository,
    private val networkPinger: NetworkPinger,
    private val networkCapabilitiesRepository: NetworkCapabilitiesRepository
) {
    companion object {
        private const val TAG = "RunSpeedTestUseCase"
        private const val OVERALL_TIMEOUT_MS = 15_000L
        private const val PER_SERVER_TIMEOUT_MS = 5_000L
        private const val WARMUP_ATTEMPTS = 0
        private const val MEASURED_ATTEMPTS = 10
        private const val DNS_QUERY_TIMEOUT_MS = 500
        private val QUERY_NAMES = listOf(
            "example.com",
            "cloudflare.com",
            "google.com",
            "ietf.org",
            "wikipedia.org",
            "github.com"
        )
    }

    suspend operator fun invoke(
        onProgress: (completed: Int, total: Int, result: SpeedTestResult) -> Unit = { _, _, _ -> }
    ): List<SpeedTestResult> = coroutineScope {
        try {
            withTimeout(OVERALL_TIMEOUT_MS) {
                val stack = networkCapabilitiesRepository.getActiveNetworkIpStack()
                val servers = (dnsRepository.getPreDefinedServers() + dnsRepository.getCustomServers())
                    .distinctBy { it.id }
                val total = servers.size
                val completed = AtomicInteger(0)

                servers.map { server ->
                    async {
                        val result = try {
                            withTimeout(PER_SERVER_TIMEOUT_MS) {
                                val addresses = getSpeedTestAddresses(server, stack)
                                val dohConfig = if (addresses.isEmpty() && !server.dohUrl.isNullOrBlank()) {
                                    buildDnsConnectionConfig(server, stack, DnsProtocol.DOH).getOrNull()
                                } else {
                                    null
                                }

                                repeat(WARMUP_ATTEMPTS) { attempt ->
                                    measureServerLatency(
                                        addresses = addresses,
                                        dohUrl = dohConfig?.dohUrl,
                                        dohBootstrapAddresses = dohConfig?.upstreamAddresses.orEmpty(),
                                        customBootstrapIp = server.customBootstrapIp,
                                        allowUntrustedCertificates = server.isCustom && server.allowUntrustedCertificates,
                                        queryName = QUERY_NAMES[attempt % QUERY_NAMES.size]
                                    )
                                }

                                val samples = mutableListOf<LatencySample>()
                                repeat(MEASURED_ATTEMPTS) { attempt ->
                                    measureServerLatency(
                                        addresses = addresses,
                                        dohUrl = dohConfig?.dohUrl,
                                        dohBootstrapAddresses = dohConfig?.upstreamAddresses.orEmpty(),
                                        customBootstrapIp = server.customBootstrapIp,
                                        allowUntrustedCertificates = server.isCustom && server.allowUntrustedCertificates,
                                        queryName = QUERY_NAMES[(attempt + WARMUP_ATTEMPTS) % QUERY_NAMES.size]
                                    )?.let { sample ->
                                        samples.add(sample)
                                    }
                                }

                                SpeedTestResult(
                                    server = server,
                                    averageLatencyMs = if (samples.isNotEmpty()) {
                                        scoreLatency(samples.map { it.latencyMs })
                                    } else {
                                        Long.MAX_VALUE
                                    },
                                    isReachable = samples.isNotEmpty(),
                                    sampleCount = samples.size,
                                    testedAddress = samples
                                        .groupingBy { it.address }
                                        .eachCount()
                                        .maxByOrNull { it.value }
                                        ?.key
                                )
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.w(TAG, "Timeout testing server: ${server.name}")
                            SpeedTestResult(
                                server = server,
                                averageLatencyMs = Long.MAX_VALUE,
                                isReachable = false
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error testing server: ${server.name}", e)
                            SpeedTestResult(
                                server = server,
                                averageLatencyMs = Long.MAX_VALUE,
                                isReachable = false
                            )
                        }

                        onProgress(completed.incrementAndGet(), total, result)
                        result
                    }
                }.awaitAll().sortedBy { it.averageLatencyMs }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Overall speed test timeout exceeded")
            throw Exception("Speed test timed out. Please check your internet connection.")
        }
    }

    private suspend fun measureServerLatency(
        addresses: List<String>,
        dohUrl: String?,
        dohBootstrapAddresses: List<String>,
        customBootstrapIp: String?,
        allowUntrustedCertificates: Boolean,
        queryName: String
    ): LatencySample? {
        for (address in addresses) {
            when (val result = networkPinger.measureDnsQueryLatency(address, DNS_QUERY_TIMEOUT_MS, queryName)) {
                is PingResult.Success -> return LatencySample(address, result.latencyMs)
                else -> { /* Try the next address, if any */ }
            }
        }
        if (!dohUrl.isNullOrBlank()) {
            when (
                val result = networkPinger.measureDohQueryLatency(
                    dohUrl = dohUrl,
                    upstreamAddresses = dohBootstrapAddresses,
                    timeoutMs = DNS_QUERY_TIMEOUT_MS,
                    queryName = queryName,
                    customBootstrapIp = customBootstrapIp,
                    allowUntrustedCertificates = allowUntrustedCertificates
                )
            ) {
                is PingResult.Success -> return LatencySample(dohUrl, result.latencyMs)
                else -> { /* No reachable path for this attempt */ }
            }
        }
        return null
    }

    private fun getSpeedTestAddresses(server: DnsServer, stack: NetworkIpStack): List<String> {
        val ipv4Addresses = listOfNotNull(
            server.primary.takeIf { it.isNotBlank() },
            server.secondary
        )
        val ipv6Addresses = listOfNotNull(
            server.ipv6Primary,
            server.ipv6Secondary
        )

        return when (stack) {
            NetworkIpStack.IPv6_ONLY -> ipv6Addresses.ifEmpty { ipv4Addresses }
            NetworkIpStack.IPv4_ONLY,
            NetworkIpStack.DUAL_STACK -> ipv4Addresses.ifEmpty { ipv6Addresses }
        }
    }

    private fun scoreLatency(latencies: List<Long>): Long {
        val sorted = latencies.sorted()
        val scoredSamples = if (sorted.size >= 3) {
            sorted.drop(1).dropLast(1)
        } else {
            sorted
        }
        return scoredSamples.average().toLong()
    }

    private data class LatencySample(
        val address: String,
        val latencyMs: Long
    )
}
