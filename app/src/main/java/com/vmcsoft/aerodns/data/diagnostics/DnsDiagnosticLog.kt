package com.vmcsoft.aerodns.data.diagnostics

import android.util.Log
import com.vmcsoft.aerodns.BuildConfig

object DnsDiagnosticLog {
    fun d(tag: String, message: String) {
        if (!VERBOSE) return
        safeLog { Log.d(tag, message) }
    }

    fun i(tag: String, message: String) {
        if (!VERBOSE) return
        safeLog { Log.i(tag, message) }
    }

    fun w(tag: String, message: String) {
        safeLog { Log.w(tag, message) }
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        safeLog { Log.w(tag, message, throwable) }
    }

    private inline fun safeLog(block: () -> Unit) {
        if (!BuildConfig.DEBUG) return
        try {
            block()
        } catch (_: RuntimeException) {
            // Local JVM tests do not provide android.util.Log.
        }
    }

    private const val VERBOSE = false
}
