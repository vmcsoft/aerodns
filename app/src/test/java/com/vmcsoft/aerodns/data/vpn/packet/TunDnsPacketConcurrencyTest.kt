package com.vmcsoft.aerodns.data.vpn.packet

import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TunDnsPacketConcurrencyTest {
    private val handler = mockk<TunDnsPacketHandler>()
    private val loop = TunDnsPacketLoop(handler)
    private val config = DnsConnectionConfig("test", "Test", DnsProtocol.DOH, emptyList(),
        dohUrl = "https://resolver.example/dns-query", enableExperimentalPacketLoop = true)

    @Test fun `fast query completes before an earlier slow query`() = runTest {
        coEvery { handler.handlePacket(any(), config, any()) } coAnswers {
            val packet = firstArg<ByteArray>()
            if (packet[0].toInt() == 0) delay(900)
            packet
        }
        val output = ByteArrayOutputStream()
        loop.run(Packets(2), output, config, 1500, 1000)
        assertArrayEquals(byteArrayOf(1, 0), output.toByteArray())
    }

    @Test fun `burst never exceeds worker and queue bounds and drops newest`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        val accepted = mutableListOf<Int>()
        coEvery { handler.handlePacket(any(), config, any()) } coAnswers {
            active++
            maximum = maxOf(maximum, active)
            accepted += firstArg<ByteArray>()[0].toInt()
            gate.await()
            active--
            firstArg<ByteArray>()
        }
        val output = ByteArrayOutputStream()
        val job = launch { loop.run(Packets(100), output, config, 1500, 1000) }
        runCurrent()
        assertEquals(TunDnsPacketLoop.WORKER_COUNT, active)
        gate.complete(Unit)
        job.join()
        assertEquals(TunDnsPacketLoop.WORKER_COUNT, maximum)
        assertEquals((0 until TunDnsPacketLoop.WORKER_COUNT + TunDnsPacketLoop.QUEUE_CAPACITY).toList(), accepted)
        assertEquals(accepted.size, output.size())
    }

    @Test fun `expired queued packets never start upstream work`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        coEvery { handler.handlePacket(any(), config, any()) } coAnswers {
            started++
            gate.await()
            firstArg<ByteArray>()
        }
        val job = launch { loop.run(Packets(20), ByteArrayOutputStream(), config, 1500, 100) }
        runCurrent()
        assertEquals(TunDnsPacketLoop.WORKER_COUNT, started)
        // The packet admission deadline uses monotonic real time, not the test scheduler.
        Thread.sleep(150)
        gate.complete(Unit)
        job.join()
        assertEquals(TunDnsPacketLoop.WORKER_COUNT, started)
    }

    @Test fun `cancellation discards queued packets and late replies from every worker`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        coEvery { handler.handlePacket(any(), config, any()) } coAnswers {
            started++
            withContext(NonCancellable) { gate.await() }
            firstArg<ByteArray>()
        }
        val output = ByteArrayOutputStream()
        val job = launch { loop.run(Packets(100), output, config, 1500, 1000) }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertEquals(TunDnsPacketLoop.WORKER_COUNT, started)
        assertEquals(0, output.size())
    }

    @Test fun `parallel workers never overlap packet writes`() = runTest {
        val entries = java.util.concurrent.atomic.AtomicInteger()
        val overlaps = java.util.concurrent.atomic.AtomicInteger()
        val writes = java.util.concurrent.atomic.AtomicInteger()
        coEvery { handler.handlePacket(any(), config, any()) } coAnswers { firstArg<ByteArray>() }
        val output = object : OutputStream() {
            override fun write(value: Int) {
                if (entries.incrementAndGet() != 1) overlaps.incrementAndGet()
                Thread.sleep(5)
                writes.incrementAndGet()
                entries.decrementAndGet()
            }
        }
        withContext(Dispatchers.IO) { loop.run(Packets(20), output, config, 1500, 1000) }
        assertEquals(20, writes.get())
        assertEquals(0, overlaps.get())
    }

    @Test fun `worker write failure cancels and unblocks the reader`() = runTest {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val input = object : InputStream() {
            var first = true
            override fun read(): Int = error("packet read expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) { first = false; buffer[offset] = 1; return 1 }
                reading.countDown()
                check(closed.await(3, TimeUnit.SECONDS)) { "Reader was not cancelled" }
                throw IOException("Reader closed")
            }
            override fun close() { closed.countDown() }
        }
        val failure = IOException("Writer failed")
        coEvery { handler.handlePacket(any(), config, any()) } coAnswers {
            check(reading.await(3, TimeUnit.SECONDS))
            byteArrayOf(1)
        }
        val output = object : OutputStream() { override fun write(value: Int) { throw failure } }
        val result = runCatching { withContext(Dispatchers.IO) { loop.run(input, output, config, 1500, 1000) } }
        // Coroutine stack-trace recovery may copy IOException, retaining the original cause.
        assertTrue(generateSequence(result.exceptionOrNull()) { it.cause }.any { it === failure })
        assertEquals(0L, closed.count)
    }

    private class Packets(private val count: Int) : InputStream() {
        private var next = 0
        override fun read(): Int = error("packet read expected")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (next == count) return -1
            buffer[offset] = (next++).toByte()
            return 1
        }
    }
}
