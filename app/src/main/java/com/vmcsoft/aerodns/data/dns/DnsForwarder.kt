package com.vmcsoft.aerodns.data.dns

import com.vmcsoft.aerodns.data.diagnostics.DnsDiagnosticLog
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DnsForwarder @Inject constructor(
    private val udpDnsTransport: UdpDnsTransport,
    private val tcpDnsTransport: TcpDnsTransport,
    private val dohDnsTransport: DohDnsTransport,
    private val dotDnsTransport: DotDnsTransport
) {
    suspend fun forward(
        payload: ByteArray,
        config: DnsConnectionConfig,
        timeoutMs: Int
    ): DnsTransportResult {
        DnsDiagnosticLog.d(
            TAG,
            "forward_start provider=${config.displayName} protocol=${config.protocol} " +
                "payloadBytes=${payload.size} upstreams=${config.upstreamAddresses} timeoutMs=$timeoutMs"
        )

        val result = when (config.protocol) {
            DnsProtocol.STANDARD -> forwardStandardDns(payload, config, timeoutMs)
            DnsProtocol.DOH -> forwardDoh(payload, config, timeoutMs)
            DnsProtocol.DOT -> forwardDot(payload, config, timeoutMs)
        }

        DnsDiagnosticLog.d(TAG, "forward_result protocol=${config.protocol} result=${result.toDiagnosticString()}")
        return result
    }

    private suspend fun forwardStandardDns(
        payload: ByteArray,
        config: DnsConnectionConfig,
        timeoutMs: Int
    ): DnsTransportResult {
        if (config.upstreamAddresses.isEmpty()) {
            return DnsTransportResult.Error("No upstream DNS addresses configured")
        }

        var lastError: DnsTransportResult? = null
        for (address in config.upstreamAddresses) {
            DnsDiagnosticLog.d(TAG, "standard_forward_try upstream=$address payloadBytes=${payload.size}")
            when (val result = udpDnsTransport.query(payload, address, timeoutMs)) {
                is DnsTransportResult.Success -> {
                    DnsDiagnosticLog.d(
                        TAG,
                        "standard_forward_udp_success upstream=$address latencyMs=${result.latencyMs} " +
                            "responseBytes=${result.payload.size} truncated=${DnsWireMessage.isTruncatedResponse(result.payload)}"
                    )
                    if (!DnsWireMessage.isTruncatedResponse(result.payload)) {
                        return result
                    }

                    when (val tcpResult = tcpDnsTransport.query(payload, address, timeoutMs)) {
                        is DnsTransportResult.Success -> {
                            DnsDiagnosticLog.d(
                                TAG,
                                "standard_forward_tcp_success upstream=$address latencyMs=${tcpResult.latencyMs} " +
                                    "responseBytes=${tcpResult.payload.size}"
                            )
                            return tcpResult
                        }
                        is DnsTransportResult.Timeout -> {
                            DnsDiagnosticLog.w(TAG, "standard_forward_tcp_timeout upstream=$address")
                            lastError = tcpResult
                        }
                        is DnsTransportResult.Error -> {
                            DnsDiagnosticLog.w(TAG, "standard_forward_tcp_error upstream=$address message=${tcpResult.message}")
                            lastError = tcpResult
                        }
                    }
                }
                is DnsTransportResult.Timeout -> {
                    DnsDiagnosticLog.w(TAG, "standard_forward_udp_timeout upstream=$address")
                    lastError = result
                }
                is DnsTransportResult.Error -> {
                    DnsDiagnosticLog.w(TAG, "standard_forward_udp_error upstream=$address message=${result.message}")
                    lastError = result
                }
            }
        }

        return lastError ?: DnsTransportResult.Error("DNS forwarding failed")
    }

    private suspend fun forwardDoh(
        payload: ByteArray,
        config: DnsConnectionConfig,
        timeoutMs: Int
    ): DnsTransportResult {
        val dohUrl = config.dohUrl?.takeIf { it.isNotBlank() }
            ?: return DnsTransportResult.Error("DNS-over-HTTPS URL is not configured")

        return dohDnsTransport.query(
            payload = payload,
            dohUrl = dohUrl,
            upstreamAddresses = config.upstreamAddresses,
            timeoutMs = timeoutMs,
            customBootstrapIp = config.customBootstrapIp,
            allowUntrustedCertificates = config.allowUntrustedCertificates
        )
    }

    private suspend fun forwardDot(
        payload: ByteArray,
        config: DnsConnectionConfig,
        timeoutMs: Int
    ): DnsTransportResult {
        val dotHostname = config.dotHostname?.takeIf { it.isNotBlank() }
            ?: return DnsTransportResult.Error("DNS-over-TLS hostname is not configured")

        return dotDnsTransport.query(payload, dotHostname, config.upstreamAddresses, timeoutMs)
    }

    private fun DnsTransportResult.toDiagnosticString(): String {
        return when (this) {
            is DnsTransportResult.Success -> {
                "success latencyMs=$latencyMs payloadBytes=${payload.size}"
            }
            is DnsTransportResult.Timeout -> "timeout"
            is DnsTransportResult.Error -> "error message=$message"
        }
    }

    private companion object {
        private const val TAG = "DnsForwarder"
    }
}
