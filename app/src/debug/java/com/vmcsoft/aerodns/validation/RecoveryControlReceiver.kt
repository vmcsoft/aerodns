package com.vmcsoft.aerodns.validation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol

/** Shell-only debug control for host-driven process-kill/reboot checks. Never in release. */
class RecoveryControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command")
        require(command == "connect" || command == "disconnect")
        val service = Intent(context, DnsVpnService::class.java)
        if (command == "disconnect") {
            service.action = DnsVpnService.ACTION_DISCONNECT
        } else {
            if (VpnService.prepare(context) != null) {
                resultData = "vpn-consent-required"
                return
            }
            val port = intent.getIntExtra("port", 18443)
            require(port in 1024..65535)
            val standard = intent.getBooleanExtra("standard", false)
            service.action = DnsVpnService.ACTION_CONNECT
            service.putExtra(DnsVpnService.EXTRA_DNS_CONFIG, DnsConnectionConfig(
                serverId = "process-recovery-fixture",
                displayName = "Recovery fixture",
                protocol = if (standard) DnsProtocol.STANDARD else DnsProtocol.DOH,
                upstreamAddresses = if (standard) listOf("8.8.8.8") else emptyList(),
                dohUrl = if (standard) null else "https://localhost:$port/health-ok-recovery",
                // Emulator host alias survives reboot; adb reverse does not.
                customBootstrapIp = if (standard) null else "10.0.2.2",
                allowUntrustedCertificates = !standard,
                connectionRequestId = "recovery-check-${System.nanoTime()}",
                enableExperimentalPacketLoop = !standard
            ))
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
        resultData = "submitted:$command"
    }
}
