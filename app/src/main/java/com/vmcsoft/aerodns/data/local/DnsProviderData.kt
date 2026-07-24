package com.vmcsoft.aerodns.data.local

import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer

object DnsProviderData {

    val providers = listOf(
        // Cloudflare DNS - Public
        DnsServer(
            id = "cloudflare_public",
            name = "Cloudflare",
            primary = "1.1.1.1",
            secondary = "1.0.0.1",
            ipv6Primary = "2606:4700:4700::1111",
            ipv6Secondary = "2606:4700:4700::1001",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            dotHostname = "cloudflare-dns.com",
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH, DnsProtocol.DOT),
            description = "Privacy-focused",
            category = "cloudflare",
            variant = "public"
        ),

        // Cloudflare DNS - Malware Blocking
        DnsServer(
            id = "cloudflare_malware",
            name = "Cloudflare (Block Malware)",
            primary = "1.1.1.2",
            secondary = "1.0.0.2",
            ipv6Primary = "2606:4700:4700::1112",
            ipv6Secondary = "2606:4700:4700::1002",
            dohUrl = "https://security.cloudflare-dns.com/dns-query",
            dotHostname = "security.cloudflare-dns.com",
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH, DnsProtocol.DOT),
            description = "Blocks malware sites",
            category = "cloudflare",
            variant = "malware"
        ),

        // Cloudflare DNS - Family Safe
        DnsServer(
            id = "cloudflare_family",
            name = "Cloudflare (Family Safe)",
            primary = "1.1.1.3",
            secondary = "1.0.0.3",
            ipv6Primary = "2606:4700:4700::1113",
            ipv6Secondary = "2606:4700:4700::1003",
            dohUrl = "https://family.cloudflare-dns.com/dns-query",
            dotHostname = "family.cloudflare-dns.com",
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH, DnsProtocol.DOT),
            description = "Blocks malware + adult content",
            category = "cloudflare",
            variant = "family"
        ),

        // Google DNS
        DnsServer(
            id = "google",
            name = "Google DNS",
            primary = "8.8.8.8",
            secondary = "8.8.4.4",
            ipv6Primary = "2001:4860:4860::8888",
            ipv6Secondary = "2001:4860:4860::8844",
            dohUrl = "https://dns.google/dns-query",
            dotHostname = "dns.google",
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH, DnsProtocol.DOT),
            description = "Fast and reliable"
        ),

        // AdGuard DNS
        DnsServer(
            id = "adguard",
            name = "AdGuard DNS",
            primary = "94.140.14.14",
            secondary = "94.140.15.15",
            ipv6Primary = "2a10:50c0::ad1:ff",
            ipv6Secondary = "2a10:50c0::ad2:ff",
            dohUrl = "https://dns.adguard-dns.com/dns-query",
            dotHostname = "dns.adguard-dns.com",
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH, DnsProtocol.DOT),
            description = "Ad-blocking"
        ),

        // OpenDNS
        DnsServer(
            id = "opendns",
            name = "OpenDNS",
            primary = "208.67.222.222",
            secondary = "208.67.220.220",
            ipv6Primary = "2620:119:35::35",
            ipv6Secondary = "2620:119:53::53",
            dohUrl = "https://doh.opendns.com/dns-query",
            dotHostname = "dns.opendns.com",
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH, DnsProtocol.DOT),
            description = "Secure and fast"
        ),

        // Quad9
        DnsServer(
            id = "quad9",
            name = "Quad9",
            primary = "9.9.9.9",
            secondary = "149.112.112.112",
            ipv6Primary = "2620:fe::fe",
            ipv6Secondary = "2620:fe::9",
            dohUrl = "https://dns.quad9.net/dns-query",
            dotHostname = "dns.quad9.net",
            supportedProtocols = listOf(DnsProtocol.STANDARD, DnsProtocol.DOH, DnsProtocol.DOT),
            description = "Security-focused"
        )
    )
}
