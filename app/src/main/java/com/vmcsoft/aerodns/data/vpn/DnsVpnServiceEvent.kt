package com.vmcsoft.aerodns.data.vpn

import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsHealth
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class DnsVpnServiceEvent {
    data class Established(val config: DnsConnectionConfig, val health: DnsHealth = DnsHealth.Checking) : DnsVpnServiceEvent()
    data class Failed(val config: DnsConnectionConfig?, val message: String) : DnsVpnServiceEvent()
    data class Stopped(val config: DnsConnectionConfig?) : DnsVpnServiceEvent()
}

object DnsVpnServiceEvents {
    private val _events = MutableSharedFlow<DnsVpnServiceEvent>(
        replay = 1,
        extraBufferCapacity = 16,
        // Status snapshots must retain the newest state even if a consumer is slow.
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events = _events.asSharedFlow()

    fun emit(event: DnsVpnServiceEvent) {
        _events.tryEmit(event)
    }
}
