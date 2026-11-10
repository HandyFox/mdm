package com.mdmagent.vpn

/**
 * Pulls a destination hostname out of the first bytes of a TCP stream:
 *   • TLS ClientHello  -> SNI (server_name extension)
 *   • Plain HTTP       -> Host: header
 *
 * This is what lets the whitelist block by domain even when the connection
 * goes straight to an IP we haven't seen resolved via DNS.
 */
object Inspect {

    fun hostFromClientData(data: ByteArray): String? =
        tlsSni(data) ?: httpHost(data)

    /** Extract the SNI host from a TLS ClientHello, or null. */
    fun tlsSni(d: ByteArray): String? {
        try {
            var p = 0
            if (d.size < 5) return null
            if (d[0].toInt() and 0xFF != 0x16) return null          // handshake
            // skip TLS record header (5)
            p = 5
            if (p + 4 > d.size) return null
            if (d[p].toInt() and 0xFF != 0x01) return null          // ClientHello
            // handshake length (3) skip
            p += 4
            p += 2                                                   // client version
            p += 32                                                  // random
            if (p >= d.size) return null
            val sidLen = d[p].toInt() and 0xFF; p += 1 + sidLen      // session id
            if (p + 2 > d.size) return null
            val csLen = u16(d, p); p += 2 + csLen                    // cipher suites
            if (p >= d.size) return null
            val compLen = d[p].toInt() and 0xFF; p += 1 + compLen    // compression
            if (p + 2 > d.size) return null
            val extTotal = u16(d, p); p += 2
            val extEnd = (p + extTotal).coerceAtMost(d.size)
            while (p + 4 <= extEnd) {
                val type = u16(d, p); val len = u16(d, p + 2); p += 4
                if (type == 0x0000) {                               // server_name
                    // server_name_list(2) + nameType(1) + nameLen(2) + name
                    if (p + 5 > d.size) return null
                    val nameType = d[p + 2].toInt() and 0xFF
                    val nameLen = u16(d, p + 3)
                    if (nameType != 0) return null
                    val start = p + 5
                    if (start + nameLen > d.size) return null
                    return String(d, start, nameLen, Charsets.US_ASCII).lowercase()
                }
                p += len
            }
        } catch (_: Exception) {}
        return null
    }

    /** Extract the Host header from a plaintext HTTP request, or null. */
    fun httpHost(d: ByteArray): String? {
        try {
            // Only look at a reasonable prefix; headers come first.
            val limit = minOf(d.size, 2048)
            val text = String(d, 0, limit, Charsets.ISO_8859_1)
            if (!text.startsWith("GET ") && !text.startsWith("POST ") &&
                !text.startsWith("HEAD ") && !text.startsWith("PUT ") &&
                !text.startsWith("DELETE ") && !text.startsWith("OPTIONS ") &&
                !text.startsWith("PATCH ") && !text.startsWith("CONNECT ")) return null
            for (line in text.split("\r\n")) {
                if (line.isEmpty()) break
                if (line.length > 5 && line.substring(0, 5).equals("Host:", true)) {
                    return line.substring(5).trim().substringBefore(':').lowercase()
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun u16(d: ByteArray, o: Int) =
        ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
}

/**
 * The whitelist decision core: domain matching plus a cache of IP addresses
 * that were resolved from whitelisted names (so direct-to-IP connections to
 * those servers are allowed too).
 */
class WhitelistEngine(@Volatile var domains: Set<String>) {

    // resolved IP (int) -> expiry time (ms)
    private val allowedIps = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val ttlMs = 10 * 60 * 1000L

    fun updateDomains(set: Set<String>) { domains = set }

    /** True if host equals, or is a subdomain of, any whitelisted domain. */
    fun matches(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.trimEnd('.').lowercase()
        for (d in domains) {
            if (h == d || h.endsWith(".$d")) return true
        }
        return false
    }

    fun rememberResolved(ips: List<Int>) {
        val exp = System.currentTimeMillis() + ttlMs
        for (ip in ips) allowedIps[ip] = exp
    }

    fun isIpAllowed(ip: Int): Boolean {
        val exp = allowedIps[ip] ?: return false
        if (exp < System.currentTimeMillis()) { allowedIps.remove(ip); return false }
        return true
    }

    fun clear() = allowedIps.clear()
}
