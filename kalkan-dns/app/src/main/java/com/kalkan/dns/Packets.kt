package com.kalkan.dns

/** Tünelden gelen IPv4/UDP paketlerini ayrıştırma ve yanıt paketi oluşturma. */
object Packets {

    class UdpPacket(
        val srcIp: ByteArray, val dstIp: ByteArray,
        val srcPort: Int, val dstPort: Int,
        val payload: ByteArray,
    )

    fun parseUdp(buf: ByteArray, len: Int): UdpPacket? {
        if (len < 28) return null
        val version = (buf[0].toInt() shr 4) and 0xF
        if (version != 4) return null
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (buf[9].toInt() != 17 || len < ihl + 8) return null // yalnızca UDP
        val total = minOf(u16(buf, 2), len)
        val srcPort = u16(buf, ihl)
        val dstPort = u16(buf, ihl + 2)
        val udpLen = u16(buf, ihl + 4)
        val payloadLen = minOf(udpLen - 8, total - ihl - 8)
        if (payloadLen <= 0) return null
        return UdpPacket(
            buf.copyOfRange(12, 16), buf.copyOfRange(16, 20),
            srcPort, dstPort,
            buf.copyOfRange(ihl + 8, ihl + 8 + payloadLen),
        )
    }

    /** Orijinal sorgunun kaynağına geri gidecek IPv4/UDP yanıt paketi. */
    fun buildReply(req: UdpPacket, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000) // Don't fragment
        p[8] = 64           // TTL
        p[9] = 17           // UDP
        System.arraycopy(req.dstIp, 0, p, 12, 4)
        System.arraycopy(req.srcIp, 0, p, 16, 4)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, req.dstPort)
        put16(p, 22, req.srcPort)
        put16(p, 24, 8 + payload.size)
        // UDP sağlama toplamı IPv4'te isteğe bağlıdır (0 = yok)
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    /** Sorgudan SERVFAIL yanıtı üretir (DoH başarısız olursa). */
    fun servfail(query: ByteArray): ByteArray {
        val r = query.copyOf()
        if (r.size >= 4) {
            r[2] = (r[2].toInt() or 0x80).toByte()                // QR = yanıt
            r[3] = ((r[3].toInt() and 0xF0) or 0x82).toByte()     // RA + RCODE=2
        }
        return r
    }

    /** DNS sorgusundaki alan adını okur (log için). */
    fun queryName(dns: ByteArray): String? {
        if (dns.size < 13) return null
        val sb = StringBuilder()
        var i = 12
        while (i < dns.size) {
            val l = dns[i].toInt() and 0xFF
            if (l == 0) break
            if (l > 63 || i + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, i + 1, l, Charsets.US_ASCII))
            i += l + 1
        }
        return sb.toString().ifEmpty { "." }
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) { sum += u16(b, i); i += 2 }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
