package com.rehearsal.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.rehearsal.app.audio.AudioBuffers
import com.rehearsal.app.audio.DrumPhase
import com.rehearsal.app.audio.KickGrid
import com.rehearsal.app.audio.MappedWav
import com.rehearsal.app.audio.PulseTempo
import com.rehearsal.app.audio.StereoPcm
import com.rehearsal.app.audio.TempoDetector
import com.rehearsal.app.audio.TrackAnalysis
import com.rehearsal.app.audio.WavReader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt
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
     * Сетка клика: Stembeat `beats[]` по миксу, потом DrumPhase.align.
     * [force] — кнопка «Пересчитать». Само открытие песни файл заново не декодирует:
     * клик уже стучит ровно по сохранённому темпу и одной фазе.
     */
    suspend fun refreshKickGrid(id: String, force: Boolean = false): TrackAnalysis? {
        val mutex = synchronized(kickGridLocks) { kickGridLocks.getOrPut(id) { Mutex() } }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val track = dao.getById(id) ?: return@withContext null
                val existing = TrackAnalysis.fromFile(files.analysisJson(id))
                if (!force && existing != null && !KickGrid.needsBeatTrack(existing)) {
                    return@withContext existing
                }
                _kickGridBusy.update { it + id }
                val mixWav = files.mixWorkWav(id)
                try {
                    var analyzed = existing
                    val rerunTempo = force || KickGrid.needsBeatTrack(analyzed)
                    val original = File(track.originalPath)
                    var mixPcm: StereoPcm? = null
                    if (rerunTempo) {
                        if (!original.isFile) return@withContext existing
                        AudioBuffers.decodeToWav44100(original, mixWav)
                        mixPcm = WavReader.readStereo(mixWav)
                        analyzed = TempoDetector.analyze(mixPcm)
                    }
                    val current = analyzed ?: return@withContext existing
                    val drumsPath = track.drumsPath
                    val drumsFile = if (drumsPath.isNullOrBlank()) null else File(drumsPath)
                    val stamped = if (drumsFile != null && drumsFile.isFile) {
                        if (mixPcm == null && original.isFile) {
                            AudioBuffers.decodeToWav44100(original, mixWav)
                            mixPcm = WavReader.readStereo(mixWav)
                        }
                        DrumPhase.align(current, WavReader.readStereo(drumsFile), mixPcm)
                    } else {
                        current
                    }
                    files.writeAnalysis(id, stamped)
                    dao.updateBpm(id, stamped.bpm, System.currentTimeMillis())
                    dao.getById(id)?.let { files.writeMeta(it) }
                    stamped
                } catch (_: Throwable) {
                    existing
                } finally {
                    mixWav.delete()
                    _kickGridBusy.update { it - id }
                }
            }
        }
    }

    /**
     * Уточняет темп по уже готовому WAV ударных. Оригинал через MediaCodec не декодирует
     * и музыку не глушит. Список песен это не вызывает — только открытый плеер.
     */
    suspend fun refinePulseTempo(id: String): TrackAnalysis? {
        val mutex = synchronized(kickGridLocks) { kickGridLocks.getOrPut(id) { Mutex() } }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val track = dao.getById(id) ?: return@withContext null
                val existing = TrackAnalysis.fromFile(files.analysisJson(id)) ?: return@withContext null
                val drumsPath = track.drumsPath
                val drumsFile = if (drumsPath.isNullOrBlank()) null else File(drumsPath)
                if (drumsFile == null || !drumsFile.isFile) return@withContext existing
                try {
                    val drumsMapped = WavReader.openMapped(drumsFile)
                    val others = openMapped(listOf(track.vocalsPath, track.bassPath, track.otherPath))
                    try {
                        val hop = PulseTempo.HOP
                        val sr = drumsMapped.info.sampleRate
                        val drumsLow = monoEnergy(listOf(drumsMapped), hop, StemBand.LOW)
                        val heard = if (others.isEmpty()) {
                            drumsLow
                        } else {
                            monoEnergy(others, hop, StemBand.HIGH)
                        }
                        val pulse = DrumPhase.phaseOnHeardAttack(drumsLow, heard, sr, existing.bpm)
                            ?: return@withContext existing
                        val bpm = pulse.bpm
                        val period = 60.0 / bpm.toDouble().coerceAtLeast(1.0)
                        val phase = pulse.phaseSec
                        val oldPhase = existing.clickPhaseSec()
                        var phaseDelta = (phase - oldPhase) % period
                        if (phaseDelta > period / 2.0) phaseDelta -= period
                        if (phaseDelta < -period / 2.0) phaseDelta += period
                        if (abs(bpm - existing.bpm) < 0.005f && abs(phaseDelta) < 0.012) {
                            return@withContext existing
                        }
                        val stamped = existing.copy(
                            bpm = bpm,
                            periodSec = period,
                            phaseSec = phase,
                            kickPhaseSec = phase,
                            beats = TrackAnalysis.quarterBeats(period, existing.duration, phase),
                            kickGridVersion = KickGrid.VERSION,
                        )
                        files.writeAnalysis(id, stamped)
                        dao.updateBpm(id, stamped.bpm, System.currentTimeMillis())
                        dao.getById(id)?.let { files.writeMeta(it) }
                        stamped
                    } finally {
                        drumsMapped.close()
                        others.forEach { it.close() }
                    }
                } catch (_: Throwable) {
                    existing
                }
            }
        }
    }

    private enum class StemBand { LOW, HIGH }

    /** Энергия по хопам, без копии всех стемов в RAM. HIGH — щелчок выше 150 Гц, без баса. */
    private fun monoEnergy(wavs: List<MappedWav>, hop: Int, band: StemBand): FloatArray {
        if (wavs.isEmpty()) return FloatArray(0)
        val sr = wavs.first().info.sampleRate.coerceAtLeast(1)
        val n = wavs.minOf { it.info.frameCount }
        if (n <= 0 || hop <= 0) return FloatArray(0)
        val frames = (n + hop - 1) / hop
        val energy = FloatArray(frames)
        val alpha = (1.0 - exp(-2.0 * PI * KickGrid.LOWPASS_HZ / sr)).toFloat()
        var lp = 0f
        val chunk = 4096
        var pos = 0
        var frame = 0
        var acc = 0.0
        var count = 0
        while (pos < n && frame < frames) {
            val take = min(chunk, n - pos)
            val mono = FloatArray(take)
            for (wav in wavs) {
                val slice = wav.readRange(pos, take)
                val m = min(take, slice.samples)
                for (i in 0 until m) {
                    mono[i] += 0.5f * (slice.left[i] + slice.right[i])
                }
            }
            for (i in 0 until take) {
                val x = mono[i]
                lp += alpha * (x - lp)
                val v = if (band == StemBand.LOW) lp else x - lp
                acc += v * v
                count++
                if (count == hop) {
                    energy[frame] = sqrt(acc / count).toFloat()
                    frame++
                    acc = 0.0
                    count = 0
                    if (frame >= frames) break
                }
            }
            pos += take
        }
        if (count > 0 && frame < frames) {
            energy[frame] = sqrt(acc / count).toFloat()
        }
        return energy
    }

    private fun openMapped(paths: List<String?>): List<MappedWav> {
        val out = ArrayList<MappedWav>()
        for (path in paths) {
            if (path.isNullOrBlank()) continue
            val file = File(path)
            if (file.isFile) out.add(WavReader.openMapped(file))
        }
        return out
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
