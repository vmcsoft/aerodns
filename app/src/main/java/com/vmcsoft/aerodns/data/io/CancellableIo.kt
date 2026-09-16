package com.vmcsoft.aerodns.data.io

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Run blocking I/O on the caller's I/O dispatcher, closing/cancelling it on cancellation. */
internal suspend fun <T> cancellableIo(cancel: () -> Unit, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching(cancel) }
        try {
            if (continuation.isActive) continuation.resume(block())
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
