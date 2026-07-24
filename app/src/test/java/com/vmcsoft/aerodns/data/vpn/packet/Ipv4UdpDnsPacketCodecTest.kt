package com.vmcsoft.aerodns.data.vpn.packet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4UdpDnsPacketCodecTest {

    @Test
    fun `parseQuery parses IPv4 UDP DNS query`() {
        val payload = byteArrayOf(0x12, 0x34, 0x01, 0x00)
        val packet = buildIpv4UdpPacket(
            sourceAddress = CLIENT_ADDRESS,
            destinationAddress = DNS_ADDRESS,
            sourcePort = 40_000,
            destinationPort = 53,
            identification = 0x1234,
            payload = payload
        )

        val parsed = Ipv4UdpDnsPacketCodec.parseQuery(packet)

        assertNotNull(parsed)
        parsed!!
        assertEquals(CLIENT_ADDRESS, parsed.sourceAddress)
        assertEquals(DNS_ADDRESS, parsed.destinationAddress)
        assertEquals(40_000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertEquals(0x1234, parsed.identification)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `parseQuery rejects non-DNS UDP destination port`() {
        val packet = buildIpv4UdpPacket(
            sourceAddress = CLIENT_ADDRESS,
            destinationAddress = DNS_ADDRESS,
            sourcePort = 40_000,
            destinationPort = 123,
            identification = 0x1234,
            payload = byteArrayOf(1, 2, 3)
        )

        assertNull(Ipv4UdpDnsPacketCodec.parseQuery(packet))
    }

    @Test
    fun `parseQuery rejects non-UDP packets`() {
        val packet = buildIpv4UdpPacket(
            sourceAddress = CLIENT_ADDRESS,
            destinationAddress = DNS_ADDRESS,
            sourcePort = 40_000,
            destinationPort = 53,
            identification = 0x1234,
            payload = byteArrayOf(1, 2, 3)
        )
        packet[9] = 6 // TCP

        assertNull(Ipv4UdpDnsPacketCodec.parseQuery(packet))
    }

    @Test
    fun `parseQuery rejects fragmented packets`() {
        val packet = buildIpv4UdpPacket(
            sourceAddress = CLIENT_ADDRESS,
            destinationAddress = DNS_ADDRESS,
            sourcePort = 40_000,
            destinationPort = 53,
            identification = 0x1234,
            payload = byteArrayOf(1, 2, 3)
        )
        writeU16(packet, 6, 0x2000) // More fragments

        assertNull(Ipv4UdpDnsPacketCodec.parseQuery(packet))
    }

    @Test
    fun `buildResponsePacket swaps addresses and ports`() {
        val query = Ipv4UdpDnsPacket(
            sourceAddress = CLIENT_ADDRESS,
            destinationAddress = DNS_ADDRESS,
            sourcePort = 40_000,
            destinationPort = 53,
            identification = 0x1234,
            payload = byteArrayOf(1, 2, 3)
        )
        val responsePayload = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte())

        val response = Ipv4UdpDnsPacketCodec.buildResponsePacket(query, responsePayload)

        assertEquals(0x45, response[0].toInt() and 0xFF)
        assertEquals(response.size, readU16(response, 2))
        assertEquals(0x1234, readU16(response, 4))
        assertEquals(17, response[9].toInt() and 0xFF)
        assertEquals(DNS_ADDRESS, readInt(response, 12))
        assertEquals(CLIENT_ADDRESS, readInt(response, 16))
        assertEquals(53, readU16(response, 20))
        assertEquals(40_000, readU16(response, 22))
        assertEquals(8 + responsePayload.size, readU16(response, 24))
        assertArrayEquals(responsePayload, response.copyOfRange(28, response.size))
    }

    @Test
    fun `buildResponsePacket writes valid IPv4 and UDP checksums`() {
        val query = Ipv4UdpDnsPacket(
            sourceAddress = CLIENT_ADDRESS,
            destinationAddress = DNS_ADDRESS,
            sourcePort = 40_000,
            destinationPort = 53,
            identification = 0x1234,
            payload = byteArrayOf(1, 2, 3)
        )

        val response = Ipv4UdpDnsPacketCodec.buildResponsePacket(
            query = query,
            responsePayload = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte())
        )

        assertEquals(0, checksum(response, 0, 20))
        assertTrue(readU16(response, 26) != 0)
        assertEquals(0, udpChecksum(response))
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

    private fun udpChecksum(packet: ByteArray): Int {
        val udpLength = readU16(packet, 24)
        var sum = 0L
        val sourceAddress = readInt(packet, 12)
        val destinationAddress = readInt(packet, 16)
        sum += (sourceAddress ushr 16) and 0xFFFF
        sum += sourceAddress and 0xFFFF
        sum += (destinationAddress ushr 16) and 0xFFFF
        sum += destinationAddress and 0xFFFF
        sum += 17
        sum += udpLength
        sum += sumWords(packet, 20, udpLength)
        return foldChecksum(sum)
    }

    private fun checksum(packet: ByteArray, offset: Int, length: Int): Int {
        return foldChecksum(sumWords(packet, offset, length))
    }

    private fun sumWords(packet: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += readU16(packet, index)
            index += 2
        }
        if (index < end) {
            sum += (packet[index].toInt() and 0xFF) shl 8
        }
        return sum
    }

    private fun foldChecksum(initialSum: Long): Int {
        var sum = initialSum
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
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
