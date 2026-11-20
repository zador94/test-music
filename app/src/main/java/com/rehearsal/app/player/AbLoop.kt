package com.rehearsal.app.player

import com.rehearsal.app.audio.BeatClock

/**
 * Петля A–B в миллисекундах песни.
 * Шкала петли — отдельный отрезок длиной B − A: 0 на ней это A в песне.
 * Прыжок на A делает плеер через seek. Поток PCM конец на B не сообщает:
 * иначе звук гаснет, а часы таймлайна (длина всей песни) идут дальше.
 */
object AbLoop {
    data class Loop(val aMs: Long, val bMs: Long)

    /** Короче одной доли петля не включается: дырка seek съедает весь повтор. */
    fun arm(aMs: Long, bMs: Long, intervalUs: Long, durationMs: Long): Loop? {
        if (intervalUs <= 0L || durationMs <= 0L) return null
        val a = aMs.coerceIn(0L, durationMs)
        val b = bMs.coerceIn(0L, durationMs)
        if (b * 1000L - a * 1000L < intervalUs) return null
        return Loop(a, b)
    }

    fun snapMs(positionMs: Long, originUs: Long, intervalUs: Long, durationMs: Long): Long {
        val position = positionMs.coerceAtLeast(0L)
        if (intervalUs <= 0L || intervalUs == Long.MAX_VALUE) {
            return if (durationMs > 0L) position.coerceAtMost(durationMs) else position
        }
        val beatUs = BeatClock.nearestBeatUs(position * 1000L, intervalUs, originUs)
        val ms = beatUs / 1000L
        return if (durationMs > 0L) ms.coerceIn(0L, durationMs) else ms.coerceAtLeast(0L)
    }

    fun spanMs(loop: Loop): Long = (loop.bMs - loop.aMs).coerceAtLeast(0L)

    /** Позиция на шкале петли. До A и после B шкала стоит на краях. */
    fun localMs(songMs: Long, loop: Loop): Long {
        val span = spanMs(loop)
        if (songMs <= loop.aMs) return 0L
        if (songMs >= loop.bMs) return span
        return songMs - loop.aMs
    }

    /** Точка шкалы петли → миллисекунда песни. */
    fun songMs(localMs: Long, loop: Loop): Long {
        val span = spanMs(loop)
        return loop.aMs + localMs.coerceIn(0L, span)
    }

    /** Пока петля включена, seek по песне остаётся внутри [A, B). */
    fun clampSongMs(songMs: Long, loop: Loop): Long {
        if (songMs < loop.aMs) return loop.aMs
        if (songMs >= loop.bMs) return (loop.bMs - 1L).coerceAtLeast(loop.aMs)
        return songMs
    }

    fun jumpsToA(positionMs: Long, loop: Loop): Boolean = positionMs >= loop.bMs
}

/** Включённый отрезок. Читают UI-поток и плеер. */
class AbLoopGate {
    @Volatile var armed: Boolean = false
        private set
    @Volatile var aMs: Long = 0L
        private set
    @Volatile var endUs: Long = Long.MAX_VALUE
        private set

    fun apply(loop: AbLoop.Loop?) {
        if (loop == null) {
            armed = false
            return
        }
        aMs = loop.aMs
        endUs = loop.bMs * 1000L
        armed = true
    }

    fun reset() {
        armed = false
        aMs = 0L
        endUs = Long.MAX_VALUE
    }

    fun current(): AbLoop.Loop? {
        if (!armed || endUs == Long.MAX_VALUE) return null
        return AbLoop.Loop(aMs, endUs / 1000L)
    }
}
