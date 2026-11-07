package dev.jyotiraditya.dmt

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import dev.jyotiraditya.dmt.core.common.BackDispatcher
import dev.jyotiraditya.dmt.core.common.LocalBackDispatcher
import dev.jyotiraditya.dmt.core.common.LocalUiCommands
import dev.jyotiraditya.dmt.core.common.LocalSpectrum
import dev.jyotiraditya.dmt.core.common.LocalWindowDpSize
import dev.jyotiraditya.dmt.core.common.TextFocus
import dev.jyotiraditya.dmt.core.common.UiCommand
import dev.jyotiraditya.dmt.core.common.UiCommands
import dev.jyotiraditya.dmt.platform.Mpris
import dev.jyotiraditya.dmt.platform.Platform
import dev.jyotiraditya.dmt.platform.uiZoom
import dev.jyotiraditya.dmt.presentation.main.DmtScreen
import dev.jyotiraditya.dmt.presentation.main.SetupScreen
import dev.jyotiraditya.dmt.presentation.player.DmtAction
import dev.jyotiraditya.dmt.presentation.player.DmtView
import dev.jyotiraditya.dmt.presentation.player.PlayerEffect
import dev.jyotiraditya.dmt.resources.Res
import dev.jyotiraditya.dmt.resources.dmt
import dev.jyotiraditya.dmt.resources.tray_next
import dev.jyotiraditya.dmt.resources.tray_play_pause
import dev.jyotiraditya.dmt.resources.tray_prev
import dev.jyotiraditya.dmt.resources.tray_quit
import dev.jyotiraditya.dmt.resources.tray_show
import dev.jyotiraditya.dmt.ui.theme.DMTTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension
import java.awt.Toolkit

private const val SEEK_STEP_MS = 5_000L
private const val SEEK_BIG_STEP_MS = 30_000L

