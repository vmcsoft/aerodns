package com.vmcsoft.aerodns.data.vpn.packet

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.vmcsoft.aerodns.data.diagnostics.DnsDiagnosticLog
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
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
    ) {
        withContext(Dispatchers.IO) {
            val input = FileInputStream(vpnInterface.fileDescriptor)
            val output = FileOutputStream(vpnInterface.fileDescriptor)
            run(input, output, config, mtu, timeoutMs)
        }
    }

    suspend fun run(
        input: InputStream,
        output: OutputStream,
        config: DnsConnectionConfig,
        mtu: Int,
        timeoutMs: Int,
        shouldContinue: () -> Boolean = { true }
    ) {
        require(mtu > 0) { "MTU must be positive" }

        val buffer = ByteArray(mtu)
        var packetsRead = 0L
        var packetsIgnored = 0L
        var responsesWritten = 0L

        DnsDiagnosticLog.i(
            TAG,
            "packet_loop_start provider=${config.displayName} protocol=${config.protocol} " +
                "packetLoop=${config.enableExperimentalPacketLoop} mtu=$mtu timeoutMs=$timeoutMs " +
                "upstreams=${config.upstreamAddresses}"
        )

        while (currentCoroutineContext().isActive && shouldContinue()) {
            val bytesRead = try {
                input.read(buffer)
            } catch (e: IOException) {
                Log.w(TAG, "Stopping TUN DNS packet loop after read failure", e)
                DnsDiagnosticLog.w(
                    TAG,
                    "packet_loop_stop reason=read_failure packetsRead=$packetsRead " +
                        "responsesWritten=$responsesWritten packetsIgnored=$packetsIgnored",
                    e
                )
                currentCoroutineContext().ensureActive()
                throw e
            }

            if (bytesRead <= 0) {
                DnsDiagnosticLog.i(
                    TAG,
                    "packet_loop_stop reason=eof packetsRead=$packetsRead " +
                        "responsesWritten=$responsesWritten packetsIgnored=$packetsIgnored"
                )
                break
            }

            packetsRead += 1
            DnsDiagnosticLog.d(TAG, "packet_loop_read count=$packetsRead bytes=$bytesRead")
            val packet = buffer.copyOf(bytesRead)
            val response = packetHandler.handlePacket(packet, config, timeoutMs)
            // A cancelled upstream may finish a blocking call late. Never write its reply.
            currentCoroutineContext().ensureActive()
            if (response != null) {
                try {
                    output.write(response)
                    output.flush()
                    responsesWritten += 1
                    DnsDiagnosticLog.d(
                        TAG,
                        "packet_loop_write count=$responsesWritten responseBytes=${response.size} " +
                            "packetsRead=$packetsRead"
                    )
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    val errno = (e.cause as? ErrnoException)?.errno
                    // Android can reject one otherwise frameable datagram under buffer
                    // pressure or a device size limit. That does not invalidate the TUN fd.
                    if (errno == OsConstants.ENOBUFS || errno == OsConstants.EMSGSIZE) {
                        DnsDiagnosticLog.w(TAG, "packet_loop_dropped_response errno=$errno bytes=${response.size}")
                        continue
                    }
                    Log.w(TAG, "Stopping TUN DNS packet loop after write failure", e)
                    DnsDiagnosticLog.w(
                        TAG,
                        "packet_loop_stop reason=write_failure packetsRead=$packetsRead " +
                            "responsesWritten=$responsesWritten packetsIgnored=$packetsIgnored",
                        e
                    )
                    currentCoroutineContext().ensureActive()
                    throw e
                }
            } else {
                packetsIgnored += 1
                DnsDiagnosticLog.d(
                    TAG,
                    "packet_loop_no_response packetsIgnored=$packetsIgnored packetsRead=$packetsRead"
                )
            }
        }
    }

    private companion object {
        private const val TAG = "TunDnsPacketLoop"
    }
}
