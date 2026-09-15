package com.vmcsoft.aerodns.data.vpn.packet

import com.vmcsoft.aerodns.data.dns.DnsForwarder
import com.vmcsoft.aerodns.data.dns.DnsTransportResult
import com.vmcsoft.aerodns.data.dns.TcpDnsTransport
import com.vmcsoft.aerodns.data.dns.UdpDnsTransport
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TunDnsPacketLoopTest {

    @Test
    fun `run writes response for handled DNS packet`() = runTest {
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
            latencyMs = 10
        )
        val input = ByteArrayInputStream(requestPacket)
        val output = ByteArrayOutputStream()

        TunDnsPacketLoop(TunDnsPacketHandler(DnsForwarder(udpTransport, mockk<TcpDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DohDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DotDnsTransport>()))).run(
            input = input,
            output = output,
            config = standardConfig(),
            mtu = 1500,
            timeoutMs = 1000
        )

        val responsePacket = output.toByteArray()
        assertEquals(DNS_ADDRESS, readInt(responsePacket, 12))
        assertEquals(CLIENT_ADDRESS, readInt(responsePacket, 16))
        assertArrayEquals(responsePayload, responsePacket.copyOfRange(28, responsePacket.size))
    }

    @Test
    fun `run ignores unhandled packets`() = runTest {
        val requestPacket = buildIpv4UdpPacket(
            sourceAddress = CLIENT_ADDRESS,
            destinationAddress = DNS_ADDRESS,
            sourcePort = 40_000,
            destinationPort = 123,
            identification = 0x1234,
            payload = byteArrayOf(0x12, 0x34, 0x01, 0x00).copyOf(12)
        )
        val udpTransport = mockk<UdpDnsTransport>()
        val input = ByteArrayInputStream(requestPacket)
        val output = ByteArrayOutputStream()

        TunDnsPacketLoop(TunDnsPacketHandler(DnsForwarder(udpTransport, mockk<TcpDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DohDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DotDnsTransport>()))).run(
            input = input,
            output = output,
            config = standardConfig(),
            mtu = 1500,
            timeoutMs = 1000
        )

        assertEquals(0, output.size())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `run rejects non-positive mtu`() = runTest {
        val udpTransport = mockk<UdpDnsTransport>()

        TunDnsPacketLoop(TunDnsPacketHandler(DnsForwarder(udpTransport, mockk<TcpDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DohDnsTransport>(), mockk<com.vmcsoft.aerodns.data.dns.DotDnsTransport>()))).run(
            input = ByteArrayInputStream(ByteArray(0)),
            output = ByteArrayOutputStream(),
            config = standardConfig(),
            mtu = 0,
            timeoutMs = 1000
        )
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
