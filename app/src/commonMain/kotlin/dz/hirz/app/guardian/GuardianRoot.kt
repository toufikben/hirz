package dz.hirz.app.guardian

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dz.hirz.app.AppEnvironment
import dz.hirz.app.api.HirzClient
import dz.hirz.app.generated.resources.Res
import dz.hirz.app.generated.resources.loading
import org.jetbrains.compose.resources.stringResource

/** Guardian/owner mode entry point; the screens arrive in Task 11. */
@Composable
fun GuardianRoot(env: AppEnvironment, client: HirzClient) {
    Text(stringResource(Res.string.loading), Modifier.padding(24.dp))
}
