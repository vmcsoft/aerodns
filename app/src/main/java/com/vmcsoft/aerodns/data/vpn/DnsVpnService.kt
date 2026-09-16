package com.vmcsoft.aerodns.data.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.vmcsoft.aerodns.R
import com.vmcsoft.aerodns.data.dns.DnsForwarder
import com.vmcsoft.aerodns.data.dns.DnsSocketProtector
import com.vmcsoft.aerodns.data.dns.DohDnsTransport
import com.vmcsoft.aerodns.data.dns.DohEndpointResolver
import com.vmcsoft.aerodns.data.dns.DotDnsTransport
import com.vmcsoft.aerodns.data.dns.TcpDnsTransport
import com.vmcsoft.aerodns.data.dns.UdpDnsTransport
import com.vmcsoft.aerodns.data.vpn.packet.TunDnsPacketHandler
import com.vmcsoft.aerodns.data.vpn.packet.TunDnsPacketLoop
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.model.DnsHealth
import com.vmcsoft.aerodns.domain.model.statusDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.Serializable

class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val udpDnsTransport by lazy {
        UdpDnsTransport().apply { socketProtector = VpnServiceDnsSocketProtector() }
    }
    private val tcpDnsTransport by lazy {
        TcpDnsTransport().apply { socketProtector = VpnServiceDnsSocketProtector() }
    }
    private val dohDnsTransport by lazy {
        DohDnsTransport(DohEndpointResolver(this)).apply { socketProtector = VpnServiceDnsSocketProtector() }
    }
    private val dotDnsTransport by lazy {
        DotDnsTransport().apply { socketProtector = VpnServiceDnsSocketProtector() }
    }
    private val packetLoop by lazy { TunDnsPacketLoop(
        TunDnsPacketHandler(
            DnsForwarder(udpDnsTransport, tcpDnsTransport, dohDnsTransport, dotDnsTransport)
        )
    ) }
    private var packetLoopJob: Job? = null
    private var healthJob: Job? = null
    private var currentHealth: DnsHealth = DnsHealth.Checking
    private val healthProbe by lazy { RuntimeDnsHealthProbe(this) }

    private var currentDnsConfig: DnsConnectionConfig? = null
    private var isForegroundStarted = false
    private var lastStartId = 0
    private val recoveryStore by lazy { VpnRecoveryStore(this) }

    companion object {
        private const val TAG = "DnsVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "aerodns_vpn_channel"
        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val PACKET_LOOP_DNS_ADDRESS = "10.0.0.1"
        private const val IPV4_HOST_PREFIX = 32
        private const val PACKET_LOOP_TIMEOUT_MS = 5_000

        const val ACTION_CONNECT = "com.vmcsoft.aerodns.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.vmcsoft.aerodns.ACTION_DISCONNECT"
        const val EXTRA_PRESERVE_RECOVERY = "extra_preserve_recovery"
        const val EXTRA_DISCONNECT_REQUEST_ID = "extra_disconnect_request_id"
        const val EXTRA_DNS_CONFIG = "extra_dns_config"
        const val EXTRA_DNS_SERVER = "extra_dns_server"
        const val EXTRA_DNS_ADDRESSES = "extra_dns_addresses"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DnsVpnService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every entry may have come from startForegroundService, including system,
        // malformed and disconnect commands. Promote before parsing or persistence.
        try {
            promoteForegroundConnecting(currentDnsConfig?.displayName)
            when (intent?.action) {
                ACTION_CONNECT -> {
                    val config = intent.serializableExtra<DnsConnectionConfig>(EXTRA_DNS_CONFIG)
                        ?: resolveLegacyDnsConfig(intent)
                    if (config == null) abortConnectStartup(null, "No DNS config provided")
                    else startVpn(config)
                }
                ACTION_DISCONNECT -> {
                    val requestId = intent.getStringExtra(EXTRA_DISCONNECT_REQUEST_ID)
                    if (requestId == null || currentDnsConfig?.connectionRequestId == requestId || !isRunning) {
                        stopVpn(clearRecovery = !intent.getBooleanExtra(EXTRA_PRESERVE_RECOVERY, false))
                    } else currentDnsConfig?.let(::updateForegroundNotification)
                }
                null, SERVICE_INTERFACE -> {
                    val config = currentDnsConfig ?: recoveryStore.load()?.copy(
                        connectionRequestId = "restored-${System.nanoTime()}"
                    )
                    if (config == null) stopVpn() else startVpn(config)
                }
                else -> {
                    // An unknown command must not restore an inactive connection.
                    val active = currentDnsConfig
                    if (isRunning && active != null) {
                        updateForegroundNotification(active)
                    } else stopVpn()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle VPN start", e)
            abortConnectStartup(currentDnsConfig, e.message ?: "Failed to start VPN")
        }
        return if (isRunning) START_STICKY else START_NOT_STICKY
    }

    private fun resolveLegacyDnsConfig(intent: Intent): DnsConnectionConfig? {
        val dnsServer = intent.serializableExtra<DnsServer>(EXTRA_DNS_SERVER) ?: return null
        val dnsAddresses = intent.getStringArrayListExtra(EXTRA_DNS_ADDRESSES)
        val addresses = when {
            !dnsAddresses.isNullOrEmpty() -> dnsAddresses
            else -> buildList {
                dnsServer.primary.takeIf { it.isNotBlank() }?.let { add(it) }
                dnsServer.secondary?.let { add(it) }
                dnsServer.ipv6Primary?.let { add(it) }
                dnsServer.ipv6Secondary?.let { add(it) }
            }
        }
        return DnsConnectionConfig(
            serverId = dnsServer.id,
            displayName = dnsServer.name,
            protocol = DnsProtocol.STANDARD,
            upstreamAddresses = addresses
        )
    }

    private fun abortConnectStartup(dnsConfig: DnsConnectionConfig?, message: String) {
        stopVpn(emitEvent = false)
        // Keep failure as the replayed terminal event, rather than replacing it with Stopped.
        DnsVpnServiceEvents.emit(DnsVpnServiceEvent.Failed(dnsConfig, message))
    }

    private fun startVpn(dnsConfig: DnsConnectionConfig) {
        if (isRunning && currentDnsConfig == dnsConfig) {
            updateForegroundNotification(dnsConfig)
            DnsVpnServiceEvents.emit(DnsVpnServiceEvent.Established(dnsConfig, currentHealth))
            return
        }

        var retiredInterface: ParcelFileDescriptor? = null
        try {
            validateRecoveryConfig(dnsConfig)
            check(recoveryStore.save(dnsConfig)) { "Could not save VPN recovery state" }
            // A new configuration must really replace the interface; never acknowledge
            // the new provider while continuing to forward through the old one.
            // Keep the old descriptor alive until establish() completes the handover.
            // Closing it first lets Android reuse its interface name while old routing
            // is still visible, so a newly bound probe can send into stale routes.
            retiredInterface = vpnInterface
            vpnInterface = null
            releaseInterface()
            currentDnsConfig = dnsConfig

            val addresses = getVpnDnsAddresses(dnsConfig)
            if (addresses.isEmpty()) {
                Log.e(TAG, "No DNS addresses to add")
                abortConnectStartup(dnsConfig, "No DNS addresses to add")
                return
            }
            // Build VPN interface - DNS ONLY, no traffic routing
            val builder = Builder()
                .setSession("AeroDNS")
                .addAddress(VPN_ADDRESS, 32)  // /32 for single IP, not a subnet
                // Permit IPv6 passthrough without adding an unrelated IPv6 DNS provider.
                .allowFamily(OsConstants.AF_INET6)
            for (addr in addresses) {
                builder.addDnsServer(addr)
            }
            addExperimentalDnsRoutesIfEnabled(builder, dnsConfig)

            // Set MTU
            builder.setMtu(VPN_MTU)

            // CRITICAL: Allow underlying networks to pass through
            // This prevents the VPN from blocking all traffic
            builder.setBlocking(dnsConfig.enableExperimentalPacketLoop)

            // Allow all apps to bypass VPN for actual traffic
            // Only DNS queries will use our DNS servers
            try {
                builder.allowBypass()
            } catch (e: Exception) {
                Log.w(TAG, "allowBypass not supported", e)
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                abortConnectStartup(dnsConfig, "Failed to establish VPN interface")
                return
            }

            isRunning = true

            updateForegroundNotification(dnsConfig)
            startPacketLoopIfEnabled(dnsConfig)
            DnsVpnServiceEvents.emit(DnsVpnServiceEvent.Established(dnsConfig))
            startHealthChecks(dnsConfig)

            Log.i(TAG, "VPN started with DNS: ${dnsConfig.displayName} (${dnsConfig.protocol}, $addresses)")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            abortConnectStartup(dnsConfig, e.message ?: "Failed to start VPN")
        } finally {
            try { retiredInterface?.close() }
            catch (e: java.io.IOException) { Log.w(TAG, "Failed to close retired VPN interface", e) }
        }
    }

    private fun promoteForegroundConnecting(displayName: String?) {
        startForeground(NOTIFICATION_ID, createConnectingNotification(displayName))
        isForegroundStarted = true
    }

    private fun updateForegroundNotification(dnsConfig: DnsConnectionConfig) {
        startForeground(NOTIFICATION_ID, createNotification(dnsConfig))
        isForegroundStarted = true
    }

    private fun stopVpn(
        clearRecovery: Boolean = true,
        stopService: Boolean = true,
        emitEvent: Boolean = true
    ) {
        val stoppedConfig = currentDnsConfig
        try {
            if (clearRecovery && !recoveryStore.clear()) {
                Log.e(TAG, "Could not persist cleared VPN recovery state")
            }
        } finally {
            try {
                releaseInterface()
            } finally {
                currentDnsConfig = null
                if (isForegroundStarted) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForegroundStarted = false
                }
                // A newer start may already be queued in ActivityManager, before
                // onStartCommand receives it. Never destroy that pending connection.
                if (stopService) stopSelfResult(lastStartId)
                if (emitEvent) DnsVpnServiceEvents.emit(DnsVpnServiceEvent.Stopped(stoppedConfig))
            }
        }
    }

    private fun releaseInterface() {
        isRunning = false
        healthJob?.cancel()
        healthJob = null
        currentHealth = DnsHealth.Checking
        stopPacketLoop()
        val oldInterface = vpnInterface
        vpnInterface = null
        try {
            oldInterface?.close()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Failed to close VPN interface", e)
        }
    }

    private fun startPacketLoopIfEnabled(dnsConfig: DnsConnectionConfig) {
        if (!dnsConfig.enableExperimentalPacketLoop) {
            return
        }

        val activeInterface = vpnInterface ?: return
        packetLoopJob?.cancel()
        packetLoopJob = serviceScope.launch {
            try {
                Log.i(TAG, "Starting experimental TUN DNS packet loop")
                packetLoop.run(
                    vpnInterface = activeInterface,
                    config = dnsConfig,
                    mtu = VPN_MTU,
                    timeoutMs = PACKET_LOOP_TIMEOUT_MS
                )
                ensureActive()
                handlePacketLoopFailure(activeInterface, dnsConfig, "DNS forwarding stopped unexpectedly")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Experimental packet loop stopped with error", e)
                handlePacketLoopFailure(activeInterface, dnsConfig, "DNS forwarding failed: ${e.message}")
            }
        }
    }

    private fun handlePacketLoopFailure(
        failedInterface: ParcelFileDescriptor,
        config: DnsConnectionConfig,
        message: String
    ) {
        serviceScope.launch(Dispatchers.Main.immediate) {
            // A cancelled/replaced loop cannot tear down the new connection.
            if (vpnInterface === failedInterface && isRunning) abortConnectStartup(config, message)
        }
    }

    private fun stopPacketLoop() {
        packetLoopJob?.cancel()
        packetLoopJob = null
    }

    private fun startHealthChecks(config: DnsConnectionConfig) {
        val activeInterface = vpnInterface ?: return
        healthJob = serviceScope.launch {
            while (isActive) {
                val health = healthProbe.check(config)
                withContext(Dispatchers.Main.immediate) {
                    // Results from a replaced or disconnected interface cannot alter its successor.
                    if (isRunning && vpnInterface === activeInterface && currentDnsConfig == config) {
                        currentHealth = health
                        updateForegroundNotification(config)
                        DnsVpnServiceEvents.emit(DnsVpnServiceEvent.Established(config, health))
                    }
                }
                delay(30_000)
            }
        }
    }

    private inner class VpnServiceDnsSocketProtector : DnsSocketProtector {
        override fun protect(socket: java.net.DatagramSocket): Boolean {
            return this@DnsVpnService.protect(socket)
        }

        override fun protect(socket: java.net.Socket): Boolean {
            return this@DnsVpnService.protect(socket)
        }
    }

    private fun addExperimentalDnsRoutesIfEnabled(builder: Builder, dnsConfig: DnsConnectionConfig) {
        if (!dnsConfig.enableExperimentalPacketLoop) {
            return
        }

        try {
            builder.addRoute(PACKET_LOOP_DNS_ADDRESS, IPV4_HOST_PREFIX)
            Log.d(TAG, "Added experimental DNS route: $PACKET_LOOP_DNS_ADDRESS/$IPV4_HOST_PREFIX")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add experimental DNS route: $PACKET_LOOP_DNS_ADDRESS", e)
        }
    }

    private fun getVpnDnsAddresses(dnsConfig: DnsConnectionConfig): List<String> {
        if (!dnsConfig.enableExperimentalPacketLoop) {
            return dnsConfig.upstreamAddresses
        }

        return listOf(PACKET_LOOP_DNS_ADDRESS)
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AeroDNS VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createConnectingNotification(displayName: String?): Notification {
        val targetName = displayName?.takeIf { it.isNotBlank() } ?: "DNS server"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AeroDNS")
            .setContentText("Connecting to $targetName")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setOngoing(true)
            .build()
    }

    private fun createNotification(dnsConfig: DnsConnectionConfig): Notification {
        val disconnectIntent = Intent(this, DnsVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            0,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AeroDNS Active")
            .setContentText(dnsConfig.statusDescription(currentHealth))
            .setSmallIcon(R.drawable.ic_vpn_key)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Disconnect",
                    disconnectPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Android may recreate this service. Explicit disconnect/revoke/failure paths
        // clear the recovery record; lifecycle destruction only releases resources.
        stopVpn(clearRecovery = false, stopService = false, emitEvent = currentDnsConfig != null)
        serviceScope.cancel()
        Log.d(TAG, "DnsVpnService destroyed")
    }

    override fun onRevoke() {
        // Android may invoke onRevoke off the main thread; serialize with start commands.
        if (Looper.myLooper() == Looper.getMainLooper()) stopVpn()
        else Handler(Looper.getMainLooper()).post { stopVpn() }
    }

    private inline fun <reified T : Serializable> Intent.serializableExtra(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSerializableExtra(key) as? T
        }
    }
}
