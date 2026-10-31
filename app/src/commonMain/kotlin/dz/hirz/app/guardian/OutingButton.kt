package dz.hirz.app.guardian

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dz.hirz.app.api.HirzClient
import dz.hirz.shared.api.OutingPing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The accompanied outings this phone is running (design §12.2 LEASH_BREAK), one per asset. It outlives the screen that
 * started it: every 15 s it sends this phone's position and the BLE tags it hears, until the outing is ended.
 */
@OptIn(ExperimentalTime::class)
object OutingSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Pair<String, Job>>()
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val assets: StateFlow<Set<String>> = active

    fun start(client: HirzClient, assetId: String) {
        if (assetId in jobs) return
        scope.launch {
            val outing = runCatching { client.startOuting(assetId, 3) }.getOrNull() ?: return@launch
            val job = scope.launch {
                while (isActive) {
                    val tags = runCatching { nearbyTags() }.getOrDefault(emptyList())
                    currentLocation()?.let { (at, acc) ->
                        runCatching { client.pingOuting(outing.id, OutingPing(at, acc ?: 30.0, Clock.System.now().toEpochMilliseconds(), tags)) }
                    }
                    delay(15_000)
                }
            }
            jobs[assetId] = outing.id to job
            active.value = jobs.keys.toSet()
        }
    }

    fun end(client: HirzClient, assetId: String) {
        val (outing, job) = jobs.remove(assetId) ?: return
        job.cancel()
        active.value = jobs.keys.toSet()
        scope.launch { runCatching { client.endOuting(outing) } }
    }
}

/** "We're going out": a 3-hour outing for [assetId]; the button ends it again. */
@Composable
fun OutingButton(client: HirzClient, assetId: String) {
    val lang = currentLang()
    val running by OutingSession.assets.collectAsState()
    @Suppress("UNUSED_VARIABLE") val scope = rememberCoroutineScope()
    if (assetId !in running) OutlinedButton({ OutingSession.start(client, assetId) }) {
        Text(when (lang) { "fr" -> "On sort ensemble (3 h)"; "en" -> "We're going out (3 h)"; else -> "سنخرج معًا (3 سا)" })
    } else OutlinedButton({ OutingSession.end(client, assetId) }) {
        Text(when (lang) { "fr" -> "Fin de la sortie"; "en" -> "End the outing"; else -> "إنهاء الخرجة" })
    }
}
