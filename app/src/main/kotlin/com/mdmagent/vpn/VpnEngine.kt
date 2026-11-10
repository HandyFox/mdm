package com.mdmagent.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mdmagent.service.MdmVpnService
import com.mdmagent.util.FilterLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * The whitelist enforcement engine.
 *
 * Reads IPv4 packets from the tun, and for every new flow decides ALLOW/BLOCK
 * by domain (DNS name, TLS SNI, or HTTP Host). Allowed flows are forwarded in
 * userspace through protected sockets (so they don't re-enter the VPN);
 * everything else is dropped. The result: with the whitelist on, only traffic
 * to whitelisted domains (and the IPs they resolve to) can leave the device.
 *
 * This is a pragmatic userspace NAT, not a full RFC-793 TCP stack: it assumes
 * the local tun delivers reliably and does not implement its own retransmission
 * or congestion control. That is appropriate for a locked, low-throughput fleet
 * device. It should be validated on the target hardware.
 */
class VpnEngine(
    private val service: MdmVpnService,
    private val tun: ParcelFileDescriptor,
    private val whitelist: WhitelistEngine
) {
    private val ctx: Context = service.applicationContext
    private val tunIn = FileInputStream(tun.fileDescriptor)
    private val tunOut = FileOutputStream(tun.fileDescriptor)
    private val writeLock = Any()

    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    private val tcpFlows = ConcurrentHashMap<Long, TcpFlow>()
    private val udpSessions = ConcurrentHashMap<Long, UdpSession>()

    companion object {
        private const val TAG = "VpnEngine"
        private const val MTU = 1500
        private const val MSS = 1400
        private const val WINDOW = 65535
        private const val DNS_UPSTREAM = "8.8.8.8"
        private const val MAX_SNIFF = 8 * 1024
    }

    fun start() {
        running = true
        pool.execute { readLoop() }
    }

    fun stop() {
        running = false
        try { tunIn.close() } catch (_: Exception) {}
        try { tunOut.close() } catch (_: Exception) {}
        tcpFlows.values.forEach { it.close() }
        udpSessions.values.forEach { it.close() }
        tcpFlows.clear(); udpSessions.clear()
        pool.shutdownNow()
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(writeLock) {
            try { tunOut.write(packet); tunOut.flush() } catch (_: Exception) {}
        }
    }

    // ── main read loop ─────────────────────────────────────────────────────
    private fun readLoop() {
        val buf = ByteArray(32767)
        while (running) {
            val n = try { tunIn.read(buf) } catch (e: Exception) { if (running) Log.w(TAG, "tun read: ${e.message}"); break }
            if (n <= 0) continue
            val ip = Ip4Packet(buf.copyOf(n), n)
            if (!ip.isIpv4) continue                 // drop IPv6 etc.
            when (ip.protocol) {
                IpProto.TCP -> handleTcp(TcpSegment(ip))
                IpProto.UDP -> handleUdp(UdpDatagram(ip))
                else -> { /* drop */ }
            }
        }
    }

    private fun tcpKey(srcPort: Int, dstAddr: Int, dstPort: Int): Long =
        (srcPort.toLong() shl 48) or (dstPort.toLong() shl 32) or (dstAddr.toLong() and 0xFFFFFFFFL)

    private fun handleTcp(seg: TcpSegment) {
        val key = tcpKey(seg.srcPort, seg.ip.dstAddr, seg.dstPort)
        var flow = tcpFlows[key]
        if (flow == null) {
            if (!seg.syn) return  // ignore stray segments with no flow
            flow = TcpFlow(key, seg.ip.srcAddr, seg.srcPort, seg.ip.dstAddr, seg.dstPort)
            tcpFlows[key] = flow
        }
        flow.onClientSegment(seg)
    }

    private fun handleUdp(dg: UdpDatagram) {
        if (dg.dstPort == 53) { pool.execute { handleDns(dg) }; return }
        // Non-DNS UDP (e.g. QUIC/443): allow only to IPs resolved from whitelist.
        if (!whitelistOn()) { relayUdp(dg); return }
        if (whitelist.isIpAllowed(dg.ip.dstAddr)) relayUdp(dg)
        else FilterLog.record(ctx, false, Ip4Packet.ipToString(dg.ip.dstAddr) + ":${dg.dstPort}/udp")
    }

    private fun whitelistOn() = com.mdmagent.util.PolicyPrefs.isWhitelistEnabled(ctx)

    // ── DNS filtering ──────────────────────────────────────────────────────
    private fun handleDns(dg: UdpDatagram) {
        val query = dg.payload()
        val name = Dns.parseQueryName(query)
        val on = whitelistOn()
        if (on && name != null && !whitelist.matches(name)) {
            // Blocked name -> answer NXDOMAIN so the app fails to resolve.
            val resp = Dns.buildNxDomain(query)
            writeToTun(PacketBuilder.udp(dg.ip.dstAddr, dg.ip.srcAddr, dg.dstPort, dg.srcPort, resp))
            FilterLog.record(ctx, false, name)
            return
        }
        // Allowed (or whitelist off): forward upstream and learn the IPs.
        try {
            DatagramSocket().use { sock ->
                service.protect(sock)
                sock.soTimeout = 5000
                val up = InetAddress.getByName(DNS_UPSTREAM)
                sock.send(DatagramPacket(query, query.size, up, 53))
                val respBuf = ByteArray(1500)
                val rp = DatagramPacket(respBuf, respBuf.size)
                sock.receive(rp)
                val resp = respBuf.copyOf(rp.length)
                if (on && name != null) {
                    whitelist.rememberResolved(Dns.parseAnswerIps(resp))
                    FilterLog.record(ctx, true, name)
                }
                writeToTun(PacketBuilder.udp(dg.ip.dstAddr, dg.ip.srcAddr, dg.dstPort, dg.srcPort, resp))
            }
        } catch (e: Exception) {
            Log.d(TAG, "dns forward failed: ${e.message}")
        }
    }

    private fun relayUdp(dg: UdpDatagram) {
        val key = tcpKey(dg.srcPort, dg.ip.dstAddr, dg.dstPort)
        val s = udpSessions.getOrPut(key) {
            UdpSession(key, dg.ip.srcAddr, dg.srcPort, dg.ip.dstAddr, dg.dstPort).also { it.open() }
        }
        s.send(dg.payload())
    }

    // ── TCP flow ────────────────────────────────────────────────────────────
    private inner class TcpFlow(
        val key: Long,
        val clientAddr: Int, val clientPort: Int,
        val serverAddr: Int, val serverPort: Int
    ) {
        private var clientSeqNext = 0        // next seq we expect FROM client
        private var ourSeq = 1               // our seq TOWARD client
        private var established = false
        private var decided = false
        private var allowed = false
        private var upstream: Socket? = null
        private val sniff = java.io.ByteArrayOutputStream()
        private val outQueue = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false

        fun onClientSegment(seg: TcpSegment) {
            if (seg.rst) { close(); return }

            if (seg.syn && !established) {
                clientSeqNext = seg.seq + 1
                // SYN-ACK
                sendToClient(TcpSegment.SYN or TcpSegment.ACK, ByteArray(0))
                ourSeq += 1
                established = true
                // If the destination IP is already known-good, connect now.
                if (whitelist.isIpAllowed(serverAddr) || !whitelistOn()) {
                    decided = true; allowed = true; connectUpstream()
                }
                return
            }

            val payload = seg.payload()
            if (payload.isNotEmpty()) {
                // Accept only in-order data; re-ACK duplicates, ignore gaps.
                if (seg.seq != clientSeqNext) { sendAck(); return }
                clientSeqNext += payload.size
                sendAck()

                if (allowed) {
                    outQueue.offer(payload)
                } else if (!decided) {
                    sniff.write(payload)
                    val data = sniff.toByteArray()
                    val host = Inspect.hostFromClientData(data)
                    when {
                        host != null -> decide(whitelist.matches(host), host)
                        data.size > MAX_SNIFF -> decide(false, Ip4Packet.ipToString(serverAddr))
                        // else: wait for more client bytes
                    }
                }
            }

            if (seg.fin) {
                clientSeqNext += 1
                sendAck()
                try { upstream?.shutdownOutput() } catch (_: Exception) {}
            }
        }

        private fun decide(allow: Boolean, host: String) {
            decided = true; allowed = allow
            FilterLog.record(ctx, allow, host)
            if (allow) {
                connectUpstream()
                // flush whatever the client already sent
                val buffered = sniff.toByteArray()
                if (buffered.isNotEmpty()) outQueue.offer(buffered)
            } else {
                sendToClient(TcpSegment.RST or TcpSegment.ACK, ByteArray(0))
                close()
            }
        }

        private fun connectUpstream() {
            pool.execute {
                try {
                    val s = Socket()
                    service.protect(s)
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(
                        InetAddress.getByAddress(ipBytes(serverAddr)), serverPort), 8000)
                    upstream = s
                    // writer: client -> upstream
                    pool.execute { pumpToUpstream(s) }
                    // reader: upstream -> client
                    pumpToClient(s)
                } catch (e: Exception) {
                    Log.d(TAG, "upstream connect failed ${Ip4Packet.ipToString(serverAddr)}:$serverPort ${e.message}")
                    sendToClient(TcpSegment.RST or TcpSegment.ACK, ByteArray(0))
                    close()
                }
            }
        }

        private fun pumpToUpstream(s: Socket) {
            try {
                val out = s.getOutputStream()
                while (!closed) {
                    val chunk = outQueue.take()
                    if (chunk.isEmpty()) break
                    out.write(chunk); out.flush()
                }
            } catch (_: Exception) {}
        }

        private fun pumpToClient(s: Socket) {
            try {
                val input = s.getInputStream()
                val buf = ByteArray(MSS)
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    sendToClient(TcpSegment.ACK or TcpSegment.PSH, buf.copyOf(n))
                    ourSeq += n
                }
                // upstream closed -> FIN to client
                sendToClient(TcpSegment.FIN or TcpSegment.ACK, ByteArray(0))
                ourSeq += 1
            } catch (_: Exception) {
                sendToClient(TcpSegment.RST or TcpSegment.ACK, ByteArray(0))
            } finally {
                close()
            }
        }

        private fun sendAck() = sendToClient(TcpSegment.ACK, ByteArray(0))

        private fun sendToClient(flags: Int, payload: ByteArray) {
            // Packet originates from the real server, destined to the client.
            val pkt = PacketBuilder.tcp(
                srcAddr = serverAddr, dstAddr = clientAddr,
                srcPort = serverPort, dstPort = clientPort,
                seq = ourSeq, ack = clientSeqNext, flags = flags,
                window = WINDOW, payload = payload
            )
            writeToTun(pkt)
        }

        fun close() {
            if (closed) return
            closed = true
            outQueue.offer(ByteArray(0))
            try { upstream?.close() } catch (_: Exception) {}
            tcpFlows.remove(key)
        }
    }

    // ── UDP NAT session (non-DNS, to already-allowed IPs) ────────────────────
    private inner class UdpSession(
        val key: Long,
        val clientAddr: Int, val clientPort: Int,
        val serverAddr: Int, val serverPort: Int
    ) {
        private var sock: DatagramSocket? = null
        @Volatile private var closed = false

        fun open() {
            try {
                val s = DatagramSocket()
                service.protect(s)
                s.connect(InetSocketAddress(InetAddress.getByAddress(ipBytes(serverAddr)), serverPort))
                sock = s
                pool.execute { readLoop(s) }
            } catch (e: Exception) { close() }
        }

        fun send(data: ByteArray) {
            try { sock?.send(DatagramPacket(data, data.size)) } catch (_: Exception) {}
        }

        private fun readLoop(s: DatagramSocket) {
            try {
                s.soTimeout = 30000
                val buf = ByteArray(2048)
                while (!closed) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val payload = buf.copyOf(p.length)
                    writeToTun(PacketBuilder.udp(serverAddr, clientAddr, serverPort, clientPort, payload))
                }
            } catch (_: Exception) {} finally { close() }
        }

        fun close() {
            if (closed) return
            closed = true
            try { sock?.close() } catch (_: Exception) {}
            udpSessions.remove(key)
        }
    }

    private fun ipBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )
}
