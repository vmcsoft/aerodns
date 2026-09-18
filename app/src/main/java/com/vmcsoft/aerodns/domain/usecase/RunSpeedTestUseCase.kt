package com.vmcsoft.aerodns.domain.usecase

import com.vmcsoft.aerodns.data.vpn.NetworkPinger
import com.vmcsoft.aerodns.data.vpn.PingResult
import com.vmcsoft.aerodns.domain.model.*
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import com.vmcsoft.aerodns.domain.repository.NetworkCapabilitiesRepository
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class RunSpeedTestUseCase @Inject constructor(
    private val dnsRepository: DnsRepository,
    private val networkPinger: NetworkPinger,
    private val networkCapabilitiesRepository: NetworkCapabilitiesRepository
) {
    companion object {
        private const val OVERALL_TIMEOUT_MS = 15_000L
        private const val PER_SERVER_TIMEOUT_MS = 5_000L
        private const val MEASURED_ATTEMPTS = 10
        private const val DNS_QUERY_TIMEOUT_MS = 500
        private val QUERY_NAMES = listOf("example.com", "cloudflare.com", "google.com", "ietf.org", "wikipedia.org", "github.com")
    }

    suspend operator fun invoke(
        protocol: DnsProtocol,
        onProgress: (completed: Int, total: Int, result: SpeedTestResult) -> Unit = { _, _, _ -> }
    ): List<SpeedTestResult> = coroutineScope {
        withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
            val stack = networkCapabilitiesRepository.getActiveNetworkIpStack()
            val servers = (dnsRepository.getPreDefinedServers() + dnsRepository.getCustomServers()).distinctBy { it.id }
            val completed = AtomicInteger(0)
            servers.map { server ->
                async {
                    val result = measure(server, stack, protocol)
                    currentCoroutineContext().ensureActive()
                    onProgress(completed.incrementAndGet(), servers.size, result)
                    result
                }
            }.awaitAll().sortedWith(SpeedTestResult.ranking)
        } ?: throw IllegalStateException("Speed test timed out. Please check your internet connection.")
    }

    private suspend fun measure(server: DnsServer, stack: NetworkIpStack, protocol: DnsProtocol): SpeedTestResult {
        fun unavailable(reason: String) = SpeedTestResult(server, Long.MAX_VALUE, false,
            testedProtocol = protocol, plannedSampleCount = MEASURED_ATTEMPTS, failureReason = reason)
        if (!protocol.isUserSelectable() || protocol !in server.supportedProtocols) {
            return unavailable("Protocol unavailable")
        }
        // Share the connection's address ordering, bootstrap and custom TLS policy.
        val config = buildDnsConnectionConfig(server, stack, protocol).getOrNull()
            ?: return unavailable("Configuration unavailable")
        val samples = mutableListOf<LatencySample>()
        var attempted = 0
        var failure: String? = null
        val finished = withTimeoutOrNull(PER_SERVER_TIMEOUT_MS) {
            repeat(MEASURED_ATTEMPTS) { attempt ->
                currentCoroutineContext().ensureActive()
                attempted++
                try {
                    measureSample(config, QUERY_NAMES[attempt % QUERY_NAMES.size])?.let(samples::add)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = "Request failed"
                }
            }
            true
        } ?: false
        return SpeedTestResult(
            server = server,
            averageLatencyMs = if (samples.isEmpty()) Long.MAX_VALUE else scoreLatency(samples.map { it.latencyMs }),
            isReachable = samples.isNotEmpty(),
            sampleCount = samples.size,
            testedAddress = samples.groupingBy { it.address }.eachCount().maxByOrNull { it.value }?.key,
            testedProtocol = protocol,
            attemptedSampleCount = attempted,
            plannedSampleCount = MEASURED_ATTEMPTS,
            timedOut = !finished,
            failureReason = if (samples.isNotEmpty()) null else if (!finished) "Timed out" else failure ?: "No successful replies"
        )
    }

    private suspend fun measureSample(config: DnsConnectionConfig, name: String): LatencySample? {
        when (config.protocol) {
            DnsProtocol.STANDARD -> for (address in config.upstreamAddresses) {
                currentCoroutineContext().ensureActive()
                val result = networkPinger.measureDnsQueryLatency(address, DNS_QUERY_TIMEOUT_MS, name)
                if (result is PingResult.Success) return LatencySample(address, result.latencyMs)
            }
            DnsProtocol.DOH -> {
                val endpoint = requireNotNull(config.dohUrl)
                val result = networkPinger.measureDohQueryLatency(endpoint, config.upstreamAddresses,
                    DNS_QUERY_TIMEOUT_MS, name, config.customBootstrapIp, config.allowUntrustedCertificates)
                if (result is PingResult.Success) return LatencySample(endpoint, result.latencyMs)
            }
            DnsProtocol.DOT -> return null
        }
        return null
    }

    private fun scoreLatency(latencies: List<Long>): Long {
        val sorted = latencies.sorted()
        val scored = if (sorted.size >= 3) sorted.drop(1).dropLast(1) else sorted
        return scored.average().toLong()
    }

    private data class LatencySample(val address: String, val latencyMs: Long)
}
