package com.vmcsoft.aerodns.data.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread latest-state delivery, including bounded retries after Android drops an update. */
internal class VpnNotificationUpdater(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val intervalMillis: Long = 1_000,
    private val maxAttempts: Int = 10
) {
    private class Update(val publish: () -> Unit, val visible: () -> Boolean)
    private var pending: Update? = null
    private var job: Job? = null

    fun submit(publish: () -> Unit, visible: () -> Boolean) {
        pending = Update(publish, visible)
        if (job?.isActive == true) return
        job = scope.launch {
            var previous: Update? = null
            var attempts = 0
            while (true) {
                delay(intervalMillis)
                val update = pending ?: break
                if (update !== previous) { previous = update; attempts = 0 }
                if (!enabled() || update.visible() || attempts >= maxAttempts) {
                    if (pending === update) pending = null
                    break
                }
                update.publish()
                attempts++
            }
        }
    }

    fun cancel() {
        pending = null
        job?.cancel()
        job = null
    }
}
