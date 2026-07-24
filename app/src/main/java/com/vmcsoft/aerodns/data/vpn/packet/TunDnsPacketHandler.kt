package com.vmcsoft.aerodns.data.vpn.packet

import com.vmcsoft.aerodns.data.diagnostics.DnsDiagnosticLog
import com.vmcsoft.aerodns.data.dns.DnsForwarder
import com.vmcsoft.aerodns.data.dns.DnsTransportResult
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TunDnsPacketHandler @Inject constructor(
    private val dnsForwarder: DnsForwarder
) {
    suspend fun handlePacket(
        packet: ByteArray,
        config: DnsConnectionConfig,
        timeoutMs: Int
    ): ByteArray? {
        val query = Ipv4UdpDnsPacketCodec.parseQuery(packet)
        if (query == null) {
            DnsDiagnosticLog.d(
                TAG,
                "packet_handler_ignored reason=${describeIgnoredPacket(packet)} bytes=${packet.size}"
            )
            return null
        }

        DnsDiagnosticLog.d(
            TAG,
            "packet_handler_query src=${formatIpv4(query.sourceAddress)}:${query.sourcePort} " +
                "dst=${formatIpv4(query.destinationAddress)}:${query.destinationPort} " +
                "txid=${readDnsTransactionId(query.payload)} payloadBytes=${query.payload.size} " +
                "protocol=${config.protocol}"
        )
        return when (val result = dnsForwarder.forward(query.payload, config, timeoutMs)) {
            is DnsTransportResult.Success -> {
                DnsDiagnosticLog.d(
                    TAG,
                    "packet_handler_forward_success txid=${readDnsTransactionId(query.payload)} " +
                        "responseBytes=${result.payload.size} latencyMs=${result.latencyMs}"
                )
                Ipv4UdpDnsPacketCodec.buildResponsePacket(query, result.payload)
            }
            is DnsTransportResult.Timeout -> {
                DnsDiagnosticLog.w(
                    TAG,
                    "packet_handler_forward_timeout txid=${readDnsTransactionId(query.payload)}"
                )
                null
            }
            is DnsTransportResult.Error -> {
                DnsDiagnosticLog.w(
                    TAG,
                    "packet_handler_forward_error txid=${readDnsTransactionId(query.payload)} " +
                        "message=${result.message}"
                )
                null
            }
        }
    }

    private fun describeIgnoredPacket(packet: ByteArray): String {
        if (packet.isEmpty()) return "empty"

        val version = (packet[0].toInt() and 0xFF) ushr 4
        if (version != IPV4_VERSION) return "unsupported_ip_version_$version"
        if (packet.size < MIN_IPV4_HEADER_SIZE) return "short_ipv4_header"

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < MIN_IPV4_HEADER_SIZE) return "invalid_ipv4_header_length_$headerLength"
        if (packet.size < headerLength + UDP_HEADER_SIZE) return "short_udp_header"

        val protocol = packet[IPV4_PROTOCOL_OFFSET].toInt() and 0xFF
        if (protocol != UDP_PROTOCOL) return "non_udp_protocol_$protocol"

        val destinationPort = readU16(packet, headerLength + UDP_DESTINATION_PORT_OFFSET)
        if (destinationPort != DNS_PORT) return "non_dns_udp_port_$destinationPort"

        return "invalid_ipv4_udp_dns_packet"
    }

    private fun formatIpv4(address: Int): String {
        return listOf(
            (address ushr 24) and 0xFF,
            (address ushr 16) and 0xFF,
            (address ushr 8) and 0xFF,
            address and 0xFF
        ).joinToString(".")
    }

    private fun readDnsTransactionId(payload: ByteArray): String {
        if (payload.size < 2) return "n/a"
        return "0x" + readU16(payload, 0).toString(16).padStart(4, '0')
    }

    private fun readU16(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 8) or
            (packet[offset + 1].toInt() and 0xFF)
    }

    private companion object {
        private const val TAG = "TunDnsPacketHandler"
        private const val IPV4_VERSION = 4
        private const val MIN_IPV4_HEADER_SIZE = 20
        private const val UDP_HEADER_SIZE = 8
        private const val UDP_PROTOCOL = 17
        private const val DNS_PORT = 53
        private const val IPV4_PROTOCOL_OFFSET = 9
        private const val UDP_DESTINATION_PORT_OFFSET = 2
    }
}
