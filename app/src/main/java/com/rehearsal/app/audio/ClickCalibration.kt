package com.rehearsal.app.audio

import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Ручной сдвиг клика: запас на задержку аппарата, не способ попасть в долю.
 * Положительный — позже, отрицательный — раньше. 0 — как `phaseSec` в анализе.
 *
 * 0.3.21: `click_shift_ms_v21`, дефолт 0.
 * 0.3.28: `click_shift_ms_v28`, дефолт −180 (HAL). Не читаем.
 * 0.3.37: `click_shift_ms_v37`, дефолт 0. Старый −180 не подхватываем.
 * Дальше фаза — атака в остальных дорожках, если тело ударных стабильно позже.
 * v42 сел на вершину атаки, и понадобился ещё +20.
 * v43 брал центр только пока спад громче половины вершины: у короткого
 * щелчка это снова вершина, и +20 остался. v44 включает хвост до 20% вершины,
 * и осталось ещё +5. v45 берёт тот же хвост до 15%.
 * v46 не считает предыдущий низкий удар атакой текущей доли.
 * v47 не сажает клик на хет ровно на 16-ю.
 * v48 не уводит клик на звук ближе шестнадцатой: это ещё та же доля.
 * v49 не сажает клик на хет чуть мимо 16-й и не даёт редкому дальнему
 * щелчку перетянуть фазу, когда на большинстве ударов это всё ещё та же доля.
 * v50 не берёт дальний край окна поиска: там предыдущая нота, и клик
 * вставал примерно на шестнадцатую раньше удара.
 * v51 вообще не сдвигает клик на звук раньше бочки: этот звук и был +140.
 * v52 берёт фазу по громкому месту всего микса, а не по раннему всплеску
 * одних ударных. На «Фонарях» это громкое место низа оказалось басом
 * на шестнадцатую раньше щелчка доли.
 * v53, если щелчок сверху ровно на следующей шестнадцатой, сажает клик
 * на него. На «Фонарях» верх сидел там же, где низ: оба раньше доли.
 * v54 берёт короткий удар на шестнадцатую позже громкого горба, если он
 * заметно острее. На «Фонарях» позже горба острого пика в том же низу не было.
 * v55: бас даёт ранний горб, бочка — короткий остаток ударных.
 * Свободный остаток на «Фонарях» уехал примерно на −235, не на шестнадцатую.
 * v56 переезжает на остаток только если он на следующей шестнадцатой
 * после громкого места. На «Фонарях» короткий удар был ближе, осталось +85.
 * v57 берёт остаток уже от половины шестнадцатой, не только от 0.75.
 * На «Фонарях» остаток сидел там же, где горб, снова +85.
 * v58: правый край того же горба и атака сверху в том же окне доли.
 * На «31-й весне» доля — размазанный хет почти на восьмую позже низа, стало +140.
 * v59 сажает клик на этот громкий верх. На «Титанике» верх чуть больше
 * шестнадцатой позже низа, стало −180. v60 берёт хет только почти на
 * восьмую. На стемах «Титаника» громкость верха всё равно в том окне.
 * v61 берёт только атаку верха почти на восьмую, не среднюю громкость.
 * На стемах «Титаника» атака верха всего микса всё равно в том окне
 * (тарелки other). v62 берёт почти восьмую только у верха ударных.
 * v63 снова не берёт верх дальше шестнадцатой: так «Титаник» сидел в 0.3.67.
 * v64: клик на атаке той же бочки, если тело низа позже щелчка (~180 мс).
 * v65: атака — поток ударных, не верх микса. Сетка пишется при каждом открытии.
 * v66: слышимая атака баса и «музыки», если она дальше шестнадцатой до тела бочки.
 * v67: окно 150–220 мс до тела и горб низа на 50–110 мс позже атаки.
 * v68: ранний удар только если его нет в ударных; горб не дальше 80 мс.
 * Старый сдвиг не подхватываем.
 */
