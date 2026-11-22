package com.kalkan.dns

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File

/**
 * WARP modu: tüm internet trafiğini WireGuard tüneli üzerinden şifreler.
 * Yapılandırma standart bir WireGuard .conf dosyasıdır (kendi sunucunuz ya da bir sağlayıcı).
 */
object WarpManager {

    private const val FILE = "warp.conf"
    @Volatile private var backend: GoBackend? = null
    @Volatile private var config: Config? = null

    private val tunnel = object : Tunnel {
        override fun getName() = "kalkan-warp"
        override fun onStateChange(newState: Tunnel.State) {
            VpnState.status = if (newState == Tunnel.State.UP) VpnState.Status.ON else VpnState.Status.OFF
        }
    }

    private fun backend(ctx: Context): GoBackend =
        backend ?: synchronized(this) { backend ?: GoBackend(ctx.applicationContext).also { backend = it } }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun hasConfig(ctx: Context) = file(ctx).exists()

    /** Yapılandırmayı doğrular ve kaydeder. Hatalıysa açıklayıcı bir mesajla istisna fırlatır. */
    fun importConfig(ctx: Context, text: String) {
        val cfg = try {
            Config.parse(text.byteInputStream())
        } catch (e: Exception) {
            throw IllegalArgumentException("Geçersiz WireGuard yapılandırması: ${e.message ?: e.javaClass.simpleName}")
        }
        require(cfg.peers.isNotEmpty()) { "Yapılandırmada [Peer] bölümü yok" }
        file(ctx).writeText(cfg.toWgQuickString())
        config = cfg
    }

    fun deleteConfig(ctx: Context) {
        file(ctx).delete()
        config = null
    }

    private fun load(ctx: Context): Config =
        config ?: file(ctx).inputStream().use { Config.parse(it) }.also { config = it }

    /** Sunucu adresi ve tünel IP'si (arayüzde göstermek için). */
    fun summary(ctx: Context): String? = try {
        val c = load(ctx)
        val ep = c.peers.firstOrNull()?.endpoint?.orElse(null)?.toString() ?: "?"
        val addr = c.`interface`.addresses.joinToString { it.toString() }
        "$ep  ·  $addr"
    } catch (_: Exception) { null }

    /** Engelleyici: arka plan iş parçacığından çağırın. */
    fun up(ctx: Context) {
        VpnState.reset()
        VpnState.mode = VpnState.Mode.WARP
        VpnState.status = VpnState.Status.CONNECTING
        try {
            val state = backend(ctx).setState(tunnel, Tunnel.State.UP, load(ctx))
            VpnState.status = if (state == Tunnel.State.UP) VpnState.Status.ON else VpnState.Status.OFF
        } catch (e: Exception) {
            VpnState.lastError = "WARP başlatılamadı: ${e.message ?: e.javaClass.simpleName}"
            VpnState.status = VpnState.Status.OFF
        }
    }

    /** Engelleyici: arka plan iş parçacığından çağırın. */
    fun down(ctx: Context) {
        try { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        if (VpnState.mode == VpnState.Mode.WARP) VpnState.status = VpnState.Status.OFF
    }

    fun isUp(ctx: Context): Boolean =
        backend?.let { tunnel.name in it.runningTunnelNames } ?: false

    /** İndirilen / yüklenen bayt. */
    fun traffic(ctx: Context): Pair<Long, Long> = try {
        val s = backend(ctx).getStatistics(tunnel)
        s.totalRx() to s.totalTx()
    } catch (_: Exception) { 0L to 0L }
}
