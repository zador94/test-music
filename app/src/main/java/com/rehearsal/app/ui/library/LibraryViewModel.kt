package com.rehearsal.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rehearsal.app.RehearsalApplication
import com.rehearsal.app.data.TrackEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RehearsalApplication

    val tracks: StateFlow<List<TrackEntity>> = app.repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val kickGridBusy: StateFlow<Set<String>> = app.repository.kickGridBusy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    private val _openProcessing = MutableSharedFlow<String>()
    val openProcessing = _openProcessing.asSharedFlow()

    private val _tempoRecalc = MutableStateFlow<TempoRecalcProgress?>(null)
    val tempoRecalc: StateFlow<TempoRecalcProgress?> = _tempoRecalc.asStateFlow()

    private var tempoRecalcJob: Job? = null

    init {
        viewModelScope.launch {
            tracks.collect { list ->
                list.forEach { track ->
                    if (track.drumsPath.isNullOrBlank()) return@forEach
                    launch { app.repository.refreshKickGrid(track.id, force = false) }
                }
            }
        }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            try {
                val id = app.repository.importFromUri(uri)
                _openProcessing.emit(id)
            } catch (t: Throwable) {
                _messages.emit(t.message ?: "Не удалось скопировать файл")
            } finally {
                _importing.value = false
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            app.playback.pause()
            app.repository.delete(id)
        }
    }

    /** Та же сила, что кнопка в плеере, но по очереди для каждой песни с ударными. */
    fun recalculateAllTempos() {
        if (tempoRecalcJob?.isActive == true) return
        tempoRecalcJob = viewModelScope.launch {
            val ids = TempoRecalc.ids(tracks.value)
            if (ids.isEmpty()) {
                _messages.emit("Нет песен с готовыми ударными")
                return@launch
            }
            _tempoRecalc.value = TempoRecalcProgress(0, ids.size)
            try {
                ids.forEachIndexed { index, id ->
                    app.repository.refreshKickGrid(id, force = true)
                    _tempoRecalc.value = TempoRecalcProgress(index + 1, ids.size)
                }
            } finally {
                _tempoRecalc.value = null
            }
        }
    }
}

/** Сколько песен уже пройдено общей кнопкой. `null` — кнопка свободна. */
data class TempoRecalcProgress(val done: Int, val total: Int)

internal object TempoRecalc {
    /** Ударные уже лежат на диске. Без них темп заново не из чего считать, HT-Demucs не трогаем. */
    fun ids(tracks: List<TrackEntity>): List<String> =
        tracks.filter { !it.drumsPath.isNullOrBlank() }.map { it.id }
}
