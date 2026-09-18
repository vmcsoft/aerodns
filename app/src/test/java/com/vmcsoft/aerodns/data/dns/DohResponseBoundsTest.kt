package com.vmcsoft.aerodns.data.dns

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class DohResponseBoundsTest {
    @Test fun `declared oversized body is rejected before reading and closed`() = runBlocking {
        val body = CountingBody(1_000_000, 1_000_000)
        assertTrue(query(body) is DnsTransportResult.Error)
        assertEquals(0L, body.bytesRead)
        assertTrue(body.closed)
    }

    @Test fun `unknown length body stops after a bounded oversize probe and closes`() = runBlocking {
        val body = CountingBody(-1, 1_000_000)
        assertTrue(query(body) is DnsTransportResult.Error)
        // Allow one Okio segment of read-ahead, never the full remote body.
        assertTrue("Read ${body.bytesRead} bytes", body.bytesRead <= 65_536 + 8192)
        assertTrue(body.closed)
    }

    @Test fun `understated length cannot bypass streaming bound`() = runBlocking {
        val body = CountingBody(12, 1_000_000)
        assertTrue(query(body) is DnsTransportResult.Error)
        assertTrue("Read ${body.bytesRead} bytes", body.bytesRead <= 65_536 + 8192)
        assertTrue(body.closed)
    }

    @Test fun `exact DoH maximum remains available to callers outside TUN`() = runBlocking {
        for (length in listOf(-1L, 65_535L)) {
            val body = CountingBody(length, 65_535)
            val result = query(body)
            assertTrue(result.toString(), result is DnsTransportResult.Success)
            assertEquals(65_535, (result as DnsTransportResult.Success).payload.size)
            assertTrue(body.closed)
        }
    }

    @Test fun `empty and mismatched declared lengths are rejected`() = runBlocking {
        for (body in listOf(CountingBody(-1, 0), CountingBody(20, 12), CountingBody(8, 12))) {
            assertTrue(query(body) is DnsTransportResult.Error)
            assertTrue(body.closed)
        }
    }

    @Test fun `non DNS media type is rejected without consuming its body`() = runBlocking {
        val body = CountingBody(-1, 1_000_000, "text/html")
        assertTrue(query(body) is DnsTransportResult.Error)
        assertEquals(0L, body.bytesRead)
        assertTrue(body.closed)
    }

    private suspend fun query(body: ResponseBody): DnsTransportResult {
        val factory = Call.Factory { request ->
            mockk<Call> {
                every { execute() } returns Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body).build()
            }
        }
        val transport = DohDnsTransport(DohEndpointResolver(mockk())).apply {
            callFactoryBuilder = { _, _, _ -> factory }
        }
        return transport.query(byteArrayOf(1), "https://resolver.example/dns-query", listOf("192.0.2.1"), 1000)
    }

    private class CountingBody(
        private val declaredLength: Long,
        private val actualLength: Long,
        private val mediaType: String = "application/dns-message"
    ) : ResponseBody() {
        var bytesRead = 0L
        var closed = false
        private val stream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (bytesRead == actualLength) return -1
                val count = minOf(byteCount, actualLength - bytesRead, 8192).toInt()
                sink.write(ByteArray(count))
                bytesRead += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        override fun contentType() = mediaType.toMediaType()
        override fun contentLength() = declaredLength
        override fun source() = stream
    }
}
