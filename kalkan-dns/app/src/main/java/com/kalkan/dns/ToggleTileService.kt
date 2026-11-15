package com.kalkan.dns

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Hızlı ayarlar paneli kutucuğu: tek dokunuşla aç/kapat. */
class ToggleTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (VpnState.status != VpnState.Status.OFF) {
            DnsVpnService.stop(this)
        } else if (VpnService.prepare(this) == null) {
            DnsVpnService.start(this)
        } else {
            // VPN izni henüz verilmemiş: uygulamayı aç
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_CONNECT, true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(i)
            }
        }
        qsTile?.let { it.state = Tile.STATE_UNAVAILABLE; it.updateTile() }
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (VpnState.status == VpnState.Status.ON) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
