package com.rehearsal.app.player

import com.rehearsal.app.audio.ClickMix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioTrack.setPlaybackParams на части HAL (BlueStacks) принимает только speed ≤ 1:
 * слайдер вниз замедляет, вверх — тишина в изменении темпа.
 * Для speed > 1 ужимаем stem PCM и отдаём в AudioTrack на 1×. Клики — [ClickTrack], не этот буфер.
 * Это не Sonic drain: буфер тот же identity, гейт по-прежнему режет частоту setPlaybackSpeed.
 */
object SpeedStretch {
    const val APPLY_ABOVE = 1.001f

    fun softwareSpeed(requested: Float): Float {
        if (!requested.isFinite()) return 1f
        return if (requested > APPLY_ABOVE) {
            requested.coerceAtMost(PlaybackMath.MAX_SPEED)
        } else {
            1f
        }
    }

    fun audioTrackSpeed(requested: Float): Float {
        if (!requested.isFinite()) return 1f
        val clamped = requested.coerceIn(PlaybackMath.MIN_SPEED, PlaybackMath.MAX_SPEED)
        return if (clamped > APPLY_ABOVE) 1f else clamped
    }

    fun outputFrameCount(inputFrames: Int, speed: Float): Int {
        val stretch = softwareSpeed(speed)
        if (stretch <= APPLY_ABOVE) return inputFrames.coerceAtLeast(0)
        val frames = inputFrames.coerceAtLeast(0)
        if (frames <= 1) return frames
        return (frames / stretch.toDouble()).toInt().coerceAtLeast(1)
    }

    fun decimate(
        source: ByteBuffer,
        dest: ByteBuffer,
        speed: Float,
        encoding: Int,
        channelCount: Int,
    ): ByteBuffer {
        val stretch = softwareSpeed(speed)
        if (stretch <= APPLY_ABOVE) return source
        val frameSize = ClickMix.frameSizeBytes(encoding, channelCount)
        val remaining = source.remaining()
        if (frameSize <= 0 || remaining < frameSize * 2) return source
        val inFrames = remaining / frameSize
        val outFrames = outputFrameCount(inFrames, stretch)
        if (outFrames >= inFrames) return source
        val outBytes = outFrames * frameSize
        val out = if (dest.capacity() < outBytes) {
            ByteBuffer.allocateDirect(outBytes.coerceAtLeast(4096))
        } else {
            dest
        }
        out.order(ByteOrder.nativeOrder())
        out.clear()
        val srcPos = source.position()
        for (i in 0 until outFrames) {
            val srcFrame = ((i.toLong() * inFrames) / outFrames).toInt().coerceAtMost(inFrames - 1)
            val from = srcPos + srcFrame * frameSize
            val to = i * frameSize
            for (b in 0 until frameSize) {
                out.put(to + b, source.get(from + b))
            }
        }
        out.position(0)
        out.limit(outBytes)
        return out
    }
}
