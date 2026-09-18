package com.vmcsoft.aerodns.data.vpn.packet

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.IOException
import java.io.InputStream

/**
 * A single TUN reader with an explicit cancellation signal. Closing a duplicated
 * descriptor does not reliably interrupt a blocked TUN read on older Android.
 * Pipe hangup wakes poll without periodic timeouts or closing the service's TUN.
 */
internal class CancellableTunInputStream(vpnInterface: ParcelFileDescriptor) : InputStream() {
    private val wake = ParcelFileDescriptor.createPipe()
    private val tun = try { vpnInterface.dup() } catch (error: Exception) {
        wake.forEach { runCatching { it.close() } }
        throw error
    }
    private val descriptors = arrayOf(
        StructPollfd().apply { fd = tun.fileDescriptor; events = OsConstants.POLLIN.toShort() },
        StructPollfd().apply { fd = wake[0].fileDescriptor; events = OsConstants.POLLIN.toShort() }
    )
    private val gate = Any()
    @Volatile private var closed = false
    private var reading = false
    private var disposed = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        synchronized(gate) {
            if (closed) throw IOException("TUN reader closed")
            check(!reading) { "Only one TUN reader is supported" }
            reading = true
        }
        try {
            while (true) {
                if (closed) throw IOException("TUN reader closed")
                try {
                    Os.poll(descriptors, -1)
                    if (closed) throw IOException("TUN reader closed")
                    val events = descriptors[0].revents.toInt()
                    if (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                        throw IOException("TUN reader descriptor failed")
                    }
                    if (events and OsConstants.POLLIN != 0) return Os.read(tun.fileDescriptor, buffer, offset, length)
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EINTR) throw IOException("TUN read failed", error)
                }
            }
        } finally {
            synchronized(gate) {
                reading = false
                if (closed) disposeLocked()
            }
        }
    }

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) <= 0) -1 else byte[0].toInt() and 0xff
    }

    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            // Closing the only writer produces POLLHUP on the reader, even while
            // the VPN is idle. Keep polled descriptors open until poll returns.
            try { wake[1].close() } finally { if (!reading) disposeLocked() }
        }
    }

    private fun disposeLocked() {
        if (disposed) return
        disposed = true
        listOf(tun, wake[0], wake[1]).forEach { runCatching { it.close() } }
    }
}
