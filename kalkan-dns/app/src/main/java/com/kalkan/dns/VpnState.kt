package com.kalkan.dns

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Servis ile arayüz arasında paylaşılan basit durum. */
object VpnState {
    enum class Status { OFF, CONNECTING, ON }
    enum class Mode { DNS, WARP }

    @Volatile var mode = Mode.DNS

    @Volatile var status = Status.OFF
    @Volatile var providerTitle = ""
    @Volatile var lastError: String? = null

    val queries = AtomicLong()
    val failures = AtomicLong()
    private val totalMs = AtomicLong()
    private val recent = ArrayDeque<String>()

    fun reset() {
        queries.set(0); failures.set(0); totalMs.set(0)
        synchronized(recent) { recent.clear() }
        lastError = null
    }

    fun record(name: String?, ms: Long, ok: Boolean) {
        queries.incrementAndGet()
        if (ok) totalMs.addAndGet(ms) else failures.incrementAndGet()
        if (name != null) synchronized(recent) {
            recent.addFirst(if (ok) "$name  ·  $ms ms" else "$name  ·  hata")
            while (recent.size > 30) recent.removeLast()
        }
    }

    fun avgMs(): Long {
        val ok = queries.get() - failures.get()
        return if (ok > 0) totalMs.get() / ok else 0
    }

    fun recentList(): List<String> = synchronized(recent) { recent.toList() }
}
