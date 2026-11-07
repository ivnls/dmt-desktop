package dev.jyotiraditya.dmt.core.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import dev.jyotiraditya.dmt.presentation.player.DmtView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Replaces androidx.activity's BackHandler: Esc (and the mouse back button) walk this stack. */
class BackDispatcher {
    internal class Callback {
        var enabled = true
        var onBack: () -> Unit = {}
    }

    private val callbacks = mutableStateListOf<Callback>()

    internal fun register(callback: Callback) {
        callbacks += callback
    }

    internal fun unregister(callback: Callback) {
        callbacks -= callback
    }

    fun onBack(): Boolean {
        val callback = callbacks.lastOrNull { it.enabled } ?: return false
        callback.onBack()
        return true
    }
}

val LocalBackDispatcher = staticCompositionLocalOf { BackDispatcher() }

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val dispatcher = LocalBackDispatcher.current
    val currentOnBack by rememberUpdatedState(onBack)
    val callback = remember { BackDispatcher.Callback() }
    callback.enabled = enabled
    callback.onBack = { currentOnBack() }
    DisposableEffect(dispatcher, callback) {
        dispatcher.register(callback)
        onDispose { dispatcher.unregister(callback) }
    }
}

/** Keyboard shortcuts that need the screen's navigation state rather than the view model. */
sealed interface UiCommand {
    data object Search : UiCommand
    data object Home : UiCommand
    data class Library(val view: DmtView) : UiCommand
    data object Config : UiCommand
    data object Sources : UiCommand
}

class UiCommands {
    private val flow = MutableSharedFlow<UiCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<UiCommand> = flow.asSharedFlow()
    fun send(command: UiCommand) {
        flow.tryEmit(command)
    }
}

val LocalUiCommands = staticCompositionLocalOf { UiCommands() }

/** Lets global shortcuts (space, n, p, ...) stand down while the user is typing. */
object TextFocus {
    var focused by mutableIntStateOf(0)
        internal set

    val active: Boolean get() = focused > 0
}

fun Modifier.trackTextFocus(): Modifier {
    var had = false
    return onFocusChanged { state ->
        if (state.isFocused != had) {
            had = state.isFocused
            TextFocus.focused = (TextFocus.focused + if (had) 1 else -1).coerceAtLeast(0)
        }
    }
}
