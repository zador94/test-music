package com.rehearsal.app.player

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.ExoPlayer
import com.rehearsal.app.audio.MetronomeClickEngine
import com.rehearsal.app.audio.RehearsalRenderersFactory
import com.rehearsal.app.audio.StemMixBank
import com.rehearsal.app.audio.StemMixMediaSource
import com.rehearsal.app.data.StemId
import com.rehearsal.app.data.TrackEntity
import java.io.File

/**
 * Один ExoPlayer. Оригинал — файл. Стемы — [StemMixMediaSource] (PCM 16-bit из bank).
 * Клики метронома в sink поверх любого таба. speed = metronomeBpm / originalBpm.
 */
class PlaybackEngine(context: Context) {
    val clicks = MetronomeClickEngine()
    private val mixBank = StemMixBank()

    private val appContext = context.applicationContext
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(RehearsalRenderersFactory(appContext, clicks))
        .build()
        .apply {
            setAudioAttributes(audioAttributes, true)
            skipSilenceEnabled = false
            disableOffload()
        }

    private var stemsMode: Boolean = false
    private var loadedTrackId: String? = null
    private var appliedSpeed: Float = 1f

    var originalBpm: Float = MetronomeClickEngine.DEFAULT_BPM
        private set
    var metronomeBpm: Float = MetronomeClickEngine.DEFAULT_BPM
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.pause()
                    player.seekTo(0)
                }
            }
        })
    }

    fun load(track: TrackEntity, preferStems: Boolean) {
        val canStems = preferStems && track.hasFourStems()
        val sameTrack = loadedTrackId == track.id
        val resumeMs = if (sameTrack) player.currentPosition else 0L
        val keepPlaying = player.playWhenReady
        loadedTrackId = track.id
        stemsMode = canStems

        player.stop()
        player.clearMediaItems()
        appliedSpeed = Float.NaN
        clicks.onFlush()

        if (canStems) {
            val files = mapOf(
                StemId.VOCALS to File(track.vocalsPath!!),
                StemId.DRUMS to File(track.drumsPath!!),
                StemId.BASS to File(track.bassPath!!),
                StemId.OTHER to File(track.otherPath!!),
            )
            try {
                mixBank.load(files)
                player.setMediaSource(StemMixMediaSource(mixBank))
            } catch (_: Throwable) {
                mixBank.clear()
                stemsMode = false
                setMedia(File(track.originalPath).toUri())
            }
        } else {
            mixBank.clear()
            setMedia(File(track.originalPath).toUri())
        }
        player.prepare()
        player.seekTo(resumeMs)
        applySpeed(force = true)
        player.playWhenReady = keepPlaying
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun togglePlay() {
        if (player.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun setOriginalBpm(bpm: Float) {
        originalBpm = bpm.coerceIn(MetronomeClickEngine.MIN_BPM, MetronomeClickEngine.MAX_BPM)
        clicks.originalBpm = originalBpm
        metronomeBpm = metronomeBpm.coerceIn(minMetronomeBpm(), maxMetronomeBpm())
        applySpeed()
    }

    fun setMetronomeBpm(bpm: Float) {
        metronomeBpm = bpm.coerceIn(minMetronomeBpm(), maxMetronomeBpm())
        applySpeed()
    }

    fun setMetronomeEnabled(enabled: Boolean) {
        // Только флаг кликов. Не stop/flush/prepare — иначе mid-play ON глушит ExoPlayer.
        clicks.enabled = enabled
    }

    fun setStemGain(id: StemId, gain: Float) {
        mixBank.setGain(id, gain.coerceIn(0f, 1f))
    }

    fun minMetronomeBpm(): Float = PlaybackMath.minMetronomeBpm(originalBpm)

    fun maxMetronomeBpm(): Float = PlaybackMath.maxMetronomeBpm(originalBpm)

    fun playbackSpeed(): Float = PlaybackMath.playbackSpeed(metronomeBpm, originalBpm)

    fun positionMs(): Long = player.currentPosition

    fun durationMs(): Long {
        val d = player.duration
        return if (d == C.TIME_UNSET) 0L else d
    }

    fun isPlaying(): Boolean = player.isPlaying

    fun usingStems(): Boolean = stemsMode

    fun release() {
        mixBank.clear()
        player.release()
    }

    private fun applySpeed(force: Boolean = false) {
        val speed = playbackSpeed()
        if (!force && kotlin.math.abs(speed - appliedSpeed) < 0.0008f) return
        appliedSpeed = speed
        player.setPlaybackSpeed(speed)
    }

    private fun setMedia(uri: Uri) {
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
    }

    private fun ExoPlayer.disableOffload() {
        trackSelectionParameters = trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build(),
            )
            .build()
    }
}
