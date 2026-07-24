package com.vmcsoft.aerodns.data.dns

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UdpDnsTransport @Inject constructor() : DnsTransport {

    var socketProtector: DnsSocketProtector = NoopDnsSocketProtector

    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
    private val overallTimeoutMs: Long = 5000L

    override suspend fun query(
        payload: ByteArray,
        upstreamAddress: String,
        timeoutMs: Int
    ): DnsTransportResult {
        return withContext(dispatcher) {
            try {
                withTimeout(overallTimeoutMs) {
                    val address = InetAddress.getByName(upstreamAddress)

                    DatagramSocket().use { socket ->
                        if (!socketProtector.protect(socket)) {
                            return@withTimeout DnsTransportResult.Error("Failed to protect DNS socket from VPN")
                        }

                        socket.soTimeout = timeoutMs
                        val request = DatagramPacket(payload, payload.size, address, DNS_PORT)
                        val responseBuffer = ByteArray(DnsWireMessage.DEFAULT_RESPONSE_BUFFER_SIZE)
                        val response = DatagramPacket(responseBuffer, responseBuffer.size)

                        val startTime = System.nanoTime()
                        socket.send(request)
                        socket.receive(response)
                        val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)

                        DnsTransportResult.Success(
                            payload = responseBuffer.copyOf(response.length),
                            latencyMs = latencyMs
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                DnsTransportResult.Timeout
            } catch (e: SocketTimeoutException) {
                DnsTransportResult.Timeout
            } catch (e: UnknownHostException) {
                DnsTransportResult.Error("Unknown host: $upstreamAddress")
            } catch (e: IOException) {
                DnsTransportResult.Error("Network error: ${e.message}")
            } catch (e: Exception) {
                DnsTransportResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private companion object {
        private const val DNS_PORT = 53
    }
}
