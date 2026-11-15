package com.kalkan.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketsTest {

    /** example.com için A kaydı sorgusu */
    private fun dnsQuery(): ByteArray {
        val name = "example.com".split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }
        return byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0) +
            name.toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
    }

    private fun ipUdp(payload: ByteArray): ByteArray {
        val src = Packets.UdpPacket(byteArrayOf(10, 111, 222.toByte(), 2), byteArrayOf(10, 111, 222.toByte(), 1), 53, 40000, byteArrayOf())
        // buildReply kaynak/hedefi çevirir: 10.111.222.1:40000 -> 10.111.222.2:53
        return Packets.buildReply(src, payload)
    }

    @Test
    fun parseAndReplyRoundTrip() {
        val q = dnsQuery()
        val raw = ipUdp(q)
        val pkt = Packets.parseUdp(raw, raw.size)
        assertNotNull(pkt)
        assertEquals(53, pkt!!.dstPort)
        assertEquals(40000, pkt.srcPort)
        assertTrue(q.contentEquals(pkt.payload))
        assertEquals("example.com", Packets.queryName(pkt.payload))

        val reply = Packets.parseUdp(Packets.buildReply(pkt, byteArrayOf(1, 2, 3)), 31)!!
        assertEquals(53, reply.srcPort)
        assertEquals(40000, reply.dstPort)
        assertTrue(reply.dstIp.contentEquals(pkt.srcIp))
    }

    @Test
    fun ipChecksumIsValid() {
        val raw = ipUdp(dnsQuery())
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum)
    }

    @Test
    fun servfailKeepsIdAndSetsRcode() {
        val r = Packets.servfail(dnsQuery())
        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        assertEquals(2, r[3].toInt() and 0x0F)
        assertTrue(r[2].toInt() and 0x80 != 0)
    }

    /** Ağ erişimi gerektirir: KALKAN_NET_TEST=1 ile çalıştırın. */
    @Test
    fun liveDohResolve() {
        if (System.getenv("KALKAN_NET_TEST") != "1") return
        val q = dnsQuery()
        val ans = DohClient(Providers.all[0].url).resolve(q)
        assertEquals(0x12, ans[0].toInt()); assertEquals(0x34, ans[1].toInt())
        assertEquals(0, ans[3].toInt() and 0x0F) // NOERROR
        val ancount = ((ans[6].toInt() and 0xFF) shl 8) or (ans[7].toInt() and 0xFF)
        assertTrue("cevap kaydı yok", ancount > 0)
        println("DoH yanıtı: ${ans.size} bayt, $ancount kayıt")
    }
}
