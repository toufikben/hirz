package dz.hirz.app.companion

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dz.hirz.app.AppEnvironment
import dz.hirz.app.generated.resources.Res
import dz.hirz.app.generated.resources.loading
import org.jetbrains.compose.resources.stringResource

/** Companion mode entry point; pairing and background location arrive in Task 13. */
@Composable
fun CompanionRoot(env: AppEnvironment, baseUrl: String) {
    Text(stringResource(Res.string.loading), Modifier.padding(24.dp))
}
