package com.rehearsal.app.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.rehearsal.app.RehearsalApplication
import com.rehearsal.app.audio.MetronomeClickEngine
import com.rehearsal.app.data.StemId
import com.rehearsal.app.data.TrackEntity
import com.rehearsal.app.player.MixerMath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StemFader(
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val lastUnmuted: Float = 1f,
)

data class PlayerUi(
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val originalBpmText: String = "120",
    val metronomeBpm: Float = 120f,
    val metronomeOn: Boolean = false,
    val speed: Float = 1f,
    val useStems: Boolean = false,
    val faders: Map<StemId, StemFader> = StemId.entries.associateWith { StemFader() },
)

class PlayerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application as RehearsalApplication
    private val playback = app.playback
    val trackId: String = checkNotNull(savedStateHandle["trackId"])

    val track: StateFlow<TrackEntity?> = app.repository.observe(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private var loadKey: String? = null
    private var bpmInitialized = false
    private var userPickedSource = false

    init {
        viewModelScope.launch {
            track.collect { item ->
                if (item == null) return@collect
                if (!bpmInitialized) {
                    val bpm = item.originalBpm ?: MetronomeClickEngine.DEFAULT_BPM
                    playback.setOriginalBpm(bpm)
                    playback.setMetronomeBpm(bpm)
                    playback.setMetronomeEnabled(false)
                    _ui.update {
                        it.copy(
                            originalBpmText = bpm.toInt().toString(),
                            metronomeBpm = bpm,
                            metronomeOn = false,
                            speed = playback.playbackSpeed(),
                        )
                    }
                    bpmInitialized = true
                }
                if (!userPickedSource && item.hasFourStems()) {
                    _ui.update { it.copy(useStems = true) }
                }
                maybeLoad(item)
            }
        }
        viewModelScope.launch {
            while (true) {
                _ui.update {
                    it.copy(
                        playing = playback.isPlaying(),
                        positionMs = playback.positionMs(),
                        durationMs = playback.durationMs(),
                        speed = playback.playbackSpeed(),
                        metronomeBpm = playback.metronomeBpm,
                    )
                }
                delay(100)
            }
        }
    }

    fun togglePlay() = playback.togglePlay()

    fun seek(positionMs: Long) = playback.seekTo(positionMs)

    fun setOriginalBpmText(text: String) {
        val filtered = text.filter { it.isDigit() || it == '.' }.take(5)
        _ui.update { it.copy(originalBpmText = filtered) }
        val value = filtered.toFloatOrNull() ?: return
        if (value < MetronomeClickEngine.MIN_BPM || value > MetronomeClickEngine.MAX_BPM) return
        playback.setOriginalBpm(value)
        _ui.update {
            it.copy(
                metronomeBpm = playback.metronomeBpm,
                speed = playback.playbackSpeed(),
            )
        }
        viewModelScope.launch { app.repository.saveOriginalBpm(trackId, value) }
    }

    fun setMetronomeBpm(bpm: Float) {
        playback.setMetronomeBpm(bpm)
        _ui.update {
            it.copy(
                metronomeBpm = playback.metronomeBpm,
                speed = playback.playbackSpeed(),
            )
        }
    }

    fun setMetronomeOn(on: Boolean) {
        playback.setMetronomeEnabled(on)
        _ui.update { it.copy(metronomeOn = on) }
    }

    fun setUseStems(use: Boolean) {
        userPickedSource = true
        _ui.update { it.copy(useStems = use) }
        loadKey = null
        track.value?.let(::maybeLoad)
        applyMixer()
    }

    fun setVolume(id: StemId, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        _ui.update { ui ->
            val current = ui.faders.getValue(id)
            ui.copy(
                faders = ui.faders + (id to current.copy(
                    volume = v,
                    muted = v <= 0.001f,
                    lastUnmuted = if (v > 0.001f) v else current.lastUnmuted,
                )),
            )
        }
        applyMixer()
    }

    fun toggleMute(id: StemId) {
        _ui.update { ui ->
            val current = ui.faders.getValue(id)
            val muted = !current.muted
            val volume = if (muted) 0f else current.lastUnmuted.coerceAtLeast(0.01f)
            ui.copy(
                faders = ui.faders + (id to current.copy(
                    muted = muted,
                    volume = volume,
                    solo = if (muted) false else current.solo,
                )),
            )
        }
        applyMixer()
    }

    fun toggleSolo(id: StemId) {
        _ui.update { ui ->
            val current = ui.faders.getValue(id)
            ui.copy(
                faders = ui.faders + (id to current.copy(
                    solo = !current.solo,
                    muted = if (!current.solo) false else current.muted,
                )),
            )
        }
        applyMixer()
    }

    fun retrySplit() {
        viewModelScope.launch { app.repository.retrySplit(trackId) }
    }

    override fun onCleared() {
        playback.pause()
        super.onCleared()
    }

    private fun maybeLoad(item: TrackEntity) {
        val preferStems = _ui.value.useStems
        val key = listOf(
            item.id,
            item.originalPath,
            item.vocalsPath.orEmpty(),
            item.drumsPath.orEmpty(),
            item.bassPath.orEmpty(),
            item.otherPath.orEmpty(),
            preferStems.toString(),
        ).joinToString("|")
        if (key == loadKey) return
        loadKey = key
        playback.load(item, preferStems)
        applyMixer()
    }

    private fun applyMixer() {
        val mapped = _ui.value.faders.mapValues { (_, fader) ->
            MixerMath.Fader(volume = fader.volume, muted = fader.muted, solo = fader.solo)
        }
        val anySolo = MixerMath.anySolo(mapped.values)
        StemId.entries.forEach { id ->
            playback.setStemGain(id, MixerMath.gain(mapped.getValue(id), anySolo))
        }
    }
}
