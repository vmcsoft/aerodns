package com.vmcsoft.aerodns.data.vpn

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnNotificationUpdaterTest {
    @Test fun `a burst delivers only the latest state after the interval`() = runTest {
        val published = mutableListOf<Int>()
        var visible = 0
        val updates = VpnNotificationUpdater(this, { true })
        for (value in 1..20) updates.submit({ published += value; visible = value }, { visible == value })
        runCurrent(); advanceTimeBy(999); runCurrent()
        assertTrue(published.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(20), published)
    }
    @Test fun `a dropped notification is retried until the actual state is visible`() = runTest {
        var calls = 0
        val updates = VpnNotificationUpdater(this, { true })
        updates.submit({ calls++ }, { calls >= 3 })
        advanceUntilIdle()
        assertEquals(3, calls)
        assertEquals(4000, testScheduler.currentTime)
    }
    @Test fun `a replacement supersedes retry of the previous state`() = runTest {
        val published = mutableListOf<String>()
        val updates = VpnNotificationUpdater(this, { true })
        updates.submit({ published += "old" }, { false })
        runCurrent(); advanceTimeBy(1000); runCurrent()
        updates.submit({ published += "new" }, { published.lastOrNull() == "new" })
        advanceUntilIdle()
        assertEquals(listOf("old", "new"), published)
    }
    @Test fun `disconnect cancels pending publication and retry`() = runTest {
        var calls = 0
        val updates = VpnNotificationUpdater(this, { true })
        updates.submit({ calls++ }, { false })
        runCurrent(); advanceTimeBy(1000); runCurrent()
        updates.cancel()
        advanceUntilIdle()
        assertEquals(1, calls)
    }
    @Test fun `blocked delivery has a bounded retry budget and accepts a later update`() = runTest {
        var calls = 0
        val updates = VpnNotificationUpdater(this, { true }, maxAttempts = 3)
        updates.submit({ calls++ }, { false }); advanceUntilIdle()
        assertEquals(3, calls)
        updates.submit({ calls++ }, { calls == 4 }); advanceUntilIdle()
        assertEquals(4, calls)
    }
    @Test fun `disabled notifications do not trigger retries`() = runTest {
        var calls = 0
        val updates = VpnNotificationUpdater(this, { false })
        updates.submit({ calls++ }, { false }); advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(1000, testScheduler.currentTime)
    }
}
