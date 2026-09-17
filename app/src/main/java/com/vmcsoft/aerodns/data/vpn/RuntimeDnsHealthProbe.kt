package com.vmcsoft.aerodns.data.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
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
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Checks the established DNS path; never protect this probe socket from the VPN. */
internal class RuntimeDnsHealthProbe(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    suspend fun check(config: DnsConnectionConfig): DnsHealth = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + 5_000_000_000L
        val destinations = if (config.enableExperimentalPacketLoop) listOf("10.0.0.1") else config.upstreamAddresses
        try {
            readiness@ while (remainingMillis(deadline) > 0) {
                val vpn = findVpn(destinations)
                if (vpn == null) {
                    delay(50)
                    continue
                }
                var routeNotReady = false
                for ((index, address) in destinations.withIndex()) {
                    val remaining = remainingMillis(deadline)
                    if (remaining <= 0) break
                    val query = DnsWireMessage.buildAQuery(System.nanoTime().toInt(), "example.com")
                    val start = System.nanoTime()
                    try {
                        val reply = exchange(vpn.network, config.enableExperimentalPacketLoop, address, query, maxOf(1, remaining / (destinations.size - index))) {
                            findVpn(destinations) == vpn
                        }
                        if (DnsHealthResponse.isHealthy(query, reply)) {
                            return@withContext DnsHealth.Healthy(System.currentTimeMillis(), (System.nanoTime() - start) / 1_000_000)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: VpnNetworkNotReady) {
                        // Routing may lag establishment, or the active network may change
                        // while a query is in flight. Try other selected addresses first,
                        // so an unreachable family cannot starve a working resolver IP.
                        routeNotReady = true
                    }
                    catch (_: java.io.IOException) { /* Try only another address in the selected configuration. */ }
                }
                if (routeNotReady) {
                    delay(50)
                    continue@readiness
                }
                break
            }
            DnsHealth.Unhealthy(System.currentTimeMillis())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { DnsHealth.Unhealthy(System.currentTimeMillis()) }
    }

    private data class VpnRoute(val network: Network, val interfaceName: String?)

    private fun findVpn(destinations: List<String>): VpnRoute? {
        val expectedDns = destinations.map { InetAddress.getByName(it).hostAddress }.toSet()
        fun matchingRoute(network: Network): VpnRoute? {
            val properties = connectivity.getLinkProperties(network) ?: return null
            return VpnRoute(network, properties.interfaceName).takeIf {
                connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                    properties.linkAddresses.any { it.address.hostAddress == "10.0.0.2" } &&
                    properties.dnsServers.map { it.hostAddress }.toSet() == expectedDns
            }
        }
        connectivity.activeNetwork?.let { matchingRoute(it)?.let { route -> return route } }
        // Android 7–9 can keep reporting the physical default for a DNS-only VPN.
        // Modern Android must finish the default-network handoff before reporting
        // healthy: an explicit probe can succeed while OS lookups still use the underlay.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        // During ambiguous legacy handover, wait rather than choosing an old VPN.
        return connectivity.allNetworks.mapNotNull(::matchingRoute).singleOrNull()
    }

    private class VpnNetworkNotReady(cause: java.io.IOException? = null) : java.io.IOException(cause)

    private suspend fun exchange(
        network: Network,
        bindToVpn: Boolean,
        address: String,
        query: ByteArray,
        timeoutMs: Int,
        isCurrentNetwork: () -> Boolean
    ): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            val retryAt = System.nanoTime() + timeoutMs * 500_000L
            var retried = false
            val socket = DatagramSocket()
            continuation.invokeOnCancellation { socket.close() }
            try {
                // Standard DNS has no TUN route. Follow Android's normal UID
                // routing to the exact advertised resolver; explicitly binding to
                // the VPN disables split-tunnel fallthrough on older Android.
                // DoH must bind to its virtual resolver inside TUN.
                if (bindToVpn) {
                    try { network.bindSocket(socket) }
                    catch (e: java.io.IOException) { throw VpnNetworkNotReady(e) }
                }
                if (!isCurrentNetwork()) throw VpnNetworkNotReady()
                val destination = InetAddress.getByName(address)
                try { socket.connect(destination, 53) }
                catch (e: java.io.IOException) { throw VpnNetworkNotReady(e) }
                // Older Android's native datagram path can require the packet's
                // destination even on a connected socket. Keep both identities equal.
                val request = DatagramPacket(query, query.size, destination, 53)
                fun sendRequest() {
                    try { socket.send(request) }
                    // Network visibility can precede route installation on older
                    // Android. Reacquire after failed sends within the same deadline.
                    catch (e: java.io.IOException) { throw VpnNetworkNotReady(e) }
                }
                sendRequest()
                val packet = DatagramPacket(ByteArray(4096), 4096)
                while (true) {
                    if (!isCurrentNetwork()) throw VpnNetworkNotReady()
                    val remaining = remainingMillis(deadline)
                    if (remaining <= 0) throw SocketTimeoutException("DNS health check timed out")
                    if (!retried && System.nanoTime() >= retryAt) {
                        // UDP can be dropped while netd applies routes even when Android
                        // retains the same Network. Retry once; do not extend the deadline.
                        sendRequest()
                        retried = true
                    }
                    // A socket stays bound to its original network during VPN replacement.
                    // Poll network and interface identity so a retired route cannot
                    // consume the entire health-check budget.
                    socket.soTimeout = minOf(100, remaining)
                    try {
                        socket.receive(packet)
                        if (!isCurrentNetwork()) throw VpnNetworkNotReady()
                        continuation.resume(packet.data.copyOf(packet.length))
                        break
                    } catch (_: SocketTimeoutException) {
                        // Keep waiting for this query while its VPN is still current.
                    }
                }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            } finally { socket.close() }
        }

    private fun remainingMillis(deadline: Long): Int = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0).toInt()
}
