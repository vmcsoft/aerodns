package com.vmcsoft.aerodns.data.dns

import com.vmcsoft.aerodns.data.diagnostics.DnsDiagnosticLog
import okhttp3.Dns
import java.net.InetAddress

class CustomDohBootstrapDns(
    private val customBootstrapIp: String?,
    private val fallbackBootstrapAddresses: List<String>
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = customBootstrapIp
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(it) }
            ?: fallbackBootstrapAddresses

        DnsDiagnosticLog.d(TAG, "doh_bootstrap_lookup hostname=$hostname upstreams=$addresses")
        return addresses.map { address ->
            InetAddress.getByName(address)
        }
    }

    private companion object {
        private const val TAG = "CustomDohBootstrapDns"
    }
}
