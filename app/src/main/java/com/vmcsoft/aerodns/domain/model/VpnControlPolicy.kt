package com.vmcsoft.aerodns.domain.model

/** Android owns disconnect while Always-on is selected; lockdown blocks DNS-only bypass. */
data class VpnControlPolicy(val alwaysOn: Boolean = false, val lockdown: Boolean = false) {
    fun statusText(health: DnsHealth): String = if (lockdown) "Android is blocking traffic" else health.statusText
}