object ClickCalibration {
    const val PREFS_NAME = "click_prefs"
    const val KEY_SHIFT_MS = "click_shift_ms_v68"
    const val LEGACY_KEY_SHIFT_MS = "click_shift_ms"
    const val LEGACY_KEY_SHIFT_MS_V21 = "click_shift_ms_v21"
    const val LEGACY_KEY_SHIFT_MS_V28 = "click_shift_ms_v28"
    const val LEGACY_KEY_SHIFT_MS_V37 = "click_shift_ms_v37"
    const val LEGACY_KEY_SHIFT_MS_V38 = "click_shift_ms_v38"
    const val LEGACY_KEY_SHIFT_MS_V39 = "click_shift_ms_v39"
    const val LEGACY_KEY_SHIFT_MS_V40 = "click_shift_ms_v40"
    const val LEGACY_KEY_SHIFT_MS_V41 = "click_shift_ms_v41"
    const val LEGACY_KEY_SHIFT_MS_V42 = "click_shift_ms_v42"
    const val LEGACY_KEY_SHIFT_MS_V43 = "click_shift_ms_v43"
    const val LEGACY_KEY_SHIFT_MS_V44 = "click_shift_ms_v44"
    const val LEGACY_KEY_SHIFT_MS_V45 = "click_shift_ms_v45"
    const val LEGACY_KEY_SHIFT_MS_V46 = "click_shift_ms_v46"
    const val LEGACY_KEY_SHIFT_MS_V47 = "click_shift_ms_v47"
    const val LEGACY_KEY_SHIFT_MS_V48 = "click_shift_ms_v48"
    const val LEGACY_KEY_SHIFT_MS_V49 = "click_shift_ms_v49"
    const val LEGACY_KEY_SHIFT_MS_V50 = "click_shift_ms_v50"
    const val LEGACY_KEY_SHIFT_MS_V51 = "click_shift_ms_v51"
    const val LEGACY_KEY_SHIFT_MS_V52 = "click_shift_ms_v52"
    const val LEGACY_KEY_SHIFT_MS_V53 = "click_shift_ms_v53"
    const val LEGACY_KEY_SHIFT_MS_V54 = "click_shift_ms_v54"
    const val LEGACY_KEY_SHIFT_MS_V55 = "click_shift_ms_v55"
    const val LEGACY_KEY_SHIFT_MS_V56 = "click_shift_ms_v56"
    const val LEGACY_KEY_SHIFT_MS_V57 = "click_shift_ms_v57"
    const val LEGACY_KEY_SHIFT_MS_V58 = "click_shift_ms_v58"
    const val LEGACY_KEY_SHIFT_MS_V59 = "click_shift_ms_v59"
    const val LEGACY_KEY_SHIFT_MS_V60 = "click_shift_ms_v60"
    const val LEGACY_KEY_SHIFT_MS_V61 = "click_shift_ms_v61"
    const val LEGACY_KEY_SHIFT_MS_V62 = "click_shift_ms_v62"
    const val LEGACY_KEY_SHIFT_MS_V63 = "click_shift_ms_v63"
    const val LEGACY_KEY_SHIFT_MS_V64 = "click_shift_ms_v64"
    const val LEGACY_KEY_SHIFT_MS_V65 = "click_shift_ms_v65"
    const val LEGACY_KEY_SHIFT_MS_V66 = "click_shift_ms_v66"
    const val LEGACY_KEY_SHIFT_MS_V67 = "click_shift_ms_v67"
    const val MIN_MS = -500
    const val MAX_MS = 500
    const val STEP_MS = 5
    const val DEFAULT_MS = 0

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
            .remove(LEGACY_KEY_SHIFT_MS_V28)
            .remove(LEGACY_KEY_SHIFT_MS_V37)
            .remove(LEGACY_KEY_SHIFT_MS_V38)
            .remove(LEGACY_KEY_SHIFT_MS_V39)
            .remove(LEGACY_KEY_SHIFT_MS_V40)
            .remove(LEGACY_KEY_SHIFT_MS_V41)
            .remove(LEGACY_KEY_SHIFT_MS_V42)
            .remove(LEGACY_KEY_SHIFT_MS_V43)
            .remove(LEGACY_KEY_SHIFT_MS_V44)
            .remove(LEGACY_KEY_SHIFT_MS_V45)
            .remove(LEGACY_KEY_SHIFT_MS_V46)
            .remove(LEGACY_KEY_SHIFT_MS_V47)
            .remove(LEGACY_KEY_SHIFT_MS_V48)
            .remove(LEGACY_KEY_SHIFT_MS_V49)
            .remove(LEGACY_KEY_SHIFT_MS_V50)
            .remove(LEGACY_KEY_SHIFT_MS_V51)
            .remove(LEGACY_KEY_SHIFT_MS_V52)
            .remove(LEGACY_KEY_SHIFT_MS_V53)
            .remove(LEGACY_KEY_SHIFT_MS_V54)
            .remove(LEGACY_KEY_SHIFT_MS_V55)
            .remove(LEGACY_KEY_SHIFT_MS_V56)
            .remove(LEGACY_KEY_SHIFT_MS_V57)
            .remove(LEGACY_KEY_SHIFT_MS_V58)
            .remove(LEGACY_KEY_SHIFT_MS_V59)
            .remove(LEGACY_KEY_SHIFT_MS_V60)
            .remove(LEGACY_KEY_SHIFT_MS_V61)
            .remove(LEGACY_KEY_SHIFT_MS_V62)
            .remove(LEGACY_KEY_SHIFT_MS_V63)
            .remove(LEGACY_KEY_SHIFT_MS_V64)
            .remove(LEGACY_KEY_SHIFT_MS_V65)
            .remove(LEGACY_KEY_SHIFT_MS_V66)
            .remove(LEGACY_KEY_SHIFT_MS_V67)
            .apply()
    }
}
