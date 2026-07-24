package com.vmcsoft.aerodns.presentation.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.vmcsoft.aerodns.R
import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.repository.SettingsRepository
import com.vmcsoft.aerodns.domain.repository.VpnRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings Tile for AeroDNS
 * Allows users to toggle VPN connection from the notification panel
 */
@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class DnsTileService : TileService() {

    @Inject
    lateinit var vpnRepository: VpnRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var dnsRepository: com.vmcsoft.aerodns.domain.repository.DnsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObservationJob: Job? = null

    companion object {
        private const val TAG = "DnsTileService"
        private const val OPEN_APP_REQUEST_CODE = 100
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DnsTileService created")
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "Tile started listening")

        // Set initial tile state immediately
        val currentState = vpnRepository.connectionState.value
        updateTileState(currentState)

        // Observe VPN connection state and update tile
        stateObservationJob?.cancel()
        stateObservationJob = serviceScope.launch {
            vpnRepository.connectionState.collect { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "Tile stopped listening")
        // Only cancel state observation, not the entire scope
        stateObservationJob?.cancel()
        stateObservationJob = null
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Tile clicked")

        serviceScope.launch {
            try {
                val currentState = vpnRepository.connectionState.value
                Log.d(TAG, "Current state: ${currentState::class.simpleName}")

                when (currentState) {
                    is ConnectionState.Disconnected -> {
                        // Check VPN permission first
                        if (!vpnRepository.isVpnPrepared()) {
                            Log.w(TAG, "VPN not prepared, need user permission")
                            // Open the app to request VPN permission
                            openAppAndCollapse()
                            return@launch
                        }

                        // Get the last selected DNS server
                        val selectedDnsId = settingsRepository.getSelectedDnsId()
                        Log.d(TAG, "Selected DNS ID: $selectedDnsId")

                        if (selectedDnsId != null) {
                            // Get the DNS server from repository
                            val dnsServer = getDnsServerById(selectedDnsId)
                            Log.d(TAG, "DNS Server found: ${dnsServer?.name}")

                            if (dnsServer != null) {
                                Log.i(TAG, "Connecting to ${dnsServer.name}")
                                vpnRepository.connect(dnsServer)
                            } else {
                                Log.w(TAG, "No DNS server found for ID: $selectedDnsId")
                                updateTileUnavailable()
                                // Open app to select DNS
                                openAppAndCollapse()
                            }
                        } else {
                            Log.w(TAG, "No DNS server selected")
                            updateTileUnavailable()
                            // Open app to select DNS
                            openAppAndCollapse()
                        }
                    }
                    is ConnectionState.Connected -> {
                        Log.i(TAG, "Disconnecting from ${currentState.server.name}")
                        vpnRepository.disconnect()
                    }
                    is ConnectionState.Error -> {
                        Log.w(TAG, "Error state, retrying connection")
                        // Retry connection
                        val selectedDnsId = settingsRepository.getSelectedDnsId()
                        if (selectedDnsId != null) {
                            val dnsServer = getDnsServerById(selectedDnsId)
                            if (dnsServer != null) {
                                vpnRepository.connect(dnsServer)
                            }
                        }
                    }
                    else -> {
                        // Ignore clicks during connecting/disconnecting
                        Log.d(TAG, "Tile clicked during transition, ignoring")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling tile click", e)
            }
        }
    }

    private fun updateTileState(state: ConnectionState) {
        val tile = qsTile ?: return

        when (state) {
            is ConnectionState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_label)
                tile.contentDescription = getString(R.string.tile_connected, state.server.name)
                QuickSettingsTileCompat.setSubtitle(tile, state.server.name)
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_active)
            }
            is ConnectionState.Disconnected -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_label)
                tile.contentDescription = getString(R.string.tile_disconnected)
                QuickSettingsTileCompat.setSubtitle(tile, getString(R.string.tile_disconnected))
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_inactive)
            }
            is ConnectionState.Connecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_label)
                tile.contentDescription = "Connecting..."
                QuickSettingsTileCompat.setSubtitle(tile, "Connecting...")
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_active)
            }
            is ConnectionState.Disconnecting -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_label)
                tile.contentDescription = "Disconnecting..."
                QuickSettingsTileCompat.setSubtitle(tile, "Disconnecting...")
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_inactive)
            }
            is ConnectionState.Error -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.tile_label)
                tile.contentDescription = "Error: ${state.message}"
                QuickSettingsTileCompat.setSubtitle(tile, "Error")
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_inactive)
            }
        }

        tile.updateTile()
        Log.d(TAG, "Tile updated: state=${state::class.simpleName}")
    }

    private fun updateTileUnavailable() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_UNAVAILABLE
        tile.label = getString(R.string.tile_label)
        tile.contentDescription = getString(R.string.tile_unavailable)
        QuickSettingsTileCompat.setSubtitle(tile, getString(R.string.tile_unavailable))
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_inactive)
        tile.updateTile()
    }

    private fun openAppAndCollapse() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    OPEN_APP_REQUEST_CODE,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            startActivityAndCollapseLegacy(launchIntent)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    /**
     * Helper method to get DNS server by ID
     */
    private suspend fun getDnsServerById(id: String): DnsServer? {
        // Try predefined servers first
        val predefinedServer = dnsRepository.getPreDefinedServers().find { it.id == id }
        if (predefinedServer != null) {
            return predefinedServer
        }

        // Try custom servers
        return dnsRepository.getCustomServers().find { it.id == id }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "DnsTileService destroyed")
    }

}
