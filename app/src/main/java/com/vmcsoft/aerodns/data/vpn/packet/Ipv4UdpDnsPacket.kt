package com.vmcsoft.aerodns.data.vpn.packet

data class Ipv4UdpDnsPacket(
    val sourceAddress: Int,
    val destinationAddress: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val identification: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ipv4UdpDnsPacket) return false

        return sourceAddress == other.sourceAddress &&
            destinationAddress == other.destinationAddress &&
            sourcePort == other.sourcePort &&
            destinationPort == other.destinationPort &&
            identification == other.identification &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sourceAddress
        result = 31 * result + destinationAddress
        result = 31 * result + sourcePort
        result = 31 * result + destinationPort
        result = 31 * result + identification
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
