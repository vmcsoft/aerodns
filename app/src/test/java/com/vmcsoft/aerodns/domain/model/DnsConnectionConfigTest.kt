package com.vmcsoft.aerodns.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsConnectionConfigTest {

    @Test
    fun `standard config prefers IPv6 first on dual stack`() {
        val server = DnsServer(
            id = "cloudflare",
            name = "Cloudflare",
            primary = "1.1.1.1",
            secondary = "1.0.0.1",
            ipv6Primary = "2606:4700:4700::1111",
            ipv6Secondary = "2606:4700:4700::1001"
        )

        val result = buildDnsConnectionConfig(server, NetworkIpStack.DUAL_STACK)

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "2606:4700:4700::1111",
                "2606:4700:4700::1001",
                "1.1.1.1",
                "1.0.0.1"
            ),
            result.getOrThrow().upstreamAddresses
        )
    }

    @Test
    fun `standard config uses IPv4 addresses only on IPv4-only network`() {
        val server = DnsServer(
            id = "google",
            name = "Google",
            primary = "8.8.8.8",
            secondary = "8.8.4.4",
            ipv6Primary = "2001:4860:4860::8888",
            ipv6Secondary = "2001:4860:4860::8844"
        )

        val result = buildDnsConnectionConfig(server, NetworkIpStack.IPv4_ONLY)

        assertTrue(result.isSuccess)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), result.getOrThrow().upstreamAddresses)
    }

    @Test
    fun `standard config appends IPv6 fallback for IPv4-only custom DNS on dual stack`() {
        val server = DnsServer(
            id = "custom",
            name = "Custom",
            primary = "192.0.2.1",
            isCustom = true
        )

        val result = buildDnsConnectionConfig(
            server = server,
            stack = NetworkIpStack.DUAL_STACK,
            dualStackIpv6Fallback = listOf("2001:db8::1", "2001:db8::2")
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("192.0.2.1", "2001:db8::1", "2001:db8::2"),
            result.getOrThrow().upstreamAddresses
        )
    }

    @Test
    fun `standard config fails when no compatible addresses exist`() {
        val server = DnsServer(id = "empty", name = "Empty", primary = "")

        val result = buildDnsConnectionConfig(server, NetworkIpStack.IPv4_ONLY)

        assertFalse(result.isSuccess)
    }

    @Test
    fun `DoH config requires DoH URL`() {
        val server = DnsServer(id = "custom", name = "Custom", primary = "1.1.1.1")

        val result = buildDnsConnectionConfig(server, NetworkIpStack.IPv4_ONLY, DnsProtocol.DOH)

        assertFalse(result.isSuccess)
    }

    @Test
    fun `DoH config includes DoH URL when supported`() {
        val server = DnsServer(
            id = "cloudflare",
            name = "Cloudflare",
            primary = "1.1.1.1",
            dohUrl = "https://cloudflare-dns.com/dns-query"
        )

        val result = buildDnsConnectionConfig(server, NetworkIpStack.IPv4_ONLY, DnsProtocol.DOH)

        assertTrue(result.isSuccess)
        assertEquals(DnsProtocol.DOH, result.getOrThrow().protocol)
        assertEquals("https://cloudflare-dns.com/dns-query", result.getOrThrow().dohUrl)
    }

    @Test
    fun `DoH config uses fallback bootstrap when server has no addresses`() {
        val server = DnsServer(
            id = "custom-doh",
            name = "Custom DoH",
            primary = "",
            dohUrl = "https://dns.example/dns-query",
            supportedProtocols = listOf(DnsProtocol.DOH)
        )

        val result = buildDnsConnectionConfig(
            server = server,
            stack = NetworkIpStack.IPv4_ONLY,
            protocol = DnsProtocol.DOH,
            dohBootstrapFallback = listOf("9.9.9.9")
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("9.9.9.9"), result.getOrThrow().upstreamAddresses)
    }

    @Test
    fun `DoT config requires DoT hostname`() {
        val server = DnsServer(id = "custom", name = "Custom", primary = "1.1.1.1")

        val result = buildDnsConnectionConfig(server, NetworkIpStack.IPv4_ONLY, DnsProtocol.DOT)

        assertFalse(result.isSuccess)
    }

    @Test
    fun `DoT config includes DoT hostname when supported`() {
        val server = DnsServer(
            id = "cloudflare",
            name = "Cloudflare",
            primary = "1.1.1.1",
            dotHostname = "cloudflare-dns.com"
        )

        val result = buildDnsConnectionConfig(server, NetworkIpStack.IPv4_ONLY, DnsProtocol.DOT)

        assertTrue(result.isSuccess)
        assertEquals(DnsProtocol.DOT, result.getOrThrow().protocol)
        assertEquals("cloudflare-dns.com", result.getOrThrow().dotHostname)
    }

    @Test
    fun `selectable protocols expose DoH and hide DoT`() {
        val server = DnsServer(
            id = "cloudflare",
            name = "Cloudflare",
            primary = "1.1.1.1",
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH, DnsProtocol.DOT)
        )

        assertEquals(listOf(DnsProtocol.STANDARD, DnsProtocol.DOH), server.selectableProtocols())
        assertEquals(DnsProtocol.STANDARD, server.resolveSelectedProtocol(DnsProtocol.DOT))
    }

    @Test
    fun `DoH only server falls back to DoH protocol`() {
        val server = DnsServer(
            id = "custom-doh",
            name = "Custom DoH",
            primary = "",
            dohUrl = "https://dns.example/dns-query",
            supportedProtocols = listOf(DnsProtocol.DOH)
        )

        assertEquals(listOf(DnsProtocol.DOH), server.selectableProtocols())
        assertEquals(DnsProtocol.DOH, server.resolveSelectedProtocol(DnsProtocol.STANDARD))
    }

    @Test
    fun `config disables experimental packet loop by default`() {
        val server = DnsServer(id = "cloudflare", name = "Cloudflare", primary = "1.1.1.1")

        val result = buildDnsConnectionConfig(server, NetworkIpStack.IPv4_ONLY)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().enableExperimentalPacketLoop)
    }
}
