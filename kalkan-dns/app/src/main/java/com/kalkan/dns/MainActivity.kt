package com.kalkan.dns

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        const val EXTRA_CONNECT = "connect"
        private const val REQ_VPN = 10
    }

    private lateinit var toggle: BigSwitch
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var providerBtn: TextView
    private lateinit var queries: TextView
    private lateinit var latency: TextView
    private lateinit var recent: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
        subtitle = findViewById(R.id.subtitle)
        providerBtn = findViewById(R.id.provider)
        queries = findViewById(R.id.queries)
        latency = findViewById(R.id.latency)
        recent = findViewById(R.id.recent)

        toggle.setOn(VpnState.status != VpnState.Status.OFF, animate = false)
        toggle.onToggle = { wantOn -> if (wantOn) connect() else disconnect() }
        providerBtn.setOnClickListener { pickProvider() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (intent?.getBooleanExtra(EXTRA_CONNECT, false) == true) connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) connect()
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    private fun connect() {
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, REQ_VPN)
        else onActivityResult(REQ_VPN, RESULT_OK, null)
    }

    private fun disconnect() {
        DnsVpnService.stop(this)
        toggle.setOn(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VPN) return
        if (resultCode == RESULT_OK) {
            DnsVpnService.start(this)
            toggle.setOn(true)
        } else {
            toggle.setOn(false)
        }
        render()
    }

    private fun pickProvider() {
        val list = Providers.all
        val current = list.indexOf(Providers.selected(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_provider)
            .setSingleChoiceItems(list.map { "${it.title}\n${it.subtitle}" }.toTypedArray(), current) { d, which ->
                Providers.select(this, list[which])
                d.dismiss()
                if (VpnState.status != VpnState.Status.OFF) DnsVpnService.start(this) // yeniden bağlan
                render()
            }
            .show()
    }

    private fun render() {
        val st = VpnState.status
        if (st == VpnState.Status.OFF && toggle.isOn && VpnState.lastError != null) toggle.setOn(false)
        if (st == VpnState.Status.ON && !toggle.isOn) toggle.setOn(true)
        status.setText(
            when (st) {
                VpnState.Status.ON -> R.string.connected
                VpnState.Status.CONNECTING -> R.string.connecting
                VpnState.Status.OFF -> R.string.disconnected
            }
        )
        subtitle.setText(if (st == VpnState.Status.ON) R.string.connected_sub else R.string.disconnected_sub)
        providerBtn.text = getString(R.string.provider_label, Providers.selected(this).title)
        queries.text = VpnState.queries.get().toString()
        latency.text = if (VpnState.avgMs() > 0) "${VpnState.avgMs()} ms" else "–"
        val items = VpnState.recentList()
        recent.text = if (items.isEmpty()) getString(R.string.no_queries) else items.joinToString("\n")
    }
}
