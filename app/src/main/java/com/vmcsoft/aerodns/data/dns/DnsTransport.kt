package com.vmcsoft.aerodns.data.dns

sealed class DnsTransportResult {
    data class Success(
        val payload: ByteArray,
        val latencyMs: Long
    ) : DnsTransportResult()

    object Timeout : DnsTransportResult()

    data class Error(val message: String) : DnsTransportResult()
}

interface DnsTransport {
    suspend fun query(
        payload: ByteArray,
        upstreamAddress: String,
        timeoutMs: Int
    ): DnsTransportResult
}

object DnsSecurityMessages {
    const val UNTRUSTED_CERTIFICATE =
        "Connection blocked due to an untrusted or self-signed certificate. If you are using an OpenNIC server, you may enable 'Allow untrusted certificates' in advanced settings at your own risk."
}
