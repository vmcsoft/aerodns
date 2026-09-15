package com.vmcsoft.aerodns.data.dns

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Maps only this HTTPS endpoint to its configured or discovered IP addresses. */
class CustomDohBootstrapDns(private val endpoint: DohEndpoint) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(endpoint.hostname, ignoreCase = true) || endpoint.addresses.isEmpty()) {
            throw UnknownHostException("No bootstrap addresses for requested DoH endpoint")
        }
        return endpoint.addresses
    }
}