fun main() {
    setLinuxWmClass()
    val graph = AppGraph()
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { graph.player.release() } })
    val raise = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val quitRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    var mpris: Mpris? = null
    if (Platform.isLinux && System.getenv("DMT_NO_MPRIS") == null) {
        Thread {
            mpris = Mpris.start(graph, onRaise = { raise.tryEmit(Unit) }, onQuit = { quitRequest.tryEmit(Unit) })
        }.apply { isDaemon = true }.start()
    }

    application {
        val vm = graph.viewModel
        val state by vm.state.collectAsState()
        var visible by remember { mutableStateOf(true) }
        val windowState = rememberSavedWindowState(graph)
        val backDispatcher = remember { BackDispatcher() }
        val uiCommands = remember { UiCommands() }
        val trayOk = remember { isTraySupported }

        fun quit() {
            saveWindowState(graph, windowState)
            mpris?.close()
            graph.shutdown()
            exitApplication()
        }

        LaunchedEffect(Unit) {
            raise.collect { visible = true }
        }
        LaunchedEffect(Unit) {
            quitRequest.collect { quit() }
        }
        LaunchedEffect(Unit) {
            vm.effects.collect { effect ->
                when (effect) {
                    PlayerEffect.Quit -> quit()
                }
            }
        }

        if (trayOk) {
            Tray(
                icon = painterResource(Res.drawable.dmt),
                tooltip = if (state.nowPlayingId != null) "${state.title} · ${state.artist}" else "dmt",
                onAction = { visible = true },
                menu = {
                    Item(stringResource(Res.string.tray_show), onClick = { visible = true })
                    Item(stringResource(Res.string.tray_play_pause), onClick = { vm.onIntent(DmtAction.TogglePlay) })
                    Item(stringResource(Res.string.tray_next), onClick = { vm.onIntent(DmtAction.Next) })
                    Item(stringResource(Res.string.tray_prev), onClick = { vm.onIntent(DmtAction.Prev) })
                    Separator()
                    Item(stringResource(Res.string.tray_quit), onClick = ::quit)
                },
            )
        }

        Window(
            onCloseRequest = {
                // "stop on app close" off + something playing: keep going from the tray.
                if (trayOk && !state.settings.stopOnDismiss && state.isPlaying) {
                    saveWindowState(graph, windowState)
                    visible = false
                } else {
                    quit()
                }
            },
            visible = visible,
            state = windowState,
            title = if (state.nowPlayingId != null && state.isPlaying) "${state.title} · ${state.artist} — dmt" else "dmt",
            icon = painterResource(Res.drawable.dmt),
            onPreviewKeyEvent = { event ->
                handleShortcut(event, state.expanded, backDispatcher, uiCommands) { vm.onIntent(it) }
            },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(380, 520)
                raise.collect {
                    window.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
            DMTTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val base = LocalDensity.current
                        val zoom = uiZoom(state.settings.uiScale, maxHeight.value)
                        CompositionLocalProvider(
                            LocalDensity provides Density(base.density * zoom, base.fontScale),
                            LocalWindowDpSize provides DpSize(maxWidth / zoom, maxHeight / zoom),
                            LocalBackDispatcher provides backDispatcher,
                            LocalUiCommands provides uiCommands,
                            LocalSpectrum provides graph.output::currentSpectrum,
                        ) {
                            AppContent(graph)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppContent(graph: AppGraph) {
    val vm = graph.viewModel
    val state by vm.state.collectAsState()
    when {
        !state.settingsLoaded -> Unit
        !state.settings.setupDone -> SetupScreen(state = state, dispatch = vm::onIntent)
        else -> DmtScreen(state = state, dispatch = vm::onIntent, art = vm::homeArt)
    }
}

private fun handleShortcut(
    event: KeyEvent,
    expanded: Boolean,
    back: BackDispatcher,
    ui: UiCommands,
    dispatch: (DmtAction) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    // media keys work everywhere
    when (event.key) {
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { dispatch(DmtAction.TogglePlay); return true }
        Key.MediaNext -> { dispatch(DmtAction.Next); return true }
        Key.MediaPrevious -> { dispatch(DmtAction.Prev); return true }
        Key.Escape -> return back.onBack() || (expanded.also { if (it) dispatch(DmtAction.Expand(false)) })
    }
    if (TextFocus.active || event.isAltPressed) return false
    val ctrl = event.isCtrlPressed
    val step = if (event.isShiftPressed) SEEK_BIG_STEP_MS else SEEK_STEP_MS
    when (event.key) {
        Key.Spacebar -> dispatch(DmtAction.TogglePlay)
        Key.DirectionRight -> if (ctrl) dispatch(DmtAction.Next) else dispatch(DmtAction.SeekBy(step))
        Key.DirectionLeft -> if (ctrl) dispatch(DmtAction.Prev) else dispatch(DmtAction.SeekBy(-step))
        Key.N -> dispatch(DmtAction.Next)
        Key.P -> dispatch(DmtAction.Prev)
        Key.S -> dispatch(DmtAction.ToggleShuffle)
        Key.R -> dispatch(DmtAction.CycleRepeat)
        Key.F, Key.Enter -> dispatch(DmtAction.Expand(!expanded))
        Key.Plus, Key.Equals, Key.NumPadAdd -> dispatch(if (ctrl) DmtAction.Zoom(1) else DmtAction.Volume(0.05f))
        Key.Minus, Key.NumPadSubtract -> dispatch(if (ctrl) DmtAction.Zoom(-1) else DmtAction.Volume(-0.05f))
        Key.Zero, Key.NumPad0 -> if (ctrl) dispatch(DmtAction.Zoom(0)) else return false
        Key.Slash -> ui.send(UiCommand.Search)
        Key.H -> ui.send(UiCommand.Home)
        Key.One -> ui.send(UiCommand.Library(DmtView.LIBRARY))
        Key.Two -> ui.send(UiCommand.Library(DmtView.ALBUMS))
        Key.Three -> ui.send(UiCommand.Library(DmtView.ARTISTS))
        Key.Four -> ui.send(UiCommand.Library(DmtView.FOLDERS))
        Key.Five -> ui.send(UiCommand.Library(DmtView.PLAYLISTS))
        Key.Comma -> if (ctrl) ui.send(UiCommand.Config) else return false
        else -> return false
    }
    return true
}

@Composable
private fun ApplicationScope.rememberSavedWindowState(graph: AppGraph): WindowState {
    val saved = remember { graph.preferences.windowState()?.split(',')?.mapNotNull { it.toFloatOrNull() } }
    return rememberWindowState(
        placement = if (saved?.getOrNull(4) == 1f) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = if (saved != null && saved.size >= 4) {
            WindowPosition(saved[0].dp, saved[1].dp)
        } else {
            WindowPosition.PlatformDefault
        },
        size = if (saved != null && saved.size >= 4) DpSize(saved[2].dp, saved[3].dp) else DpSize(1120.dp, 720.dp),
    )
}

private fun saveWindowState(graph: AppGraph, state: WindowState) {
    val pos = state.position
    val x = if (pos is WindowPosition.Absolute) pos.x.value else 0f
    val y = if (pos is WindowPosition.Absolute) pos.y.value else 0f
    val maximized = if (state.placement == WindowPlacement.Maximized) 1 else 0
    val value = "$x,$y,${state.size.width.value},${state.size.height.value},$maximized"
    runCatching { runBlocking { graph.preferences.saveWindowState(value) } }
}

/** So GNOME/KDE match the window to dmt.desktop (StartupWMClass=dmt) instead of "MainKt". */
private fun setLinuxWmClass() {
    if (!Platform.isLinux) return
    runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(toolkit, "dmt")
    }
}
