package com.rehearsal.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.File

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val originalFileName: String,
    val originalPath: String,
    val originalBpm: Float?,
    val status: String,
    val errorMessage: String?,
    val vocalsPath: String?,
    val drumsPath: String?,
    val bassPath: String?,
    val otherPath: String?,
    val splitKind: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    val isReady: Boolean get() = status == TrackStatus.READY

    val isWorking: Boolean
        get() = status == TrackStatus.QUEUED || status == TrackStatus.PROCESSING

    fun hasFourStems(): Boolean {
        if (splitKind != SplitKind.HTDEMUCS_4STEM) return false
        return listOf(vocalsPath, drumsPath, bassPath, otherPath).all { path ->
            !path.isNullOrBlank() && File(path).exists()
        }
    }
}
