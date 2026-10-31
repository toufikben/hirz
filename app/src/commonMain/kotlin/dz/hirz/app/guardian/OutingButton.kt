package dz.hirz.app.guardian

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dz.hirz.app.api.HirzClient
import dz.hirz.shared.api.OutingPing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * "We're going out" (design §12.2 LEASH_BREAK): starts a 3-hour outing and, while this screen is open, sends this
 * phone's position every 15 s. If the phone stops sending, the server raises nothing (no alarm from a silent phone).
 */
@OptIn(ExperimentalTime::class)
@Composable
fun OutingButton(client: HirzClient, assetId: String) {
    val lang = currentLang()
    val scope = rememberCoroutineScope()
    var outing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(outing) {
        val id = outing ?: return@LaunchedEffect
        while (true) {
            currentLocation()?.let { (at, acc) ->
                runCatching { client.pingOuting(id, OutingPing(at, acc ?: 30.0, Clock.System.now().toEpochMilliseconds())) }
            }
            delay(15_000)
        }
    }
    val current = outing
    if (current == null) OutlinedButton({ scope.launch { runCatching { client.startOuting(assetId, 3) }.onSuccess { outing = it.id } } }) {
        Text(when (lang) { "fr" -> "On sort ensemble (3 h)"; "en" -> "We're going out (3 h)"; else -> "سنخرج معًا (3 سا)" })
    } else OutlinedButton({ scope.launch { runCatching { client.endOuting(current) }; outing = null } }) {
        Text(when (lang) { "fr" -> "Fin de la sortie"; "en" -> "End the outing"; else -> "إنهاء الخرجة" })
    }
}
