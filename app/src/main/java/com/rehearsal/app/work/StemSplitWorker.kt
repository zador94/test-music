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
import com.rehearsal.app.audio.HtDemucs
import com.rehearsal.app.audio.HtdemucsModelStore
import com.rehearsal.app.audio.WavWriter
import com.rehearsal.app.data.SplitKind
import com.rehearsal.app.data.StemId
import com.rehearsal.app.data.TrackDao
import com.rehearsal.app.data.TrackFileStore
import com.rehearsal.app.data.TrackStatus
import java.io.File

/**
 * Один проход HT-Demucs ONNX (htdemucs, не FT-bag и не 6-stem).
 * Пишет tracks/{id}/stems/{vocals,drums,bass,other}.wav
 * Unique work + KEEP: повторный вход не гоняет модель снова, пока нет «пересчитать».
 */
class StemSplitWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return foreground("HT-Demucs: готовим 4 дорожки")
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
            setForeground(foreground("HT-Demucs: скачивание модели / разделение"))
        } catch (_: Exception) {
        }

        markProcessing(dao, files, trackId)

        val original = File(track.originalPath)
        if (!original.exists()) {
            return fail(dao, files, trackId, "Копия исходника не найдена: ${original.path}")
        }

        val stemFiles = StemId.entries.associateWith { files.stemWav(trackId, it) }
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

            report(22, "Декодирую исходник в 44.1 кГц stereo")
            val mix = AudioBuffers.decodeToStereo44100(original)
            report(28, "HT-Demucs считает стемы. Минуты, телефон греется.")

            val separated = HtDemucs.separate(mix, model) { index, total ->
                val pct = 28 + (index * 62 / total)
                setProgressAsync(
                    workDataOf(
                        KEY_PROGRESS to pct.coerceIn(28, 90),
                        KEY_MESSAGE to "Чанк $index из $total (по 7.8 с)",
                    ),
                )
            }

            report(92, "Пишу WAV")
            StemId.entries.forEach { stem ->
                val pcm = separated.getValue(stem)
                WavWriter(stemFiles.getValue(stem), HtDemucs.SAMPLE_RATE, channels = 2).use { writer ->
                    writer.writePlanarFloatStereo(pcm.left, pcm.right)
                }
            }

            val note = "HT-Demucs (htdemucs) ONNX, 4 стемы, WAV IEEE float32. Гитара в «Музыка» (other)."
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
        } catch (oom: OutOfMemoryError) {
            stemFiles.values.forEach { it.delete() }
            return fail(
                dao,
                files,
                trackId,
                "Не хватило RAM для HT-Demucs. Попробуйте более короткий трек.",
            )
        } catch (t: Throwable) {
            stemFiles.values.forEach { it.delete() }
            return fail(dao, files, trackId, t.message ?: t.javaClass.simpleName)
        }
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

    private fun foreground(text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_split)
            .setContentTitle(applicationContext.getString(R.string.split_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
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
            NotificationManager.IMPORTANCE_LOW,
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
