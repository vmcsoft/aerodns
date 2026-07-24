package com.vmcsoft.aerodns.domain.usecase

import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import java.net.URI
import javax.inject.Inject

class SaveCustomDnsUseCase @Inject constructor(
    private val dnsRepository: DnsRepository
) {
    private val ipv4Regex = Regex(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
        "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    )

    private val ipv6Regex = Regex(
        "^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|" +
        "([0-9a-fA-F]{1,4}:){1,7}:|" +
        "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|" +
        "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|" +
        "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|" +
        "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|" +
        "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|" +
        "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|" +
        ":((:[0-9a-fA-F]{1,4}){1,7}|:))$"
    )

    suspend operator fun invoke(
        name: String,
        primary: String,
        secondary: String? = null,
        dohUrl: String? = null,
        protocol: DnsProtocol = DnsProtocol.STANDARD,
        customBootstrapIp: String? = null,
        allowUntrustedCertificates: Boolean = false
    ): Result<DnsServer> {
        val normalizedDohUrl = normalizeDohUrl(dohUrl).getOrElse {
            return Result.failure(it)
        }
        val normalizedBootstrapIp = normalizeBootstrapIp(customBootstrapIp).getOrElse {
            return Result.failure(it)
        }
        if (protocol == DnsProtocol.DOH && normalizedDohUrl == null) {
            return Result.failure(IllegalArgumentException("DoH URL is required"))
        }

        // Validate primary DNS
        val normalizedPrimary = primary.trim()
        val isPrimaryIpv4 = ipv4Regex.matches(normalizedPrimary)
        val isPrimaryIpv6 = ipv6Regex.matches(normalizedPrimary)

        if (normalizedPrimary.isBlank() && protocol != DnsProtocol.DOH) {
            return Result.failure(IllegalArgumentException("Invalid primary DNS address"))
        }

        if (normalizedPrimary.isNotBlank() && !isPrimaryIpv4 && !isPrimaryIpv6) {
            return Result.failure(IllegalArgumentException("Invalid primary DNS address"))
        }

        if (normalizedPrimary.isBlank() && !secondary.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Primary bootstrap DNS is required before secondary DNS"))
        }

        // Validate secondary DNS if provided
        if (secondary != null && secondary.isNotBlank()) {
            val isSecondaryIpv4 = ipv4Regex.matches(secondary)
            val isSecondaryIpv6 = ipv6Regex.matches(secondary)

            if (!isSecondaryIpv4 && !isSecondaryIpv6) {
                return Result.failure(IllegalArgumentException("Invalid secondary DNS address"))
            }
        }

        val supportedProtocols = when {
            protocol == DnsProtocol.DOH && normalizedPrimary.isBlank() -> listOf(DnsProtocol.DOH)
            protocol == DnsProtocol.DOH -> listOf(DnsProtocol.STANDARD, DnsProtocol.DOH)
            else -> listOf(DnsProtocol.STANDARD)
        }

        val server = if (isPrimaryIpv4) {
            DnsServer(
                id = "custom_${System.currentTimeMillis()}",
                name = name.ifBlank { "Custom DNS" },
                primary = normalizedPrimary,
                secondary = secondary?.takeIf { it.isNotBlank() },
                dohUrl = normalizedDohUrl,
                customBootstrapIp = normalizedBootstrapIp,
                allowUntrustedCertificates = protocol == DnsProtocol.DOH && allowUntrustedCertificates,
                supportedProtocols = supportedProtocols,
                isCustom = true
            )
        } else {
            // IPv6
            DnsServer(
                id = "custom_${System.currentTimeMillis()}",
                name = name.ifBlank { "Custom DNS" },
                primary = "", // Empty IPv4
                ipv6Primary = normalizedPrimary.takeIf { it.isNotBlank() },
                ipv6Secondary = secondary?.takeIf { it.isNotBlank() },
                dohUrl = normalizedDohUrl,
                customBootstrapIp = normalizedBootstrapIp,
                allowUntrustedCertificates = protocol == DnsProtocol.DOH && allowUntrustedCertificates,
                supportedProtocols = supportedProtocols,
                isCustom = true
            )
        }

        dnsRepository.saveCustomServer(server)
        return Result.success(server)
    }

    private fun normalizeDohUrl(value: String?): Result<String?> {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Result.success(null)
        }

        val uri = runCatching { URI(trimmed) }.getOrElse {
            return Result.failure(IllegalArgumentException("Invalid DoH URL"))
        }

        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("DoH URL must be an HTTPS URL"))
        }

        return Result.success(trimmed)
    }

    private fun normalizeBootstrapIp(value: String?): Result<String?> {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Result.success(null)
        }

        if (!ipv4Regex.matches(trimmed) && !ipv6Regex.matches(trimmed)) {
            return Result.failure(IllegalArgumentException("Invalid custom bootstrap IP"))
        }

        return Result.success(trimmed)
    }
}
