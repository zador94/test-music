package com.rehearsal.app.audio

import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Ручной сдвиг клика поверх фазы из данных. На устройство, не на трек.
 * Положительный — позже, отрицательный — раньше. 0 — как в анализе.
 *
 * 0.3.21: ключ `click_shift_ms_v21`, дефолт 0 (старый −150 не переносили).
 * 0.3.28: ключ `click_shift_ms_v28`, дефолт −180 — калибровка BlueStacks/HAL,
 * не сетка KickGrid. Старый 0 или −170 с v21 не читаем.
 */
object ClickCalibration {
    const val PREFS_NAME = "click_prefs"
    const val KEY_SHIFT_MS = "click_shift_ms_v28"
    const val LEGACY_KEY_SHIFT_MS = "click_shift_ms"
    const val LEGACY_KEY_SHIFT_MS_V21 = "click_shift_ms_v21"
    const val MIN_MS = -500
    const val MAX_MS = 500
    const val STEP_MS = 5
    const val DEFAULT_MS = -180

    fun clampMs(ms: Int): Int {
        val clamped = ms.coerceIn(MIN_MS, MAX_MS)
        val step = STEP_MS.coerceAtLeast(1)
        return ((clamped.toFloat() / step).roundToInt() * step).coerceIn(MIN_MS, MAX_MS)
    }

    fun toUs(ms: Int): Long = clampMs(ms) * 1_000L

    fun sliderSteps(): Int =
        ((MAX_MS - MIN_MS) / STEP_MS.coerceAtLeast(1) - 1).coerceAtLeast(0)

    /** Нет текущего ключа → дефолт. Старый ключ не подставляем. */
    fun resolveStoredMs(hasCurrentKey: Boolean, currentValue: Int): Int {
        if (!hasCurrentKey) return DEFAULT_MS
        return clampMs(currentValue)
    }

    fun load(prefs: SharedPreferences): Int =
        resolveStoredMs(prefs.contains(KEY_SHIFT_MS), prefs.getInt(KEY_SHIFT_MS, DEFAULT_MS))

    fun save(prefs: SharedPreferences, ms: Int) {
        prefs.edit()
            .putInt(KEY_SHIFT_MS, clampMs(ms))
            .remove(LEGACY_KEY_SHIFT_MS)
            .remove(LEGACY_KEY_SHIFT_MS_V21)
            .apply()
    }
}
