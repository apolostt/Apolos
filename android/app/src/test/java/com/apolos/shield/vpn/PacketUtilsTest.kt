package com.apolos.shield.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure IPv4/UDP/DNS byte-manipulation helpers used by the
 * DNS-filtering VPN tunnel. These are plain JVM functions with no Android
 * framework dependency, so they can be exercised directly.
 */
class PacketUtilsTest {

    // ---------------------------------------------------------------- header parsing

    @Test
    fun `ipVersion reads the high nibble of the first byte`() {
        val v4 = byteArrayOf(0x45)
        val v6 = byteArrayOf(0x60)
        assertEquals(4, PacketUtils.ipVersion(v4))
        assertEquals(6, PacketUtils.ipVersion(v6))
    }

    @Test
    fun `ihlBytes reads the low nibble and multiplies by 4`() {
        // 0x45 -> IHL 5 words -> 20 bytes (no options)
        assertEquals(20, PacketUtils.ihlBytes(byteArrayOf(0x45)))
        // 0x4F -> IHL 15 words -> 60 bytes (max header with options)
        assertEquals(60, PacketUtils.ihlBytes(byteArrayOf(0x4F.toByte())))
    }

    @Test
    fun `protocol reads byte 9 as unsigned`() {
        val pkt = ByteArray(10)
        pkt[9] = PacketUtils.PROTO_UDP.toByte()
        assertEquals(17, PacketUtils.protocol(pkt))

        val tcp = ByteArray(10)
        tcp[9] = 6
        assertEquals(6, PacketUtils.protocol(tcp))
    }

    @Test
    fun `protocol handles values above 127 without going negative`() {
        val pkt = ByteArray(10)
        pkt[9] = 0xFF.toByte() // would be -1 as a signed byte
        assertEquals(255, PacketUtils.protocol(pkt))
    }

    // ---------------------------------------------------------------- UDP ports

    @Test
    fun `udpSrcPort and udpDstPort decode big-endian 16-bit values`() {
        val ihl = 20
        val pkt = ByteArray(ihl + 8)
        // src port 12345 = 0x3039, dst port 53 = 0x0035
        pkt[ihl] = 0x30
        pkt[ihl + 1] = 0x39
        pkt[ihl + 2] = 0x00
        pkt[ihl + 3] = 0x35

        assertEquals(12345, PacketUtils.udpSrcPort(pkt, ihl))
        assertEquals(53, PacketUtils.udpDstPort(pkt, ihl))
    }

    @Test
    fun `udpSrcPort and udpDstPort handle max port value 65535`() {
        val ihl = 0
        val pkt = ByteArray(4)
        pkt[0] = 0xFF.toByte()
        pkt[1] = 0xFF.toByte()
        pkt[2] = 0xFF.toByte()
        pkt[3] = 0xFF.toByte()
        assertEquals(65535, PacketUtils.udpSrcPort(pkt, ihl))
        assertEquals(65535, PacketUtils.udpDstPort(pkt, ihl))
    }

    @Test
    fun `udpPayload returns the bytes after the 8-byte UDP header`() {
        val ihl = 20
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val pkt = ByteArray(ihl + 8) + payload
        val extracted = PacketUtils.udpPayload(pkt, ihl, pkt.size)
        assertArrayEquals(payload, extracted)
    }

    @Test
    fun `udpPayload returns an empty array when there is no payload`() {
        val ihl = 20
        val pkt = ByteArray(ihl + 8)
        val extracted = PacketUtils.udpPayload(pkt, ihl, pkt.size)
        assertEquals(0, extracted.size)
    }

    // ---------------------------------------------------------------- DNS query name

    private fun encodeDnsQuery(name: String, trailingBytes: ByteArray = ByteArray(0)): ByteArray {
        val header = ByteArray(12)
        val body = ArrayList<Byte>()
        if (name.isNotEmpty()) {
            for (label in name.split('.')) {
                body.add(label.length.toByte())
                for (c in label) body.add(c.code.toByte())
            }
        }
        body.add(0) // root terminator
        body.add(0); body.add(1) // QTYPE = A
        body.add(0); body.add(1) // QCLASS = IN
        return header + body.toByteArray() + trailingBytes
    }

