package com.rehearsal.app.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.ExoPlayer
import com.rehearsal.app.audio.ClickClock
import com.rehearsal.app.audio.ClickTrack
import com.rehearsal.app.audio.MetronomeClickEngine
import com.rehearsal.app.audio.RehearsalRenderersFactory
import com.rehearsal.app.audio.StemMixBank
import com.rehearsal.app.audio.StemMixMediaSource
import com.rehearsal.app.audio.TrackAnalysis
import com.rehearsal.app.data.StemId
import com.rehearsal.app.data.TrackEntity
import java.io.File

/**
 * Один ExoPlayer, только стемы: [StemMixMediaSource] (PCM 16-bit из bank).
 * Импортированный mix не ставится, даже если четырёх WAV нет.
 * Клики метронома — отдельный [ClickTrack], ровные четверти, один клик.
 * speed = metronomeBpm / originalBpm.
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
        .setRenderersFactory(RehearsalRenderersFactory(appContext, clicks, mixBank))
        .build()
        .apply {
            setAudioAttributes(audioAttributes, true)
            skipSilenceEnabled = false
            disableOffload()
        }

    private val clickTrack = ClickTrack(clicks, ::clickClock) { player.audioSessionId }
    @Volatile private var clickOrigin = ClickClock.Origin()

    private var stemsMode: Boolean = false
    private var loadedTrackId: String? = null
    private var appliedSpeed: Float = 1f
    private val speedGate = PlaybackSpeedGate()

    var originalBpm: Float = MetronomeClickEngine.DEFAULT_BPM
        private set
    var metronomeBpm: Float = MetronomeClickEngine.DEFAULT_BPM
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                captureClickClock()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.pause()
                    player.seekTo(0)
                    clickTrack.onSeek()
                    captureClickClock()
                }
            }
        })
    }

    fun load(track: TrackEntity) {
        val sameTrack = loadedTrackId == track.id
        val resumeMs = if (sameTrack) player.currentPosition else 0L
        val keepPlaying = player.playWhenReady
        loadedTrackId = track.id
        stemsMode = false

        player.stop()
        player.clearMediaItems()
        appliedSpeed = Float.NaN
        speedGate.reset()
        clicks.onFlush()
        clickTrack.onSeek()
        mixBank.clear()
        loadAnalysis(track)

        if (!PlaybackPolicy.canStartPlayback(track)) {
            captureClickClock()
            return
        }

        val files = mapOf(
            StemId.VOCALS to File(track.vocalsPath!!),
            StemId.DRUMS to File(track.drumsPath!!),
            StemId.BASS to File(track.bassPath!!),
            StemId.OTHER to File(track.otherPath!!),
        )
        try {
            mixBank.load(files)
            player.setMediaSource(StemMixMediaSource(mixBank))
            stemsMode = true
            player.prepare()
            player.seekTo(resumeMs)
            applySpeed(force = true)
            player.playWhenReady = keepPlaying
        } catch (_: Throwable) {
            mixBank.clear()
            stemsMode = false
            player.stop()
            player.clearMediaItems()
        }
        captureClickClock()
    }

    fun play() {
        player.play()
        captureClickClock()
        if (clicks.enabled) clickTrack.start()
    }

    fun pause() {
        player.pause()
        captureClickClock()
    }

    fun togglePlay() {
        if (player.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        clickTrack.onSeek()
        captureClickClock()
    }

    fun setOriginalBpm(bpm: Float) {
        originalBpm = PlaybackMath.committedOriginalBpm(bpm)
        clicks.originalBpm = originalBpm
        metronomeBpm = PlaybackMath.clampMetronomeBpm(metronomeBpm, originalBpm)
        applySpeed()
    }

    fun setMetronomeBpm(bpm: Float) {
        metronomeBpm = PlaybackMath.clampMetronomeBpm(bpm, originalBpm)
        applySpeed()
    }

    fun setMetronomeEnabled(enabled: Boolean) {
        // Флаг + отдельный ClickTrack. ExoPlayer не stop/prepare.
        clicks.enabled = enabled
        if (enabled) {
            captureClickClock()
            clickTrack.start()
        } else {
            clickTrack.stop()
        }
    }

    fun loadAnalysis(track: TrackEntity) {
        val dir = File(track.originalPath).parentFile
        val file = dir?.let { File(it, TrackAnalysis.FILE_NAME) }
        val analysis = if (file != null) TrackAnalysis.fromFile(file) else null
        clicks.setAnalysis(analysis)
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

    fun applyPendingSpeed() {
        val speed = speedGate.poll()
        if (speed != null) pushSpeed(speed)
        captureClickClock()
    }

    fun release() {
        clickTrack.release()
        mixBank.clear()
        player.release()
    }

    /**
     * Только volatile [clickOrigin] + nanoTime. Не трогать ExoPlayer:
     * этот лямбда зовётся с потока `metronome-click`.
     */
    private fun clickClock(): ClickTrack.Snapshot =
        ClickClock.snapshot(clickOrigin, System.nanoTime())

    /** Только с потока ExoPlayer (main). */
    private fun captureClickClock() {
        try {
            val playing = player.playWhenReady &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
            clickOrigin = ClickClock.Origin(
                mediaUs = player.currentPosition.coerceAtLeast(0L) * 1000L,
                wallNs = System.nanoTime(),
                speed = playbackSpeed(),
                playing = playing,
            )
        } catch (_: Throwable) {
            clickOrigin = clickOrigin.copy(playing = false, wallNs = System.nanoTime())
        }
    }

    private fun applySpeed(force: Boolean = false) {
        val speed = speedGate.request(playbackSpeed(), force) ?: return
        pushSpeed(speed)
    }

    private fun pushSpeed(speed: Float) {
        appliedSpeed = speed
        try {
            player.setPlaybackSpeed(speed)
        } catch (_: Throwable) {
            // Не stop/pause и не роняем Activity.
        }
        captureClickClock()
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
