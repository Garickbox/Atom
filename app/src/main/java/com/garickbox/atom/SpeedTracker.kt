package com.garickbox.atom

import java.util.ArrayDeque

/**
 * Скользящее окно скорости. Глобальный rate-limit 500мс — не чаще чем 2 раза в секунду,
 * даже если 4 потока дёргают одновременно.
 */
class SpeedTracker(private val windowMs: Long = 5_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    @Volatile private var lastAccept: Long = 0L

    @Synchronized
    fun onProgress(totalBytes: Long) {
        val now = System.currentTimeMillis()
        if (now - lastAccept < 500) return
        lastAccept = now
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
        lastAccept = 0L
    }
}
