package com.rehearsal.app.player

import com.rehearsal.app.data.TrackEntity

/**
 * Плеер только стемов. Импортированный mix не играет: ни как источник,
 * ни как fallback, если стемы не встали. Вкладки «Оригинал» нет.
 */
object PlaybackPolicy {
    fun canStartPlayback(track: TrackEntity): Boolean = track.hasFourStems()

    fun opensPlayer(track: TrackEntity): Boolean = canStartPlayback(track)

    fun fallsBackToOriginal(track: TrackEntity): Boolean {
        canStartPlayback(track)
        return false
    }
}
