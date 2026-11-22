package com.kalkan.dns

import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Test

class WarpConfigTest {

    private fun sample(): String {
        val me = KeyPair(); val server = KeyPair()
        return """
            [Interface]
            PrivateKey = ${me.privateKey.toBase64()}
            Address = 10.66.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = ${server.publicKey.toBase64()}
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 203.0.113.10:51820
            PersistentKeepalive = 25
        """.trimIndent()
    }

    @Test
    fun parsesAndRoundTrips() {
        val cfg = Config.parse(sample().byteInputStream())
        assertEquals(1, cfg.peers.size)
        assertEquals("203.0.113.10:51820", cfg.peers[0].endpoint.get().toString())
        val again = Config.parse(cfg.toWgQuickString().byteInputStream())
        assertEquals(cfg.toWgQuickString(), again.toWgQuickString())
    }

    @Test(expected = BadConfigException::class)
    fun rejectsBadKey() {
        Config.parse(sample().replace(Regex("PrivateKey = .*"), "PrivateKey = bozuk").byteInputStream())
    }
}
