package com.vmcsoft.aerodns.data.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsWireMessageTest {

    @Test
    fun `buildAQuery writes DNS header and qname`() {
        val query = DnsWireMessage.buildAQuery(0x1234, "example.com")

        assertArrayEquals(
            byteArrayOf(
                0x12, 0x34, // ID
                0x01, 0x00, // recursion desired
                0x00, 0x01, // one question
                0x00, 0x00, // no answers
                0x00, 0x00, // no authority records
                0x00, 0x00  // no additional records
            ),
            query.copyOfRange(0, 12)
        )
        assertArrayEquals(
            byteArrayOf(
                7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
                'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                0,
                0x00, 0x01, // A
                0x00, 0x01  // IN
            ),
            query.copyOfRange(12, query.size)
        )
    }

    @Test
    fun `isSuccessfulResponse accepts matching no-error response`() {
        val response = successfulResponse(0xBEEF)

        assertTrue(DnsWireMessage.isSuccessfulResponse(response, response.size, 0xBEEF))
    }

    @Test
    fun `isSuccessfulResponse rejects wrong transaction id`() {
        val response = successfulResponse(0xBEEF)

        assertFalse(DnsWireMessage.isSuccessfulResponse(response, response.size, 0xCAFE))
    }

    @Test
    fun `isSuccessfulResponse rejects DNS error response`() {
        val response = successfulResponse(0xBEEF)
        response[3] = 0x83.toByte() // RCODE 3, NXDOMAIN

        assertFalse(DnsWireMessage.isSuccessfulResponse(response, response.size, 0xBEEF))
    }

    @Test
    fun `isTruncatedResponse detects truncated response flag`() {
        val response = successfulResponse(0xBEEF)
        response[2] = (response[2].toInt() or 0x02).toByte()

        assertTrue(DnsWireMessage.isTruncatedResponse(response))
    }

    @Test
    fun `isTruncatedResponse returns false when truncated flag is absent`() {
        val response = successfulResponse(0xBEEF)

        assertFalse(DnsWireMessage.isTruncatedResponse(response))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildAQuery rejects empty names`() {
        DnsWireMessage.buildAQuery(1, ".")
    }

    private fun successfulResponse(queryId: Int): ByteArray {
        return ByteArray(12).also { response ->
            response[0] = ((queryId ushr 8) and 0xFF).toByte()
            response[1] = (queryId and 0xFF).toByte()
            response[2] = 0x81.toByte()
            response[3] = 0x80.toByte()
        }
    }
}