    @Test
    fun `dnsQueryName decodes a simple multi-label name`() {
        val query = encodeDnsQuery("www.example.com")
        assertEquals("www.example.com", PacketUtils.dnsQueryName(query))
    }

    @Test
    fun `dnsQueryName lowercases the decoded name`() {
        val query = encodeDnsQuery("WWW.Example.COM")
        assertEquals("www.example.com", PacketUtils.dnsQueryName(query))
    }

    @Test
    fun `dnsQueryName decodes a single-label name`() {
        val query = encodeDnsQuery("localhost")
        assertEquals("localhost", PacketUtils.dnsQueryName(query))
    }

    @Test
    fun `dnsQueryName returns null for a message shorter than a header`() {
        assertNull(PacketUtils.dnsQueryName(ByteArray(5)))
    }

    @Test
    fun `dnsQueryName returns null when a compression pointer is present`() {
        val header = ByteArray(12)
        // 0xC0 flags the top two bits of the length byte -> compression pointer
        val body = byteArrayOf(0xC0.toByte(), 0x0C)
        assertNull(PacketUtils.dnsQueryName(header + body))
    }

    @Test
    fun `dnsQueryName returns null when a label length overruns the buffer`() {
        val header = ByteArray(12)
        // Declares a 10-byte label but supplies none.
        val body = byteArrayOf(10)
        assertNull(PacketUtils.dnsQueryName(header + body))
    }

    // ---------------------------------------------------------------- NXDOMAIN builder

    @Test
    fun `buildNxDomain sets QR, RD and NXDOMAIN rcode bits`() {
        val query = encodeDnsQuery("blocked.example.com")

        val reply = PacketUtils.buildNxDomain(query)

        assertEquals(0x81.toByte(), reply[2]) // QR=1, RD=1
        assertEquals(0x83.toByte(), reply[3]) // RA=1, rcode=3 (NXDOMAIN)
        assertEquals(0, reply[6].toInt()); assertEquals(0, reply[7].toInt())   // ANCOUNT
        assertEquals(0, reply[8].toInt()); assertEquals(0, reply[9].toInt())   // NSCOUNT
        assertEquals(0, reply[10].toInt()); assertEquals(0, reply[11].toInt()) // ARCOUNT
    }

    @Test
    fun `buildNxDomain preserves the transaction id from the query header`() {
        val query = encodeDnsQuery("example.com")
        query[0] = 0x12
        query[1] = 0x34

        val reply = PacketUtils.buildNxDomain(query)

        assertEquals(0x12, reply[0].toInt())
        assertEquals(0x34, reply[1].toInt())
    }

    @Test
    fun `buildNxDomain truncates trailing EDNS OPT records`() {
        val withoutOpt = encodeDnsQuery("example.com")
        val withOpt = encodeDnsQuery("example.com", trailingBytes = ByteArray(11)) // fake OPT RR

        val replyWithoutOpt = PacketUtils.buildNxDomain(withoutOpt)
        val replyWithOpt = PacketUtils.buildNxDomain(withOpt)

        // The OPT record must be dropped, so both replies end at the same length.
        assertEquals(replyWithoutOpt.size, replyWithOpt.size)
    }

    @Test
    fun `buildNxDomain keeps only header, question name, qtype and qclass`() {
        val query = encodeDnsQuery("a.b")
        val reply = PacketUtils.buildNxDomain(query)
        // header(12) + label 'a'(1+1) + label 'b'(1+1) + root(1) + qtype(2) + qclass(2) = 21
        assertEquals(21, reply.size)
    }

    // ---------------------------------------------------------------- UDP response builder

