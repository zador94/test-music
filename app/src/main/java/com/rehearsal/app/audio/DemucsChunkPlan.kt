package com.rehearsal.app.audio

/**
 * OOM и сообщение «короткий трек». Сам сплит — [HtDemucs.streamSeparate]:
 * окна модели 7.8 с, overlap-add как в 0.3.28, стемы сразу на диск.
 *
 * 0.3.29 резала mix на 30 с с линейным кроссфейдом 2 с поверх уже оконченного
 * HT-Demucs. Последнее внутреннее окно каждого 30-секундного куска паддилось
 * нулями → помехи вместо музыки. Этот слой больше не используется.
 */
object DemucsChunkPlan {
    const val SAMPLE_RATE = HtDemucs.SAMPLE_RATE
    const val MIN_CHUNK_SAMPLES = HtDemucs.SEGMENT_SAMPLES
    const val SHORT_TRACK_MESSAGE =
        "Не хватило RAM для HT-Demucs. Попробуйте более короткий трек."

    fun isMemoryFailure(error: Throwable): Boolean {
        var cur: Throwable? = error
        var hop = 0
        while (cur != null && hop < 8) {
            if (cur is OutOfMemoryError) return true
            val m = cur.message.orEmpty().lowercase()
            if (m.contains("out of memory") ||
                m.contains("cannot allocate") ||
                m.contains("failed to allocate") ||
                m.contains("oom")
            ) {
                return true
            }
            cur = cur.cause
            hop++
        }
        return false
    }
}

class MinChunkOomException : Exception(DemucsChunkPlan.SHORT_TRACK_MESSAGE)
