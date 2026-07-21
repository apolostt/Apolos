package com.apolos.shieldlite;

/** Minimal IPv4/UDP/DNS byte-manipulation helpers for the DNS-filtering tunnel. */
final class PacketUtils {

    static final int PROTO_UDP = 17;

    private PacketUtils() { }

    static int ipVersion(byte[] pkt) { return (pkt[0] & 0xF0) >>> 4; }

    static int ihlBytes(byte[] pkt) { return (pkt[0] & 0x0F) * 4; }

    static int protocol(byte[] pkt) { return pkt[9] & 0xFF; }

    static int udpDstPort(byte[] pkt, int ihl) {
        return ((pkt[ihl + 2] & 0xFF) << 8) | (pkt[ihl + 3] & 0xFF);
    }

    static int udpSrcPort(byte[] pkt, int ihl) {
        return ((pkt[ihl] & 0xFF) << 8) | (pkt[ihl + 1] & 0xFF);
    }

    static byte[] udpPayload(byte[] pkt, int ihl, int length) {
        int start = ihl + 8;
        byte[] out = new byte[Math.max(0, length - start)];
        System.arraycopy(pkt, start, out, 0, out.length);
        return out;
    }

    static String dnsQueryName(byte[] dns) {
        if (dns.length < 13) return null;
        int pos = 12;
        StringBuilder sb = new StringBuilder();
        while (pos < dns.length) {
            int len = dns[pos] & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) != 0) return null;
            pos++;
            if (pos + len > dns.length) return null;
            if (sb.length() > 0) sb.append('.');
            for (int i = 0; i < len; i++) sb.append((char) (dns[pos + i] & 0xFF));
            pos += len;
        }
        return sb.toString().toLowerCase();
    }

    static byte[] buildNxDomain(byte[] query) {
        int pos = 12;
        while (pos < query.length) {
            int len = query[pos] & 0xFF;
            if (len == 0) { pos++; break; }
            if ((len & 0xC0) != 0) { pos += 2; break; }
            pos += 1 + len;
        }
        int end = Math.min(pos + 4, query.length);
        byte[] out = new byte[end];
        System.arraycopy(query, 0, out, 0, end);
        out[2] = (byte) 0x81;
        out[3] = (byte) 0x83;
        out[6] = 0; out[7] = 0;
        out[8] = 0; out[9] = 0;
        out[10] = 0; out[11] = 0;
        return out;
    }

    static byte[] buildUdpResponse(byte[] request, int requestIhl, byte[] payload) {
        int ipHeaderLen = 20;
        int udpLen = 8 + payload.length;
        int total = ipHeaderLen + udpLen;
        byte[] out = new byte[total];

        out[0] = 0x45;
        out[1] = 0;
        putShort(out, 2, total);
        putShort(out, 4, 0);
        putShort(out, 6, 0x4000);
        out[8] = 64;
        out[9] = (byte) PROTO_UDP;
        System.arraycopy(request, 16, out, 12, 4);
        System.arraycopy(request, 12, out, 16, 4);
        putShort(out, 10, 0);
        putShort(out, 10, checksum(out, 0, ipHeaderLen));

        int srcPort = udpDstPort(request, requestIhl);
        int dstPort = udpSrcPort(request, requestIhl);
        putShort(out, 20, srcPort);
        putShort(out, 22, dstPort);
        putShort(out, 24, udpLen);
        putShort(out, 26, 0);
        System.arraycopy(payload, 0, out, 28, payload.length);
        return out;
    }

    private static void putShort(byte[] buf, int off, int value) {
        buf[off] = (byte) ((value >>> 8) & 0xFF);
        buf[off + 1] = (byte) (value & 0xFF);
    }

    private static int checksum(byte[] buf, int off, int len) {
        int sum = 0;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) sum += (buf[i] & 0xFF) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (~sum) & 0xFFFF;
    }
}
