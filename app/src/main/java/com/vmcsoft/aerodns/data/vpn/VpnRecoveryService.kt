package com.vmcsoft.aerodns.data.vpn

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Shares the foreground VPN's process, but has no system VPN binding. Some Android
 * builds lose the VPN service's sticky restart when interface removal unbinds its
 * already-dead process. This started service retains an independent restart record.
 * It owns no timer, network work, configuration copy, or separate foreground status.
 */
class VpnRecoveryService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recovery = VpnRecoveryStore(this)
        // Always reread current intent: queued starts must not resurrect a disconnect
        // or replay an older provider after a newer connect command.
        val config = recovery.load()
        if (config == null || VpnService.prepare(this) != null) {
            recovery.clear()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (val state = DnsVpnServiceEvents.events.replayCache.lastOrNull()) {
            is DnsVpnServiceEvent.Established -> if (state.config == config) {
                // Normal tracking start: avoid replaying startup and notification work.
                return START_STICKY
            }
            is DnsVpnServiceEvent.Stopped, is DnsVpnServiceEvent.Failed -> {
                // An intentional pause may retain its record for the caller to restore.
                // A delayed start in this process must respect its terminal event.
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            null -> Unit // Fresh process: no live service has acknowledged this record.
        }
        return try {
            val restore = Intent(this, DnsVpnService::class.java).setAction(VpnService.SERVICE_INTERFACE)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(restore) else startService(restore)
            START_STICKY
        } catch (error: RuntimeException) {
            // No retry loop if Android refuses startup. A user can retry explicitly.
            Log.e("VpnRecoveryService", "Could not request VPN restoration", error)
            stopSelfResult(startId)
            START_NOT_STICKY
        }
    }
}
