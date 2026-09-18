package com.vmcsoft.aerodns.data.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkMonitorTest {
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private val context = mockk<Context> {
        every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns manager
    }
    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val wifi = mockk<Network>()
    private val mobile = mockk<Network>()

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        mockkConstructor(NetworkRequest.Builder::class)
        every { anyConstructed<NetworkRequest.Builder>().addCapability(any()) } answers { self as NetworkRequest.Builder }
        every { anyConstructed<NetworkRequest.Builder>().build() } returns mockk()
        every { manager.registerNetworkCallback(any<NetworkRequest>(), capture(callback)) } just Runs
    }
    @After fun cleanup() {
        unmockkConstructor(NetworkRequest.Builder::class)
        unmockkStatic(Log::class)
    }

    private fun TestScope.observe(): MutableList<NetworkState> {
        val states = mutableListOf<NetworkState>()
        backgroundScope.launch { NetworkMonitor(context).networkState.collect { states += it } }
        runCurrent()
        return states
    }

    @Test fun `arrival and repeated capabilities produce one available state`() = runTest {
        val states = observe()
        callback.captured.onAvailable(wifi)
        repeat(8) { callback.captured.onCapabilitiesChanged(wifi, mockk()) }
        callback.captured.onAvailable(wifi)
        runCurrent()
        assertEquals(listOf(NetworkState.Lost, NetworkState.Available(setOf(wifi))), states)
    }

    @Test fun `interleaved callbacks for two networks cannot replay either arrival`() = runTest {
        val states = observe()
        callback.captured.onAvailable(wifi)
        callback.captured.onAvailable(mobile)
        repeat(4) {
            callback.captured.onCapabilitiesChanged(wifi, mockk())
            callback.captured.onCapabilitiesChanged(mobile, mockk())
            callback.captured.onAvailable(wifi)
        }
        runCurrent()
        assertEquals(listOf(NetworkState.Lost, NetworkState.Available(setOf(wifi)),
            NetworkState.Available(setOf(wifi, mobile))), states)
    }

    @Test fun `losing one network reports the survivor and losing the last reports loss`() = runTest {
        val states = observe()
        callback.captured.onAvailable(wifi)
        callback.captured.onAvailable(mobile)
        callback.captured.onLost(wifi)
        callback.captured.onCapabilitiesChanged(wifi, mockk())
        callback.captured.onLost(wifi)
        callback.captured.onLost(mobile)
        runCurrent()
        assertEquals(listOf(NetworkState.Lost, NetworkState.Available(setOf(wifi)),
            NetworkState.Available(setOf(wifi, mobile)), NetworkState.Available(setOf(mobile)), NetworkState.Lost), states)
    }

    @Test fun `same network regaining validation remains a new arrival`() = runTest {
        val states = observe()
        callback.captured.onAvailable(wifi)
        callback.captured.onLost(wifi)
        callback.captured.onAvailable(wifi)
        runCurrent()
        assertEquals(listOf(NetworkState.Lost, NetworkState.Available(setOf(wifi)),
            NetworkState.Lost, NetworkState.Available(setOf(wifi))), states)
    }

    @Test fun `initial VPN default is not synthetic physical availability`() = runTest {
        every { manager.activeNetwork } returns mockk()
        val states = observe()
        assertEquals(listOf(NetworkState.Lost), states)
        verify(exactly = 0) { manager.activeNetwork }
        verify { anyConstructed<NetworkRequest.Builder>().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
    }

    @Test fun `cancelling collection unregisters its callback`() = runTest {
        val job = backgroundScope.launch { NetworkMonitor(context).networkState.collect() }
        runCurrent()
        job.cancel(); runCurrent()
        verify(exactly = 1) { manager.unregisterNetworkCallback(callback.captured) }
    }

    private fun capabilities(vpn: Boolean, internet: Boolean = true, validated: Boolean = true) = mockk<NetworkCapabilities> {
        every { hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns vpn
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns internet
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
    }

    @Test fun `a validated VPN alone cannot make physical networking available`() {
        every { manager.activeNetwork } returns wifi
        every { manager.allNetworks } returns arrayOf(wifi)
        every { manager.getNetworkCapabilities(wifi) } returns capabilities(vpn = true)
        assertFalse(NetworkMonitor(context).isNetworkAvailable())
    }

    @Test fun `unvalidated non-internet and vanished networks do not allow reconnect`() {
        every { manager.allNetworks } returns arrayOf(wifi)
        for (caps in listOf(capabilities(false, validated = false), capabilities(false, internet = false), null)) {
            every { manager.getNetworkCapabilities(wifi) } returns caps
            assertFalse(NetworkMonitor(context).isNetworkAvailable())
        }
    }

    @Test fun `validated physical survivor allows reconnect even with a VPN default`() {
        every { manager.activeNetwork } returns wifi
        every { manager.allNetworks } returns arrayOf(wifi, mobile)
        every { manager.getNetworkCapabilities(wifi) } returns capabilities(vpn = true)
        every { manager.getNetworkCapabilities(mobile) } returns capabilities(vpn = false)
        assertTrue(NetworkMonitor(context).isNetworkAvailable())
    }
}
