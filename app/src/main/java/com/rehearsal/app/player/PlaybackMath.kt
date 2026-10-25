package com.rehearsal.app.player

import com.rehearsal.app.audio.MetronomeClickEngine

/** Чистая формула скорости и ввода BPM: без ExoPlayer, её же крутят тесты. */
object PlaybackMath {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2.0f

    fun playbackSpeed(metronomeBpm: Float, originalBpm: Float): Float {
        if (!metronomeBpm.isFinite() || !originalBpm.isFinite() || originalBpm <= 0f) return 1f
        val raw = metronomeBpm / originalBpm
        if (!raw.isFinite()) return 1f
        return raw.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    fun minMetronomeBpm(originalBpm: Float): Float =
        (committedOriginalBpm(originalBpm) * 0.5f).coerceAtLeast(MetronomeClickEngine.MIN_BPM)

    fun maxMetronomeBpm(originalBpm: Float): Float =
        (committedOriginalBpm(originalBpm) * 2.0f).coerceAtMost(MetronomeClickEngine.MAX_BPM)

    /**
     * Диапазон слайдера метронома. Всегда start ≤ end.
     * Не строить из сырого текста original BPM: «1»/«12» дают min 30 и max 2/24 →
     * [Float.coerceIn] бросает IllegalArgumentException (force-close).
     */
    fun metronomeSliderRange(committedOriginalBpm: Float): ClosedFloatingPointRange<Float> {
        val min = minMetronomeBpm(committedOriginalBpm)
        val max = maxMetronomeBpm(committedOriginalBpm)
        return if (min <= max) min..max else MetronomeClickEngine.DEFAULT_BPM..MetronomeClickEngine.DEFAULT_BPM
    }

    fun clampMetronomeBpm(metronomeBpm: Float, committedOriginalBpm: Float): Float {
        val range = metronomeSliderRange(committedOriginalBpm)
        val value = if (metronomeBpm.isFinite()) metronomeBpm else range.start
        val clamped = value.coerceIn(range.start, range.endInclusive)
        return displayedBpm(clamped).toFloat().coerceIn(range.start, range.endInclusive)
    }

    /**
     * Принимает только полный BPM в [MIN_BPM, MAX_BPM]. Пусто, «1», «10», «12», «.» — null,
     * плеер и слайдер не трогаем. Первый символ в поле темпа не должен ничего применять.
     */
    fun parseOriginalBpm(text: String): Float? {
        val filtered = sanitizeBpmDraft(text)
        if (filtered.isEmpty() || filtered == ".") return null
        val value = filtered.toFloatOrNull() ?: return null
        if (!value.isFinite()) return null
        if (value < MetronomeClickEngine.MIN_BPM || value > MetronomeClickEngine.MAX_BPM) return null
        return value
    }

    fun sanitizeBpmDraft(raw: String): String =
        raw.filter { it.isDigit() || it == '.' }.take(5)

    fun commitOriginalBpm(draft: String, committedBpm: Float): OriginalBpmCommit {
        val parsed = parseOriginalBpm(draft)?.let { committedOriginalBpm(it) }
        val display = (parsed ?: committedOriginalBpm(committedBpm)).toInt().toString()
        return OriginalBpmCommit(applyBpm = parsed, displayText = display)
    }

    /**
     * UI shows [displayedBpm] (`toInt`). Grid and speed must use that same integer:
     * OLS 102.8 with a «102» label was a 0.8 BPM rush.
     */
    fun displayedBpm(bpm: Float): Int {
        if (!bpm.isFinite()) return MetronomeClickEngine.DEFAULT_BPM.toInt()
        return bpm.toInt().coerceIn(
            MetronomeClickEngine.MIN_BPM.toInt(),
            MetronomeClickEngine.MAX_BPM.toInt(),
        )
    }

    fun committedOriginalBpm(originalBpm: Float): Float {
        if (!originalBpm.isFinite()) return MetronomeClickEngine.DEFAULT_BPM
        val clamped = originalBpm.coerceIn(MetronomeClickEngine.MIN_BPM, MetronomeClickEngine.MAX_BPM)
        return displayedBpm(clamped).toFloat()
    }
}

/** Результат IME Done / unfocus поля темпа. applyBpm == null — черновик отбросить. */
data class OriginalBpmCommit(
    val applyBpm: Float?,
    val displayText: String,
)
