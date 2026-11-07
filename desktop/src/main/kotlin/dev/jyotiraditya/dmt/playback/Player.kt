package dev.jyotiraditya.dmt.playback

import dev.jyotiraditya.dmt.data.repository.PreferencesRepository
import dev.jyotiraditya.dmt.domain.model.LastSession
import dev.jyotiraditya.dmt.domain.model.Track
import dev.jyotiraditya.metadata.AudioTags
import dev.jyotiraditya.metadata.TagKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object RepeatMode {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}

private const val RESTART_THRESHOLD_MS = 3_000L
private const val MIN_STAT_MS = 5_000L
/** A track that "ends" this fast while it is longer than this... */
private const val SUSPICIOUS_END_MS = 2_000L
private const val SUSPICIOUS_TRACK_MS = 5_000L
/** ...this many times in a row means the decoder is not really playing anything: stop. */
private const val MAX_SUSPICIOUS_ENDS = 2

interface PlayerListener {
    fun onMediaItemTransition(track: Track?) {}
    fun onIsPlayingChanged(isPlaying: Boolean) {}
    fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}
    fun onRepeatModeChanged(repeatMode: Int) {}
    fun onPlaybackSpeedChanged(speed: Float) {}
    fun onTimelineChanged() {}
    fun onUnsupported() {}
    fun onPlayerError(code: String) {}
}

/**
 * Desktop stand-in for media3's ExoPlayer + MediaSession: queue, shuffle order, repeat,
 * speed, replay gain, sleep timer, resume and listening stats. Everything public is called
 * on the ui thread ([scope] runs on Dispatchers.Main); listeners are notified there too.
 */
