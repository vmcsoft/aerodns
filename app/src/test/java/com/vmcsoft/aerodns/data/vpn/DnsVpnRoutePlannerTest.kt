package com.vmcsoft.aerodns.data.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsVpnRoutePlannerTest {

    @Test
    fun `planRoutes creates host routes for IPv4 and IPv6 addresses`() {
        val routes = DnsVpnRoutePlanner.planRoutes(
            listOf(
                "1.1.1.1",
                "2606:4700:4700::1111"
            )
        )

        assertEquals(
            listOf(
                DnsVpnRoute("1.1.1.1", 32),
                DnsVpnRoute("2606:4700:4700::1111", 128)
            ),
            routes
        )
    }

    @Test
    fun `planRoutes skips invalid addresses`() {
        val routes = DnsVpnRoutePlanner.planRoutes(
            listOf(
                "not-an-ip",
                "8.8.8.8"
            )
        )

        assertEquals(listOf(DnsVpnRoute("8.8.8.8", 32)), routes)
    }

    @Test
    fun `planIpv4Routes skips IPv6 addresses for current packet loop`() {
        val routes = DnsVpnRoutePlanner.planIpv4Routes(
            listOf(
                "1.1.1.1",
                "2606:4700:4700::1111"
            )
        )

        assertEquals(listOf(DnsVpnRoute("1.1.1.1", 32)), routes)
    }
}
