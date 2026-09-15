package com.vmcsoft.aerodns.data.vpn.packet

object Ipv4UdpDnsPacketCodec {

    fun canFrameResponse(payloadSize: Int): Boolean =
        payloadSize in 0..(MAX_IPV4_PACKET_SIZE - MIN_IPV4_HEADER_SIZE - UDP_HEADER_SIZE)

    fun parseQuery(packet: ByteArray): Ipv4UdpDnsPacket? {
        if (packet.size < MIN_IPV4_HEADER_SIZE + UDP_HEADER_SIZE) return null

        val versionAndHeaderLength = packet[0].toInt() and 0xFF
        val version = versionAndHeaderLength ushr 4
        val headerLength = (versionAndHeaderLength and 0x0F) * 4
        if (version != IPV4_VERSION || headerLength < MIN_IPV4_HEADER_SIZE) return null
        if (packet.size < headerLength + UDP_HEADER_SIZE) return null

        val totalLength = readU16(packet, IPV4_TOTAL_LENGTH_OFFSET)
        if (totalLength < headerLength + UDP_HEADER_SIZE || totalLength > packet.size) return null

        val protocol = packet[IPV4_PROTOCOL_OFFSET].toInt() and 0xFF
        if (protocol != UDP_PROTOCOL) return null

        val flagsAndFragmentOffset = readU16(packet, IPV4_FLAGS_FRAGMENT_OFFSET)
        val hasMoreFragments = flagsAndFragmentOffset and IPV4_MORE_FRAGMENTS_FLAG != 0
        val fragmentOffset = flagsAndFragmentOffset and IPV4_FRAGMENT_OFFSET_MASK
        if (hasMoreFragments || fragmentOffset != 0) return null

        val udpOffset = headerLength
        val sourcePort = readU16(packet, udpOffset + UDP_SOURCE_PORT_OFFSET)
        val destinationPort = readU16(packet, udpOffset + UDP_DESTINATION_PORT_OFFSET)
        if (destinationPort != DNS_PORT) return null

        val udpLength = readU16(packet, udpOffset + UDP_LENGTH_OFFSET)
        if (udpLength < UDP_HEADER_SIZE || udpOffset + udpLength > totalLength) return null

        val payloadOffset = udpOffset + UDP_HEADER_SIZE
        val payloadLength = udpLength - UDP_HEADER_SIZE

        return Ipv4UdpDnsPacket(
            sourceAddress = readInt(packet, IPV4_SOURCE_ADDRESS_OFFSET),
            destinationAddress = readInt(packet, IPV4_DESTINATION_ADDRESS_OFFSET),
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            identification = readU16(packet, IPV4_IDENTIFICATION_OFFSET),
            payload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
        )
    }

    fun buildResponsePacket(
        query: Ipv4UdpDnsPacket,
        responsePayload: ByteArray
    ): ByteArray {
        val totalLength = MIN_IPV4_HEADER_SIZE + UDP_HEADER_SIZE + responsePayload.size
        require(totalLength <= MAX_IPV4_PACKET_SIZE) {
            "IPv4 UDP DNS response is too large: $totalLength bytes"
        }

        val packet = ByteArray(totalLength)

        packet[0] = IPV4_VERSION_IHL_NO_OPTIONS.toByte()
        packet[1] = 0
        writeU16(packet, IPV4_TOTAL_LENGTH_OFFSET, totalLength)
        writeU16(packet, IPV4_IDENTIFICATION_OFFSET, query.identification)
        writeU16(packet, IPV4_FLAGS_FRAGMENT_OFFSET, 0)
        packet[IPV4_TTL_OFFSET] = DEFAULT_TTL.toByte()
        packet[IPV4_PROTOCOL_OFFSET] = UDP_PROTOCOL.toByte()
        writeInt(packet, IPV4_SOURCE_ADDRESS_OFFSET, query.destinationAddress)
        writeInt(packet, IPV4_DESTINATION_ADDRESS_OFFSET, query.sourceAddress)

        val ipv4Checksum = calculateChecksum(packet, 0, MIN_IPV4_HEADER_SIZE)
        writeU16(packet, IPV4_CHECKSUM_OFFSET, ipv4Checksum)

        val udpOffset = MIN_IPV4_HEADER_SIZE
        val udpLength = UDP_HEADER_SIZE + responsePayload.size
        writeU16(packet, udpOffset + UDP_SOURCE_PORT_OFFSET, query.destinationPort)
        writeU16(packet, udpOffset + UDP_DESTINATION_PORT_OFFSET, query.sourcePort)
        writeU16(packet, udpOffset + UDP_LENGTH_OFFSET, udpLength)
        responsePayload.copyInto(packet, udpOffset + UDP_HEADER_SIZE)

        val udpChecksum = calculateUdpChecksum(
            packet = packet,
            sourceAddress = query.destinationAddress,
            destinationAddress = query.sourceAddress,
            udpOffset = udpOffset,
            udpLength = udpLength
        )
        writeU16(packet, udpOffset + UDP_CHECKSUM_OFFSET, udpChecksum)

        return packet
    }

    fun ipv4Address(a: Int, b: Int, c: Int, d: Int): Int {
        return ((a and 0xFF) shl 24) or
            ((b and 0xFF) shl 16) or
            ((c and 0xFF) shl 8) or
            (d and 0xFF)
    }

    private fun calculateUdpChecksum(
        packet: ByteArray,
        sourceAddress: Int,
        destinationAddress: Int,
        udpOffset: Int,
        udpLength: Int
    ): Int {
        var sum = 0L
        sum += (sourceAddress ushr 16) and 0xFFFF
        sum += sourceAddress and 0xFFFF
        sum += (destinationAddress ushr 16) and 0xFFFF
        sum += destinationAddress and 0xFFFF
        sum += UDP_PROTOCOL
        sum += udpLength
        sum += sumWords(packet, udpOffset, udpLength)
        return foldChecksum(sum)
    }

    private fun calculateChecksum(packet: ByteArray, offset: Int, length: Int): Int {
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

    private const val IPV4_VERSION = 4
    private const val IPV4_VERSION_IHL_NO_OPTIONS = 0x45
    private const val MIN_IPV4_HEADER_SIZE = 20
    private const val MAX_IPV4_PACKET_SIZE = 65_535
    private const val UDP_HEADER_SIZE = 8
    private const val UDP_PROTOCOL = 17
    private const val DNS_PORT = 53
    private const val DEFAULT_TTL = 64

    private const val IPV4_TOTAL_LENGTH_OFFSET = 2
    private const val IPV4_IDENTIFICATION_OFFSET = 4
    private const val IPV4_FLAGS_FRAGMENT_OFFSET = 6
    private const val IPV4_TTL_OFFSET = 8
    private const val IPV4_PROTOCOL_OFFSET = 9
    private const val IPV4_CHECKSUM_OFFSET = 10
    private const val IPV4_SOURCE_ADDRESS_OFFSET = 12
    private const val IPV4_DESTINATION_ADDRESS_OFFSET = 16

    private const val UDP_SOURCE_PORT_OFFSET = 0
    private const val UDP_DESTINATION_PORT_OFFSET = 2
    private const val UDP_LENGTH_OFFSET = 4
    private const val UDP_CHECKSUM_OFFSET = 6

    private const val IPV4_MORE_FRAGMENTS_FLAG = 0x2000
    private const val IPV4_FRAGMENT_OFFSET_MASK = 0x1FFF
}
