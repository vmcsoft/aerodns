package com.vmcsoft.aerodns.data.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsVpnForegroundLifecycleTest {

    @Test
    fun `connect action requires immediate foreground promotion`() {
        assertTrue(
            DnsVpnForegroundLifecycle.requiresImmediateForegroundPromotion(
                DnsVpnService.ACTION_CONNECT
            )
        )
    }

    @Test
    fun `disconnect and unknown actions do not require immediate foreground promotion`() {
        assertFalse(
            DnsVpnForegroundLifecycle.requiresImmediateForegroundPromotion(
                DnsVpnService.ACTION_DISCONNECT
            )
        )
        assertFalse(DnsVpnForegroundLifecycle.requiresImmediateForegroundPromotion(null))
    }

    @Test
    fun `connect session is invalid until foreground is promoted`() {
        val session = DnsVpnForegroundLifecycle.ConnectSession()

        assertFalse(session.isValidOnComplete())

        session.markForegroundPromoted()

        assertTrue(session.isValidOnComplete())
    }
}
