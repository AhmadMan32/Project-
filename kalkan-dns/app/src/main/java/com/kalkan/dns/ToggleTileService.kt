package com.kalkan.dns

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Hızlı ayarlar paneli kutucuğu: seçili modu (1.1.1.1 / WARP) tek dokunuşla aç/kapat. */
class ToggleTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val needsApp = VpnService.prepare(this) != null ||
            (Connection.mode(this) == VpnState.Mode.WARP && !WarpManager.hasConfig(this))
        when {
            Connection.isActive() -> Connection.disconnect(this)
            !needsApp -> Connection.connect(this)
            else -> {
                // VPN izni ya da WARP yapılandırması eksik: uygulamayı aç
                val i = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_CONNECT, true)
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
                } else {
                    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                    startActivityAndCollapse(i)
                }
                return
            }
        }
        qsTile?.let { it.state = if (Connection.isActive()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; it.updateTile() }
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (VpnState.status == VpnState.Status.ON) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (Connection.mode(this) == VpnState.Mode.WARP) "WARP" else getString(R.string.app_name)
        t.updateTile()
    }
}
