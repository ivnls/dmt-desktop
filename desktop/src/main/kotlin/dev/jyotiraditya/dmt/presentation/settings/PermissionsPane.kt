package dev.jyotiraditya.dmt.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jyotiraditya.dmt.core.common.TuiKey
import dev.jyotiraditya.dmt.domain.model.DmtSettings
import dev.jyotiraditya.dmt.domain.model.displayFolder
import dev.jyotiraditya.dmt.platform.FolderPicker
import dev.jyotiraditya.dmt.platform.normalPath
import dev.jyotiraditya.dmt.presentation.player.DmtAction
import dev.jyotiraditya.dmt.presentation.player.DmtState
import dev.jyotiraditya.dmt.resources.*
import dev.jyotiraditya.dmt.ui.theme.TuiAccent
import dev.jyotiraditya.dmt.ui.theme.TuiDim
import dev.jyotiraditya.dmt.ui.theme.TuiFaint
import dev.jyotiraditya.dmt.ui.theme.TuiFg
import dev.jyotiraditya.dmt.ui.theme.TuiLine
import dev.jyotiraditya.dmt.ui.theme.TuiRed
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * Desktop take on the android "permissions" page: there is no media store to grant, so this is
 * where you tell dmt which folders hold your music.
 */
@Composable
fun PermissionsPane(state: DmtState, dispatch: (DmtAction) -> Unit) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        MusicFolders(state.settings, dispatch)
        Text(
            text = stringResource(Res.string.folders_hint),
            style = MaterialTheme.typography.labelSmall,
            color = TuiFaint,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
fun MusicFolders(settings: DmtSettings, dispatch: (DmtAction) -> Unit) {
    val scope = rememberCoroutineScope()

    if (settings.musicFolders.isEmpty()) {
        Text(
            text = stringResource(Res.string.folders_empty),
            style = MaterialTheme.typography.labelMedium,
            color = TuiDim,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        HorizontalDivider(color = TuiLine)
    }
    settings.musicFolders.forEach { path ->
        FolderRow(
            path = path,
            exists = remember(path) { File(path).isDirectory },
            onRemove = {
                dispatch(DmtAction.Config(settings.copy(musicFolders = settings.musicFolders - path)))
            },
        )
    }
    TuiKey(
        label = stringResource(Res.string.folders_add),
        bright = true,
        modifier = Modifier.padding(top = 12.dp),
    ) {
        scope.launch {
            val start = settings.musicFolders.lastOrNull()?.let(::File)?.parentFile
            val picked = FolderPicker.pick(getString(Res.string.folders_pick), start) ?: return@launch
            val path = picked.normalPath()
            if (path !in settings.musicFolders) {
                dispatch(DmtAction.Config(settings.copy(musicFolders = settings.musicFolders + path)))
            }
        }
    }
}

@Composable
private fun FolderRow(path: String, exists: Boolean, onRemove: () -> Unit) {
    val mark = if (exists) "[x] " else "[!] "
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = if (exists) TuiAccent else TuiRed)) { append(mark) }
                        withStyle(SpanStyle(color = TuiFg)) { append(path.displayFolder()) }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!exists) {
                    Text(
                        text = stringResource(Res.string.folder_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = TuiDim,
                        modifier = Modifier.padding(start = markWidth(mark), top = 2.dp),
                    )
                }
            }
            TuiKey(label = stringResource(Res.string.folders_remove), onClick = onRemove)
        }
        HorizontalDivider(color = TuiLine)
    }
}

@Composable
fun markWidth(mark: String): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    return remember(mark, style, density) {
        with(density) { measurer.measure(mark, style).size.width.toDp() }
    }
}
