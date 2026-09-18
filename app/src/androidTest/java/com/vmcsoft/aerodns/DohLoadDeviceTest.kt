package com.vmcsoft.aerodns

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/** Uses only APIs shared with v1.5.2 so the identical driver can measure both APKs. */
@RunWith(AndroidJUnit4::class)
class DohLoadDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(ConnectivityManager::class.java)
    private val args get() = InstrumentationRegistry.getArguments()
    private val nextId = AtomicInteger(100)

    @Test fun measureControlledWorkload(): Unit = runBlocking {
        assumeTrue("Explicit owned emulator opt-in required", args.getString("ownedLoadEmulator") == "true")
        val qemu = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")).bufferedReader().use { it.readText().trim() }
        check(qemu == "1")
        val variant = requireNotNull(args.getString("loadVariant"))
        require(variant in listOf("baseline", "candidate"))
        val phase = requireNotNull(args.getString("loadPhase"))
        require(phase in listOf("steady", "mixed", "burst"))
        val seconds = args.getString("loadSeconds")?.toInt() ?: 60
        require(seconds in 5..300)
        val trial = args.getString("loadTrial") ?: "1"
        require(trial.matches(Regex("[a-zA-Z0-9_-]+")))
        assertNull(VpnService.prepare(context))
        assertFalse(hasVpn())
        val config = DnsConnectionConfig("load-$variant-$phase", "Load fixture", DnsProtocol.DOH,
            emptyList(), "https://localhost:18446/$variant/$phase/$trial", "127.0.0.1", true,
            connectionRequestId = "load-${System.nanoTime()}", enableExperimentalPacketLoop = true)
        val report = JSONObject().put("variant", variant).put("phase", phase).put("trial", trial)
            .put("configured_seconds", seconds).put("fixture_path", "/$variant/$phase/$trial")
        val file = File(context.filesDir, "load-$variant-$phase-$trial.json")
        try {
            context.startForegroundService(Intent(context, DnsVpnService::class.java).apply {
                action = DnsVpnService.ACTION_CONNECT
                putExtra(DnsVpnService.EXTRA_DNS_CONFIG, config)
            })
            val network = awaitNetwork()
            assertTrue("Fixture must answer before measuring", recover(network, 15000) >= 0)
            repeat(10) { assertTrue(probe(network, "warm", 2000)) }
            delay(2000)
            val result = withContext(Dispatchers.IO) { workload(network, phase, seconds) }
            report.put("workload", result)
            val recovery = withContext(Dispatchers.IO) { recover(network, 15000) }
            report.put("recovery_ms", recovery).put("vpn_retained", isVpn(network))
            delay(5000)
            report.put("resources_after_cooldown", resources())
            assertEquals("Every received reply must match its transaction and question", 0, result.getInt("invalid_replies"))
            assertTrue("Workload must not stop VPN", isVpn(network))
            if (variant == "candidate") {
                assertTrue("Fresh query must recover within 15 seconds", recovery >= 0)
                if (phase != "burst") {
                    assertTrue("Controlled non-overload workload requires >=99% replies",
                        result.getInt("received").toDouble() / result.getInt("sent") >= 0.99)
                    assertTrue("Fast-query p95 must stay below 1 second in this local fixture",
                        result.getDouble("fast_p95_ms") < 1000.0)
                }
            }
            report.put("measurement_completed", true)
        } catch (error: Throwable) {
            report.put("measurement_completed", false).put("error", error.toString())
            throw error
        } finally {
            context.startService(Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_DISCONNECT))
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (hasVpn() && SystemClock.elapsedRealtime() < deadline) delay(100)
            report.put("vpn_removed_on_disconnect", !hasVpn())
            file.writeText(report.toString(2))
            if (variant == "candidate") assertFalse("Candidate disconnect must remove VPN", hasVpn())
        }
    }

    private data class Pending(val sent: Long, val query: ByteArray, val fast: Boolean)

    private suspend fun workload(network: Network, phase: String, seconds: Int): JSONObject = coroutineScope {
        val count = if (phase == "burst") 96 else seconds * if (phase == "steady") 20 else 10
        val spacing = if (phase == "burst") 0L else if (phase == "steady") 50L else 100L
        val pending = ConcurrentHashMap<Int, Pending>()
        val latencies = Collections.synchronizedList(mutableListOf<Double>())
        val fastLatencies = Collections.synchronizedList(mutableListOf<Double>())
        val invalid = AtomicInteger()
        val samples = JSONArray()
        val before = resources()
        val start = SystemClock.elapsedRealtime()
        val cpuStart = Process.getElapsedCpuTime()
        var maxSendLag = 0L
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.connect(InetAddress.getByName("10.0.0.1"), 53)
            socket.soTimeout = 100
            val finish = start + (count - 1) * spacing + 6000
            val receiver = launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                while (isActive && SystemClock.elapsedRealtime() < finish) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val id = ((buffer[0].toInt() and 255) shl 8) or (buffer[1].toInt() and 255)
                    val item = pending.remove(id)
                    if (item == null || !validReply(item.query, buffer, packet.length)) invalid.incrementAndGet()
                    else {
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - item.sent) / 1_000_000.0
                        latencies += elapsed
                        if (item.fast) fastLatencies += elapsed
                    }
                }
            }
            val sampler = launch(Dispatchers.IO) {
                while (isActive) { samples.put(resources().put("elapsed_ms", SystemClock.elapsedRealtime() - start)); delay(2000) }
            }
            try {
                repeat(count) { i ->
                    val expected = start + i * spacing
                    val wait = expected - SystemClock.elapsedRealtime()
                    if (wait > 0) delay(wait)
                    maxSendLag = maxOf(maxSendLag, SystemClock.elapsedRealtime() - expected)
                    val slow = phase == "burst" || (phase == "mixed" && i % 20 == 0)
                    val kind = if (phase == "burst") "burst" else if (slow) "slow" else "fast"
                    val id = nextId.incrementAndGet()
                    val query = query(id, "$kind-$i.load.test")
                    pending[id] = Pending(SystemClock.elapsedRealtimeNanos(), query, !slow)
                    socket.send(DatagramPacket(query, query.size))
                }
                receiver.join()
            } finally { receiver.cancelAndJoin(); sampler.cancelAndJoin() }
        }
        fun percentile(values: List<Double>, p: Double): Any {
            val sorted = values.sorted()
            return if (sorted.isEmpty()) JSONObject.NULL else sorted[(ceil(sorted.size * p).toInt() - 1).coerceAtLeast(0)]
        }
        JSONObject().put("sent", count).put("received", latencies.size).put("missing", pending.size)
            .put("invalid_replies", invalid.get()).put("elapsed_ms", SystemClock.elapsedRealtime() - start)
            .put("cpu_ms", Process.getElapsedCpuTime() - cpuStart).put("max_send_lag_ms", maxSendLag)
            .put("p50_ms", percentile(latencies, 0.5)).put("p95_ms", percentile(latencies, 0.95)).put("p99_ms", percentile(latencies, 0.99))
            .put("fast_p95_ms", percentile(fastLatencies, 0.95)).put("resources_before", before)
            .put("resources_samples", samples).put("resources_after", resources())
    }

    private fun query(id: Int, name: String): ByteArray {
        val bytes = mutableListOf<Byte>((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        name.split('.').forEach { label -> bytes += label.length.toByte(); bytes.addAll(label.toByteArray().toList()) }
        bytes.addAll(listOf<Byte>(0, 0, 1, 0, 1))
        return bytes.toByteArray()
    }
    private fun validReply(query: ByteArray, reply: ByteArray, size: Int): Boolean =
        size == query.size + 16 && reply[0] == query[0] && reply[1] == query[1] &&
            reply[2] == 0x81.toByte() && reply[3] == 0x80.toByte() && reply[7] == 1.toByte() &&
            query.copyOfRange(12, query.size).contentEquals(reply.copyOfRange(12, query.size)) &&
            reply.copyOfRange(size - 4, size).contentEquals(byteArrayOf(192.toByte(), 0, 2, 42))
    private fun probe(network: Network, label: String, timeout: Int): Boolean = DatagramSocket().use { socket ->
        network.bindSocket(socket)
        socket.connect(InetAddress.getByName("10.0.0.1"), 53)
        socket.soTimeout = timeout
        val query = query(nextId.incrementAndGet(), "$label.load.test")
        socket.send(DatagramPacket(query, query.size))
        val reply = DatagramPacket(ByteArray(4096), 4096)
        try { socket.receive(reply); validReply(query, reply.data, reply.length) } catch (_: SocketTimeoutException) { false }
    }
    private suspend fun recover(network: Network, timeout: Long): Long {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeout) {
            if (probe(network, "recovery", 500)) return SystemClock.elapsedRealtime() - start
            delay(100)
        }
        return -1
    }
    private fun resources(): JSONObject {
        val status = File("/proc/self/status").readLines()
        fun value(key: String) = status.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
        return JSONObject().put("rss_kb", value("VmRSS")).put("pss_kb", Debug.getPss())
            .put("threads", value("Threads")).put("fds", File("/proc/self/fd").list()?.size)
    }
    private suspend fun awaitNetwork(): Network {
        var result: Network? = null
        withTimeout(15000) {
            while (result == null) {
                result = manager.activeNetwork?.takeIf { isVpn(it) && manager.getLinkProperties(it)?.dnsServers?.any { ip -> ip.hostAddress == "10.0.0.1" } == true }
                if (result == null) delay(100)
            }
        }
        return requireNotNull(result)
    }
    private fun isVpn(network: Network) = manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    @Suppress("DEPRECATION") private fun hasVpn() = manager.allNetworks.any(::isVpn)
}
