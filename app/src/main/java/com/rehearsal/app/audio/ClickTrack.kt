package com.rehearsal.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.rehearsal.app.player.PlaybackBuffers
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Второй AudioTrack — отдельный клик, не через DefaultAudioSink / MetronomePcmPipe.
 *
 * Часы: wall-chunk 0.12 с, media window = 0.12 × speed. Playhead ExoPlayer —
 * только seek/resync; курсор сдвинут на latency трека, чтобы клик попадал в долю.
 */
class ClickTrack(
    private val engine: MetronomeClickEngine,
    private val clock: () -> Snapshot,
    private val audioSessionId: () -> Int = { 0 },
) {
    data class Snapshot(
        val positionUs: Long,
        val speed: Float,
        val playing: Boolean,
    )

    private val lock = Any()
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var session = 0
    private var cursorUs = Long.MIN_VALUE
    private var lastClickUs = Long.MIN_VALUE
    private val chunk: ByteBuffer = ClickLayer.allocateChunk()

    fun start() {
        synchronized(lock) {
            if (running) return
            try {
                running = true
                session++
                val mySession = session
                cursorUs = Long.MIN_VALUE
                lastClickUs = Long.MIN_VALUE
                if (track == null || track?.state != AudioTrack.STATE_INITIALIZED) {
                    runCatching { track?.release() }
                    track = createTrack() ?: run {
                        running = false
                        return
                    }
                }
                worker = Thread({ loop(mySession) }, "metronome-click").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: Throwable) {
                running = false
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            session++
            worker?.interrupt()
            worker = null
            try {
                track?.pause()
                track?.flush()
            } catch (_: Throwable) {
            }
            cursorUs = Long.MIN_VALUE
            lastClickUs = Long.MIN_VALUE
        }
    }

    fun onSeek() {
        cursorUs = Long.MIN_VALUE
        lastClickUs = Long.MIN_VALUE
        synchronized(lock) {
            try {
                track?.pause()
                track?.flush()
            } catch (_: Throwable) {
            }
        }
    }

    fun release() {
        stop()
        synchronized(lock) {
            try {
                track?.release()
            } catch (_: Throwable) {
            }
            track = null
        }
    }

    private fun loop(mySession: Int) {
        while (running && mySession == session) {
            try {
                val snap = clock()
                val on = engine.enabled && snap.playing
                val audio = synchronized(lock) { track }
                if (!on || audio == null) {
                    cursorUs = Long.MIN_VALUE
                    lastClickUs = Long.MIN_VALUE
                    try {
                        audio?.pause()
                    } catch (_: Throwable) {
                    }
                    Thread.sleep(15)
                    continue
                }
                if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audio.play()
                }
                val speed = snap.speed
                val playhead = snap.positionUs.coerceAtLeast(0L)
                val target = ClickClock.scheduledMediaUs(
                    playhead,
                    speed,
                    ClickClock.outputLatencyMs(audio.bufferSizeInFrames, ClickLayer.SAMPLE_RATE),
                )
                val driftLimit = ClickLayer.lookaheadUs(speed) * 4
                if (cursorUs == Long.MIN_VALUE || abs(target - cursorUs) > driftLimit) {
                    try {
                        audio.pause()
                        audio.flush()
                        audio.play()
                    } catch (_: Throwable) {
                    }
                    cursorUs = target
                    lastClickUs = Long.MIN_VALUE
                }
                chunk.clear()
                chunk.limit(ClickLayer.chunkBytes())
                lastClickUs = ClickLayer.render(
                    chunk,
                    engine,
                    cursorUs,
                    speed,
                    lastClickUs,
                )
                chunk.position(0)
                chunk.limit(ClickLayer.chunkBytes())
                audio.write(chunk, ClickLayer.chunkBytes(), AudioTrack.WRITE_BLOCKING)
                if (!running || mySession != session) break
                cursorUs += ClickLayer.mediaAdvanceUs(speed)
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                sleepQuiet()
            }
        }
    }

    private fun sleepQuiet() {
        try {
            Thread.sleep(15)
        } catch (_: InterruptedException) {
        }
    }

    private fun createTrack(): AudioTrack? {
        val sampleRate = ClickLayer.SAMPLE_RATE
        val min = runCatching {
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }.getOrDefault(0)
        val requested = PlaybackBuffers.audioTrackBytes(sampleRate, channelCount = 2, bytesPerSample = 2)
        val size = PlaybackBuffers.chooseTrackBytes(
            requested,
            min,
            PlaybackBuffers.fallbackAudioTrackBytes(sampleRate, 2, 2),
        )
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
            val sessionId = runCatching { audioSessionId() }.getOrDefault(0)
            if (sessionId > 0) builder.setSessionId(sessionId)
            val created = builder.build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                null
            } else {
                created
            }
        } catch (_: Throwable) {
            null
        }
    }
}
