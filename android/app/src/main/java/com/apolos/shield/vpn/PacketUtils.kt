package com.apolos.shield.vpn

import java.nio.ByteBuffer

/**
 * Minimal IPv4 / UDP helpers for the DNS-filtering tunnel. Only what the DNS
 * path needs: parse a query name, and rebuild an IPv4+UDP response packet with
 * correct checksums so the OS resolver accepts it.
 */
object PacketUtils {

    const val PROTO_UDP = 17

    fun ipVersion(pkt: ByteArray): Int = (pkt[0].toInt() and 0xF0) ushr 4
    fun ihlBytes(pkt: ByteArray): Int = (pkt[0].toInt() and 0x0F) * 4
    fun protocol(pkt: ByteArray): Int = pkt[9].toInt() and 0xFF

    fun udpDstPort(pkt: ByteArray, ihl: Int): Int =
        ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)

    fun udpSrcPort(pkt: ByteArray, ihl: Int): Int =
        ((pkt[ihl].toInt() and 0xFF) shl 8) or (pkt[ihl + 1].toInt() and 0xFF)

    /** UDP payload (the DNS message) as a fresh array. */
    fun udpPayload(pkt: ByteArray, ihl: Int, length: Int): ByteArray {
        val start = ihl + 8
        return pkt.copyOfRange(start, length)
    }

    /** Reads the queried host name from a DNS query message. */
    fun dnsQueryName(dns: ByteArray): String? {
        if (dns.size < 13) return null
        var pos = 12
        val sb = StringBuilder()
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 != 0) return null // compression not expected in a query
            pos++
            if (pos + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) sb.append((dns[pos + i].toInt() and 0xFF).toChar())
            pos += len
        }
        return sb.toString().lowercase()
    }

    /** Builds an NXDOMAIN reply for a blocked query (works for any record type). */
    fun buildNxDomain(query: ByteArray): ByteArray {
        // Truncate to header (12) + the single question, dropping any EDNS OPT so
        // the response is well-formed. Question = name labels + qtype(2) + qclass(2).
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if (len and 0xC0 != 0) { pos += 2; break } // pointer (unexpected in query)
            pos += 1 + len
        }
        val end = (pos + 4).coerceAtMost(query.size) // + qtype + qclass
        val out = query.copyOf(end)
        out[2] = 0x81.toByte()   // QR=1, Opcode=0, RD=1
        out[3] = 0x83.toByte()   // RA=1, rcode=3 (NXDOMAIN)
        out[6] = 0; out[7] = 0   // ANCOUNT
        out[8] = 0; out[9] = 0   // NSCOUNT
        out[10] = 0; out[11] = 0 // ARCOUNT
        return out
    }

    /**
     * Wraps [payload] into an IPv4+UDP packet that is the reply to [request]:
     * addresses and ports swapped, lengths and checksums recomputed.
     */
    fun buildUdpResponse(request: ByteArray, requestIhl: Int, payload: ByteArray): ByteArray {
        val ipHeaderLen = 20
        val udpLen = 8 + payload.size
        val total = ipHeaderLen + udpLen
        val out = ByteArray(total)

        // ---- IPv4 header ----
        out[0] = 0x45                      // version 4, IHL 5
        out[1] = 0                         // DSCP/ECN
        putShort(out, 2, total)            // total length
        putShort(out, 4, 0)                // identification
        putShort(out, 6, 0x4000)          // flags = don't fragment
        out[8] = 64                        // TTL
        out[9] = PROTO_UDP.toByte()
        // swap src/dst IPs
        System.arraycopy(request, 16, out, 12, 4) // dst -> src
        System.arraycopy(request, 12, out, 16, 4) // src -> dst
        putShort(out, 10, 0)               // checksum placeholder
        putShort(out, 10, checksum(out, 0, ipHeaderLen))

        // ---- UDP header ----
        val srcPort = udpDstPort(request, requestIhl) // reply from the port we were asked on (53)
        val dstPort = udpSrcPort(request, requestIhl)
        putShort(out, 20, srcPort)
        putShort(out, 22, dstPort)
        putShort(out, 24, udpLen)
        putShort(out, 26, 0)               // checksum optional for IPv4; 0 = disabled
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    private fun putShort(buf: ByteArray, off: Int, value: Int) {
        buf[off] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 1] = (value and 0xFF).toByte()
    }

    private fun checksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }
}
