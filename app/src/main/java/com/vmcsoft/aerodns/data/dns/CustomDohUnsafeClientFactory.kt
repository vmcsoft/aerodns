package com.vmcsoft.aerodns.data.dns

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Builds an isolated, opt-in client for custom DoH resolvers whose certificates
 * cannot be validated by Android's normal trust store. Never use this for the
 * app's default providers or for general HTTP traffic.
 */
object CustomDohUnsafeClientFactory {
    fun build(
        upstreamAddresses: List<String>,
        timeoutMs: Int,
        socketProtector: DnsSocketProtector,
        customBootstrapIp: String?
    ): OkHttpClient {
        val trustManager = UntrustedCustomDohTrustManager()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }

        return OkHttpClient.Builder()
            .dns(CustomDohBootstrapDns(customBootstrapIp, upstreamAddresses))
            .socketFactory(DohDnsTransport.ProtectedSocketFactory(socketProtector))
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(CustomDohHostnameVerifier)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(OVERALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private class UntrustedCustomDohTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private object CustomDohHostnameVerifier : HostnameVerifier {
        override fun verify(hostname: String?, session: javax.net.ssl.SSLSession?): Boolean = true
    }

    private const val OVERALL_TIMEOUT_MS = 5_000L
}
