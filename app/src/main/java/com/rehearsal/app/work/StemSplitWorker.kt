package com.rehearsal.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rehearsal.app.R
import com.rehearsal.app.RehearsalApplication
import com.rehearsal.app.audio.AudioBuffers
import com.rehearsal.app.audio.DemucsChunkPlan
import com.rehearsal.app.audio.HtDemucs
import com.rehearsal.app.audio.HtdemucsModelStore
import com.rehearsal.app.audio.MinChunkOomException
import com.rehearsal.app.audio.TempoDetector
import com.rehearsal.app.audio.TrackAnalysis
import com.rehearsal.app.audio.WavReader
import com.rehearsal.app.audio.WavWriter
import com.rehearsal.app.data.SplitKind
import com.rehearsal.app.data.StemId
import com.rehearsal.app.data.TrackDao
import com.rehearsal.app.data.TrackFileStore
import com.rehearsal.app.data.TrackStatus
import java.io.File
import java.util.Locale

/**
 * HT-Demucs ONNX (htdemucs, не FT-bag и не 6-stem) окнами модели 7.8 с на диск.
 * Overlap-add как in-memory 0.3.28; без внешнего слоя 30 с / линейного кроссфейда.
 * Пишет tracks/{id}/stems/{vocals,drums,bass,other}.wav
 * Unique work + KEEP: повторный вход не гоняет модель снова, пока нет «пересчитать».
 */
class StemSplitWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return foreground()
    }

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.failure()
        val app = applicationContext as RehearsalApplication
        val dao = app.database.trackDao()
        val files = app.files
        val track = dao.getById(trackId) ?: return Result.failure()

        if (track.hasFourStems()) {
            return Result.success()
        }

        ensureChannel()
        try {
            setForeground(foreground())
        } catch (_: Exception) {
        }

        markProcessing(dao, files, trackId)

        val original = File(track.originalPath)
        if (!original.exists()) {
            return fail(dao, files, trackId, "Копия исходника не найдена: ${original.path}")
        }

        val stemFiles = StemId.entries.associateWith { files.stemWav(trackId, it) }
        val mixWav = files.mixWorkWav(trackId)
        try {
            val models = HtdemucsModelStore(applicationContext, files)
            report(5, "Скачиваю HT-Demucs (~166 МБ), один раз")
            val model = models.ensureModel { downloaded, total ->
                val pct = if (total > 0) {
                    (5 + (downloaded * 15 / total)).toInt().coerceIn(5, 20)
                } else {
                    10
                }
                setProgressAsync(
                    workDataOf(
                        KEY_PROGRESS to pct,
                        KEY_MESSAGE to "Модель HT-Demucs: ${downloaded / 1_000_000} МБ",
                    ),
                )
            }

            report(22, "Декодирую исходник в 44.1 кГц на диск")
            AudioBuffers.decodeToWav44100(original, mixWav)
            report(25, "Считаю BPM и доли")
            persistAnalysisFromMixWav(dao, files, trackId, mixWav)
            System.gc()

            report(28, "HT-Demucs окна 7.8 с, overlap-add на диск")
            splitMixStreaming(mixWav, model, stemFiles)

            val note =
                "HT-Demucs (htdemucs) ONNX, 4 стемы, WAV IEEE float32. Окна модели 7.8 с, " +
                    "overlap-add как 0.3.28, стемы сразу на диск. Гитара в «Музыка» (other)."
            files.writeStemReadme(trackId, SplitKind.HTDEMUCS_4STEM, note)
            dao.updateSplit(
                id = trackId,
                status = TrackStatus.READY,
                errorMessage = null,
                vocalsPath = stemFiles.getValue(StemId.VOCALS).absolutePath,
                drumsPath = stemFiles.getValue(StemId.DRUMS).absolutePath,
                bassPath = stemFiles.getValue(StemId.BASS).absolutePath,
                otherPath = stemFiles.getValue(StemId.OTHER).absolutePath,
                splitKind = SplitKind.HTDEMUCS_4STEM,
                now = System.currentTimeMillis(),
            )
            dao.getById(trackId)?.let { files.writeMeta(it) }
            report(100, "Готово")
            return Result.success()
        } catch (_: MinChunkOomException) {
            stemFiles.values.forEach { it.delete() }
            return fail(dao, files, trackId, DemucsChunkPlan.SHORT_TRACK_MESSAGE)
        } catch (oom: OutOfMemoryError) {
            stemFiles.values.forEach { it.delete() }
            return fail(dao, files, trackId, oom.message ?: oom.javaClass.simpleName)
        } catch (t: Throwable) {
            stemFiles.values.forEach { it.delete() }
            return fail(dao, files, trackId, t.message ?: t.javaClass.simpleName)
        } finally {
            mixWav.delete()
        }
    }

    private fun splitMixStreaming(
        mixWav: File,
        model: File,
        stemFiles: Map<StemId, File>,
    ) {
        HtDemucs.withSession(model) { session ->
            val writers = HashMap<StemId, WavWriter>()
            try {
                StemId.entries.forEach { stem ->
                    writers[stem] = WavWriter(
                        stemFiles.getValue(stem),
                        HtDemucs.SAMPLE_RATE,
                        channels = 2,
                    )
                }
                HtDemucs.streamSeparate(mixWav, session, writers) { index, total ->
                    val pct = 28 + (index * 62 / total.coerceAtLeast(1))
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS to pct.coerceIn(28, 90),
                            KEY_MESSAGE to String.format(
                                Locale.US,
                                "HT-Demucs окно %d из %d (7.8 с, overlap-add)",
                                index,
                                total,
                            ),
                        ),
                    )
                }
            } catch (t: Throwable) {
                stemFiles.values.forEach { it.delete() }
                if (DemucsChunkPlan.isMemoryFailure(t)) {
                    throw MinChunkOomException()
                }
                throw t
            } finally {
                writers.values.forEach { writer ->
                    try {
                        writer.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private suspend fun persistAnalysisFromMixWav(
        dao: TrackDao,
        files: TrackFileStore,
        trackId: String,
        mixWav: File,
    ) {
        val frames = WavReader.frameCount(mixWav)
        val analysis = try {
            val mix = WavReader.readStereo(mixWav)
            TempoDetector.analyze(mix)
        } catch (_: OutOfMemoryError) {
            TrackAnalysis.fallbackGrid(frames, HtDemucs.SAMPLE_RATE)
        } catch (_: Throwable) {
            TrackAnalysis.fallbackGrid(frames, HtDemucs.SAMPLE_RATE)
        }
        files.writeAnalysis(trackId, analysis)
        dao.updateBpm(trackId, analysis.bpm, System.currentTimeMillis())
        dao.getById(trackId)?.let { files.writeMeta(it) }
    }

    private suspend fun markProcessing(dao: TrackDao, files: TrackFileStore, trackId: String) {
        dao.updateSplit(
            id = trackId,
            status = TrackStatus.PROCESSING,
            errorMessage = null,
            vocalsPath = null,
            drumsPath = null,
            bassPath = null,
            otherPath = null,
            splitKind = null,
            now = System.currentTimeMillis(),
        )
        dao.getById(trackId)?.let { files.writeMeta(it) }
    }

    private fun report(percent: Int, message: String) {
        setProgressAsync(workDataOf(KEY_PROGRESS to percent, KEY_MESSAGE to message))
    }

    private fun foreground(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_split)
            .setContentTitle(SplitChrome.NOTIFICATION_TITLE)
            .setContentText(SplitChrome.NOTIFICATION_TEXT)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun fail(
        dao: TrackDao,
        files: TrackFileStore,
        trackId: String,
        message: String,
    ): Result {
        dao.updateSplit(
            id = trackId,
            status = TrackStatus.ERROR,
            errorMessage = message,
            vocalsPath = null,
            drumsPath = null,
            bassPath = null,
            otherPath = null,
            splitKind = null,
            now = System.currentTimeMillis(),
        )
        dao.getById(trackId)?.let { files.writeMeta(it) }
        return Result.failure()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.split_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_TRACK_ID = "trackId"
        const val KEY_PROGRESS = "progress"
        const val KEY_MESSAGE = "message"
        private const val CHANNEL_ID = "stem_split"
        private const val NOTIFICATION_ID = 42

        fun uniqueName(trackId: String): String = "stem_split_$trackId"

        fun enqueue(context: Context, trackId: String, replace: Boolean) {
            val request = OneTimeWorkRequestBuilder<StemSplitWorker>()
                .setInputData(workDataOf(KEY_TRACK_ID to trackId))
                .addTag("stem_split")
                .build()
            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(trackId), policy, request)
        }

        fun cancel(context: Context, trackId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(trackId))
        }
    }
}