    /** Builds a minimal IPv4(20 bytes, no options)+UDP request packet for tests. */
    private fun buildIpv4UdpRequest(
        srcIp: ByteArray = byteArrayOf(10, 0, 0, 2),
        dstIp: ByteArray = byteArrayOf(10, 111, 222, 2),
        srcPort: Int = 54321,
        dstPort: Int = 53,
        payload: ByteArray = byteArrayOf(9, 9, 9),
    ): ByteArray {
        val ihl = 20
        val udpLen = 8 + payload.size
        val pkt = ByteArray(ihl + udpLen)
        pkt[0] = 0x45
        System.arraycopy(srcIp, 0, pkt, 12, 4)
        System.arraycopy(dstIp, 0, pkt, 16, 4)
        pkt[ihl] = ((srcPort ushr 8) and 0xFF).toByte()
        pkt[ihl + 1] = (srcPort and 0xFF).toByte()
        pkt[ihl + 2] = ((dstPort ushr 8) and 0xFF).toByte()
        pkt[ihl + 3] = (dstPort and 0xFF).toByte()
        pkt[ihl + 4] = ((udpLen ushr 8) and 0xFF).toByte()
        pkt[ihl + 5] = (udpLen and 0xFF).toByte()
        System.arraycopy(payload, 0, pkt, ihl + 8, payload.size)
        return pkt
    }

    /** Recomputes the standard IPv4 header checksum over [len] bytes starting at [off]. */
    private fun verifyIpChecksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum and 0xFFFF
    }

    @Test
    fun `buildUdpResponse produces a packet of the expected total length`() {
        val request = buildIpv4UdpRequest()
        val payload = ByteArray(42)
        val response = PacketUtils.buildUdpResponse(request, 20, payload)
        assertEquals(20 + 8 + payload.size, response.size)
    }

    @Test
    fun `buildUdpResponse swaps source and destination IP addresses`() {
        val srcIp = byteArrayOf(10, 0, 0, 2)
        val dstIp = byteArrayOf(10, 111, 222, 2)
        val request = buildIpv4UdpRequest(srcIp = srcIp, dstIp = dstIp)
        val response = PacketUtils.buildUdpResponse(request, 20, byteArrayOf(1))

        val respSrc = response.copyOfRange(12, 16)
        val respDst = response.copyOfRange(16, 20)
        assertArrayEquals(dstIp, respSrc) // request's dst becomes response's src
        assertArrayEquals(srcIp, respDst) // request's src becomes response's dst
    }

    @Test
    fun `buildUdpResponse swaps source and destination UDP ports`() {
        val request = buildIpv4UdpRequest(srcPort = 54321, dstPort = 53)
        val response = PacketUtils.buildUdpResponse(request, 20, byteArrayOf(1))

        assertEquals(53, PacketUtils.udpSrcPort(response, 20))    // reply is "from" port 53
        assertEquals(54321, PacketUtils.udpDstPort(response, 20)) // reply is "to" the original source port
    }

    @Test
    fun `buildUdpResponse sets protocol UDP and a positive TTL`() {
        val request = buildIpv4UdpRequest()
        val response = PacketUtils.buildUdpResponse(request, 20, byteArrayOf(1))

        assertEquals(PacketUtils.PROTO_UDP, PacketUtils.protocol(response))
        assertTrue((response[8].toInt() and 0xFF) > 0)
    }

    @Test
    fun `buildUdpResponse copies the payload verbatim after the headers`() {
        val request = buildIpv4UdpRequest()
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val response = PacketUtils.buildUdpResponse(request, 20, payload)

        val copied = response.copyOfRange(28, 28 + payload.size)
        assertArrayEquals(payload, copied)
    }

    @Test
    fun `buildUdpResponse writes a valid IPv4 header checksum`() {
        val request = buildIpv4UdpRequest()
        val response = PacketUtils.buildUdpResponse(request, 20, byteArrayOf(1, 2, 3))

        // Standard IPv4 checksum validation: summing all 16-bit words of the
        // header, including the checksum field itself, must fold to 0xFFFF.
        assertEquals(0xFFFF, verifyIpChecksum(response, 0, 20))
    }

    @Test
    fun `buildUdpResponse encodes the correct UDP length field`() {
        val request = buildIpv4UdpRequest()
        val payload = ByteArray(16)
        val response = PacketUtils.buildUdpResponse(request, 20, payload)

        val udpLenField = ((response[24].toInt() and 0xFF) shl 8) or (response[25].toInt() and 0xFF)
        assertEquals(8 + payload.size, udpLenField)
    }
}