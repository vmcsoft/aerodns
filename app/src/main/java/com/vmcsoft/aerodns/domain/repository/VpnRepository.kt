package com.vmcsoft.aerodns.domain.repository

import com.vmcsoft.aerodns.domain.model.ConnectionState
import com.vmcsoft.aerodns.domain.model.DnsServer
import kotlinx.coroutines.flow.StateFlow

/** Opaque, one-use ownership of a connection temporarily stopped for measurement. */
class SpeedTestPause

interface VpnRepository {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(server: DnsServer)
    suspend fun disconnect()
    fun invalidateSpeedTestRestoration()
    suspend fun pauseForSpeedTest(): SpeedTestPause?
    suspend fun restoreAfterSpeedTest(pause: SpeedTestPause)
    fun isVpnPrepared(): Boolean
}
