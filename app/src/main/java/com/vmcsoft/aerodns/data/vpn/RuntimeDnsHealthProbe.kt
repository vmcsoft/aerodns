package com.vmcsoft.aerodns.data.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.vmcsoft.aerodns.data.dns.DnsHealthResponse
import com.vmcsoft.aerodns.data.dns.DnsWireMessage
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Sends wire DNS through the established VPN; never protect this probe socket from that VPN. */
internal class RuntimeDnsHealthProbe(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    suspend fun check(config: DnsConnectionConfig): DnsHealth = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + 5_000_000_000L
        val destinations = if (config.enableExperimentalPacketLoop) listOf("10.0.0.1") else config.upstreamAddresses
        try {
            readiness@ while (remainingMillis(deadline) > 0) {
                val network = findVpn(destinations)
                if (network == null) {
                    delay(50)
                    continue
                }
                for ((index, address) in destinations.withIndex()) {
                    val remaining = remainingMillis(deadline)
                    if (remaining <= 0) break
                    val query = DnsWireMessage.buildAQuery(System.nanoTime().toInt(), "example.com")
                    val start = System.nanoTime()
                    try {
                        val reply = exchange(network, address, query, maxOf(1, remaining / (destinations.size - index)))
                        if (DnsHealthResponse.isHealthy(query, reply)) {
                            return@withContext DnsHealth.Healthy(System.currentTimeMillis(), (System.nanoTime() - start) / 1_000_000)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: VpnNetworkNotReady) {
                        // Link properties may appear before netd installs this UID's routing.
                        // Reacquire the active VPN; no packet has been sent yet.
                        delay(50)
                        continue@readiness
                    }
                    catch (_: java.io.IOException) { /* Try only another address in the selected configuration. */ }
                }
                break
            }
            DnsHealth.Unhealthy(System.currentTimeMillis())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { DnsHealth.Unhealthy(System.currentTimeMillis()) }
    }

    private fun findVpn(destinations: List<String>): Network? {
        val network = connectivity.activeNetwork ?: return null
        val properties = connectivity.getLinkProperties(network) ?: return null
        return network.takeIf {
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                properties.linkAddresses.any { it.address.hostAddress == "10.0.0.2" } &&
                properties.dnsServers.map { it.hostAddress }.toSet() == destinations.map { InetAddress.getByName(it).hostAddress }.toSet()
        }
    }

    private class VpnNetworkNotReady(cause: java.io.IOException) : java.io.IOException(cause)

    private suspend fun exchange(network: Network, address: String, query: ByteArray, timeoutMs: Int): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val socket = DatagramSocket()
            continuation.invokeOnCancellation { socket.close() }
            try {
                try { network.bindSocket(socket) }
                catch (e: java.io.IOException) { throw VpnNetworkNotReady(e) }
                socket.soTimeout = timeoutMs
                socket.connect(InetAddress.getByName(address), 53)
                socket.send(DatagramPacket(query, query.size))
                val packet = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(packet)
                continuation.resume(packet.data.copyOf(packet.length))
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            } finally { socket.close() }
        }

    private fun remainingMillis(deadline: Long): Int = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0).toInt()
}
