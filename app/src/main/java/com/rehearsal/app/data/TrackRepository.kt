package com.rehearsal.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.rehearsal.app.audio.KickGrid
import com.rehearsal.app.audio.TrackAnalysis
import com.rehearsal.app.audio.WavReader
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
     * Пересчёт BPM/фазы по существующему drums WAV. Без Demucs.
     * [force] — кнопка «Пересчитать темп и фазу»; иначе только если `kickGridVersion` устарел.
     */
    suspend fun refreshKickGrid(id: String, force: Boolean = false): TrackAnalysis? {
        val mutex = synchronized(kickGridLocks) { kickGridLocks.getOrPut(id) { Mutex() } }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val track = dao.getById(id) ?: return@withContext null
                val drumsFile = drumsWav(track) ?: return@withContext null
                val existing = TrackAnalysis.fromFile(files.analysisJson(id))
                if (!force && !KickGrid.needsRefresh(existing)) return@withContext existing
                _kickGridBusy.update { it + id }
                try {
                    val drums = WavReader.readStereo(drumsFile)
                    val seed = existing ?: TrackAnalysis.fallbackGrid(drums.samples, drums.sampleRate)
                    val refined = KickGrid.refine(seed, drums)
                    files.writeAnalysis(id, refined)
                    dao.updateBpm(id, refined.bpm, System.currentTimeMillis())
                    dao.getById(id)?.let { files.writeMeta(it) }
                    refined
                } catch (_: Throwable) {
                    existing
                } finally {
                    _kickGridBusy.update { it - id }
                }
            }
        }
    }

    private fun drumsWav(track: TrackEntity): File? {
        val path = track.drumsPath ?: return null
        val file = File(path)
        return if (file.isFile) file else null
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
