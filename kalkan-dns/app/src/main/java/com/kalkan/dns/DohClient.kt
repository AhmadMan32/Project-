package com.kalkan.dns

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** RFC 8484 DNS-over-HTTPS istemcisi (POST, application/dns-message). */
class DohClient(private val endpoint: String) {

    fun resolve(query: ByteArray): ByteArray {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.setFixedLengthStreamingMode(query.size)
            conn.outputStream.use { it.write(query) }
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(512)
                input.copyTo(out)
                out.toByteArray()
            }
        } finally {
            // Bağlantı havuzunda kalması için disconnect() çağrılmıyor (keep-alive).
        }
    }
}
