package com.kalkan.dns

import android.content.Context
import java.util.concurrent.Executors

/** İki modu (1.1.1.1 DNS ve WARP) tek yerden yönetir; ikisi aynı anda açık olamaz. */
object Connection {

    private const val PREFS = "kalkan"
    private const val KEY_MODE = "mode"
    private val worker = Executors.newSingleThreadExecutor()

    fun mode(ctx: Context): VpnState.Mode =
        if (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "dns") == "warp")
            VpnState.Mode.WARP else VpnState.Mode.DNS

    fun setMode(ctx: Context, m: VpnState.Mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, if (m == VpnState.Mode.WARP) "warp" else "dns").apply()
    }

    fun isActive() = VpnState.status != VpnState.Status.OFF

    /** VPN izni alınmış olmalı (VpnService.prepare == null). */
    fun connect(ctx: Context) {
        val app = ctx.applicationContext
        if (mode(app) == VpnState.Mode.WARP) {
            if (!WarpManager.hasConfig(app)) {
                VpnState.lastError = app.getString(R.string.warp_no_config)
                return
            }
            VpnState.mode = VpnState.Mode.WARP
            VpnState.status = VpnState.Status.CONNECTING
            DnsVpnService.stop(app)
            worker.execute { WarpManager.up(app) }
        } else {
            worker.execute {
                WarpManager.down(app)
                VpnState.mode = VpnState.Mode.DNS
                DnsVpnService.start(app)
            }
        }
    }

    fun disconnect(ctx: Context) {
        val app = ctx.applicationContext
        DnsVpnService.stop(app)
        worker.execute { WarpManager.down(app) }
        VpnState.status = VpnState.Status.OFF
    }

    /** Bağlıyken mod ya da sağlayıcı değiştiyse yeniden bağlan. */
    fun reconnectIfActive(ctx: Context) {
        if (isActive()) connect(ctx)
    }
}
