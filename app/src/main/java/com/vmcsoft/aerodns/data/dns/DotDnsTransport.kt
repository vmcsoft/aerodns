package com.vmcsoft.aerodns.data.dns

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

@Singleton
class DotDnsTransport @Inject constructor() {

    var socketProtector: DnsSocketProtector = NoopDnsSocketProtector

    internal var connectionFactory: (
        dotHostname: String,
        connectAddress: String,
        timeoutMs: Int,
        socketProtector: DnsSocketProtector
    ) -> Socket = ::openTlsSocket

    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
    private val overallTimeoutMs: Long = 5000L

    suspend fun query(
        payload: ByteArray,
        dotHostname: String,
        upstreamAddresses: List<String>,
        timeoutMs: Int
    ): DnsTransportResult {
        return withContext(dispatcher) {
            try {
                withTimeout(overallTimeoutMs) {
                    val connectAddresses = buildConnectAddresses(upstreamAddresses, dotHostname)
                    var lastError: DnsTransportResult? = null

                    for (connectAddress in connectAddresses) {
                        val result = queryAddress(payload, dotHostname, connectAddress, timeoutMs)
                        if (result is DnsTransportResult.Success) {
                            return@withTimeout result
                        }
                        lastError = result
                    }

                    lastError ?: DnsTransportResult.Error("DNS-over-TLS forwarding failed")
                }
            } catch (e: TimeoutCancellationException) {
                DnsTransportResult.Timeout
            }
        }
    }

    private fun buildConnectAddresses(
        upstreamAddresses: List<String>,
        dotHostname: String
    ): List<String> {
        val addresses = upstreamAddresses
            .filter { it.isNotBlank() }
            .distinct()
        if (addresses.isEmpty()) {
            return listOf(dotHostname).filter { it.isNotBlank() }
        }

        val (ipv4Addresses, ipv6Addresses) = addresses.partition { !it.contains(':') }
        return ipv4Addresses + ipv6Addresses
    }

    private fun queryAddress(
        payload: ByteArray,
        dotHostname: String,
        connectAddress: String,
        timeoutMs: Int
    ): DnsTransportResult {
        return try {
            connectionFactory(dotHostname, connectAddress, timeoutMs, socketProtector).use { socket ->
                socket.soTimeout = timeoutMs
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val startTime = System.nanoTime()
                output.writeShort(payload.size)
                output.write(payload)
                output.flush()

                val responseLength = input.readUnsignedShort()
                if (responseLength <= 0 || responseLength > MAX_DNS_TLS_RESPONSE_SIZE) {
                    return DnsTransportResult.Error("Invalid DNS-over-TLS response size: $responseLength")
                }

                val response = ByteArray(responseLength)
                input.readFully(response)
                val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)

                DnsTransportResult.Success(
                    payload = response,
                    latencyMs = latencyMs
                )
            }
        } catch (e: SocketProtectionException) {
            DnsTransportResult.Error(e.message ?: "Failed to protect DNS TLS socket from VPN")
        } catch (e: SocketTimeoutException) {
            DnsTransportResult.Timeout
        } catch (e: UnknownHostException) {
            DnsTransportResult.Error("Unknown host: $connectAddress")
        } catch (e: IOException) {
            DnsTransportResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            DnsTransportResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun openTlsSocket(
        dotHostname: String,
        connectAddress: String,
        timeoutMs: Int,
        socketProtector: DnsSocketProtector
    ): Socket {
        val rawSocket = Socket()
        if (!socketProtector.protect(rawSocket)) {
            rawSocket.close()
            throw SocketProtectionException("Failed to protect DNS TLS socket from VPN")
        }

        rawSocket.connect(InetSocketAddress(connectAddress, DNS_TLS_PORT), timeoutMs)
        val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tlsSocket = sslSocketFactory.createSocket(rawSocket, dotHostname, DNS_TLS_PORT, true) as SSLSocket
        tlsSocket.soTimeout = timeoutMs
        configureTlsHostnameVerification(tlsSocket, dotHostname)
        tlsSocket.startHandshake()
        return tlsSocket
    }

    private fun configureTlsHostnameVerification(socket: SSLSocket, dotHostname: String) {
        val parameters = socket.sslParameters
        parameters.endpointIdentificationAlgorithm = HTTPS_ENDPOINT_IDENTIFICATION
        try {
            parameters.serverNames = listOf(SNIHostName(dotHostname))
        } catch (_: IllegalArgumentException) {
            // Endpoint identification still verifies the hostname for IP literals or invalid SNI names.
        }
        socket.sslParameters = parameters
    }

    private class SocketProtectionException(message: String) : IOException(message)

    private companion object {
        private const val DNS_TLS_PORT = 853
        private const val HTTPS_ENDPOINT_IDENTIFICATION = "HTTPS"
        private const val MAX_DNS_TLS_RESPONSE_SIZE = 65_535
    }
}
