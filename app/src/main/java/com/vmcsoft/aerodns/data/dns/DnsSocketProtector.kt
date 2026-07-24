package com.vmcsoft.aerodns.data.dns

import java.net.DatagramSocket
import java.net.Socket

interface DnsSocketProtector {
    fun protect(socket: DatagramSocket): Boolean
    fun protect(socket: Socket): Boolean
}

object NoopDnsSocketProtector : DnsSocketProtector {
    override fun protect(socket: DatagramSocket): Boolean = true
    override fun protect(socket: Socket): Boolean = true
}
