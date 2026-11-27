package com.vmcsoft.aerodns.domain.usecase

import com.vmcsoft.aerodns.domain.model.DnsProtocol
import com.vmcsoft.aerodns.domain.model.DnsServer
import com.vmcsoft.aerodns.domain.repository.DnsRepository
import java.net.URI
import javax.inject.Inject

class UpdateCustomDnsUseCase @Inject constructor(
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
        serverId: String,
        name: String,
        primary: String,
        secondary: String? = null,
        dohUrl: String? = null,
        protocol: DnsProtocol = DnsProtocol.STANDARD,
        customBootstrapIp: String? = null,
        allowUntrustedCertificates: Boolean = false
    ): Result<DnsServer> {
        val existing = dnsRepository.getCustomServers().firstOrNull { it.id == serverId }
            ?: return Result.failure(IllegalArgumentException("Custom DNS profile no longer exists"))
        val edited = runCatching {
            when (protocol) {
                DnsProtocol.STANDARD -> updateStandardAddresses(existing, primary, secondary)
                DnsProtocol.DOH -> existing.copy(
                    dohUrl = requireNotNull(normalizeDohUrl(dohUrl).getOrThrow()) {
                        "DoH URL is required"
                    },
                    customBootstrapIp = normalizeBootstrapIp(customBootstrapIp).getOrThrow(),
                    allowUntrustedCertificates = allowUntrustedCertificates
                )
                DnsProtocol.DOT -> throw IllegalArgumentException("Unsupported custom DNS protocol")
            }
        }.getOrElse { return Result.failure(it) }
        val server = edited.copy(
            name = name.ifBlank { "Custom DNS" },
            supportedProtocols = (existing.supportedProtocols + protocol).distinct().sorted()
        )

        dnsRepository.updateCustomServer(server)
        return Result.success(server)
    }

    private fun updateStandardAddresses(
        server: DnsServer,
        primary: String,
        secondary: String?
    ): DnsServer {
        val newPrimary = primary.trim()
        val newSecondary = secondary?.trim()?.takeIf(String::isNotEmpty)
        val useIpv4 = ipv4Regex.matches(newPrimary)
        require(useIpv4 || ipv6Regex.matches(newPrimary)) { "Invalid primary DNS address" }
        require(newSecondary == null || ipv4Regex.matches(newSecondary) || ipv6Regex.matches(newSecondary)) {
            "Invalid secondary DNS address"
        }

        // The editor shows the pair with an IPv4 primary first, or the IPv6 pair otherwise.
        val editingIpv4 = server.primary.isNotBlank()
        val editingIpv6 = !editingIpv4 && !server.ipv6Primary.isNullOrBlank()
        require(!(editingIpv4 && !useIpv4 &&
            (!server.ipv6Primary.isNullOrBlank() || !server.ipv6Secondary.isNullOrBlank()))) {
            "An IPv6 address is already saved. Enter an IPv4 primary to preserve it."
        }
        return if (useIpv4) {
            server.copy(
                primary = newPrimary,
                secondary = newSecondary,
                ipv6Primary = server.ipv6Primary.takeUnless { editingIpv6 },
                ipv6Secondary = server.ipv6Secondary.takeUnless { editingIpv6 }
            )
        } else {
            server.copy(
                primary = if (editingIpv4) "" else server.primary,
                secondary = server.secondary.takeUnless { editingIpv4 },
                ipv6Primary = newPrimary,
                ipv6Secondary = newSecondary
            )
        }
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
