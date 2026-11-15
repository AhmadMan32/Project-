package com.kalkan.dns

import android.content.Context

/** DNS-over-HTTPS sağlayıcıları. */
data class Provider(val id: String, val title: String, val subtitle: String, val url: String)

object Providers {
    val all = listOf(
        Provider("cf", "Standart (1.1.1.1)", "Hızlı ve gizli DNS", "https://cloudflare-dns.com/dns-query"),
        Provider("cf_sec", "Kötü amaçlı yazılım engelleme (1.1.1.2)", "Zararlı siteleri engeller", "https://security.cloudflare-dns.com/dns-query"),
        Provider("cf_family", "Aile koruması (1.1.1.3)", "Zararlı + yetişkin içeriği engeller", "https://family.cloudflare-dns.com/dns-query"),
        Provider("adguard", "Reklam engelleme (AdGuard)", "Reklam ve izleyicileri engeller", "https://dns.adguard-dns.com/dns-query"),
        Provider("quad9", "Quad9 (9.9.9.9)", "Güvenlik odaklı DNS", "https://dns.quad9.net/dns-query"),
        Provider("google", "Google (8.8.8.8)", "Google Public DNS", "https://dns.google/dns-query"),
    )

    private const val PREFS = "kalkan"
    private const val KEY = "provider"

    fun selected(ctx: Context): Provider {
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "cf")
        return all.firstOrNull { it.id == id } ?: all[0]
    }

    fun select(ctx: Context, p: Provider) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, p.id).apply()
    }
}
