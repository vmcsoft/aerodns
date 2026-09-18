package com.vmcsoft.aerodns.data.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DnsVpnServiceEventsTest {
    @Test fun `a slow subscriber cannot hide the latest terminal state`() = runTest {
        val blocked = CompletableDeferred<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            DnsVpnServiceEvents.events.collect { blocked.await() }
        }
        repeat(32) { DnsVpnServiceEvents.emit(DnsVpnServiceEvent.Failed(null, "transition $it")) }
        val stopped = DnsVpnServiceEvent.Stopped(null)
        DnsVpnServiceEvents.emit(stopped)
        assertSame(stopped, DnsVpnServiceEvents.events.replayCache.last())
    }
}
