package com.vmcsoft.aerodns.domain.model

import java.io.Serializable

data class DnsServer(
    val id: String,
    val name: String,
    val primary: String,
    val secondary: String? = null,
    val ipv6Primary: String? = null,
    val ipv6Secondary: String? = null,
    val dohUrl: String? = null,
    val customBootstrapIp: String? = null,
    val allowUntrustedCertificates: Boolean = false,
    val dotHostname: String? = null,
    val supportedProtocols: List<DnsProtocol> = listOf(DnsProtocol.STANDARD),
    val iconResId: Int? = null,
    val description: String = "",
    val isCustom: Boolean = false,
    val category: String? = null,  // For grouping variants (e.g., "cloudflare")
    val variant: String? = null     // Variant type (e.g., "public", "malware", "family")
) : Serializable
