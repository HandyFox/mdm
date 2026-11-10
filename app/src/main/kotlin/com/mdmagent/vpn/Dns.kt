package com.mdmagent.vpn

/**
 * Just enough DNS to: read the queried name, read A-record answers, and build
 * an NXDOMAIN reply for blocked names.
 */
object Dns {

    /** The domain in the first question of a DNS query, lowercased, or null. */
    fun parseQueryName(payload: ByteArray): String? {
        if (payload.size < 13) return null
        val qdcount = short(payload, 4)
        if (qdcount < 1) return null
        val (name, _) = readName(payload, 12) ?: return null
        return name.lowercase().ifEmpty { null }
    }

    /** IPv4 addresses (as ints) from the answer section of a DNS response. */
    fun parseAnswerIps(payload: ByteArray): List<Int> {
        try {
            if (payload.size < 12) return emptyList()
            val qd = short(payload, 4)
            val an = short(payload, 6)
            if (an == 0) return emptyList()
            var off = 12
            repeat(qd) {
                off = skipName(payload, off)
                off += 4 // QTYPE + QCLASS
            }
            val ips = ArrayList<Int>()
            repeat(an) {
                off = skipName(payload, off)
                if (off + 10 > payload.size) return ips
                val type = short(payload, off)
                val rdlen = short(payload, off + 8)
                val rdata = off + 10
                if (type == 1 && rdlen == 4 && rdata + 4 <= payload.size) {
                    ips.add(Ip4Packet.readInt(payload, rdata))
                }
                off = rdata + rdlen
            }
            return ips
        } catch (_: Exception) { return emptyList() }
    }

    /** Turn a query into an NXDOMAIN response (QR=1, RCODE=3, no answers). */
    fun buildNxDomain(query: ByteArray): ByteArray {
        // Question end = after first QNAME + QTYPE(2) + QCLASS(2)
        val afterName = skipName(query, 12)
        val qEnd = (afterName + 4).coerceAtMost(query.size)
        val out = query.copyOfRange(0, qEnd)
        // Flags: QR=1, Opcode=0, AA=0, TC=0, RD=copy, RA=1, RCODE=3
        val rd = query[2].toInt() and 0x01   // preserve the Recursion-Desired bit
        out[2] = (0x80 or rd).toByte()       // QR=1, opcode=0, AA=0, TC=0, RD=copy
        out[3] = 0x83.toByte()               // RA=1, RCODE=3 (NXDOMAIN)
        short(out, 4, 1)                  // QDCOUNT = 1
        short(out, 6, 0)                  // ANCOUNT = 0
        short(out, 8, 0)                  // NSCOUNT
        short(out, 10, 0)                 // ARCOUNT
        return out
    }

    // ── helpers ──────────────────────────────────────────────────────────
    private fun short(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun short(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
    }

    private fun readName(b: ByteArray, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var off = start
        var jumped = false
        var next = -1
        var guard = 0
        while (off < b.size) {
            if (guard++ > 128) return null
            val len = b[off].toInt() and 0xFF
            if (len == 0) { if (!jumped) next = off + 1; break }
            if (len and 0xC0 == 0xC0) {
                val ptr = ((len and 0x3F) shl 8) or (b[off + 1].toInt() and 0xFF)
                if (!jumped) next = off + 2
                off = ptr; jumped = true; continue
            }
            if (off + 1 + len > b.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, off + 1, len, Charsets.US_ASCII))
            off += 1 + len
        }
        if (next < 0) next = off + 1
        return sb.toString() to next
    }

    private fun skipName(b: ByteArray, start: Int): Int {
        var off = start
        var guard = 0
        while (off < b.size) {
            if (guard++ > 128) return off
            val len = b[off].toInt() and 0xFF
            if (len == 0) return off + 1
            if (len and 0xC0 == 0xC0) return off + 2
            off += 1 + len
        }
        return off
    }
}
