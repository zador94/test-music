package com.rehearsal.app.ui.processing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rehearsal.app.RehearsalApplication
import com.rehearsal.app.data.TrackEntity
import com.rehearsal.app.work.StemSplitWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProcessingViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application as RehearsalApplication
    val trackId: String = checkNotNull(savedStateHandle["trackId"])

    val track: StateFlow<TrackEntity?> = app.repository.observe(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val progress: StateFlow<Int> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(StemSplitWorker.uniqueName(trackId))
        .map { infos ->
            infos.firstOrNull()?.progress?.getInt(StemSplitWorker.KEY_PROGRESS, 0) ?: 0
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val message: StateFlow<String> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(StemSplitWorker.uniqueName(trackId))
        .map { infos ->
            infos.firstOrNull()?.progress?.getString(StemSplitWorker.KEY_MESSAGE).orEmpty()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val workState: StateFlow<WorkInfo.State?> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(StemSplitWorker.uniqueName(trackId))
        .map { it.firstOrNull()?.state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun retry() {
        viewModelScope.launch { app.repository.retrySplit(trackId) }
    }
}
