package com.rehearsal.app.data

import android.content.Context
import com.rehearsal.app.audio.TrackAnalysis
import org.json.JSONObject
import java.io.File

class TrackFileStore(private val context: Context) {

    fun tracksRoot(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "tracks").also { dir ->
            dir.mkdirs()
            File(dir, ".nomedia").apply { if (!exists()) writeText("") }
        }
    }

    fun modelsDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "models").also { it.mkdirs() }
    }

    fun trackDir(id: String): File = File(tracksRoot(), id).also { it.mkdirs() }

    fun originalFile(id: String, extension: String): File {
        val ext = extension.trimStart('.').ifBlank { "bin" }
        return File(trackDir(id), "original.$ext")
    }

    fun stemsDir(id: String): File = File(trackDir(id), "stems").also { it.mkdirs() }

    fun stemWav(id: String, stem: StemId): File = File(stemsDir(id), stem.fileName)

    fun vocalsWav(id: String): File = stemWav(id, StemId.VOCALS)

    fun drumsWav(id: String): File = stemWav(id, StemId.DRUMS)

    fun bassWav(id: String): File = stemWav(id, StemId.BASS)

    fun otherWav(id: String): File = stemWav(id, StemId.OTHER)

    fun metaJson(id: String): File = File(trackDir(id), "meta.json")

    fun analysisJson(id: String): File = File(trackDir(id), TrackAnalysis.FILE_NAME)

    fun writeAnalysis(id: String, analysis: TrackAnalysis) {
        analysisJson(id).writeText(analysis.toJson())
    }

    fun stemReadme(id: String): File = File(stemsDir(id), "README.txt")

    fun deleteTrack(id: String) {
        trackDir(id).deleteRecursively()
    }

    fun writeMeta(track: TrackEntity) {
        val json = JSONObject()
            .put("id", track.id)
            .put("title", track.title)
            .put("originalFileName", track.originalFileName)
            .put("originalPath", track.originalPath)
            .put("originalBpm", track.originalBpm?.toDouble() ?: JSONObject.NULL)
            .put("status", track.status)
            .put("errorMessage", track.errorMessage ?: JSONObject.NULL)
            .put("vocalsPath", track.vocalsPath ?: JSONObject.NULL)
            .put("drumsPath", track.drumsPath ?: JSONObject.NULL)
            .put("bassPath", track.bassPath ?: JSONObject.NULL)
            .put("otherPath", track.otherPath ?: JSONObject.NULL)
            .put("splitKind", track.splitKind ?: JSONObject.NULL)
            .put("sourceOfTruth", "Room, not this file")
            .put("updatedAtEpochMs", track.updatedAtEpochMs)
        metaJson(track.id).writeText(json.toString(2))
    }

    fun writeStemReadme(id: String, splitKind: String, note: String) {
        stemReadme(id).writeText(
            buildString {
                appendLine("splitKind=$splitKind")
                appendLine("model=HT-Demucs (htdemucs) ONNX 4-stem")
                appendLine("wav=IEEE float32 stereo 44.1 kHz")
                appendLine("order=drums, bass, other, vocals")
                appendLine("other = «Музыка»: гитара, клавиши, всё кроме вокала/ударных/баса.")
                appendLine()
                appendLine(note)
            },
        )
    }
}
