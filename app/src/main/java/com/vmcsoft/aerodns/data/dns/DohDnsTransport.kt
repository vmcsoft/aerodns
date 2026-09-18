package com.vmcsoft.aerodns.data.dns

import android.net.Network
import com.vmcsoft.aerodns.data.io.cancellableIo
import com.vmcsoft.aerodns.data.diagnostics.DnsDiagnosticLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import javax.net.ssl.SSLException

@Singleton
class DohDnsTransport @Inject constructor(
    private val endpointResolver: DohEndpointResolver
) {

    var socketProtector: DnsSocketProtector = NoopDnsSocketProtector

    internal var callFactoryBuilder: (
        endpoint: DohEndpoint,
        timeoutMs: Int,
        socketProtector: DnsSocketProtector
    ) -> Call.Factory = ::buildCallFactory

    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
    private val overallTimeoutMs: Long = 5000L
    private val callFactoryCache = LinkedHashMap<CallFactoryCacheKey, OkHttpClient>(8, 0.75f, true)

    suspend fun query(
        payload: ByteArray,
        dohUrl: String,
        upstreamAddresses: List<String>,
        timeoutMs: Int,
        customBootstrapIp: String? = null,
        allowUntrustedCertificates: Boolean = false
    ): DnsTransportResult {
        return withContext(dispatcher) {
            try {
                withTimeout(overallTimeoutMs) {
                    val url = dohUrl.toHttpUrl()
                    if (!url.isHttps) {
                        return@withTimeout DnsTransportResult.Error("DNS-over-HTTPS URL must use https")
                    }

                    val bootstrapAddresses = buildBootstrapAddresses(upstreamAddresses, customBootstrapIp)
                    val endpoint = endpointResolver.resolve(url.host, bootstrapAddresses)

                    DnsDiagnosticLog.d(
                        TAG,
                        "doh_query_start url=$url bootstrap=$bootstrapAddresses " +
                            "payloadBytes=${payload.size} timeoutMs=$timeoutMs"
                    )
                    val request = Request.Builder()
                        .url(url)
                        .post(payload.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
                        .header("Accept", DNS_MESSAGE_CONTENT_TYPE)
                        .header("Content-Type", DNS_MESSAGE_CONTENT_TYPE)
                        .header("Accept-Encoding", "identity")
                        .build()

                    val startTime = System.nanoTime()
                    val callFactory = if (allowUntrustedCertificates) {
                        cachedCallFactory(
                            endpoint = endpoint,
                            timeoutMs = timeoutMs,
                            socketProtector = socketProtector,
                            allowUntrustedCertificates = true
                        )
                    } else {
                        callFactoryBuilder(endpoint, timeoutMs, socketProtector)
                    }
                    val call = callFactory.newCall(request)
                    cancellableIo(cancel = { call.cancel() }) {
                        // Keep cancellation attached through body consumption as well as headers.
                        call.execute().use { response -> response.toDnsTransportResult(startTime) }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                DnsDiagnosticLog.w(TAG, "doh_query_timeout kind=overall timeoutMs=$overallTimeoutMs")
                DnsTransportResult.Timeout
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                DnsDiagnosticLog.w(TAG, "doh_query_timeout kind=socket message=${e.message}")
                DnsTransportResult.Timeout
            } catch (e: UnknownHostException) {
                DnsDiagnosticLog.w(TAG, "doh_query_error kind=unknown_host message=${e.message}")
                DnsTransportResult.Error("Unknown DoH bootstrap host: ${e.message}")
            } catch (e: SSLException) {
                DnsDiagnosticLog.w(TAG, "doh_query_error kind=ssl message=${e.message}", e)
                DnsTransportResult.Error(DnsSecurityMessages.UNTRUSTED_CERTIFICATE)
            } catch (e: IOException) {
                DnsDiagnosticLog.w(TAG, "doh_query_error kind=io message=${e.message}", e)
                DnsTransportResult.Error("Network error: ${e.message}")
            } catch (e: IllegalArgumentException) {
                DnsDiagnosticLog.w(TAG, "doh_query_error kind=invalid_argument message=${e.message}")
                DnsTransportResult.Error(e.message ?: "Invalid DNS-over-HTTPS URL")
            } catch (e: Exception) {
                DnsDiagnosticLog.w(TAG, "doh_query_error kind=unexpected message=${e.message}", e)
                DnsTransportResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    internal fun buildCallFactory(
        endpoint: DohEndpoint,
        timeoutMs: Int,
        socketProtector: DnsSocketProtector
    ): Call.Factory = cachedCallFactory(endpoint, timeoutMs, socketProtector, false)

    internal fun cachedCallFactory(
        endpoint: DohEndpoint,
        timeoutMs: Int,
        socketProtector: DnsSocketProtector,
        allowUntrustedCertificates: Boolean
    ): OkHttpClient {
        val key = CallFactoryCacheKey(
            endpoint = endpoint,
            timeoutMs = timeoutMs,
            socketProtector = socketProtector,
            allowUntrustedCertificates = allowUntrustedCertificates
        )
        return synchronized(callFactoryCache) {
            // Do not retain pooled connections for an old network or an old answer.
            val obsolete = callFactoryCache.keys.filter {
                it.endpoint.hostname == endpoint.hostname &&
                    (it.endpoint != endpoint || it.socketProtector != socketProtector)
            }
            obsolete.forEach { oldKey ->
                callFactoryCache.remove(oldKey)?.connectionPool?.evictAll()
            }
            callFactoryCache[key]?.let { return@synchronized it }
            // A profile/timeout sweep must not retain an unbounded set of pools.
            if (callFactoryCache.size >= MAX_CACHED_CLIENTS) {
                val oldest = callFactoryCache.keys.first()
                callFactoryCache.remove(oldest)?.connectionPool?.evictAll()
            }
            // Certificate policy is part of the key. Never reuse an opted-in TLS
            // connection for a request that requires normal certificate validation.
            val client = if (allowUntrustedCertificates) {
                CustomDohUnsafeClientFactory.build(endpoint, timeoutMs, socketProtector)
            } else {
                OkHttpClient.Builder()
                    .dns(CustomDohBootstrapDns(endpoint))
                    .socketFactory(ProtectedSocketFactory(socketProtector, endpoint.network))
                    .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .callTimeout(overallTimeoutMs, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(true)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
            }
            callFactoryCache[key] = client
            client
        }
    }

    private fun Response.toDnsTransportResult(startTime: Long): DnsTransportResult {
        DnsDiagnosticLog.d(TAG, "doh_http_response protocol=$protocol code=$code successful=$isSuccessful")
        if (!isSuccessful) {
            return DnsTransportResult.Error("DoH request failed with HTTP $code")
        }

        val responseBody = body ?: return DnsTransportResult.Error("Empty DoH response body")
        val mediaType = responseBody.contentType()
        if (mediaType?.type != "application" || mediaType.subtype != "dns-message") {
            return DnsTransportResult.Error("Invalid DoH response content type")
        }
        val declaredLength = responseBody.contentLength()
        if (declaredLength == 0L || declaredLength > MAX_DNS_RESPONSE_SIZE) {
            return DnsTransportResult.Error("Invalid DoH response size: $declaredLength")
        }

        // Content-Length may be missing or dishonest. Read at most the wire limit
        // plus one byte, rather than allocating the entire remote body first.
        val buffer = Buffer()
        val source = responseBody.source()
        while (buffer.size <= MAX_DNS_RESPONSE_SIZE) {
            if (source.read(buffer, MAX_DNS_RESPONSE_SIZE + 1L - buffer.size) == -1L) break
        }
        if (buffer.size == 0L || buffer.size > MAX_DNS_RESPONSE_SIZE ||
            (declaredLength >= 0 && declaredLength != buffer.size)) {
            return DnsTransportResult.Error("Invalid DoH response size: ${buffer.size}")
        }
        val responsePayload = buffer.readByteArray()

        val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
        DnsDiagnosticLog.d(
            TAG,
            "doh_query_success protocol=$protocol latencyMs=$latencyMs responseBytes=${responsePayload.size}"
        )
        return DnsTransportResult.Success(
            payload = responsePayload,
            latencyMs = latencyMs
        )
    }

    private fun buildBootstrapAddresses(
        upstreamAddresses: List<String>,
        customBootstrapIp: String?
    ): List<String> {
        customBootstrapIp?.takeIf { it.isNotBlank() }?.let {
            return listOf(it)
        }

        val addresses = upstreamAddresses
            .filter { it.isNotBlank() }
            .distinct()
        val (ipv4Addresses, ipv6Addresses) = addresses.partition { !it.contains(':') }
        return ipv4Addresses + ipv6Addresses
    }

    internal class ProtectedSocketFactory(
        private val socketProtector: DnsSocketProtector,
        private val network: Network? = null
    ) : SocketFactory() {
        override fun createSocket(): Socket {
            return createProtectedSocket()
        }

        override fun createSocket(host: String, port: Int): Socket {
            return createSocket().apply {
                connect(InetSocketAddress(host, port))
            }
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            return createProtectedSocket(localHost, localPort).apply {
                connect(InetSocketAddress(host, port))
            }
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            return createSocket().apply {
                connect(InetSocketAddress(host, port))
            }
        }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
            return createProtectedSocket(localAddress, localPort).apply {
                connect(InetSocketAddress(address, port))
            }
        }

        private fun createProtectedSocket(
            localAddress: InetAddress? = null,
            localPort: Int = 0
        ): Socket {
            return Socket().also { socket ->
                try {
                    socket.bind(
                        if (localAddress == null) {
                            null
                        } else {
                            InetSocketAddress(localAddress, localPort)
                        }
                    )
                    if (!socketProtector.protect(socket)) {
                        throw IOException("Failed to protect DNS HTTPS socket from VPN")
                    }
                    network?.bindSocket(socket)
                    DnsDiagnosticLog.d(TAG, "doh_socket_protected bound=${socket.isBound}")
                } catch (e: IOException) {
                    socket.close()
                    DnsDiagnosticLog.w(TAG, "doh_socket_protect_failed message=${e.message}")
                    throw e
                }
            }
        }
    }

    private companion object {
        private const val TAG = "DohDnsTransport"
        private const val DNS_MESSAGE_CONTENT_TYPE = "application/dns-message"
        private val DNS_MESSAGE_MEDIA_TYPE = DNS_MESSAGE_CONTENT_TYPE.toMediaType()
        private const val MAX_DNS_RESPONSE_SIZE = 65_535
        private const val MAX_CACHED_CLIENTS = 8
    }

    private data class CallFactoryCacheKey(
        val endpoint: DohEndpoint,
        val timeoutMs: Int,
        val socketProtector: DnsSocketProtector,
        val allowUntrustedCertificates: Boolean
    )
}
