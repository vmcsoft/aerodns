package com.vmcsoft.aerodns.data.dns

/** Checks the response to our one-question A probe, not general DNS or DNSSEC authenticity. */
internal object DnsHealthResponse {
    fun isHealthy(query: ByteArray, response: ByteArray): Boolean {
        if (!DnsWireMessage.isResponseForQuery(response, query) || query.size < 17 ||
            response.size < query.size || u16(response, 2) and 0x020f != 0 ||
            u16(response, 4) != 1 || !response.copyOfRange(12, query.size).contentEquals(query.copyOfRange(12, query.size))) return false
        val question = readName(query, 12) ?: return false
        var offset = query.size
        val addresses = mutableSetOf<String>()
        val aliases = mutableMapOf<String, String>()
        val answers = u16(response, 6)
        val records = answers + u16(response, 8) + u16(response, 10)
        repeat(records) { index ->
            val owner = readName(response, offset) ?: return false
            offset = owner.end
            if (offset + 10 > response.size) return false
            val type = u16(response, offset)
            val klass = u16(response, offset + 2)
            val length = u16(response, offset + 8)
            offset += 10
            if (offset + length > response.size) return false
            if (index < answers && klass == 1) {
                when (type) {
                    1 -> {
                        if (length != 4) return false
                        addresses.add(owner.value)
                    }
                    5 -> {
                        val target = readName(response, offset) ?: return false
                        if (target.end != offset + length) return false
                        aliases[owner.value] = target.value
                    }
                }
            }
            offset += length
        }
        if (offset != response.size) return false
        var name = question.value
        val visited = mutableSetOf<String>()
        while (visited.add(name)) {
            if (name in addresses) return true
            name = aliases[name] ?: return false
        }
        return false
    }

    private data class Name(val value: String, val end: Int)

    private fun readName(bytes: ByteArray, start: Int): Name? {
        var offset = start
        var expandedSize = 0
        var end: Int? = null
        var hops = 0
        val labels = mutableListOf<String>()
        while (offset < bytes.size && hops++ < 128) {
            val length = bytes[offset].toInt() and 255
            if (length == 0) return Name(labels.joinToString("."), end ?: (offset + 1))
            if (length and 0xc0 == 0xc0) {
                if (offset + 1 >= bytes.size) return null
                val pointer = ((length and 63) shl 8) or (bytes[offset + 1].toInt() and 255)
                if (pointer < 12 || pointer >= offset) return null
                if (end == null) end = offset + 2
                offset = pointer
            } else {
                if (length > 63 || offset + 1 + length > bytes.size) return null
                expandedSize += length + 1
                if (expandedSize > 254) return null
                // Probe names are ASCII; reject other bytes rather than conflating replacements.
                val label = bytes.copyOfRange(offset + 1, offset + 1 + length)
                if (label.any { it.toInt() !in 33..126 || it == '.'.code.toByte() }) return null
                labels.add(label.toString(Charsets.US_ASCII).lowercase(java.util.Locale.ROOT))
                offset += length + 1
            }
        }
        return null
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
}
