package com.vmcsoft.aerodns.data.vpn

import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class DnsVpnServiceEvent {
    data class Established(val config: DnsConnectionConfig) : DnsVpnServiceEvent()
    data class Failed(val config: DnsConnectionConfig?, val message: String) : DnsVpnServiceEvent()
    data class Stopped(val config: DnsConnectionConfig?) : DnsVpnServiceEvent()
}

object DnsVpnServiceEvents {
    private val _events = MutableSharedFlow<DnsVpnServiceEvent>(
        replay = 1,
        extraBufferCapacity = 16
    )

    val events = _events.asSharedFlow()

    fun emit(event: DnsVpnServiceEvent) {
        _events.tryEmit(event)
    }
}
