package com.vmcsoft.aerodns.data.dns

object DnsWireMessage {
    const val DEFAULT_RESPONSE_BUFFER_SIZE = 512

    fun buildAQuery(queryId: Int, queryName: String): ByteArray {
        val labels = queryName.trim('.').split('.').filter { it.isNotBlank() }
        require(labels.isNotEmpty()) { "DNS query name must not be empty" }
        require(labels.all { it.length in 1..MAX_LABEL_LENGTH }) {
            "DNS query label must be between 1 and 63 characters"
        }

        val normalizedQueryId = queryId and 0xFFFF
        val querySize = DNS_HEADER_SIZE + labels.sumOf { it.length + 1 } + 1 + DNS_QUESTION_TRAILER_SIZE
        val query = ByteArray(querySize)
        var offset = 0

        offset = writeShort(query, offset, normalizedQueryId)
        offset = writeShort(query, offset, DNS_RECURSION_DESIRED_FLAGS)
        offset = writeShort(query, offset, 1) // QDCOUNT
        offset = writeShort(query, offset, 0) // ANCOUNT
        offset = writeShort(query, offset, 0) // NSCOUNT
        offset = writeShort(query, offset, 0) // ARCOUNT

        for (label in labels) {
            query[offset++] = label.length.toByte()
            for (char in label) {
                query[offset++] = char.code.toByte()
            }
        }
        query[offset++] = 0 // QNAME terminator
        offset = writeShort(query, offset, DNS_TYPE_A)
        writeShort(query, offset, DNS_CLASS_IN)

        return query
    }

    fun isSuccessfulResponse(buffer: ByteArray, length: Int, queryId: Int): Boolean {
        if (length < DNS_HEADER_SIZE) return false

        val responseId = readShort(buffer, 0)
        val flags = readShort(buffer, 2)
        val isResponse = flags and DNS_RESPONSE_FLAG != 0
        val responseCode = flags and DNS_RESPONSE_CODE_MASK

        return responseId == (queryId and 0xFFFF) && isResponse && responseCode == DNS_RCODE_NOERROR
    }

    fun isTruncatedResponse(buffer: ByteArray): Boolean {
        if (buffer.size < DNS_HEADER_SIZE) return false

        val flags = readShort(buffer, 2)
        return flags and DNS_TRUNCATED_RESPONSE_FLAG != 0
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int): Int {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
        return offset + 2
    }

    private const val DNS_HEADER_SIZE = 12
    private const val DNS_QUESTION_TRAILER_SIZE = 4
    private const val DNS_RECURSION_DESIRED_FLAGS = 0x0100
    private const val DNS_RESPONSE_FLAG = 0x8000
    private const val DNS_TRUNCATED_RESPONSE_FLAG = 0x0200
    private const val DNS_RESPONSE_CODE_MASK = 0x000F
    private const val DNS_RCODE_NOERROR = 0
    private const val DNS_TYPE_A = 1
    private const val DNS_CLASS_IN = 1
    private const val MAX_LABEL_LENGTH = 63
}
