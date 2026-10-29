package dz.hirz.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dz.hirz.app.companion.CompanionRoot
import dz.hirz.app.generated.resources.Res
import dz.hirz.app.generated.resources.app_name
import dz.hirz.app.generated.resources.back
import dz.hirz.app.generated.resources.base_url
import dz.hirz.app.generated.resources.choose_mode
import dz.hirz.app.generated.resources.coming_soon
import dz.hirz.app.generated.resources.mode_companion
import dz.hirz.app.generated.resources.mode_guardian
import dz.hirz.app.generated.resources.mode_sms_gateway
import dz.hirz.app.generated.resources.save
import dz.hirz.app.generated.resources.settings
import dz.hirz.app.generated.resources.sign_in_failed
import dz.hirz.app.generated.resources.sign_in_google
import dz.hirz.app.generated.resources.tagline
import dz.hirz.app.guardian.GuardianRoot
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The three ways one install can run (design §6). */
enum class AppMode { GUARDIAN, COMPANION, SMS_GATEWAY }

val AppMode.label: StringResource
    get() = when (this) {
        AppMode.GUARDIAN -> Res.string.mode_guardian
        AppMode.COMPANION -> Res.string.mode_companion
        AppMode.SMS_GATEWAY -> Res.string.mode_sms_gateway
    }

@Composable
fun App(env: AppEnvironment) {
    val mode by env.modes.mode.collectAsState(initial = null)
    val baseUrl by env.settings.baseUrl.collectAsState(initial = null)
    var showSettings by remember { mutableStateOf(false) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            val url = baseUrl
            when {
                url == null -> Unit
                showSettings -> SettingsScreen(env, url) { showSettings = false }
                mode == null -> ModePicker(env) { showSettings = true }
                mode == AppMode.GUARDIAN -> GuardianGate(env, url)
                mode == AppMode.COMPANION -> CompanionRoot(env, url)
                mode == AppMode.SMS_GATEWAY -> dz.hirz.app.gateway.GatewayRoot(env, url)
                else -> ModePicker(env) { showSettings = true }
            }
        }
    }
}

@Composable
private fun ModePicker(env: AppEnvironment, openSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(Res.string.tagline), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(Res.string.choose_mode), style = MaterialTheme.typography.titleMedium)
        Button({ scope.launch { env.modes.set(AppMode.GUARDIAN) } }, Modifier.fillMaxWidth()) { Text(stringResource(AppMode.GUARDIAN.label)) }
        Button({ scope.launch { env.modes.set(AppMode.COMPANION) } }, Modifier.fillMaxWidth()) { Text(stringResource(AppMode.COMPANION.label)) }
        if (env.gateway.supported) OutlinedButton({ scope.launch { env.modes.set(AppMode.SMS_GATEWAY) } }, Modifier.fillMaxWidth()) {
            Text(stringResource(AppMode.SMS_GATEWAY.label))
        }
        TextButton(openSettings) { Text(stringResource(Res.string.settings)) }
    }
}

@Composable
private fun GuardianGate(env: AppEnvironment, baseUrl: String) {
    val signedIn by env.auth.signedIn.collectAsState()
    if (signedIn) {
        GuardianRoot(env, remember(baseUrl) { env.client(baseUrl) })
        return
    }
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Button({ scope.launch { failed = env.auth.signIn().isFailure } }, Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.sign_in_google))
        }
        if (failed) Text(stringResource(Res.string.sign_in_failed), color = MaterialTheme.colorScheme.error)
        TextButton({ scope.launch { env.modes.set(null) } }) { Text(stringResource(Res.string.back)) }
    }
}

@Composable
private fun SettingsScreen(env: AppEnvironment, current: String, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(current) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.settings), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(text, { text = it }, label = { Text(stringResource(Res.string.base_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ scope.launch { env.settings.setBaseUrl(text); close() } }) { Text(stringResource(Res.string.save)) }
        TextButton(close) { Text(stringResource(Res.string.back)) }
    }
}
