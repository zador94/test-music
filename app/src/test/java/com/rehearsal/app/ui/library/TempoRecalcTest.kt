package com.rehearsal.app.ui.library

import com.rehearsal.app.data.TrackEntity
import com.rehearsal.app.data.TrackStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TempoRecalcTest {
    @Test
    fun onlySongsWithDrumsAndInListOrder() {
        val ids = TempoRecalc.ids(
            listOf(
                track("ready", drums = "/tracks/ready/stems/drums.wav"),
                track("queued", drums = null),
                track("blank", drums = "  "),
                track("next", drums = "/tracks/next/stems/drums.wav"),
            ),
        )
        assertEquals(listOf("ready", "next"), ids)
    }

    @Test
    fun emptyLibraryHasNothingToRecalculate() {
        assertEquals(emptyList<String>(), TempoRecalc.ids(emptyList()))
    }

    private fun track(id: String, drums: String?) = TrackEntity(
        id = id,
        title = id,
        originalFileName = "$id.mp3",
        originalPath = "/tracks/$id/original.mp3",
        originalBpm = 120f,
        status = TrackStatus.READY,
        errorMessage = null,
        vocalsPath = null,
        drumsPath = drums,
        bassPath = null,
        otherPath = null,
        splitKind = null,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )
}
