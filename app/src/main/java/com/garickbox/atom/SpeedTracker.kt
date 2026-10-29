package com.garickbox.atom

import java.util.ArrayDeque

/**
 * Скользящее окно 5 сек — считает мгновенную скорость в байт/с.
 */
class SpeedTracker(private val windowMs: Long = 5_000L) {

    private val samples = ArrayDeque<Pair<Long, Long>>()  // (timestamp, totalBytes)

    @Synchronized
    fun onProgress(totalBytes: Long) {
        val now = System.currentTimeMillis()
        samples.addLast(now to totalBytes)
        while (samples.isNotEmpty() && now - samples.first().first > windowMs) {
            samples.removeFirst()
        }
    }

    @Synchronized
    fun bytesPerSecond(): Long {
        if (samples.size < 2) return 0L
        val first = samples.first()
        val last = samples.last()
        val dt = last.first - first.first
        if (dt <= 0) return 0L
        return ((last.second - first.second) * 1000L) / dt
    }

    @Synchronized
    fun reset() {
        samples.clear()
    }

    fun formatSpeed(bps: Long): String {
        if (bps <= 0) return "—"
        val mbps = bps / 1024.0 / 1024.0
        return "%.1f МБ/с".format(mbps)
    }
}
