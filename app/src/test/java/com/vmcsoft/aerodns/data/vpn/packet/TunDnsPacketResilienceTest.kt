package com.vmcsoft.aerodns.data.vpn.packet

import android.util.Log
import android.system.ErrnoException
import android.system.OsConstants
import com.vmcsoft.aerodns.data.dns.DnsForwarder
import com.vmcsoft.aerodns.data.dns.DnsTransportResult
import com.vmcsoft.aerodns.data.dns.DnsWireMessage
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

class TunDnsPacketResilienceTest {
    private val config = DnsConnectionConfig("custom", "Custom", DnsProtocol.DOH, emptyList(),
        dohUrl = "https://resolver.example/dns-query", enableExperimentalPacketLoop = true)
    private val query = DnsWireMessage.buildAQuery(0x1234, "example.com")
    private val response = query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() }
    private val forwarder = mockk<DnsForwarder>()
    private val loop = TunDnsPacketLoop(TunDnsPacketHandler(forwarder))

    @Before fun mockAndroidLogging() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
    }

    @After fun restoreAndroidLogging() = unmockkStatic(Log::class)

    @Test fun `oversized reply cannot stop the next packet`() = runTest {
        coEvery { forwarder.forward(any(), any(), any()) } returnsMany listOf(
            DnsTransportResult.Success(response.copyOf(65_535), 1), DnsTransportResult.Success(response, 1))
        val output = ByteArrayOutputStream()
        loop.run(Packets(packet(query), packet(query)), output, config, 1500, 1000)
        assertEquals(28 + response.size, output.size())
        assertArrayEquals(response, output.toByteArray().copyOfRange(28, output.size()))
        coVerify(exactly = 2) { forwarder.forward(any(), any(), any()) }
    }

    @Test fun `IPv4 framing boundary permits exact packet and rejects one byte over`() = runTest {
        coEvery { forwarder.forward(any(), any(), any()) } returnsMany listOf(
            DnsTransportResult.Success(response.copyOf(65_508), 1),
            DnsTransportResult.Success(response.copyOf(65_507), 1))
        val output = ByteArrayOutputStream()
        loop.run(Packets(packet(query), packet(query)), output, config, 1500, 1000)
        assertEquals(65_535, output.size())
        assertEquals(65_535, ByteBuffer.wrap(output.toByteArray()).getShort(2).toInt() and 0xffff)
    }

    @Test fun `short mismatched and non response headers are isolated`() = runTest {
        val invalid = listOf(response.copyOf(11), response.copyOf().apply { this[0] = 9 },
            query, response.copyOf().apply { this[2] = 0x89.toByte() })
        coEvery { forwarder.forward(any(), any(), any()) } returnsMany
            (invalid + listOf(response)).map { DnsTransportResult.Success(it, 1) }
        val output = ByteArrayOutputStream()
        loop.run(Packets(*Array(invalid.size + 1) { packet(query) }), output, config, 1500, 1000)
        assertEquals(28 + response.size, output.size())
        assertArrayEquals(response, output.toByteArray().copyOfRange(28, output.size()))
    }

    @Test fun `DNS error and truncated flags pass through unchanged`() = runTest {
        for (reply in listOf(response.copyOf().apply { this[3] = 0x83.toByte() },
            response.copyOf().apply { this[2] = 0x83.toByte() })) {
            coEvery { forwarder.forward(any(), any(), any()) } returns DnsTransportResult.Success(reply, 1)
            val output = ByteArrayOutputStream()
            loop.run(Packets(packet(query)), output, config, 1500, 1000)
            assertArrayEquals(reply, output.toByteArray().copyOfRange(28, output.size()))
        }
    }

    @Test fun `malformed query headers never reach an upstream`() = runTest {
        val output = ByteArrayOutputStream()
        loop.run(Packets(packet(query.copyOf(11)), packet(response)), output, config, 1500, 1000)
        assertEquals(0, output.size())
        coVerify(exactly = 0) { forwarder.forward(any(), any(), any()) }
    }

    @Test fun `read fault retains its cause for service failure reporting`() = runTest {
        val failure = IOException("injected TUN read failure")
        val input = object : InputStream() {
            override fun read(): Int = throw failure
        }
        val result = runCatching { loop.run(input, ByteArrayOutputStream(), config, 1500, 1000) }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test fun `write fault terminates before consuming another query`() = runTest {
        val failure = IOException("injected TUN write failure")
        val output = object : OutputStream() { override fun write(value: Int) { throw failure } }
        coEvery { forwarder.forward(any(), any(), any()) } returns DnsTransportResult.Success(response, 1)
        val result = runCatching { loop.run(Packets(packet(query), packet(query)), output, config, 1500, 1000) }
        assertSame(failure, result.exceptionOrNull())
        coVerify(exactly = 1) { forwarder.forward(any(), any(), any()) }
    }

    @Test fun `cancelled connection cannot write a late upstream response`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { forwarder.forward(any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { entered.complete(Unit); finish.await() }
            DnsTransportResult.Success(response, 1)
        }
        val output = ByteArrayOutputStream()
        val job = launch { loop.run(Packets(packet(query)), output, config, 1500, 1000) }
        entered.await()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, output.size())
    }

    @Test fun `buffer rejection drops one reply and permits the next write`() = runTest {
        // Android's exception constructor is unavailable on the host JVM; populate
        // its public errno field on a test instance instead.
        val cause = mockk<ErrnoException>()
        ErrnoException::class.java.getField("errno").setInt(cause, OsConstants.ENOBUFS)
        var writes = 0
        val delivered = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) = error("Use packet writes")
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (writes++ == 0) throw IOException("packet rejected", cause)
                delivered.write(bytes, offset, length)
            }
        }
        coEvery { forwarder.forward(any(), any(), any()) } returns DnsTransportResult.Success(response, 1)
        loop.run(Packets(packet(query), packet(query)), output, config, 1500, 1000)
        assertEquals(2, writes)
        assertArrayEquals(response, delivered.toByteArray().copyOfRange(28, delivered.size()))
    }

    @Test fun `IPv4 UDP payload boundary accounts for both headers`() {
        assertTrue(Ipv4UdpDnsPacketCodec.canFrameResponse(65_507))
        assertFalse(Ipv4UdpDnsPacketCodec.canFrameResponse(65_508))
        assertFalse(Ipv4UdpDnsPacketCodec.canFrameResponse(-1))
    }

    private fun packet(payload: ByteArray): ByteArray = ByteBuffer.allocate(28 + payload.size).apply {
        put(0, 0x45.toByte()); putShort(2, capacity().toShort()); put(8, 64.toByte()); put(9, 17.toByte())
        putInt(12, 0x0a000002); putInt(16, 0x0a000001)
        putShort(20, 40_000.toShort()); putShort(22, 53.toShort()); putShort(24, (8 + payload.size).toShort())
        position(28); put(payload)
    }.array()

    // TUN read boundaries are packets, not a concatenated byte stream.
    private class Packets(vararg packets: ByteArray) : InputStream() {
        private val iterator = packets.iterator()
        override fun read(): Int = error("Use packet reads")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!iterator.hasNext()) return -1
            val packet = iterator.next()
            check(packet.size <= length)
            packet.copyInto(buffer, offset)
            return packet.size
        }
    }
}
