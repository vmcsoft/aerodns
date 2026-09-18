package com.vmcsoft.aerodns.data.vpn

import com.vmcsoft.aerodns.domain.model.VpnControlPolicy

/**
 * Android 7–9 persist these per-user settings and allow installed apps to read them.
 * Limit this compatibility path to API 24–28; API 29+ has public VpnService getters.
 * Never persist inferred policy: Android can change it while our process is absent.
 */
internal fun readLegacyVpnControlPolicy(
    packageName: String,
    readSetting: (String) -> String?
): VpnControlPolicy = try {
    val selected = readSetting("always_on_vpn_app") == packageName
    if (!selected) VpnControlPolicy() else {
        when (readSetting("always_on_vpn_lockdown")) {
            null, "0" -> VpnControlPolicy(alwaysOn = true)
            "1" -> VpnControlPolicy(alwaysOn = true, lockdown = true)
            else -> VpnControlPolicy(isKnown = false)
        }
    }
} catch (_: RuntimeException) {
    // A vendor may restrict these non-SDK keys. Keep the connection and expose
    // Android settings instead of guessing that disconnect or a benchmark is safe.
    VpnControlPolicy(isKnown = false)
}
