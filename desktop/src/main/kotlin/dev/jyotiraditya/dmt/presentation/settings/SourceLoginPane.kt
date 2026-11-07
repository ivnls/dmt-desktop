package dev.jyotiraditya.dmt.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.jyotiraditya.dmt.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.PluralStringResource
import dev.jyotiraditya.dmt.core.common.Caption
import dev.jyotiraditya.dmt.core.common.TuiKey
import dev.jyotiraditya.dmt.domain.model.SourceMode
import dev.jyotiraditya.dmt.presentation.player.DmtAction
import dev.jyotiraditya.dmt.presentation.player.DmtView
import dev.jyotiraditya.dmt.ui.theme.TuiAccent
import dev.jyotiraditya.dmt.ui.theme.TuiDim
import dev.jyotiraditya.dmt.ui.theme.TuiFg
import dev.jyotiraditya.dmt.ui.theme.TuiLine
import dev.jyotiraditya.dmt.core.common.trackTextFocus
import dev.jyotiraditya.dmt.data.remote.jellyfin.ServerUrlProblem
import dev.jyotiraditya.dmt.data.remote.jellyfin.ServerUrls
import dev.jyotiraditya.dmt.ui.theme.TuiRed

@Composable
fun SourceLoginPane(mode: SourceMode, dispatch: (DmtAction) -> Unit) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showMissing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<StringResource?>(null) }
    // plain http is only accepted on the local network, and only after a second, explicit press
    var httpConfirmedFor by remember { mutableStateOf<String?>(null) }

    val connect = {
        val server = ServerUrls.normalize(url)
        problem = null
        when {
            url.isBlank() || username.isBlank() -> showMissing = true
            ServerUrls.problem(server) == ServerUrlProblem.INVALID -> problem = Res.string.source_url_invalid
            ServerUrls.problem(server) == ServerUrlProblem.INSECURE_PUBLIC -> problem = Res.string.source_http_public
            ServerUrls.isPlainHttp(server) && httpConfirmedFor != server -> {
                showMissing = false
                httpConfirmedFor = server
                problem = Res.string.source_http_local
            }

            else -> {
                showMissing = false
                dispatch(DmtAction.SourceLogin(mode, server, username.trim(), password))
            }
        }
    }

    Column {
        Caption(stringResource(Res.string.source_login_title, mode.label))

        LoginField(
            label = stringResource(Res.string.source_url_label),
            value = url,
            hint = stringResource(Res.string.source_url_hint),
            onValueChange = {
                url = it
                httpConfirmedFor = null
                problem = null
            },
        )
        LoginField(
            label = stringResource(Res.string.source_user_label),
            value = username,
            hint = stringResource(Res.string.source_user_hint),
            onValueChange = { username = it },
        )
        LoginField(
            label = stringResource(Res.string.source_pass_label),
            value = password,
            hint = stringResource(Res.string.source_pass_hint),
            onValueChange = { password = it },
            keyboardType = KeyboardType.Password,
            mask = true,
        )

        Row(modifier = Modifier.padding(top = 18.dp)) {
            TuiKey(
                label = "[ ${stringResource(Res.string.source_login_cancel)} ]",
                onClick = { dispatch(DmtAction.Show(DmtView.SOURCES)) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            TuiKey(
                label = "[ ${stringResource(Res.string.source_connect)} ]",
                bright = true,
                onClick = connect,
            )
        }

        problem?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.labelSmall,
                color = if (it == Res.string.source_http_local) TuiAccent else TuiRed,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (showMissing) {
            Text(
                text = stringResource(Res.string.source_login_missing),
                style = MaterialTheme.typography.labelSmall,
                color = TuiAccent,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun LoginField(
    label: String,
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    mask: Boolean = false,
) {
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            Text(
                text = label.padEnd(6),
                style = MaterialTheme.typography.labelMedium,
                color = TuiDim,
            )
            Text(
                text = " > ",
                style = MaterialTheme.typography.bodyLarge,
                color = TuiAccent,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = TuiFg,
                    fontFeatureSettings = if (mask) "calt off" else null,
                ),
                cursorBrush = SolidColor(TuiAccent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (mask) {
                    PasswordVisualTransformation('*')
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier.weight(1f).trackTextFocus(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TuiDim.copy(alpha = 0.55f),
                        )
                    }
                    inner()
                },
            )
        }
        HorizontalDivider(color = TuiLine)
    }
}
