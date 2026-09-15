package com.vmcsoft.aerodns.data.vpn

import android.content.SharedPreferences
import com.vmcsoft.aerodns.domain.model.DnsConnectionConfig
import com.vmcsoft.aerodns.domain.model.DnsProtocol
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class VpnRecoveryStoreTest {
    private val values = mutableMapOf<String, Any?>()
    private var commitSucceeds = true
    private val editor = mockk<SharedPreferences.Editor> {
        every { clear() } answers { values.clear(); this@mockk }
        every { putString(any(), any()) } answers { values[firstArg()] = secondArg<String?>(); this@mockk }
        every { putInt(any(), any()) } answers { values[firstArg()] = secondArg<Int>(); this@mockk }
        every { putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); this@mockk }
        every { commit() } answers { commitSucceeds }
    }
    private val preferences = mockk<SharedPreferences> {
        every { edit() } returns editor
        every { getString(any(), any()) } answers { (values[firstArg()] as String?) ?: secondArg<String?>() }
        every { getInt(any(), any()) } answers { (values[firstArg()] as Int?) ?: secondArg<Int>() }
        every { getBoolean(any(), any()) } answers { (values[firstArg()] as Boolean?) ?: secondArg<Boolean>() }
    }
    private val store = VpnRecoveryStore(preferences)
    private val config = DnsConnectionConfig("custom", "Custom", DnsProtocol.DOH,
        listOf("2001:4860:4860::8888", "8.8.8.8"), "https://resolver.example/dns-query",
        customBootstrapIp = "2001:db8::1", allowUntrustedCertificates = true,
        connectionRequestId = "operation-1", enableExperimentalPacketLoop = true)

    @Test fun `round trip retains order protocol endpoint and certificate opt in`() {
        assertTrue(store.save(config))
        assertEquals(config, VpnRecoveryStore(preferences).load())
    }

    @Test fun `url only endpoint survives without substituting upstream addresses`() {
        val urlOnly = config.copy(upstreamAddresses = emptyList(), customBootstrapIp = null)
        store.save(urlOnly)
        assertEquals(urlOnly, store.load())
    }

    @Test fun `standard replacement removes old certificate and endpoint settings`() {
        store.save(config)
        val standard = DnsConnectionConfig("standard", "Standard", DnsProtocol.STANDARD, listOf("9.9.9.9"))
        store.save(standard)
        assertEquals(standard, store.load())
    }

    @Test fun `disconnect record cannot be loaded by a new store instance`() {
        store.save(config)
        assertTrue(store.clear())
        assertNull(VpnRecoveryStore(preferences).load())
    }

    @Test fun `missing or unsupported schema fails closed`() {
        assertNull(store.load())
        store.save(config)
        values["schema"] = 2
        assertNull(store.load())
    }

    @Test fun `invalid protocol and missing identity fail closed`() {
        store.save(config)
        values["protocol"] = "UNKNOWN"
        assertNull(store.load())
        store.save(config)
        values.remove("serverId")
        assertNull(store.load())
    }

    @Test fun `corrupt preference types fail closed`() {
        store.save(config)
        values["packetLoop"] = "true"
        assertNull(store.load())
    }

    @Test fun `non durable save or clear is reported to caller`() {
        commitSucceeds = false
        assertFalse(store.save(config))
        assertFalse(store.clear())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypted protocol cannot be persisted as address only fallback`() {
        store.save(config.copy(enableExperimentalPacketLoop = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `plaintext DoH endpoint cannot be persisted`() {
        store.save(config.copy(dohUrl = "http://resolver.example/dns-query"))
    }
}
