package com.rehearsal.app.audio

import android.content.Context
import com.rehearsal.app.data.TrackFileStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Веса HT-Demucs не кладём в APK (~166 МБ).
 * Скачиваем один раз в getExternalFilesDir/models/ и дальше читаем с диска.
 */
class HtdemucsModelStore(
    private val context: Context,
    private val files: TrackFileStore,
) {
    fun modelFile(): File = File(files.modelsDir(), MODEL_NAME)

    fun isReady(): Boolean = modelFile().let { it.isFile && it.length() >= MIN_BYTES }

    fun ensureModel(onProgress: (downloaded: Long, total: Long) -> Unit): File {
        val dest = modelFile()
        if (dest.isFile && dest.length() >= MIN_BYTES) return dest
        copyFromAssets(dest)?.let { return it }

        val part = File(dest.parentFile, "$MODEL_NAME.part")
        part.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "RehearsalApp/0.3")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("Не удалось скачать HT-Demucs (HTTP $code). Нужен интернет или файл $MODEL_NAME в ${files.modelsDir().absolutePath}")
            }
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        onProgress(copied, total)
                    }
                }
            }
            if (part.length() < MIN_BYTES) {
                part.delete()
                error("Скачанный файл слишком маленький (${part.length()} байт). Это не модель.")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            return dest
        } finally {
            connection.disconnect()
        }
    }

    private fun copyFromAssets(dest: File): File? {
        return try {
            context.assets.open("models/$MODEL_NAME").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.length() >= MIN_BYTES) dest else {
                dest.delete()
                null
            }
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }

    companion object {
        const val MODEL_NAME = "htdemucs_fp16weights.onnx"
        const val MODEL_URL =
            "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx"
        private const val MIN_BYTES = 80L * 1024L * 1024L
    }
}
