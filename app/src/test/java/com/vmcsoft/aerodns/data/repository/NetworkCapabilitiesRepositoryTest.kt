package com.vmcsoft.aerodns.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.vmcsoft.aerodns.domain.model.NetworkIpStack
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class NetworkCapabilitiesRepositoryTest {
    private val manager = mockk<ConnectivityManager>()
    private val context = mockk<Context> {
        every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns manager
    }
    private val wifi = mockk<Network>()
    private val mobile = mockk<Network>()
    private val vpn = mockk<Network>()

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { manager.activeNetwork } returns wifi
        every { manager.allNetworks } returns arrayOf(wifi)
        network(wifi, "192.0.2.10")
        network(mobile, "2001:db8::10")
        network(vpn, "10.0.0.2", isVpn = true)
    }
    @After fun cleanup() = unmockkStatic(Log::class)

    private fun network(net: Network, vararg addresses: String, isVpn: Boolean = false, validated: Boolean = true) {
        val capabilities = mockk<NetworkCapabilities> {
            every { hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns isVpn
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        }
        val properties = mockk<LinkProperties> {
            every { linkAddresses } returns addresses.map { value ->
                mockk<LinkAddress> { every { address } returns InetAddress.getByName(value) }
            }
        }
        every { manager.getNetworkCapabilities(net) } returns capabilities
        every { manager.getLinkProperties(net) } returns properties
    }

    @Test fun `immediate physical handover uses the new family`() = runBlocking {
        val repository = NetworkCapabilitiesRepositoryImpl(context)
        assertEquals(NetworkIpStack.IPv4_ONLY, repository.getActiveNetworkIpStack())
        every { manager.activeNetwork } returns mobile
        every { manager.allNetworks } returns arrayOf(mobile)
        assertEquals(NetworkIpStack.IPv6_ONLY, repository.getActiveNetworkIpStack())
    }

    @Test fun `address changes on the same network are visible immediately`() = runBlocking {
        val repository = NetworkCapabilitiesRepositoryImpl(context)
        assertEquals(NetworkIpStack.IPv4_ONLY, repository.getActiveNetworkIpStack())
        network(wifi, "192.0.2.10", "2001:db8::10")
        assertEquals(NetworkIpStack.DUAL_STACK, repository.getActiveNetworkIpStack())
    }
    @Test fun `VPN default selects a validated physical network over an unvalidated one`() = runBlocking {
        every { manager.activeNetwork } returns vpn
        every { manager.allNetworks } returns arrayOf(vpn, wifi, mobile)
        network(wifi, "192.0.2.10", validated = false)
        assertEquals(NetworkIpStack.IPv6_ONLY, NetworkCapabilitiesRepositoryImpl(context).getActiveNetworkIpStack())
    }

    @Test fun `active physical network wins over another available network`() = runBlocking {
        every { manager.allNetworks } returns arrayOf(mobile, wifi)
        assertEquals(NetworkIpStack.IPv4_ONLY, NetworkCapabilitiesRepositoryImpl(context).getActiveNetworkIpStack())
    }

    @Test fun `missing default can still discover an available physical network`() = runBlocking {
        every { manager.activeNetwork } returns null
        every { manager.allNetworks } returns arrayOf(mobile)
        assertEquals(NetworkIpStack.IPv6_ONLY, NetworkCapabilitiesRepositoryImpl(context).getActiveNetworkIpStack())
    }

    @Test fun `VPN alone is unknown rather than synthetic IPv4 only`() = runBlocking {
        every { manager.activeNetwork } returns vpn
        every { manager.allNetworks } returns arrayOf(vpn)
        assertEquals(NetworkIpStack.DUAL_STACK, NetworkCapabilitiesRepositoryImpl(context).getActiveNetworkIpStack())
        verify(exactly = 0) { manager.getLinkProperties(vpn) }
    }

    @Test fun `unknown link properties are not cached across network readiness`() = runBlocking {
        val repository = NetworkCapabilitiesRepositoryImpl(context)
        every { manager.getLinkProperties(wifi) } returns null
        assertEquals(NetworkIpStack.DUAL_STACK, repository.getActiveNetworkIpStack())
        network(wifi, "192.0.2.10")
        assertEquals(NetworkIpStack.IPv4_ONLY, repository.getActiveNetworkIpStack())
    }

    @Test fun `unvalidated physical network remains a discovery fallback`() = runBlocking {
        every { manager.activeNetwork } returns vpn
        every { manager.allNetworks } returns arrayOf(vpn, mobile)
        network(mobile, "2001:db8::10", validated = false)
        assertEquals(NetworkIpStack.IPv6_ONLY, NetworkCapabilitiesRepositoryImpl(context).getActiveNetworkIpStack())
    }

}
