package com.mdmagent.vpn

/**
 * Minimal IPv4 / TCP / UDP packet parsing and construction for the whitelist
 * VpnService. Only the fields the filter needs are modelled. IPv6 is not
 * handled here; the engine drops it (which, for a locked device, is the
 * desired "block by default" behaviour).
 *
 * All multi-byte fields are big-endian (network order).
 */
object IpProto {
    const val TCP = 6
    const val UDP = 17
}

/** A parsed IPv4 packet (header + transport payload offsets). */
class Ip4Packet(val raw: ByteArray, val length: Int) {
    val ihl: Int = (raw[0].toInt() and 0x0F) * 4
    val protocol: Int = raw[9].toInt() and 0xFF
    val srcAddr: Int = readInt(raw, 12)
    val dstAddr: Int = readInt(raw, 16)
    val transportOffset: Int get() = ihl
    val isIpv4: Boolean get() = (raw[0].toInt() and 0xF0) == 0x40

    companion object {
        fun readInt(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

        fun writeInt(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 24).toByte()
            b[o + 1] = (v ushr 16).toByte()
            b[o + 2] = (v ushr 8).toByte()
            b[o + 3] = v.toByte()
        }

        fun readShort(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        fun writeShort(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 8).toByte()
            b[o + 1] = v.toByte()
        }

        fun ipToString(v: Int): String =
            "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
    }
}

/** Parsed view of a TCP segment inside an [Ip4Packet]. */
class TcpSegment(val ip: Ip4Packet) {
    private val o = ip.transportOffset
    val srcPort = Ip4Packet.readShort(ip.raw, o)
    val dstPort = Ip4Packet.readShort(ip.raw, o + 2)
    val seq = Ip4Packet.readInt(ip.raw, o + 4)
    val ack = Ip4Packet.readInt(ip.raw, o + 8)
    val dataOffset = ((ip.raw[o + 12].toInt() and 0xF0) ushr 4) * 4
    val flags = ip.raw[o + 13].toInt() and 0xFF
    val payloadOffset = o + dataOffset
    val payloadLength = ip.length - payloadOffset

    val fin get() = flags and 0x01 != 0
    val syn get() = flags and 0x02 != 0
    val rst get() = flags and 0x04 != 0
    val psh get() = flags and 0x08 != 0
    val ackFlag get() = flags and 0x10 != 0

    fun payload(): ByteArray =
        if (payloadLength > 0) ip.raw.copyOfRange(payloadOffset, payloadOffset + payloadLength)
        else ByteArray(0)

    companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
    }
}

/** Parsed view of a UDP datagram inside an [Ip4Packet]. */
class UdpDatagram(val ip: Ip4Packet) {
    private val o = ip.transportOffset
    val srcPort = Ip4Packet.readShort(ip.raw, o)
    val dstPort = Ip4Packet.readShort(ip.raw, o + 2)
    val payloadOffset = o + 8
    val payloadLength = (ip.length - payloadOffset).coerceAtLeast(0)

    fun payload(): ByteArray =
        if (payloadLength > 0) ip.raw.copyOfRange(payloadOffset, payloadOffset + payloadLength)
        else ByteArray(0)
}

/**
 * Builds raw IPv4 packets (header + transport) with correct checksums, for
 * writing back into the tun toward the device app.
 */
object PacketBuilder {