class Player(
    private val scope: CoroutineScope,
    private val output: AudioOutput,
    private val preferences: PreferencesRepository,
) {
    private val listeners = mutableListOf<PlayerListener>()

    var queue: List<Track> = emptyList()
        private set

    /** Play order: indices into [queue]. Identity unless shuffle is on. */
    var order: List<Int> = emptyList()
        private set

    var currentMediaItemIndex: Int = -1
        private set

    var isPlaying: Boolean = false
        private set

    var playWhenReady: Boolean = false
        private set

    var ended: Boolean = false
        private set

    var shuffleModeEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            order = buildOrder(value)
            listeners.forEach { it.onShuffleModeEnabledChanged(value) }
            persistModes()
        }

    var repeatMode: Int = RepeatMode.OFF
        set(value) {
            if (field == value) return
            field = value
            listeners.forEach { it.onRepeatModeChanged(value) }
            persistModes()
        }

    var playbackSpeed: Float = 1f
        private set

    var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            session?.gain = effectiveGain()
        }

    var normalizeVolume: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyReplayGain()
        }

    private var replayGain = 1f
    private val gainCache = mutableMapOf<String, Float>()

    private var session: PlaybackSession? = null
    private var pausedPositionMs: Long = 0
    private var knownDurationMs: Long = 0

    var sleepEndAt: Long? = null
        private set
    private var sleepJob: Job? = null

    private var playedMs = 0L
    private var sessionStartedAt = 0L
    private var suspiciousEnds = 0
    private var lastTick = 0L

    val currentTrack: Track?
        get() = queue.getOrNull(currentMediaItemIndex)

    val mediaItemCount: Int
        get() = queue.size

    val currentPosition: Long
        get() = session?.positionMs ?: pausedPositionMs

    val duration: Long
        get() = session?.durationMs?.takeIf { it > 0 } ?: knownDurationMs

    init {
        shuffleModeEnabled = preferences.savedShuffle()
        repeatMode = preferences.savedRepeat().coerceIn(0, 2)
        playbackSpeed = preferences.savedSpeed()
        volume = preferences.savedVolume()
        scope.launch {
            while (isActive) {
                tickStats()
                if (isPlaying) saveSession()
                delay(10.seconds)
            }
        }
    }

    fun addListener(listener: PlayerListener) {
        listeners += listener
    }

    // region queue

    fun setMediaItems(tracks: List<Track>, startIndex: Int, startPositionMs: Long) {
        recordStats()
        queue = tracks
        currentMediaItemIndex = if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.lastIndex)
        order = buildOrder(shuffleModeEnabled)
        ended = false
        pausedPositionMs = startPositionMs
        listeners.forEach { it.onTimelineChanged() }
        startSession(startPositionMs, cut = true)
        notifyTransition()
    }

    fun addMediaItems(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val first = queue.size
        queue = queue + tracks
        val added = (first until queue.size).toList()
        order = if (shuffleModeEnabled) order + added.shuffled() else order + added
        if (currentMediaItemIndex < 0) {
            currentMediaItemIndex = 0
            ended = false
            startSession(0, cut = true)
            notifyTransition()
        }
        listeners.forEach { it.onTimelineChanged() }
    }

    fun removeMediaItem(index: Int) {
        if (index !in queue.indices) return
        val wasCurrent = index == currentMediaItemIndex
        val nextInOrder = if (wasCurrent) nextIndex(forNavigation = true) else null
        queue = queue.toMutableList().apply { removeAt(index) }
        order = order.filter { it != index }.map { if (it > index) it - 1 else it }
        when {
            queue.isEmpty() -> {
                stopSession(cut = true)
                currentMediaItemIndex = -1
                setPlayingState(false)
                notifyTransition()
            }

            wasCurrent -> {
                currentMediaItemIndex = when {
                    nextInOrder == null -> order.first()
                    nextInOrder > index -> nextInOrder - 1
                    else -> nextInOrder
                }
                if (nextInOrder == null) playWhenReady = false
                startSession(0, cut = true)
                notifyTransition()
            }

            index < currentMediaItemIndex -> currentMediaItemIndex -= 1
        }
        listeners.forEach { it.onTimelineChanged() }
    }

    fun clearMediaItems() {
        recordStats()
        stopSession(cut = true)
        queue = emptyList()
        order = emptyList()
        currentMediaItemIndex = -1
        setPlayingState(false)
        listeners.forEach { it.onTimelineChanged() }
        notifyTransition()
    }

    private fun buildOrder(shuffle: Boolean): List<Int> {
        val all = queue.indices.toList()
        if (!shuffle || currentMediaItemIndex < 0) return if (shuffle) all.shuffled() else all
        return listOf(currentMediaItemIndex) + all.filter { it != currentMediaItemIndex }.shuffled()
    }

    // endregion

    // region transport

    fun play() {
        if (queue.isEmpty()) return
        if (ended) {
            ended = false
            seekTo(currentMediaItemIndex, 0)
        } else if (duration > 0 && currentPosition >= duration - 500) {
            // restored (or paused) right at the end: start the track over instead of ending again
            seekTo(0)
        }
        playWhenReady = true
        val s = session
        if (s == null || s.finished) startSession(pausedPositionMs, cut = true) else s.setPlaying(true)
        setPlayingState(true)
    }

    fun pause() {
        playWhenReady = false
        pausedPositionMs = currentPosition
        session?.setPlaying(false)
        setPlayingState(false)
        saveSession()
    }

    fun togglePlayPause() = if (isPlaying) pause() else play()

    fun stop() {
        pause()
        stopSession(cut = true)
    }

    fun seekTo(positionMs: Long) {
        pausedPositionMs = positionMs.coerceAtLeast(0)
        ended = false
        val s = session
        if (s == null || s.finished) startSession(pausedPositionMs, cut = true) else s.seekTo(pausedPositionMs)
    }

    fun seekTo(index: Int, positionMs: Long) {
        if (index !in queue.indices) return
        if (index == currentMediaItemIndex && session != null) {
            seekTo(positionMs)
            return
        }
        recordStats()
        currentMediaItemIndex = index
        pausedPositionMs = positionMs
        ended = false
        startSession(positionMs, cut = true)
        notifyTransition()
    }

    fun seekToNext() {
        val next = nextIndex(forNavigation = true) ?: return
        seekTo(next, 0)
    }

    fun seekToPrevious() {
        if (currentPosition > RESTART_THRESHOLD_MS) {
            seekTo(0)
            return
        }
        val prev = previousIndex() ?: run {
            seekTo(0)
            return
        }
        seekTo(prev, 0)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        session?.setSpeed(speed)
        output.effects = effectsList()
        listeners.forEach { it.onPlaybackSpeedChanged(speed) }
        scope.launch { preferences.saveSpeed(speed) }
    }

    fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
    }

    private fun nextIndex(forNavigation: Boolean): Int? {
        if (queue.isEmpty()) return null
        if (!forNavigation && repeatMode == RepeatMode.ONE) return currentMediaItemIndex
        val p = order.indexOf(currentMediaItemIndex)
        return when {
            p >= 0 && p + 1 < order.size -> order[p + 1]
            repeatMode != RepeatMode.OFF -> order.firstOrNull()
            else -> null
        }
    }

    private fun previousIndex(): Int? {
        val p = order.indexOf(currentMediaItemIndex)
        return when {
            p > 0 -> order[p - 1]
            repeatMode != RepeatMode.OFF -> order.lastOrNull()
            else -> null
        }
    }

    // endregion

    // region sessions

    private fun startSession(positionMs: Long, cut: Boolean) {
        val track = currentTrack ?: run {
            stopSession(cut)
            return
        }
        val old = session
        old?.stop(cut)
        knownDurationMs = track.durationMs
        replayGain = gainCache[track.path] ?: 1f
        val s = PlaybackSession(
            uri = track.uri,
            clipStartMs = track.clipStartMs,
            clipEndMs = track.clipEndMs,
            startPositionMs = positionMs,
            playWhenReady = playWhenReady,
            speed = playbackSpeed,
            gain = effectiveGain(),
            output = output,
            listener = sessionListener,
            flushOnStart = cut,
            previous = old,
        )
        session = s
        sessionStartedAt = System.currentTimeMillis()
        output.effects = effectsList()
        s.start()
        applyReplayGain()
        if (playWhenReady) setPlayingState(true)
    }

    private fun stopSession(cut: Boolean) {
        session?.stop(cut)
        session = null
    }

    private val sessionListener = object : SessionListener {
        override fun onReady(session: PlaybackSession, durationMs: Long) {
            scope.launch(Dispatchers.Main) {
                if (session === this@Player.session && durationMs > 0) knownDurationMs = durationMs
            }
        }

        override fun onEnded(session: PlaybackSession) {
            scope.launch(Dispatchers.Main) {
                if (session !== this@Player.session) return@launch
                recordStats()
                val wall = System.currentTimeMillis() - sessionStartedAt
                val expected = (session.durationMs.takeIf { it > 0 } ?: duration) - pausedPositionMs
                suspiciousEnds = if (wall < SUSPICIOUS_END_MS && expected > SUSPICIOUS_TRACK_MS) suspiciousEnds + 1 else 0
                if (suspiciousEnds >= MAX_SUSPICIOUS_ENDS) {
                    // tracks are "ending" without being heard: the output is broken. Stop here
                    // rather than skipping through the whole queue.
                    suspiciousEnds = 0
                    playWhenReady = false
                    pausedPositionMs = 0
                    this@Player.session = null
                    output.close()
                    setPlayingState(false)
                    listeners.forEach { it.onPlayerError("audio_output_stalled") }
                    return@launch
                }
                val next = nextIndex(forNavigation = false)
                if (next == null) {
                    ended = true
                    playWhenReady = false
                    pausedPositionMs = duration
                    this@Player.session = null
                    setPlayingState(false)
                    saveSession()
                } else {
                    currentMediaItemIndex = next
                    pausedPositionMs = 0
                    startSession(0, cut = false)
                    notifyTransition()
                }
            }
        }

        override fun onFailed(session: PlaybackSession, failure: PlaybackFailure) {
            scope.launch(Dispatchers.Main) {
                if (session !== this@Player.session) return@launch
                playWhenReady = false
                pausedPositionMs = 0
                this@Player.session = null
                setPlayingState(false)
                if (failure.unsupported) {
                    listeners.forEach { it.onUnsupported() }
                } else {
                    listeners.forEach { it.onPlayerError(failure.code) }
                }
            }
        }
    }

    private fun setPlayingState(value: Boolean) {
        tickStats()
        if (isPlaying == value) return
        isPlaying = value
        listeners.forEach { it.onIsPlayingChanged(value) }
        if (!value) saveSession()
    }

    private fun notifyTransition() {
        saveSession()
        val track = currentTrack
        listeners.forEach { it.onMediaItemTransition(track) }
    }

    private fun effectiveGain(): Float = replayGain * volume

    private fun effectsList(): List<String> = buildList {
        if (kotlin.math.abs(playbackSpeed - 1f) > 0.01f) add("ATEMPO")
        if (normalizeVolume) add("REPLAYGAIN")
        if (volume < 0.999f) add("SOFT-VOLUME")
    }

    private fun applyReplayGain() {
        output.effects = effectsList()
        val track = currentTrack
        if (!normalizeVolume || track == null || track.path.isEmpty()) {
            replayGain = 1f
            session?.gain = effectiveGain()
            return
        }
        scope.launch {
            val gain = gainCache[track.path] ?: withContext(Dispatchers.IO) {
                val db = AudioTags.read(track.path)[TagKey.REPLAYGAIN_TRACK_GAIN]
                    ?.firstOrNull()
                    ?.replace("dB", "", ignoreCase = true)
                    ?.trim()
                    ?.toFloatOrNull()
                db?.let { 10.0.pow(it / 20.0).toFloat().coerceIn(0f, 1f) } ?: 1f
            }.also { gainCache[track.path] = it }
            if (currentTrack?.path == track.path && normalizeVolume) {
                replayGain = gain
                session?.gain = effectiveGain()
            }
        }
    }

    // endregion

    // region sleep, stats, session

    fun scheduleSleep(endAt: Long) {
        sleepJob?.cancel()
        sleepEndAt = endAt.takeIf { it > System.currentTimeMillis() }
        sleepJob = sleepEndAt?.let { end ->
            scope.launch {
                delay((end - System.currentTimeMillis()).milliseconds)
                pause()
                sleepEndAt = null
            }
        }
    }

    private fun tickStats() {
        val now = System.currentTimeMillis()
        if (isPlaying && lastTick > 0) playedMs += now - lastTick
        lastTick = now
    }

    private fun recordStats() {
        tickStats()
        val played = playedMs
        playedMs = 0
        val id = currentTrack?.id
        if (played < MIN_STAT_MS) return
        scope.launch { preferences.recordPlayback(played, id) }
    }

    private fun persistModes() {
        val shuffle = shuffleModeEnabled
        val repeat = repeatMode
        scope.launch { preferences.saveModes(shuffle, repeat) }
    }

    fun saveSession() {
        if (queue.isEmpty()) return
        val snapshot = LastSession(
            queueIds = queue.map { it.id },
            index = currentMediaItemIndex,
            positionMs = currentPosition.coerceAtLeast(0L),
        )
        scope.launch { preferences.saveSession(snapshot) }
    }

    /** Called from the shutdown path: persist synchronously, then silence the line. */
    fun release() {
        recordStatsBlocking()
        if (queue.isNotEmpty()) {
            runBlocking {
                preferences.saveSession(
                    LastSession(queue.map { it.id }, currentMediaItemIndex, currentPosition.coerceAtLeast(0)),
                )
                preferences.saveVolume(volume)
            }
        }
        session?.stop(cut = true)
        session = null
        output.close()
    }

    private fun recordStatsBlocking() {
        tickStats()
        val played = playedMs
        playedMs = 0
        if (played >= MIN_STAT_MS) runBlocking { preferences.recordPlayback(played, currentTrack?.id) }
    }

    // endregion
}
