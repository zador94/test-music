package com.rehearsal.app.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Слайдер метронома шлёт десятки onValueChange в секунду.
 * Каждый [androidx.media3.common.Player.setPlaybackSpeed] на Sonic = drain
 * (queueEndOfStream). На снижении скорости Sonic отдаёт больше PCM, drain
 * не заканчивается, handleBuffer вечно false → тишина, playWhenReady уже true,
 * play() — no-op, пока не load() (смена таба).
 *
 * Гейт оставляет последний запрошенный speed и пускает его не чаще [minIntervalMs].
 */
class PlaybackSpeedGate(
    private val minIntervalMs: Long = 100L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var applied: Float = Float.NaN
    private var lastApplyMs: Long = 0L
    private var pending: Float? = null

    fun reset() {
        applied = Float.NaN
        lastApplyMs = 0L
        pending = null
    }

    fun request(speed: Float, force: Boolean = false): Float? {
        val quantized = quantize(speed) ?: return null
        if (force) {
            applied = quantized
            lastApplyMs = nowMs()
            pending = null
            return quantized
        }
        if (!applied.isNaN() && abs(quantized - applied) < MIN_DELTA) {
            pending = null
            return null
        }
        val t = nowMs()
        if (applied.isNaN() || t - lastApplyMs >= minIntervalMs) {
            applied = quantized
            lastApplyMs = t
            pending = null
            return quantized
        }
        pending = quantized
        return null
    }

    fun poll(): Float? {
        val queued = pending ?: return null
        if (nowMs() - lastApplyMs < minIntervalMs) return null
        pending = null
        applied = queued
        lastApplyMs = nowMs()
        return queued
    }

    companion object {
        const val MIN_DELTA = 0.01f

        fun quantize(speed: Float): Float? {
            if (!speed.isFinite()) return null
            val clamped = speed.coerceIn(PlaybackMath.MIN_SPEED, PlaybackMath.MAX_SPEED)
            return (clamped * 100f).roundToInt() / 100f
        }
    }
}
