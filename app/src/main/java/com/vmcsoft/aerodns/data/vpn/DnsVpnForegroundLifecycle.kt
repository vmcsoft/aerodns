package com.vmcsoft.aerodns.data.vpn

/**
 * Guards the Android foreground-service contract for [DnsVpnService]:
 * after [android.content.Context.startForegroundService], [android.app.Service.startForeground]
 * must run before connect handling returns.
 */
object DnsVpnForegroundLifecycle {
    fun requiresImmediateForegroundPromotion(serviceAction: String?): Boolean =
        serviceAction == DnsVpnService.ACTION_CONNECT

    class ConnectSession {
        var foregroundPromoted: Boolean = false
            private set

        fun markForegroundPromoted() {
            foregroundPromoted = true
        }

        fun isValidOnComplete(): Boolean = foregroundPromoted
    }
}
