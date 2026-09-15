package com.vmcsoft.aerodns.data.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

@Suppress("DEPRECATION") // Exercise the API 24-compatible network selection path.
class DohEndpointResolverTest {
    private val manager = mockk<ConnectivityManager>()
    private val context = mockk<Context>()
    private val resolver = DohEndpointResolver(context)

    init {
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns manager
    }

    @Test fun `configured endpoint IPs never invoke network DNS`() {
        val result = resolver.resolve("resolver.example", listOf("203.0.113.10", "2001:db8::10"))
        assertNull(result.network)
        assertEquals(listOf("203.0.113.10", "2001:db8:0:0:0:0:0:10"), result.addresses.map { it.hostAddress })
        verify(exactly = 0) { context.getSystemService(any<String>()) }
    }

    @Test fun `hostname is not accepted as an endpoint IP override`() {
        assertTrue(runCatching {
            resolver.resolve("resolver.example", listOf("wrong.example"))
        }.exceptionOrNull() is IllegalArgumentException)
        verify(exactly = 0) { context.getSystemService(any<String>()) }
    }

    @Test fun `URL-only endpoint uses active physical network DNS`() {
        val wifi = network("wifi")
        every { manager.activeNetwork } returns wifi
        every { wifi.getAllByName("dns.google") } returns addresses("8.8.8.8")
        val result = resolver.resolve("dns.google", emptyList())
        assertEquals("8.8.8.8", result.addresses.single().hostAddress)
        assertSame(wifi, result.network)
        verify(exactly = 1) { wifi.getAllByName("dns.google") }
    }

    @Test fun `VPN default is excluded and validated physical network is preferred`() {
        val vpn = network("vpn", vpn = true)
        val unvalidated = network("unvalidated", validated = false)
        val mobile = network("mobile")
        every { manager.activeNetwork } returns vpn
        every { manager.allNetworks } returns arrayOf(vpn, unvalidated, mobile)
        every { mobile.getAllByName("resolver.example") } returns addresses("203.0.113.20")
        val result = resolver.resolve("resolver.example", emptyList())
        assertSame(mobile, result.network)
        verify(exactly = 0) { vpn.getAllByName(any()) }
        verify(exactly = 0) { unvalidated.getAllByName(any()) }
    }

    @Test fun `unvalidated physical network can discover local custom endpoint`() {
        val wifi = network("local", validated = false)
        every { manager.activeNetwork } returns null
        every { manager.allNetworks } returns arrayOf(wifi)
        every { wifi.getAllByName("resolver.example") } returns addresses("192.168.1.53")
        assertSame(wifi, resolver.resolve("resolver.example", emptyList()).network)
    }

    @Test fun `VPN alone fails closed instead of recursing through it`() {
        val vpn = network("vpn", vpn = true)
        every { manager.activeNetwork } returns vpn
        every { manager.allNetworks } returns arrayOf(vpn)
        assertTrue(runCatching {
            resolver.resolve("resolver.example", emptyList())
        }.exceptionOrNull() is UnknownHostException)
        verify(exactly = 0) { vpn.getAllByName(any()) }
    }

    @Test fun `network switch uses fresh lookup and never reuses previous endpoint answers`() {
        val wifi = network("wifi")
        val mobile = network("mobile")
        every { manager.activeNetwork } returnsMany listOf(wifi, mobile)
        every { wifi.getAllByName("resolver.example") } returns addresses("192.168.1.53")
        every { mobile.getAllByName("resolver.example") } returns addresses("203.0.113.20")
        val first = resolver.resolve("resolver.example", emptyList())
        val second = resolver.resolve("resolver.example", emptyList())
        assertSame(wifi, first.network)
        assertSame(mobile, second.network)
        assertNotEquals(first.addresses, second.addresses)
    }

    @Test fun `failed or empty physical lookup never substitutes a public resolver`() {
        val wifi = network("wifi")
        every { manager.activeNetwork } returns wifi
        every { wifi.getAllByName("resolver.example") } throws UnknownHostException("failed")
        assertTrue(runCatching {
            resolver.resolve("resolver.example", emptyList())
        }.exceptionOrNull() is UnknownHostException)
        every { wifi.getAllByName("resolver.example") } returns emptyArray()
        assertTrue(runCatching {
            resolver.resolve("resolver.example", emptyList())
        }.exceptionOrNull() is UnknownHostException)
    }

    private fun addresses(ip: String) = arrayOf(InetAddress.getByName(ip))

    private fun network(name: String, vpn: Boolean = false, validated: Boolean = true): Network {
        val network = mockk<Network>(name = name)
        val caps = mockk<NetworkCapabilities>()
        every { manager.getNetworkCapabilities(network) } returns caps
        every { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns vpn
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        return network
    }
}