    /** Build an IPv4+TCP packet. */
    fun tcp(
        srcAddr: Int, dstAddr: Int, srcPort: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val ipHdr = 20; val tcpHdr = 20
        val total = ipHdr + tcpHdr + payload.size
        val p = ByteArray(total)

        // IPv4 header
        p[0] = 0x45.toByte()
        p[1] = 0
        Ip4Packet.writeShort(p, 2, total)
        Ip4Packet.writeShort(p, 4, 0)       // id
        Ip4Packet.writeShort(p, 6, 0x4000)  // DF
        p[8] = 64                            // TTL
        p[9] = IpProto.TCP.toByte()
        Ip4Packet.writeInt(p, 12, srcAddr)
        Ip4Packet.writeInt(p, 16, dstAddr)
        ipChecksum(p, 0, ipHdr)

        // TCP header
        val t = ipHdr
        Ip4Packet.writeShort(p, t, srcPort)
        Ip4Packet.writeShort(p, t + 2, dstPort)
        Ip4Packet.writeInt(p, t + 4, seq)
        Ip4Packet.writeInt(p, t + 8, ack)
        p[t + 12] = (5 shl 4).toByte()       // data offset = 5 words
        p[t + 13] = flags.toByte()
        Ip4Packet.writeShort(p, t + 14, window)
        Ip4Packet.writeShort(p, t + 16, 0)   // checksum placeholder
        Ip4Packet.writeShort(p, t + 18, 0)   // urgent ptr
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, p, t + tcpHdr, payload.size)
        transportChecksum(p, srcAddr, dstAddr, IpProto.TCP, t, tcpHdr + payload.size)
        return p
    }

    /** Build an IPv4+UDP packet. */
    fun udp(
        srcAddr: Int, dstAddr: Int, srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val ipHdr = 20; val udpHdr = 8
        val total = ipHdr + udpHdr + payload.size
        val p = ByteArray(total)

        p[0] = 0x45.toByte()
        Ip4Packet.writeShort(p, 2, total)
        Ip4Packet.writeShort(p, 6, 0x4000)
        p[8] = 64
        p[9] = IpProto.UDP.toByte()
        Ip4Packet.writeInt(p, 12, srcAddr)
        Ip4Packet.writeInt(p, 16, dstAddr)
        ipChecksum(p, 0, ipHdr)

        val u = ipHdr
        Ip4Packet.writeShort(p, u, srcPort)
        Ip4Packet.writeShort(p, u + 2, dstPort)
        Ip4Packet.writeShort(p, u + 4, udpHdr + payload.size)
        Ip4Packet.writeShort(p, u + 6, 0)
        System.arraycopy(payload, 0, p, u + udpHdr, payload.size)
        transportChecksum(p, srcAddr, dstAddr, IpProto.UDP, u, udpHdr + payload.size)
        return p
    }

    private fun ipChecksum(p: ByteArray, off: Int, len: Int) {
        Ip4Packet.writeShort(p, off + 10, 0)
        var sum = 0L
        var i = off
        while (i < off + len) { sum += Ip4Packet.readShort(p, i).toLong(); i += 2 }
        sum = (sum and 0xFFFF) + (sum ushr 16)
        sum = (sum and 0xFFFF) + (sum ushr 16)
        Ip4Packet.writeShort(p, off + 10, (sum.inv() and 0xFFFF).toInt())
    }

    private fun transportChecksum(p: ByteArray, src: Int, dst: Int, proto: Int, off: Int, len: Int) {
        var sum = 0L
        // Pseudo-header
        sum += ((src ushr 16) and 0xFFFF).toLong(); sum += (src and 0xFFFF).toLong()
        sum += ((dst ushr 16) and 0xFFFF).toLong(); sum += (dst and 0xFFFF).toLong()
        sum += proto.toLong()
        sum += len.toLong()
        // Transport header + data
        var i = off
        var remaining = len
        while (remaining > 1) { sum += Ip4Packet.readShort(p, i).toLong(); i += 2; remaining -= 2 }
        if (remaining == 1) sum += ((p[i].toInt() and 0xFF) shl 8).toLong()
        sum = (sum and 0xFFFF) + (sum ushr 16)
        sum = (sum and 0xFFFF) + (sum ushr 16)
        val cksumOff = if (proto == IpProto.TCP) off + 16 else off + 6
        Ip4Packet.writeShort(p, cksumOff, (sum.inv() and 0xFFFF).toInt())
    }
}
