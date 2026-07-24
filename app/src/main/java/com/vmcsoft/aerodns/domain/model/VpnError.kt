package com.vmcsoft.aerodns.domain.model

/**
 * Represents VPN-related errors for user-facing messages.
 */
sealed class VpnError {
    object PermissionDenied : VpnError()
    object NetworkUnavailable : VpnError()
    data class ConnectionFailed(val reason: String) : VpnError()
    object AlreadyConnected : VpnError()

    fun toUserMessage(): String = when (this) {
        is PermissionDenied ->
            "VPN permission required. Please allow the connection."
        is NetworkUnavailable ->
            "No internet connection. Please check your network."
        is ConnectionFailed ->
            "Connection failed: ${reason}"
        is AlreadyConnected ->
            "Already connected."
    }
}
