package com.kalkan.dns

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val EXTRA_CONNECT = "connect"
        private const val REQ_VPN = 10
        private const val REQ_FILE = 11
    }

    private lateinit var toggle: BigSwitch
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var modeDns: TextView
    private lateinit var modeWarp: TextView
    private lateinit var providerBtn: TextView
    private lateinit var warpBtn: TextView
    private lateinit var stat1: TextView
    private lateinit var stat1Label: TextView
    private lateinit var stat2: TextView
    private lateinit var stat2Label: TextView
    private lateinit var recentTitle: TextView
    private lateinit var recent: TextView
    private var shownError: String? = null
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
        modeDns = findViewById(R.id.mode_dns)
        modeWarp = findViewById(R.id.mode_warp)
        providerBtn = findViewById(R.id.provider)
        warpBtn = findViewById(R.id.warp_config)
        stat1 = findViewById(R.id.stat1); stat1Label = findViewById(R.id.stat1_label)
        stat2 = findViewById(R.id.stat2); stat2Label = findViewById(R.id.stat2_label)
        recentTitle = findViewById(R.id.recent_title)
        recent = findViewById(R.id.recent)

        toggle.setOn(Connection.isActive(), animate = false)
        toggle.onToggle = { wantOn -> if (wantOn) connect() else disconnect() }
        providerBtn.setOnClickListener { pickProvider() }
        warpBtn.setOnClickListener { warpMenu() }
        modeDns.setOnClickListener { switchMode(VpnState.Mode.DNS) }
        modeWarp.setOnClickListener { switchMode(VpnState.Mode.WARP) }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_CONNECT, false) == true) connect()
        // .conf dosyası "Birlikte aç" ile gönderildiyse
        if (i?.action == Intent.ACTION_VIEW || i?.action == Intent.ACTION_SEND) {
            val uri: Uri? = i.data ?: if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            uri?.let { importFromUri(it) }
        }
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    private fun connect() {
        if (Connection.mode(this) == VpnState.Mode.WARP && !WarpManager.hasConfig(this)) {
            toggle.setOn(false)
            warpMenu()
            return
        }
        VpnState.lastError = null
        shownError = null
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, REQ_VPN)
        else onActivityResult(REQ_VPN, RESULT_OK, null)
    }

    private fun disconnect() {
        Connection.disconnect(this)
        toggle.setOn(false)
    }

    private fun switchMode(m: VpnState.Mode) {
        if (Connection.mode(this) == m) return
        Connection.setMode(this, m)
        if (m == VpnState.Mode.WARP && !WarpManager.hasConfig(this)) {
            if (Connection.isActive()) disconnect()
            warpMenu()
        } else {
            Connection.reconnectIfActive(this)
        }
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> {
                if (resultCode == RESULT_OK) {
                    Connection.connect(this)
                    toggle.setOn(true)
                } else toggle.setOn(false)
                render()
            }
            REQ_FILE -> if (resultCode == RESULT_OK) data?.data?.let { importFromUri(it) }
        }
    }

    // ---------- 1.1.1.1 sağlayıcı seçimi ----------

    private fun pickProvider() {
        val list = Providers.all
        val current = list.indexOf(Providers.selected(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_provider)
            .setSingleChoiceItems(list.map { "${it.title}\n${it.subtitle}" }.toTypedArray(), current) { d, which ->
                Providers.select(this, list[which])
                d.dismiss()
                Connection.reconnectIfActive(this)
                render()
            }
            .show()
    }

    // ---------- WARP yapılandırması ----------

    private fun warpMenu() {
        val items = mutableListOf(getString(R.string.warp_import_file), getString(R.string.warp_import_paste))
        if (WarpManager.hasConfig(this)) items += getString(R.string.warp_delete)
        AlertDialog.Builder(this)
            .setTitle(R.string.warp_config_title)
            .setMessage(if (WarpManager.hasConfig(this)) null else getString(R.string.warp_help))
            .apply {
                if (WarpManager.hasConfig(this@MainActivity)) {
                    setItems(items.toTypedArray()) { _, w -> onWarpMenu(w) }
                } else {
                    // Mesaj ile öğe listesi aynı anda gösterilemediği için düğme kullanılır
                    setPositiveButton(R.string.warp_import_file) { _, _ -> onWarpMenu(0) }
                    setNeutralButton(R.string.warp_import_paste) { _, _ -> onWarpMenu(1) }
                    setNegativeButton(android.R.string.cancel, null)
                }
            }
            .show()
    }

    private fun onWarpMenu(which: Int) {
        when (which) {
            0 -> startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE
            )
            1 -> pasteDialog()
            2 -> {
                if (Connection.mode(this) == VpnState.Mode.WARP && Connection.isActive()) disconnect()
                WarpManager.deleteConfig(this)
                render()
            }
        }
    }

    private fun pasteDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            hint = "[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\nPublicKey = …\nEndpoint = …"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.warp_import_paste)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> importText(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFromUri(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            require(bytes.size <= 64 * 1024) { "dosya çok büyük" }
            val text = String(bytes, Charsets.UTF_8)
            importText(text)
        } catch (e: Exception) {
            toast(getString(R.string.warp_read_error, e.message ?: ""))
        }
    }

    private fun importText(text: String) {
        try {
            WarpManager.importConfig(this, text)
            Connection.setMode(this, VpnState.Mode.WARP)
            toast(getString(R.string.warp_imported))
            Connection.reconnectIfActive(this)
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle(R.string.error).setMessage(e.message)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- ekran güncelleme ----------

    private fun render() {
        val st = VpnState.status
        val mode = Connection.mode(this)
        val warp = mode == VpnState.Mode.WARP

        if (st == VpnState.Status.OFF && toggle.isOn && VpnState.lastError != null) toggle.setOn(false)
        if (st == VpnState.Status.ON && !toggle.isOn) toggle.setOn(true)
        VpnState.lastError?.let { if (it != shownError) { shownError = it; toast(it) } }

        modeDns.isSelected = !warp
        modeWarp.isSelected = warp

        status.setText(
            when (st) {
                VpnState.Status.ON -> R.string.connected
                VpnState.Status.CONNECTING -> R.string.connecting
                VpnState.Status.OFF -> R.string.disconnected
            }
        )
        subtitle.setText(
            when {
                st != VpnState.Status.ON -> if (warp) R.string.disconnected_sub_warp else R.string.disconnected_sub
                VpnState.mode == VpnState.Mode.WARP -> R.string.connected_sub_warp
                else -> R.string.connected_sub
            }
        )

        providerBtn.visibility = if (warp) View.GONE else View.VISIBLE
        warpBtn.visibility = if (warp) View.VISIBLE else View.GONE
        providerBtn.text = getString(R.string.provider_label, Providers.selected(this).title)
        warpBtn.text = WarpManager.summary(this)?.let { getString(R.string.warp_label, it) }
            ?: getString(R.string.warp_label_none)

        if (warp) {
            val (rx, tx) = if (st == VpnState.Status.ON) WarpManager.traffic(this) else 0L to 0L
            stat1.text = human(rx); stat1Label.setText(R.string.stat_download)
            stat2.text = human(tx); stat2Label.setText(R.string.stat_upload)
            recentTitle.visibility = View.GONE
            recent.visibility = View.GONE
        } else {
            stat1.text = VpnState.queries.get().toString(); stat1Label.setText(R.string.stat_queries)
            stat2.text = if (VpnState.avgMs() > 0) "${VpnState.avgMs()} ms" else "–"
            stat2Label.setText(R.string.stat_latency)
            recentTitle.visibility = View.VISIBLE
            recent.visibility = View.VISIBLE
            val items = VpnState.recentList()
            recent.text = if (items.isEmpty()) getString(R.string.no_queries) else items.joinToString("\n")
        }
    }

    private fun human(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format("%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
