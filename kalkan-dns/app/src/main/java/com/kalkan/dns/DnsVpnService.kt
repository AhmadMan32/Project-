package com.kalkan.dns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.content.ComponentName
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Yerel VPN: yalnızca sahte DNS adresine (10.111.222.2) giden trafiği tünele alır,
 * gelen DNS sorgularını DNS-over-HTTPS ile şifreli olarak çözer.
 * Diğer tüm internet trafiği tünele girmez ve normal şekilde akar.
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.kalkan.dns.START"
        const val ACTION_STOP = "com.kalkan.dns.STOP"
        private const val TUN_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.2"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, DnsVpnService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // ACTION_START ya da sistem tarafından (Her zaman açık VPN) başlatılma
        goForeground()
        startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        shutdown()
        val provider = Providers.selected(this)
        VpnState.reset()
        VpnState.mode = VpnState.Mode.DNS
        VpnState.providerTitle = provider.title
        VpnState.status = VpnState.Status.CONNECTING
        notifyTile()

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(TUN_ADDR, 32)
            .addRoute(DNS_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .setMtu(1500)
            .setBlocking(true)
            .setConfigureIntent(mainIntent())
        // Uygulamanın kendi DoH trafiği tünele girmesin (döngü önleme)
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val fd = try { builder.establish() } catch (e: Exception) { null }
        if (fd == null) {
            VpnState.lastError = "VPN başlatılamadı (izin verilmedi mi?)"
            VpnState.status = VpnState.Status.OFF
            notifyTile()
            stopSelf()
            return
        }
        tun = fd
        val client = DohClient(provider.url)
        val workers = Executors.newFixedThreadPool(8)
        pool = workers
        VpnState.status = VpnState.Status.ON
        notifyTile()

        reader = Thread({ loop(fd, client, workers) }, "tun-reader").also { it.start() }
    }

    private fun loop(fd: ParcelFileDescriptor, client: DohClient, workers: ExecutorService) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n <= 0) continue
                val pkt = Packets.parseUdp(buf, n) ?: continue
                if (pkt.dstPort != 53) continue
                workers.execute {
                    val t0 = System.nanoTime()
                    val name = Packets.queryName(pkt.payload)
                    val answer = try {
                        client.resolve(pkt.payload).also {
                            VpnState.record(name, (System.nanoTime() - t0) / 1_000_000, true)
                        }
                    } catch (e: Exception) {
                        VpnState.record(name, 0, false)
                        VpnState.lastError = e.message
                        Packets.servfail(pkt.payload)
                    }
                    val reply = Packets.buildReply(pkt, answer)
                    try { synchronized(output) { output.write(reply) } } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            // tünel kapatıldı
        }
    }

    private fun shutdown() {
        reader?.interrupt(); reader = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        pool?.shutdownNow(); pool = null
        if (VpnState.mode == VpnState.Mode.DNS) VpnState.status = VpnState.Status.OFF
        notifyTile()
    }

    override fun onRevoke() {
        // Başka bir VPN açıldığında ya da kullanıcı sistemden kapattığında
        shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(Providers.selected(this).title)
            .setContentIntent(mainIntent())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.disconnect), stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notifyTile() {
        try { TileService.requestListeningState(this, ComponentName(this, ToggleTileService::class.java)) } catch (_: Exception) {}
    }
}
