package com.rehearsal.app

import android.app.Application
import com.rehearsal.app.data.AppDatabase
import com.rehearsal.app.data.TrackFileStore
import com.rehearsal.app.data.TrackRepository
import com.rehearsal.app.player.PlaybackEngine

class RehearsalApplication : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var files: TrackFileStore
        private set
    lateinit var repository: TrackRepository
        private set
    lateinit var playback: PlaybackEngine
        private set

    override fun onCreate() {
        super.onCreate()
        files = TrackFileStore(this)
        database = AppDatabase.create(this)
        repository = TrackRepository(this, database.trackDao(), files)
        playback = PlaybackEngine(this)
    }
}
