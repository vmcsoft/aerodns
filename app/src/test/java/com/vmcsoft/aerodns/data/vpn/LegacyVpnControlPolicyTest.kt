package com.vmcsoft.aerodns.data.vpn

import com.vmcsoft.aerodns.domain.model.DnsHealth
import com.vmcsoft.aerodns.domain.model.VpnControlPolicy
import org.junit.Assert.*
import org.junit.Test

class LegacyVpnControlPolicyTest {
    private val own = "test.vpn"
    private fun read(settings: Map<String, String>) = readLegacyVpnControlPolicy(own, settings::get)

    @Test fun `current settings replace stale process history across enable disable and lockdown`() {
        val settings = mutableMapOf<String, String>()
        assertEquals(VpnControlPolicy(), read(settings))
        settings["always_on_vpn_app"] = own
        assertEquals(VpnControlPolicy(alwaysOn = true), read(settings))
        settings["always_on_vpn_lockdown"] = "1"
        assertEquals(VpnControlPolicy(true, true), read(settings))
        settings.remove("always_on_vpn_app")
        assertEquals(VpnControlPolicy(), read(settings))
        settings["always_on_vpn_app"] = "another.vpn"
        assertEquals(VpnControlPolicy(), read(settings))
    }

    @Test fun `denied or unavailable setting reads require system controls without claiming always-on`() {
        for (failure in listOf(SecurityException("denied"), IllegalStateException("unavailable"))) {
            val policy = readLegacyVpnControlPolicy(own) { throw failure }
            assertFalse(policy.isKnown)
            assertFalse(policy.alwaysOn)
            assertTrue(policy.systemManaged)
            assertEquals("Check Android VPN settings", policy.statusText(DnsHealth.Healthy(1, 1)))
        }
    }

    @Test fun `malformed lockdown is unknown and a later successful read recovers`() {
        val settings = mutableMapOf("always_on_vpn_app" to own, "always_on_vpn_lockdown" to "invalid")
        assertFalse(read(settings).isKnown)
        settings["always_on_vpn_lockdown"] = "0"
        assertEquals(VpnControlPolicy(alwaysOn = true), read(settings))
        settings.clear()
        assertFalse(read(settings).systemManaged)
    }
}
