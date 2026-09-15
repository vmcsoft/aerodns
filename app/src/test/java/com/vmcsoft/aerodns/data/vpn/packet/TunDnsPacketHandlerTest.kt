package com.vmcsoft.aerodns.data.vpn.packet

import com.vmcsoft.aerodns.data.dns.DnsForwarder
import com.vmcsoft.aerodns.data.dns.DnsTransportResult
import com.vmcsoft.aerodns.data.dns.TcpDnsTransport
import com.vmcsoft.aerodns.data.dns.UdpDnsTransport
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TunDnsPacketHandlerTest {

    @Test
    fun `handlePacket forwards DNS payload and returns response packet`() = runTest {
        val queryPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00).copyOf(12)
        val responsePayload = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte()).copyOf(12)
        val requestPacket = buildIpv4UdpPacket(
            sourceAddress = CLIENT_ADDRESS,
            destinationAddress = DNS_ADDRESS,
            sourcePort = 40_000,
            destinationPort = 53,
            identification = 0x1234,
            payload = queryPayload
        )
        val udpTransport = mockk<UdpDnsTransport>()
        coEvery { udpTransport.query(queryPayload, "1.1.1.1", 1000) } returns DnsTransportResult.Success(
            payload = responsePayload,
            latencyMs = 12
        )

        val responsePacket = TunDnsPacketHandler(DnsForwarder(udpTransport, mockk<TcpDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DohDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DotDnsTransport>())).handlePacket(
            packet = requestPacket,
            config = standardConfig(),
            timeoutMs = 1000
        )

        assertNotNull(responsePacket)
        responsePacket!!
        assertEquals(DNS_ADDRESS, readInt(responsePacket, 12))
        assertEquals(CLIENT_ADDRESS, readInt(responsePacket, 16))
        assertEquals(53, readU16(responsePacket, 20))
        assertEquals(40_000, readU16(responsePacket, 22))
        assertArrayEquals(responsePayload, responsePacket.copyOfRange(28, responsePacket.size))
    }

    @Test
    fun `handlePacket ignores non-DNS packets`() = runTest {
        val queryPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00).copyOf(12)
        val requestPacket = buildIpv4UdpPacket(
            sourceAddress = CLIENT_ADDRESS,
            destinationAddress = DNS_ADDRESS,
            sourcePort = 40_000,
            destinationPort = 123,
            identification = 0x1234,
            payload = queryPayload
        )
        val udpTransport = mockk<UdpDnsTransport>()

        val responsePacket = TunDnsPacketHandler(DnsForwarder(udpTransport, mockk<TcpDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DohDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DotDnsTransport>())).handlePacket(
            packet = requestPacket,
            config = standardConfig(),
            timeoutMs = 1000
        )

        assertNull(responsePacket)
        coVerify(exactly = 0) { udpTransport.query(any(), any(), any()) }
    }

    @Test
    fun `handlePacket returns null when forwarding fails`() = runTest {
        val queryPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00).copyOf(12)
        val requestPacket = buildIpv4UdpPacket(
            sourceAddress = CLIENT_ADDRESS,
            destinationAddress = DNS_ADDRESS,
            sourcePort = 40_000,
            destinationPort = 53,
            identification = 0x1234,
            payload = queryPayload
        )
        val udpTransport = mockk<UdpDnsTransport>()
        coEvery { udpTransport.query(queryPayload, "1.1.1.1", 1000) } returns DnsTransportResult.Timeout

        val responsePacket = TunDnsPacketHandler(DnsForwarder(udpTransport, mockk<TcpDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DohDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DotDnsTransport>())).handlePacket(
            packet = requestPacket,
            config = standardConfig(),
            timeoutMs = 1000
        )

        assertNull(responsePacket)
    }

    @Test
    fun `unframeable reply is dropped and the next query still succeeds`() = runTest {
        val query = ByteArray(12).apply { this[0] = 0x12; this[1] = 0x34; this[2] = 1 }
        val packet = buildIpv4UdpPacket(CLIENT_ADDRESS, DNS_ADDRESS, 40_000, 53, 1, query)
        val valid = query.copyOf().apply { this[2] = 0x81.toByte() }
        val forwarder = mockk<DnsForwarder>()
        coEvery { forwarder.forward(any(), any(), any()) } returnsMany listOf(
            DnsTransportResult.Success(valid.copyOf(65_535), 1),
            DnsTransportResult.Success(valid, 1)
        )
        val handler = TunDnsPacketHandler(forwarder)
        assertNull(handler.handlePacket(packet, standardConfig(), 1000))
        assertArrayEquals(valid, handler.handlePacket(packet, standardConfig(), 1000)!!.copyOfRange(28, 40))
    }

    private fun standardConfig(): DnsConnectionConfig {
        return DnsConnectionConfig(
            serverId = "cloudflare",
            displayName = "Cloudflare",
            protocol = DnsProtocol.STANDARD,
            upstreamAddresses = listOf("1.1.1.1")
        )
    }

    private fun buildIpv4UdpPacket(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        identification: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        writeU16(packet, 2, totalLength)
        writeU16(packet, 4, identification)
        packet[8] = 64
        packet[9] = 17
        writeInt(packet, 12, sourceAddress)
        writeInt(packet, 16, destinationAddress)
        writeU16(packet, 20, sourcePort)
        writeU16(packet, 22, destinationPort)
        writeU16(packet, 24, 8 + payload.size)
        payload.copyInto(packet, 28)
        return packet
    }

    private fun readU16(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 8) or
            (packet[offset + 1].toInt() and 0xFF)
    }

    private fun readInt(packet: ByteArray, offset: Int): Int {
        return ((packet[offset].toInt() and 0xFF) shl 24) or
            ((packet[offset + 1].toInt() and 0xFF) shl 16) or
            ((packet[offset + 2].toInt() and 0xFF) shl 8) or
            (packet[offset + 3].toInt() and 0xFF)
    }

    private fun writeU16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = ((value ushr 8) and 0xFF).toByte()
        packet[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeInt(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = ((value ushr 24) and 0xFF).toByte()
        packet[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        packet[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        packet[offset + 3] = (value and 0xFF).toByte()
    }

    private companion object {
        private val CLIENT_ADDRESS = Ipv4UdpDnsPacketCodec.ipv4Address(10, 0, 0, 2)
        private val DNS_ADDRESS = Ipv4UdpDnsPacketCodec.ipv4Address(1, 1, 1, 1)
    }
}
