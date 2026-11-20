package com.rehearsal.app.player

import com.rehearsal.app.data.SplitKind
import com.rehearsal.app.data.TrackEntity
import com.rehearsal.app.data.TrackStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaybackPolicyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun neverFallsBackToOriginalMix() {
        val queued = entity(status = TrackStatus.QUEUED, splitKind = null)
        val readyNoFiles = entity(
            status = TrackStatus.READY,
            splitKind = SplitKind.HTDEMUCS_4STEM,
            vocals = "/missing/vocals.wav",
            drums = "/missing/drums.wav",
            bass = "/missing/bass.wav",
            other = "/missing/other.wav",
        )
        assertFalse(PlaybackPolicy.fallsBackToOriginal(queued))
        assertFalse(PlaybackPolicy.fallsBackToOriginal(readyNoFiles))
        assertFalse(PlaybackPolicy.canStartPlayback(queued))
        assertFalse(PlaybackPolicy.canStartPlayback(readyNoFiles))
        assertFalse(PlaybackPolicy.opensPlayer(queued))
        assertFalse(PlaybackPolicy.opensPlayer(readyNoFiles))
    }

    @Test
    fun errorOpensProcessingNotPlayer() {
        val error = entity(status = TrackStatus.ERROR, splitKind = null)
        assertFalse(PlaybackPolicy.canStartPlayback(error))
        assertFalse(PlaybackPolicy.opensPlayer(error))
        assertFalse(PlaybackPolicy.fallsBackToOriginal(error))
    }

    @Test
    fun fourStemFilesAllowPlayback() {
        val dir = tmp.newFolder("stems")
        val vocals = File(dir, "vocals.wav").apply { writeText("x") }
        val drums = File(dir, "drums.wav").apply { writeText("x") }
        val bass = File(dir, "bass.wav").apply { writeText("x") }
        val other = File(dir, "other.wav").apply { writeText("x") }
        val track = entity(
            status = TrackStatus.READY,
            splitKind = SplitKind.HTDEMUCS_4STEM,
            vocals = vocals.absolutePath,
            drums = drums.absolutePath,
            bass = bass.absolutePath,
            other = other.absolutePath,
        )
        assertTrue(PlaybackPolicy.canStartPlayback(track))
        assertTrue(PlaybackPolicy.opensPlayer(track))
        assertFalse("original tab is gone: never play the mix file", PlaybackPolicy.fallsBackToOriginal(track))
    }

    private fun entity(
        status: String,
        splitKind: String?,
        vocals: String? = null,
        drums: String? = null,
        bass: String? = null,
        other: String? = null,
    ) = TrackEntity(
        id = "t1",
        title = "x",
        originalFileName = "x.mp3",
        originalPath = "/tmp/x.mp3",
        originalBpm = 120f,
        status = status,
        errorMessage = null,
        vocalsPath = vocals,
        drumsPath = drums,
        bassPath = bass,
        otherPath = other,
        splitKind = splitKind,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )
}
