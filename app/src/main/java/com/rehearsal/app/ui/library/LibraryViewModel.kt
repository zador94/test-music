package com.rehearsal.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rehearsal.app.RehearsalApplication
import com.rehearsal.app.data.TrackEntity
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
}
