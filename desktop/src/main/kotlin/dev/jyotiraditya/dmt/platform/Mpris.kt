package dev.jyotiraditya.dmt.platform

import dev.jyotiraditya.dmt.AppGraph
import dev.jyotiraditya.dmt.data.repository.CoverArtRepository
import dev.jyotiraditya.dmt.domain.model.Track
import dev.jyotiraditya.dmt.playback.RepeatMode
import dev.jyotiraditya.dmt.presentation.player.DmtAction
import dev.jyotiraditya.dmt.presentation.player.DmtState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.abs

private const val BUS_NAME = "org.mpris.MediaPlayer2.tmpl"
private const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
private const val ROOT_IFACE = "org.mpris.MediaPlayer2"
private const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"

@DBusInterfaceName(ROOT_IFACE)
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName(PLAYER_IFACE)
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(Offset: Long)
    fun SetPosition(TrackId: DBusPath, Position: Long)
    fun OpenUri(Uri: String)

    class Seeked(path: String, val Position: Long) : DBusSignal(path, Position)
}

/**
 * MPRIS2 on the session bus: media keys, the gnome/kde media widget, playerctl and friends.
 * This is what the media notification + MediaSession did on android.
 */
class Mpris private constructor(
    private val connection: DBusConnection,
    private val graph: AppGraph,
    private val onRaise: () -> Unit,
    private val onQuit: () -> Unit,
) : AutoCloseable, MediaPlayer2, MediaPlayer2Player, Properties {

    private val scope = CoroutineScope(graph.scope.coroutineContext + Job())
    private val dispatch: (DmtAction) -> Unit = { action ->
        scope.launch(Dispatchers.Main) { graph.viewModel.onIntent(action) }
    }
    private val artDir = File(AppDirs.cache, "mpris").apply { mkdirs() }
    private val coverArt = CoverArtRepository(graph.httpClient)

    @Volatile private var state: DmtState = graph.viewModel.state.value
    @Volatile private var artUrl: String? = null
    private var lastPosition = 0L
    private var lastStamp = 0L

    override fun getObjectPath(): String = OBJECT_PATH

    private fun start() {
        connection.exportObject(OBJECT_PATH, this)
        connection.requestBusName(BUS_NAME)
        scope.launch(Dispatchers.Main) {
            graph.viewModel.state.collect { next -> onState(next) }
        }
        scope.launch(Dispatchers.Main) {
            graph.viewModel.state
                .map { it.nowPlayingId to it.cover }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (id, _) -> loadArt(id) }
        }
    }

    private fun onState(next: DmtState) {
        val prev = state
        state = next
        val changed = LinkedHashMap<String, Variant<*>>()
        if (prev.isPlaying != next.isPlaying || prev.nowPlayingId != next.nowPlayingId) {
            changed["PlaybackStatus"] = Variant(playbackStatus(next))
        }
        if (prev.nowPlayingId != next.nowPlayingId || prev.durationMs != next.durationMs ||
            prev.title != next.title
        ) {
            changed["Metadata"] = Variant(metadata(next), "a{sv}")
            changed["CanGoNext"] = Variant(next.queue.isNotEmpty())
            changed["CanGoPrevious"] = Variant(next.queue.isNotEmpty())
        }
        if (prev.repeat != next.repeat) changed["LoopStatus"] = Variant(loopStatus(next))
        if (prev.shuffle != next.shuffle) changed["Shuffle"] = Variant(next.shuffle)
        if (prev.speed != next.speed) changed["Rate"] = Variant(next.speed.toDouble())
        if (prev.volume != next.volume) changed["Volume"] = Variant(next.volume.toDouble())
        if (changed.isNotEmpty()) emitChanged(changed)

        // MPRIS clients extrapolate position themselves; tell them only about jumps.
        val now = System.currentTimeMillis()
        val expected = if (prev.isPlaying) lastPosition + ((now - lastStamp) * next.speed).toLong() else lastPosition
        if (prev.nowPlayingId == next.nowPlayingId && lastStamp > 0 && abs(next.positionMs - expected) > 1_500) {
            runCatching { connection.sendMessage(MediaPlayer2Player.Seeked(OBJECT_PATH, next.positionMs * 1000)) }
        }
        lastPosition = next.positionMs
        lastStamp = now
    }

    private fun emitChanged(changed: Map<String, Variant<*>>) {
        runCatching {
            connection.sendMessage(Properties.PropertiesChanged(OBJECT_PATH, PLAYER_IFACE, changed, emptyList()))
        }
    }

    private suspend fun loadArt(id: String?) {
        artUrl = null
        val track = id?.let { key -> state.tracks.find { it.id.toString() == key } } ?: return
        val url = withContext(Dispatchers.IO) { artFile(track) }
        if (state.nowPlayingId == id && url != null) {
            artUrl = url
            emitChanged(mapOf("Metadata" to Variant(metadata(state), "a{sv}")))
        }
    }

    private suspend fun artFile(track: Track): String? {
        val file = File(artDir, "${track.id}.png")
        if (!file.isFile) {
            val image = runCatching { coverArt.loadArt(track) }.getOrNull() ?: return null
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: return null
            runCatching {
                artDir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(32)?.forEach { it.delete() }
                file.writeBytes(data.bytes)
            }.getOrElse { return null }
        }
        return file.toURI().toString()
    }

    private fun playbackStatus(s: DmtState): String = when {
        s.nowPlayingId == null -> "Stopped"
        s.isPlaying -> "Playing"
        else -> "Paused"
    }

    private fun loopStatus(s: DmtState): String = when (s.repeat) {
        RepeatMode.ONE -> "Track"
        RepeatMode.ALL -> "Playlist"
        else -> "None"
    }

    private fun trackPath(s: DmtState): DBusPath =
        DBusPath(s.nowPlayingId?.let { "/dev/ivnls/tmpl/track/${it.replace('-', '_')}" } ?: "/org/mpris/MediaPlayer2/TrackList/NoTrack")

    private fun metadata(s: DmtState): Map<String, Variant<*>> {
        if (s.nowPlayingId == null) return mapOf("mpris:trackid" to Variant(trackPath(s)))
        return buildMap {
            put("mpris:trackid", Variant(trackPath(s)))
            put("mpris:length", Variant(s.durationMs * 1000))
            put("xesam:title", Variant(s.title))
            put("xesam:artist", Variant(listOf(s.artist), "as"))
            if (s.album.isNotEmpty()) put("xesam:album", Variant(s.album))
            artUrl?.let { put("mpris:artUrl", Variant(it)) }
        }
    }

    private fun rootProps(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(true),
        "CanRaise" to Variant(true),
        "HasTrackList" to Variant(false),
        "Identity" to Variant("tmpl"),
        "DesktopEntry" to Variant("tmpl"),
        "SupportedUriSchemes" to Variant(listOf("file"), "as"),
        "SupportedMimeTypes" to Variant(listOf("audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4"), "as"),
    )

    private fun playerProps(): Map<String, Variant<*>> {
        val s = state
        val has = s.nowPlayingId != null
        return mapOf(
            "PlaybackStatus" to Variant(playbackStatus(s)),
            "LoopStatus" to Variant(loopStatus(s)),
            "Rate" to Variant(s.speed.toDouble()),
            "Shuffle" to Variant(s.shuffle),
            "Metadata" to Variant(metadata(s), "a{sv}"),
            "Volume" to Variant(s.volume.toDouble()),
            "Position" to Variant(graph.player.currentPosition * 1000),
            "MinimumRate" to Variant(0.75),
            "MaximumRate" to Variant(2.0),
            "CanGoNext" to Variant(has),
            "CanGoPrevious" to Variant(has),
            "CanPlay" to Variant(has),
            "CanPause" to Variant(has),
            "CanSeek" to Variant(has),
            "CanControl" to Variant(true),
        )
    }

    // org.mpris.MediaPlayer2
    override fun Raise() {
        scope.launch(Dispatchers.Main) { onRaise() }
    }

    override fun Quit() {
        scope.launch(Dispatchers.Main) { onQuit() }
    }

    // org.mpris.MediaPlayer2.Player
    override fun Next() = dispatch(DmtAction.Next)
    override fun Previous() = dispatch(DmtAction.Prev)
    override fun Pause() {
        if (state.isPlaying) dispatch(DmtAction.TogglePlay)
    }

    override fun PlayPause() = dispatch(DmtAction.TogglePlay)
    override fun Stop() = Pause()
    override fun Play() {
        if (!state.isPlaying) dispatch(DmtAction.TogglePlay)
    }

    override fun Seek(Offset: Long) = dispatch(DmtAction.SeekBy(Offset / 1000))

    override fun SetPosition(TrackId: DBusPath, Position: Long) {
        val duration = state.durationMs
        if (duration <= 0 || TrackId.path != trackPath(state).path) return
        dispatch(DmtAction.Seek((Position / 1000f / duration).coerceIn(0f, 1f)))
    }

    override fun OpenUri(Uri: String) = Unit

    // org.freedesktop.DBus.Properties
    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A {
        val props = if (interfaceName == ROOT_IFACE) rootProps() else playerProps()
        return props[propertyName]?.value as A
    }

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        if (interfaceName != PLAYER_IFACE) return
        when (propertyName) {
            "Shuffle" -> if ((value as? Boolean) != null && value != state.shuffle) dispatch(DmtAction.ToggleShuffle)
            "LoopStatus" -> {
                val target = when (value as? String) {
                    "Track" -> RepeatMode.ONE
                    "Playlist" -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                }
                scope.launch(Dispatchers.Main) {
                    repeat(3) { if (graph.player.repeatMode != target) graph.player.cycleRepeat() }
                }
            }

            "Volume" -> (value as? Double)?.let { v ->
                scope.launch(Dispatchers.Main) {
                    graph.viewModel.onIntent(DmtAction.Volume(v.toFloat().coerceIn(0f, 1f) - graph.player.volume))
                }
            }

            "Rate" -> (value as? Double)?.let { v ->
                scope.launch(Dispatchers.Main) { graph.player.setPlaybackSpeed(v.toFloat().coerceIn(0.75f, 2f)) }
            }
        }
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
        when (interfaceName) {
            ROOT_IFACE -> rootProps()
            PLAYER_IFACE -> playerProps()
            else -> emptyMap()
        }

    override fun close() {
        runCatching { scope.coroutineContext[Job]?.cancel() }
        runCatching { connection.releaseBusName(BUS_NAME) }
        runCatching { connection.close() }
    }

    companion object {
        fun start(graph: AppGraph, onRaise: () -> Unit = {}, onQuit: () -> Unit = {}): Mpris? {
            if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank() &&
                System.getenv("XDG_RUNTIME_DIR").isNullOrBlank()
            ) {
                return null
            }
            return runCatching {
                val connection = DBusConnectionBuilder.forSessionBus().withShared(false).build()
                Mpris(connection, graph, onRaise, onQuit).also { it.start() }
            }.onFailure {
                System.err.println("tmpl: mpris unavailable (${it.message})")
            }.getOrNull()
        }
    }
}
