package com.vmcsoft.aerodns.data.dns

import org.junit.Assert.*
import org.junit.Test

class DnsHealthResponseTest {
    private val query = DnsWireMessage.buildAQuery(42, "example.com")
    private fun reply() = query.copyOf().apply {
        this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1
    } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93, 184.toByte(), 216.toByte(), 34)

    @Test fun `complete matching compressed A answer is healthy`() {
        assertTrue(DnsHealthResponse.isHealthy(query, reply()))
    }
    @Test fun `every truncated prefix is rejected`() {
        val reply = reply()
        for (size in reply.indices) assertFalse("size=$size", DnsHealthResponse.isHealthy(query, reply.copyOf(size)))
    }
    @Test fun `wrong ID question opcode and query are rejected`() {
        for (index in listOf(0, 2, 13)) {
            val wrong = reply().apply { this[index] = (this[index].toInt() xor if (index == 2) 8 else 1).toByte() }
            assertFalse(DnsHealthResponse.isHealthy(query, wrong))
        }
        assertFalse(DnsHealthResponse.isHealthy(query, query))
    }
    @Test fun `DNS errors truncation and empty success do not prove health`() {
        for (rcode in 1..15) assertFalse(DnsHealthResponse.isHealthy(query, reply().apply { this[3] = (0x80 or rcode).toByte() }))
        assertFalse(DnsHealthResponse.isHealthy(query, reply().apply { this[2] = 0x83.toByte() }))
        assertFalse(DnsHealthResponse.isHealthy(query, reply().copyOf(query.size).apply { this[7] = 0 }))
    }
    @Test fun `malformed records counts and compression cannot prove health`() {
        for ((index, value) in listOf(4 to 1, 7 to 2, 11 to 1, query.size + 1 to query.size,
            query.size + 10 to 1, query.size + 11 to 3)) {
            assertFalse("index=$index", DnsHealthResponse.isHealthy(query, reply().apply { this[index] = value.toByte() }))
        }
        assertFalse(DnsHealthResponse.isHealthy(query, reply() + byteArrayOf(0)))
    }
    @Test fun `TXT answer is not an A result`() {
        assertFalse(DnsHealthResponse.isHealthy(query, reply().apply { this[query.size + 3] = 16 }))
    }
    @Test fun `unrelated answer owner does not prove a result for the question`() {
        val unrelated = reply().copyOf(query.size) + byteArrayOf(1, 'x'.code.toByte(), 0) + reply().copyOfRange(query.size + 2, reply().size)
        assertFalse(DnsHealthResponse.isHealthy(query, unrelated))
    }
    @Test fun `CNAME chain may resolve to the returned A record`() {
        val header = reply().copyOf(query.size).apply { this[7] = 2 }
        val alias = byteArrayOf(0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 4, 1, 'x'.code.toByte(), 0xc0.toByte(), 12)
        val address = byteArrayOf(1, 'x'.code.toByte(), 0xc0.toByte(), 12) + reply().copyOfRange(query.size + 2, reply().size)
        assertTrue(DnsHealthResponse.isHealthy(query, header + alias + address))
        assertFalse(DnsHealthResponse.isHealthy(query, header + alias.copyOf().apply { this[11] = 3 } + address))
    }

}
