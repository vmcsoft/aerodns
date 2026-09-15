package com.vmcsoft.aerodns.data.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.vmcsoft.aerodns.R
import com.vmcsoft.aerodns.BuildConfig
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Serializable

class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val udpDnsTransport = UdpDnsTransport().apply {
        socketProtector = VpnServiceDnsSocketProtector()
    }
    private val tcpDnsTransport = TcpDnsTransport().apply {
        socketProtector = VpnServiceDnsSocketProtector()
    }
    private val dohDnsTransport = DohDnsTransport(DohEndpointResolver(this)).apply {
        socketProtector = VpnServiceDnsSocketProtector()
    }
    private val dotDnsTransport = DotDnsTransport().apply {
        socketProtector = VpnServiceDnsSocketProtector()
    }
    private val packetLoop = TunDnsPacketLoop(
        TunDnsPacketHandler(
            DnsForwarder(udpDnsTransport, tcpDnsTransport, dohDnsTransport, dotDnsTransport)
        )
    )
    private var packetLoopJob: Job? = null

    private var currentDnsConfig: DnsConnectionConfig? = null
    private var isForegroundStarted = false

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
        when (intent?.action) {
            ACTION_CONNECT -> handleConnectCommand(intent)
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    private fun handleConnectCommand(intent: Intent) {
        val connectSession = DnsVpnForegroundLifecycle.ConnectSession()
        promoteForegroundConnecting(displayName = null)
        connectSession.markForegroundPromoted()

        try {
            val dnsConfig = intent.serializableExtra<DnsConnectionConfig>(EXTRA_DNS_CONFIG)
                ?: resolveLegacyDnsConfig(intent)

            if (dnsConfig == null) {
                Log.e(TAG, "No DNS config or legacy DNS server provided")
                abortConnectStartup(null, "No DNS config provided")
                return
            }

            promoteForegroundConnecting(dnsConfig.displayName)
            startVpn(dnsConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse connect intent", e)
            abortConnectStartup(null, e.message ?: "Failed to parse connect intent")
        } finally {
            if (BuildConfig.DEBUG) {
                check(connectSession.isValidOnComplete()) {
                    "startForeground must be called before connect handling completes"
                }
            }
        }
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
        if (dnsConfig != null) {
            DnsVpnServiceEvents.emit(DnsVpnServiceEvent.Failed(dnsConfig, message))
        }
        stopVpn()
    }

    private fun startVpn(dnsConfig: DnsConnectionConfig) {
        if (isRunning) {
            Log.w(TAG, "VPN already running")
            updateForegroundNotification(dnsConfig)
            DnsVpnServiceEvents.emit(DnsVpnServiceEvent.Established(dnsConfig))
            return
        }

        try {
            currentDnsConfig = dnsConfig

            val addresses = getVpnDnsAddresses(dnsConfig)
            if (addresses.isEmpty()) {
                Log.e(TAG, "No DNS addresses to add")
                abortConnectStartup(dnsConfig, "No DNS addresses to add")
                return
            }
            if (dnsConfig.protocol != DnsProtocol.STANDARD && !dnsConfig.enableExperimentalPacketLoop) {
                Log.w(TAG, "Protocol ${dnsConfig.protocol} requires the experimental packet loop; using DNS addresses only")
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

            Log.i(TAG, "VPN started with DNS: ${dnsConfig.displayName} (${dnsConfig.protocol}, $addresses)")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            abortConnectStartup(dnsConfig, e.message ?: "Failed to start VPN")
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

    private fun stopVpn() {
        val stoppedConfig = currentDnsConfig
        if (!isRunning && stoppedConfig == null && !isForegroundStarted) {
            return
        }

        try {
            isRunning = false
            stopPacketLoop()
            vpnInterface?.close()
            vpnInterface = null
            currentDnsConfig = null

            if (isForegroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForegroundStarted = false
            }
            DnsVpnServiceEvents.emit(DnsVpnServiceEvent.Stopped(stoppedConfig))
            stopSelf()

            Log.i(TAG, "VPN stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
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
            } catch (e: Exception) {
                Log.e(TAG, "Experimental packet loop stopped with error", e)
            }
        }
    }

    private fun stopPacketLoop() {
        packetLoopJob?.cancel()
        packetLoopJob = null
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
            .setContentText("Connected to ${dnsConfig.displayName}")
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
        stopVpn()
        serviceScope.cancel()
        Log.d(TAG, "DnsVpnService destroyed")
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
