package com.vmcsoft.aerodns.presentation.components

import com.vmcsoft.aerodns.domain.model.DnsServer

data class DnsProviderGroup(
    val category: String,
    val displayName: String,
    val icon: String? = null,
    val variants: List<DnsServer>,
    val isExpanded: Boolean = false
)
