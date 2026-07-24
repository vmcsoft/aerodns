package com.vmcsoft.aerodns.data.vpn

import com.vmcsoft.aerodns.data.dns.DnsTransportResult
import com.vmcsoft.aerodns.data.dns.DnsWireMessage
import com.vmcsoft.aerodns.data.dns.DohDnsTransport
import com.vmcsoft.aerodns.data.dns.UdpDnsTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkPinger @Inject constructor(
    private val udpDnsTransport: UdpDnsTransport,
    private val dohDnsTransport: DohDnsTransport
) {

    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
    private val overallTimeoutMs: Long = 5000L // Overall timeout including DNS resolution

    suspend fun ping(ipAddress: String, timeoutMs: Int = 3000): PingResult {
        return withContext(dispatcher) {
            try {
                // Add overall timeout to prevent hanging
                withTimeout(overallTimeoutMs) {
                    try {
                        val startTime = System.nanoTime()
                        val address = InetAddress.getByName(ipAddress)
                        val reachable = address.isReachable(timeoutMs)
                        val latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)

                        if (reachable) {
                            PingResult.Success(latencyMs = latency)
                        } else {
                            PingResult.Timeout
                        }
                    } catch (e: UnknownHostException) {
                        PingResult.Error("Unknown host: $ipAddress")
                    } catch (e: IOException) {
                        PingResult.Error("Network error: ${e.message}")
                    } catch (e: Exception) {
                        PingResult.Error(e.message ?: "Unknown error")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                PingResult.Timeout
            }
        }
    }

    suspend fun measureDnsQueryLatency(
        ipAddress: String,
        timeoutMs: Int = 2000,
        queryName: String = "example.com"
    ): PingResult {
        val queryId = (System.nanoTime().toInt() and 0xFFFF)
        val query = DnsWireMessage.buildAQuery(queryId, queryName)

        return when (val result = udpDnsTransport.query(query, ipAddress, timeoutMs)) {
            is DnsTransportResult.Success -> {
                if (DnsWireMessage.isSuccessfulResponse(result.payload, result.payload.size, queryId)) {
                    PingResult.Success(latencyMs = result.latencyMs)
                } else {
                    PingResult.Error("Invalid DNS response from $ipAddress")
                }
            }
            is DnsTransportResult.Timeout -> PingResult.Timeout
            is DnsTransportResult.Error -> PingResult.Error(result.message)
        }
    }

    suspend fun measureDohQueryLatency(
        dohUrl: String,
        upstreamAddresses: List<String>,
        timeoutMs: Int = 2000,
        queryName: String = "example.com",
        customBootstrapIp: String? = null,
        allowUntrustedCertificates: Boolean = false
    ): PingResult {
        val queryId = (System.nanoTime().toInt() and 0xFFFF)
        val query = DnsWireMessage.buildAQuery(queryId, queryName)

        return when (
            val result = dohDnsTransport.query(
                payload = query,
                dohUrl = dohUrl,
                upstreamAddresses = upstreamAddresses,
                timeoutMs = timeoutMs,
                customBootstrapIp = customBootstrapIp,
                allowUntrustedCertificates = allowUntrustedCertificates
            )
        ) {
            is DnsTransportResult.Success -> {
                if (DnsWireMessage.isSuccessfulResponse(result.payload, result.payload.size, queryId)) {
                    PingResult.Success(latencyMs = result.latencyMs)
                } else {
                    PingResult.Error("Invalid DoH response from $dohUrl")
                }
            }
            is DnsTransportResult.Timeout -> PingResult.Timeout
            is DnsTransportResult.Error -> PingResult.Error(result.message)
        }
    }
}
