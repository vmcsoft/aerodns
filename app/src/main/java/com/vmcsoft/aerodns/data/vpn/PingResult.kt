package com.vmcsoft.aerodns.data.vpn

sealed class PingResult {
    data class Success(val latencyMs: Long) : PingResult()
    object Timeout : PingResult()
    data class Error(val message: String) : PingResult()
}
