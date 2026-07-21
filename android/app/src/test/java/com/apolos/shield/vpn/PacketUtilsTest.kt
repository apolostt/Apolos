package com.apolos.shield.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the minimal IPv4/UDP/DNS packet helpers used by the
 * DNS-filtering tunnel ([ShieldVpnService]).
 */
class PacketUtilsTest {

    // ---------------------------------------------------------------
    // Helpers to build realistic IPv4 + UDP (+ DNS) byte buffers.
    // ---------------------------------------------------------------

    private fun ip(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    private fun putShort(buf: ByteArray, off: Int, value: Int) {
        buf[off] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 1] = (value and 0xFF).toByte()
    }

    /** Builds a bare IPv4 (20-byte header, no options) + UDP header + payload packet. */
    private fun buildIpv4Udp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        protocol: Int = PacketUtils.PROTO_UDP,
    ): ByteArray {
        val ipHeaderLen = 20
        val udpLen = 8 + payload.size
        val total = ipHeaderLen + udpLen
        val out = ByteArray(total)
        out[0] = 0x45 // version 4, IHL 5 (20 bytes)
        out[9] = protocol.toByte()
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        putShort(out, 20, srcPort)
        putShort(out, 22, dstPort)
        putShort(out, 24, udpLen)
        putShort(out, 26, 0)
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    /** Encodes a DNS name into wire format (length-prefixed labels + terminating zero). */
    private fun encodeName(name: String): ByteArray {
        if (name.isEmpty()) return byteArrayOf(0)
        val out = java.io.ByteArrayOutputStream()
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        return out.toByteArray()
    }

    /** Builds a minimal DNS query message: 12-byte header + question (+ optional trailer bytes). */
    private fun buildDnsQuery(
        name: String,
        id: Int = 0x1234,
        qtype: Int = 1,
        qclass: Int = 1,
        trailer: ByteArray = ByteArray(0),
    ): ByteArray {
        val header = ByteArray(12)
        putShort(header, 0, id)
        putShort(header, 2, 0x0100) // RD=1
        putShort(header, 4, 1)      // QDCOUNT=1
        putShort(header, 6, 0)
        putShort(header, 8, 0)
        putShort(header, 10, 0)

        val nameBytes = encodeName(name)
        val question = ByteArray(nameBytes.size + 4)
        System.arraycopy(nameBytes, 0, question, 0, nameBytes.size)
        putShort(question, nameBytes.size, qtype)
        putShort(question, nameBytes.size + 2, qclass)

        return header + question + trailer
    }

    // ---------------------------------------------------------------
    // ipVersion / ihlBytes / protocol
    // ---------------------------------------------------------------

    @Test
    fun `ipVersion reads the high nibble of the first byte`() {
        val pkt = buildIpv4Udp(ip(1, 2, 3, 4), ip(5, 6, 7, 8), 100, 200, ByteArray(4))
        assertEquals(4, PacketUtils.ipVersion(pkt))
    }

    @Test
    fun `ihlBytes returns 20 for a header with no options`() {
        val pkt = buildIpv4Udp(ip(1, 2, 3, 4), ip(5, 6, 7, 8), 100, 200, ByteArray(4))
        assertEquals(20, PacketUtils.ihlBytes(pkt))
    }

    @Test
    fun `ihlBytes scales with the IHL nibble`() {
        val pkt = ByteArray(24)
        pkt[0] = 0x46 // version 4, IHL 6 -> 24 bytes
        assertEquals(24, PacketUtils.ihlBytes(pkt))
    }

    @Test
    fun `protocol reads byte 9`() {
        val udpPkt = buildIpv4Udp(ip(1, 2, 3, 4), ip(5, 6, 7, 8), 100, 200, ByteArray(4))
        assertEquals(PacketUtils.PROTO_UDP, PacketUtils.protocol(udpPkt))

        val tcpPkt = buildIpv4Udp(ip(1, 2, 3, 4), ip(5, 6, 7, 8), 100, 200, ByteArray(4), protocol = 6)
        assertEquals(6, PacketUtils.protocol(tcpPkt))
    }

    // ---------------------------------------------------------------
    // udpSrcPort / udpDstPort / udpPayload
    // ---------------------------------------------------------------

    @Test
    fun `udpSrcPort and udpDstPort read ports relative to the ihl offset`() {
        val pkt = buildIpv4Udp(ip(10, 0, 0, 1), ip(10, 0, 0, 2), 54321, 53, ByteArray(4))
        val ihl = PacketUtils.ihlBytes(pkt)
        assertEquals(54321, PacketUtils.udpSrcPort(pkt, ihl))
        assertEquals(53, PacketUtils.udpDstPort(pkt, ihl))
    }

    @Test
    fun `udpPayload extracts exactly the bytes after the udp header`() {
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val pkt = buildIpv4Udp(ip(1, 1, 1, 1), ip(2, 2, 2, 2), 1000, 53, payload)
        val ihl = PacketUtils.ihlBytes(pkt)
        val extracted = PacketUtils.udpPayload(pkt, ihl, pkt.size)
        assertArrayEquals(payload, extracted)
    }

    // ---------------------------------------------------------------
    // dnsQueryName
    // ---------------------------------------------------------------

    @Test
    fun `dnsQueryName parses a simple multi-label name`() {
        val query = buildDnsQuery("www.example.com")
        assertEquals("www.example.com", PacketUtils.dnsQueryName(query))
    }

    @Test
    fun `dnsQueryName lowercases the parsed name`() {
        val query = buildDnsQuery("WWW.Example.COM")
        assertEquals("www.example.com", PacketUtils.dnsQueryName(query))
    }

    @Test
    fun `dnsQueryName returns empty string for the root domain`() {
        // Header (12 bytes) followed immediately by the terminating zero label.
        val query = buildDnsQuery("")
        assertEquals("", PacketUtils.dnsQueryName(query))
    }

    @Test
    fun `dnsQueryName returns null for buffers shorter than a header`() {
        assertNull(PacketUtils.dnsQueryName(ByteArray(5)))
        assertNull(PacketUtils.dnsQueryName(ByteArray(12))) // exactly 12 bytes, < 13
    }

    @Test
    fun `dnsQueryName returns null when a compression pointer is present`() {
        val query = buildDnsQuery("example.com").copyOf()
        // Overwrite the first label-length byte with a compression pointer flag (0xC0).
        query[12] = 0xC0.toByte()
        assertNull(PacketUtils.dnsQueryName(query))
    }

    @Test
    fun `dnsQueryName returns null on a malformed label length that overruns the buffer`() {
        val query = buildDnsQuery("example.com")
        // Corrupt the first label length to claim more bytes than actually exist.
        query[12] = 0x7F
        assertNull(PacketUtils.dnsQueryName(query))
    }

    // ---------------------------------------------------------------
    // buildNxDomain
    // ---------------------------------------------------------------

    @Test
    fun `buildNxDomain sets QR RD RA and NXDOMAIN rcode`() {
        val query = buildDnsQuery("example.com", id = 0xABCD)
        val nx = PacketUtils.buildNxDomain(query)

        // ID is preserved.
        assertEquals(0xAB, nx[0].toInt() and 0xFF)
        assertEquals(0xCD, nx[1].toInt() and 0xFF)
        // QR=1, Opcode=0, RD=1 => 0x81
        assertEquals(0x81, nx[2].toInt() and 0xFF)
        // RA=1, rcode=3 (NXDOMAIN) => 0x83
        assertEquals(0x83, nx[3].toInt() and 0xFF)
        // AN/NS/AR counts are all zero.
        assertEquals(0, nx[6].toInt())
        assertEquals(0, nx[7].toInt())
        assertEquals(0, nx[8].toInt())
        assertEquals(0, nx[9].toInt())
        assertEquals(0, nx[10].toInt())
        assertEquals(0, nx[11].toInt())
    }

    @Test
    fun `buildNxDomain keeps only the header and single question`() {
        val query = buildDnsQuery("example.com")
        val nx = PacketUtils.buildNxDomain(query)
        // header(12) + name("example"=8 + "com"=4 + terminator=1 = 13) + qtype(2) + qclass(2)
        assertEquals(12 + 13 + 4, nx.size)
    }

    @Test
    fun `buildNxDomain truncates trailing EDNS OPT records`() {
        val trailer = byteArrayOf(0, 0, 0x29, 0x10, 0, 0, 0, 0, 0, 0, 0) // fake OPT RR bytes
        val query = buildDnsQuery("example.com", trailer = trailer)
        val nx = PacketUtils.buildNxDomain(query)
        assertEquals(12 + 13 + 4, nx.size)
    }

    @Test
    fun `buildNxDomain preserves the question section unchanged`() {
        val query = buildDnsQuery("blocked.example.net", qtype = 28, qclass = 1)
        val nx = PacketUtils.buildNxDomain(query)
        // Bytes 12..end-4 are the name; the final 4 bytes are qtype+qclass. Everything
        // except the mutated header flags/counts should be byte-for-byte identical.
        assertArrayEquals(query.copyOfRange(12, nx.size), nx.copyOfRange(12, nx.size))
    }

    // ---------------------------------------------------------------
    // buildUdpResponse
    // ---------------------------------------------------------------

    @Test
    fun `buildUdpResponse swaps source and destination addresses and ports`() {
        val clientIp = ip(10, 0, 0, 5)
        val serverIp = ip(10, 111, 222, 2)
        val request = buildIpv4Udp(clientIp, serverIp, srcPort = 54321, dstPort = 53, payload = ByteArray(4))
        val requestIhl = PacketUtils.ihlBytes(request)
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)

        val response = PacketUtils.buildUdpResponse(request, requestIhl, payload)

        // IPv4 header fields.
        assertEquals(0x45, response[0].toInt() and 0xFF)
        assertEquals(PacketUtils.PROTO_UDP, response[9].toInt() and 0xFF)
        assertArrayEquals(serverIp, response.copyOfRange(12, 16)) // src <- request dst
        assertArrayEquals(clientIp, response.copyOfRange(16, 20)) // dst <- request src

        val responseIhl = PacketUtils.ihlBytes(response)
        assertEquals(53, PacketUtils.udpSrcPort(response, responseIhl))       // replies "from" port 53
        assertEquals(54321, PacketUtils.udpDstPort(response, responseIhl))   // back "to" the client's port

        assertArrayEquals(payload, response.copyOfRange(responseIhl + 8, response.size))
    }

    @Test
    fun `buildUdpResponse sets a correctly sized total length and udp length`() {
        val request = buildIpv4Udp(ip(1, 1, 1, 1), ip(2, 2, 2, 2), 12345, 53, ByteArray(2))
        val requestIhl = PacketUtils.ihlBytes(request)
        val payload = ByteArray(10) { it.toByte() }

        val response = PacketUtils.buildUdpResponse(request, requestIhl, payload)

        assertEquals(20 + 8 + payload.size, response.size)
        val totalLength = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        assertEquals(response.size, totalLength)
        val udpLength = ((response[24].toInt() and 0xFF) shl 8) or (response[25].toInt() and 0xFF)
        assertEquals(8 + payload.size, udpLength)
        // UDP checksum is explicitly disabled (0) for IPv4.
        assertEquals(0, response[26].toInt())
        assertEquals(0, response[27].toInt())
    }

    @Test
    fun `buildUdpResponse produces a header with a valid internet checksum`() {
        val request = buildIpv4Udp(ip(192, 168, 1, 10), ip(192, 168, 1, 1), 40000, 53, ByteArray(4))
        val requestIhl = PacketUtils.ihlBytes(request)
        val response = PacketUtils.buildUdpResponse(request, requestIhl, byteArrayOf(9, 9))

        // Standard IPv4 checksum validation: summing all 16-bit words of the header
        // (including the checksum field itself) and folding carries must yield 0xFFFF.
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((response[i].toInt() and 0xFF) shl 8) or (response[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        assertEquals(0xFFFF, sum)
    }

    @Test
    fun `buildUdpResponse handles an empty payload`() {
        val request = buildIpv4Udp(ip(1, 2, 3, 4), ip(5, 6, 7, 8), 5555, 53, ByteArray(0))
        val requestIhl = PacketUtils.ihlBytes(request)
        val response = PacketUtils.buildUdpResponse(request, requestIhl, ByteArray(0))
        assertEquals(28, response.size) // 20 (ip) + 8 (udp), no payload
        assertTrue(PacketUtils.protocol(response) == PacketUtils.PROTO_UDP)
    }
}