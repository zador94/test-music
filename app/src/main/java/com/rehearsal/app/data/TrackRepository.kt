package com.rehearsal.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.rehearsal.app.audio.ClickGrid
import com.rehearsal.app.audio.TrackAnalysis
import com.rehearsal.app.audio.WavReader
import kotlin.math.min
import com.rehearsal.app.work.StemSplitWorker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TrackRepository(
    private val context: Context,
    private val dao: TrackDao,
    private val files: TrackFileStore,
) {
    private val kickGridLocks = mutableMapOf<String, Mutex>()
    private val _kickGridBusy = MutableStateFlow<Set<String>>(emptySet())
    val kickGridBusy: StateFlow<Set<String>> = _kickGridBusy.asStateFlow()

    fun observeAll(): Flow<List<TrackEntity>> = dao.observeAll()

    fun observe(id: String): Flow<TrackEntity?> = dao.observeById(id)

    suspend fun get(id: String): TrackEntity? = dao.getById(id)

    /**
     * Одна сетка: [ClickGrid] по drums.wav, без копии всей песни в RAM.
     * Свежая версия при открытии списка не читает файл заново.
     * [force] — кнопка «Пересчитать».
     */
    suspend fun refreshKickGrid(id: String, force: Boolean = false): TrackAnalysis? {
        val mutex = synchronized(kickGridLocks) { kickGridLocks.getOrPut(id) { Mutex() } }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val track = dao.getById(id) ?: return@withContext null
                val existing = TrackAnalysis.fromFile(files.analysisJson(id))
                if (!force && ClickGrid.isCurrent(existing)) return@withContext existing
                val drumsPath = track.drumsPath
                val drumsFile = if (drumsPath.isNullOrBlank()) null else File(drumsPath)
                if (drumsFile == null || !drumsFile.isFile) return@withContext existing
                _kickGridBusy.update { it + id }
                try {
                    val mapped = WavReader.openMapped(drumsFile)
                    try {
                        val sr = mapped.info.sampleRate
                        val duration = mapped.info.frameCount.toDouble() / sr.coerceAtLeast(1)
                        val bands = ClickGrid.hopEnergies(mapped)
                        val lead = externalEnergy(track.bassPath, track.otherPath, bands.low.size)
                        val grid = ClickGrid.fromBands(
                            bands.low,
                            bands.mid,
                            bands.high,
                            sr,
                            duration,
                            hintBpm = existing?.bpm,
                            lead = lead,
                        )
                        files.writeAnalysis(id, grid)
                        dao.updateBpm(id, grid.bpm, System.currentTimeMillis())
                        dao.getById(id)?.let { files.writeMeta(it) }
                        grid
                    } finally {
                        mapped.close()
                    }
                } catch (_: Throwable) {
                    existing
                } finally {
                    _kickGridBusy.update { it - id }
                }
            }
        }
    }

    /** Бас и «музыка»: атака, которой нет в теле ударных. */
    private fun externalEnergy(bassPath: String?, otherPath: String?, hops: Int): FloatArray? {
        if (hops <= 0) return null
        var sum: FloatArray? = null
        for (path in listOf(bassPath, otherPath)) {
            val file = if (path.isNullOrBlank()) null else File(path)
            if (file == null || !file.isFile) continue
            val mapped = WavReader.openMapped(file)
            try {
                val energy = hopSum(ClickGrid.hopEnergies(mapped))
                val n = min(hops, energy.size)
                if (n <= 0) continue
                if (sum == null) sum = FloatArray(hops)
                val into = sum ?: continue
                for (i in 0 until n) into[i] += energy[i]
            } finally {
                mapped.close()
            }
        }
        return sum
    }

    private fun hopSum(bands: ClickGrid.HopEnergies): FloatArray {
        val n = minOf(bands.low.size, bands.mid.size, bands.high.size)
        return FloatArray(n) { i -> bands.low[i] + bands.mid[i] + bands.high[i] }
    }

    suspend fun importFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val displayName = queryDisplayName(uri)
        val extension = extensionOf(uri, displayName)
        val dest = files.originalFile(id, extension)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Не удалось открыть выбранный файл")

        val now = System.currentTimeMillis()
        val track = TrackEntity(
            id = id,
            title = displayName.substringBeforeLast('.').ifBlank { displayName },
            originalFileName = displayName,
            originalPath = dest.absolutePath,
            originalBpm = null,
            status = TrackStatus.QUEUED,
            errorMessage = null,
            vocalsPath = null,
            drumsPath = null,
            bassPath = null,
            otherPath = null,
            splitKind = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        dao.upsert(track)
        files.writeMeta(track)
        StemSplitWorker.enqueue(context, id, replace = false)
        id
    }

    suspend fun saveOriginalBpm(id: String, bpm: Float) {
        dao.updateBpm(id, bpm, System.currentTimeMillis())
        dao.getById(id)?.let { files.writeMeta(it) }
    }

    suspend fun retrySplit(id: String) {
        dao.updateSplit(
            id = id,
            status = TrackStatus.QUEUED,
            errorMessage = null,
            vocalsPath = null,
            drumsPath = null,
            bassPath = null,
            otherPath = null,
            splitKind = null,
            now = System.currentTimeMillis(),
        )
        StemSplitWorker.enqueue(context, id, replace = true)
    }

    suspend fun delete(id: String) {
        StemSplitWorker.cancel(context, id)
        dao.deleteById(id)
        withContext(Dispatchers.IO) { files.deleteTrack(id) }
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "track"
    }

    private fun extensionOf(uri: Uri, displayName: String): String {
        val fromName = displayName.substringAfterLast('.', missingDelimiterValue = "")
        if (fromName.length in 1..5) return fromName.lowercase()
        return when (context.contentResolver.getType(uri)) {
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/aac", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/flac" -> "flac"
            else -> "bin"
        }
    }
}
