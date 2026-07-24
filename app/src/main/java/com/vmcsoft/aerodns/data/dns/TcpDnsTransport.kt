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

@Singleton
class TcpDnsTransport @Inject constructor() : DnsTransport {

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
                    Socket().use { socket ->
                        if (!socketProtector.protect(socket)) {
                            return@withTimeout DnsTransportResult.Error("Failed to protect DNS TCP socket from VPN")
                        }

                        socket.soTimeout = timeoutMs
                        socket.connect(InetSocketAddress(upstreamAddress, DNS_PORT), timeoutMs)

                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())

                        val startTime = System.nanoTime()
                        output.writeShort(payload.size)
                        output.write(payload)
                        output.flush()

                        val responseLength = input.readUnsignedShort()
                        if (responseLength <= 0 || responseLength > MAX_DNS_TCP_RESPONSE_SIZE) {
                            return@withTimeout DnsTransportResult.Error("Invalid DNS TCP response size: $responseLength")
                        }

                        val response = ByteArray(responseLength)
                        input.readFully(response)
                        val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)

                        DnsTransportResult.Success(
                            payload = response,
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
        private const val MAX_DNS_TCP_RESPONSE_SIZE = 65_535
    }
}
