package com.rehearsal.app.audio

import com.rehearsal.app.data.SplitKind
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Контракт веб-пайплайна: `tracks/{id}/analysis.json`.
 * `beats[]` — абсолютные секунды от t=0 декодированного 44.1 stereo PCM.
 */
data class TrackAnalysis(
    val bpm: Float,
    val beats: DoubleArray,
    val downbeatOffset: Double,
    val timeSignature: IntArray = intArrayOf(4, 4),
    val duration: Double,
    val sampleRate: Int = TempoDetector.SAMPLE_RATE,
    val separator: String = SplitKind.HTDEMUCS_4STEM,
    val stems: List<String> = DEFAULT_STEMS,
    val kickPhaseSec: Double = 0.0,
    val kickGridVersion: Int = 0,
    /** Точный период tempoOfTrack. Шаг клика, не `60 / bpm` после округления до 0.1. */
    val periodSec: Double = 0.0,
    /** Фаза микса tempoOfTrack. Начало сетки клика, не KickGrid по drums. */
    val phaseSec: Double = kickPhaseSec,
) {
    fun hasClickPeriod(): Boolean = periodSec.isFinite() && periodSec > 1e-9

    fun clickPeriodSec(): Double {
        if (hasClickPeriod()) return periodSec
        val b = bpm.toDouble()
        if (b.isFinite() && b > 0.0) return 60.0 / b
        return 60.0 / MetronomeClickEngine.DEFAULT_BPM
    }

    fun clickPhaseSec(): Double = if (phaseSec.isFinite()) phaseSec else kickPhaseSec

    fun beatTimesUs(): LongArray {
        val out = LongArray(beats.size) { i ->
            TempoMath.secondsToMediaUs(beats[i])
        }
        out.sort()
        return out
    }

    fun toJson(): String {
        val beatsBody = if (beats.isEmpty()) {
            ""
        } else {
            beats.joinToString(",\n    ") { formatNum(it) }
        }
        val stemsBody = stems.joinToString(", ") { "\"$it\"" }
        val num = timeSignature.getOrElse(0) { 4 }
        val den = timeSignature.getOrElse(1) { 4 }
        return buildString {
            appendLine("{")
            appendLine("  \"bpm\": ${formatNum(bpm.toDouble())},")
            appendLine("  \"periodSec\": ${formatNum(periodSec, 12)},")
            appendLine("  \"phaseSec\": ${formatNum(clickPhaseSec(), 12)},")
            appendLine("  \"kickPhaseSec\": ${formatNum(clickPhaseSec(), 12)},")
            appendLine("  \"kickGridVersion\": $kickGridVersion,")
            append("  \"beats\": [")
            if (beatsBody.isNotEmpty()) {
                appendLine()
                append("    ")
                appendLine(beatsBody)
                append("  ")
            }
            appendLine("],")
            appendLine("  \"downbeatOffset\": ${formatNum(downbeatOffset)},")
            appendLine("  \"timeSignature\": [$num, $den],")
            appendLine("  \"duration\": ${formatNum(duration)},")
            appendLine("  \"sampleRate\": $sampleRate,")
            appendLine("  \"separator\": \"$separator\",")
            appendLine("  \"stems\": [$stemsBody]")
            append("}")
        }
    }

    companion object {
        const val FILE_NAME = "analysis.json"
        val DEFAULT_STEMS = listOf("vocals", "drums", "bass", "other")

        fun fallbackGrid(samples: Int, sampleRate: Int, bpm: Float = MetronomeClickEngine.DEFAULT_BPM): TrackAnalysis {
            val sr = sampleRate.coerceAtLeast(1)
            val duration = samples.toDouble() / sr
            val period = 60.0 / bpm.toDouble().coerceAtLeast(1.0)
            val beats = ArrayList<Double>()
            var t = 0.0
            while (t < duration - 1e-9) {
                beats.add(t)
                t += period
            }
            return TrackAnalysis(
                bpm = bpm,
                beats = beats.toDoubleArray(),
                downbeatOffset = beats.firstOrNull() ?: 0.0,
                duration = duration,
                sampleRate = sr,
                kickPhaseSec = 0.0,
                periodSec = period,
                phaseSec = 0.0,
            )
        }

        fun fromFile(file: File): TrackAnalysis? {
            return try {
                if (!file.isFile) null else fromJson(file.readText())
            } catch (_: Throwable) {
                null
            }
        }

        fun fromJson(text: String): TrackAnalysis? {
            if (text.isBlank()) return null
            return try {
                val bpm = extractNumber(text, "bpm") ?: return null
                val duration = extractNumber(text, "duration") ?: return null
                val sampleRate = extractNumber(text, "sampleRate")?.roundToLong()?.toInt()
                    ?: TempoDetector.SAMPLE_RATE
                val downbeat = extractNumber(text, "downbeatOffset") ?: 0.0
                val beats = extractNumberArray(text, "beats") ?: DoubleArray(0)
                val signature = extractNumberArray(text, "timeSignature")
                val timeSignature = if (signature != null && signature.size >= 2) {
                    intArrayOf(signature[0].roundToLong().toInt(), signature[1].roundToLong().toInt())
                } else {
                    intArrayOf(4, 4)
                }
                val separator = extractString(text, "separator") ?: SplitKind.HTDEMUCS_4STEM
                val stems = extractStringArray(text, "stems").ifEmpty { DEFAULT_STEMS }
                val kickPhase = extractNumber(text, "kickPhaseSec") ?: 0.0
                val phase = extractNumber(text, "phaseSec") ?: kickPhase
                val period = extractNumber(text, "periodSec") ?: 0.0
                val kickGridVersion = extractNumber(text, "kickGridVersion")
                    ?.roundToLong()
                    ?.toInt()
                    ?: 0
                TrackAnalysis(
                    bpm = bpm.toFloat(),
                    beats = beats,
                    downbeatOffset = downbeat,
                    timeSignature = timeSignature,
                    duration = duration,
                    sampleRate = sampleRate,
                    separator = separator,
                    stems = stems,
                    kickPhaseSec = phase,
                    kickGridVersion = kickGridVersion,
                    periodSec = period,
                    phaseSec = phase,
                )
            } catch (_: Throwable) {
                null
            }
        }

        private fun formatNum(value: Double, decimals: Int = 6): String {
            if (!value.isFinite()) return "0"
            val formatted = String.format(Locale.US, "%.${decimals}f", value)
            return formatted.trimEnd('0').trimEnd('.')
        }

        private fun extractNumber(text: String, key: String): Double? {
            val regex = "\"$key\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)".toRegex()
            return regex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        private fun extractString(text: String, key: String): String? {
            val regex = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
            return regex.find(text)?.groupValues?.get(1)
        }

        private fun extractArrayBody(text: String, key: String): String? {
            val keyPat = "\"$key\""
            val keyAt = text.indexOf(keyPat)
            if (keyAt < 0) return null
            val bracket = text.indexOf('[', keyAt)
            if (bracket < 0) return null
            var depth = 0
            for (i in bracket until text.length) {
                when (text[i]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return text.substring(bracket + 1, i)
                    }
                }
            }
            return null
        }

        private fun extractNumberArray(text: String, key: String): DoubleArray? {
            val body = extractArrayBody(text, key) ?: return null
            if (body.isBlank()) return DoubleArray(0)
            return body.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toDouble() }
                .toDoubleArray()
        }

        private fun extractStringArray(text: String, key: String): List<String> {
            val body = extractArrayBody(text, key) ?: return emptyList()
            if (body.isBlank()) return emptyList()
            return "\"([^\"]*)\"".toRegex()
                .findAll(body)
                .map { it.groupValues[1] }
                .toList()
        }
    }
}
