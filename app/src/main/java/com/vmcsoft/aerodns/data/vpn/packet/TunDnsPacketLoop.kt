package com.vmcsoft.aerodns.data.vpn.packet

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import com.vmcsoft.aerodns.data.diagnostics.DnsDiagnosticLog
import com.vmcsoft.aerodns.data.io.cancellableIo
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TunDnsPacketLoop @Inject constructor(
    private val packetHandler: TunDnsPacketHandler
) {
    suspend fun run(
        vpnInterface: ParcelFileDescriptor,
        config: DnsConnectionConfig,
        mtu: Int,
        timeoutMs: Int
    ) = withContext(Dispatchers.IO) {
        // Cancelling a blocked reader must not close the service-owned descriptor:
        // it remains alive until establish() finishes a replacement handover.
        CancellableTunInputStream(vpnInterface).use { input ->
            run(input, FileOutputStream(vpnInterface.fileDescriptor), config, mtu, timeoutMs)
        }
    }

    suspend fun run(
        input: InputStream,
        output: OutputStream,
        config: DnsConnectionConfig,
        mtu: Int,
        timeoutMs: Int,
        shouldContinue: () -> Boolean = { true }
    ) = coroutineScope {
        require(mtu > 0) { "MTU must be positive" }
        val pending = Channel<PendingPacket>(QUEUE_CAPACITY)
        val writer = Mutex()
        // All workers, queued packets and writes belong to this invocation/interface.
        repeat(WORKER_COUNT) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                for (packet in pending) {
                    ensureActive()
                    val remaining = ((packet.deadlineNanos - System.nanoTime() + 999_999) / 1_000_000).toInt()
                    if (remaining <= 0) continue
                    val response = withTimeoutOrNull(remaining.toLong()) {
                        // Keep transport settings stable for HTTP client reuse; this scope
                        // enforces the shorter remaining admission budget by cancelling I/O.
                        packetHandler.handlePacket(packet.bytes, config, timeoutMs)
                    } ?: continue
                    writer.withLock {
                        // A blocking upstream may return after its connection was cancelled.
                        ensureActive()
                        try {
                            output.write(response)
                            output.flush()
                        } catch (e: IOException) {
                            ensureActive()
                            val errno = (e.cause as? ErrnoException)?.errno
                            if (errno != OsConstants.ENOBUFS && errno != OsConstants.EMSGSIZE) throw e
                            DnsDiagnosticLog.w(TAG, "packet_loop_dropped_response errno=$errno bytes=${response.size}")
                        }
                    }
                }
            }
        }
        try {
            val buffer = ByteArray(mtu)
            while (isActive && shouldContinue()) {
                val bytesRead = cancellableIo(cancel = { input.close() }) { input.read(buffer) }
                ensureActive()
                if (bytesRead <= 0) break
                // Never suspend the reader behind a full queue or create a job per packet.
                // Drop newest on overload. UDP callers time out/retry; no provider fallback.
                if (pending.trySend(PendingPacket(buffer.copyOf(bytesRead), System.nanoTime() + timeoutMs * 1_000_000L)).isFailure) {
                    DnsDiagnosticLog.d(TAG, "packet_loop_dropped_query reason=queue_full")
                }
            }
        } finally {
            // EOF drains accepted work. Cancellation/fault cancels every worker instead.
            pending.close()
        }
    }

    private data class PendingPacket(val bytes: ByteArray, val deadlineNanos: Long)

    internal companion object {
        private const val TAG = "TunDnsPacketLoop"
        const val WORKER_COUNT = 4
        const val QUEUE_CAPACITY = 32
    }
}
