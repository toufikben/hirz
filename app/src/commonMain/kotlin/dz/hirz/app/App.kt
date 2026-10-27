package dz.hirz.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dz.hirz.app.generated.resources.Res
import dz.hirz.app.generated.resources.app_name
import dz.hirz.app.generated.resources.mode_companion
import dz.hirz.app.generated.resources.mode_guardian
import dz.hirz.app.generated.resources.mode_sms_gateway
import dz.hirz.app.generated.resources.tagline
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The three ways one install can run (design §6). */
enum class AppMode { GUARDIAN, COMPANION, SMS_GATEWAY }

private val AppMode.label: StringResource
    get() = when (this) {
        AppMode.GUARDIAN -> Res.string.mode_guardian
        AppMode.COMPANION -> Res.string.mode_companion
        AppMode.SMS_GATEWAY -> Res.string.mode_sms_gateway
    }

/** P0 placeholder: name, honest tagline and the mode labels. P1 builds the real screens on top. */
@Composable
fun App(modeOverride: AppMode? = null) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(Res.string.tagline), style = MaterialTheme.typography.bodyLarge)
                (modeOverride?.let(::listOf) ?: AppMode.entries).forEach { mode ->
                    Text(stringResource(mode.label), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
