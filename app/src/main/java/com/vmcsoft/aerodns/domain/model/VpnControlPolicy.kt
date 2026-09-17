package com.vmcsoft.aerodns.domain.model

/** Android owns disconnect while Always-on is selected; lockdown blocks DNS-only bypass. */
data class VpnControlPolicy(
    val alwaysOn: Boolean = false,
    val lockdown: Boolean = false,
    val isKnown: Boolean = true
) {
    val systemManaged: Boolean get() = alwaysOn || !isKnown

    fun statusText(health: DnsHealth): String = when {
        !isKnown -> "Check Android VPN settings"
        lockdown -> "Android is blocking traffic"
        else -> health.statusText
    }
}
