package dz.hirz.app

import dz.hirz.app.api.HirzClient
import dz.hirz.app.state.DevSettings
import dz.hirz.app.state.KeyValueStore
import dz.hirz.app.state.ModeStore
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.StateFlow

/** Sign-in as the platform provides it (Firebase + Google on Android). */
interface AuthPort {
    val signedIn: StateFlow<Boolean>
    suspend fun signIn(): Result<Unit>
    suspend fun signOut()
    suspend fun idToken(forceRefresh: Boolean): String?
}

/** Everything the shared UI needs from the platform. */
class AppEnvironment(
    val kv: KeyValueStore,
    val auth: AuthPort,
    val companion: CompanionPort,
    val gateway: GatewayPort,
    defaultBaseUrl: String,
    private val engineFactory: () -> HttpClientEngine,
) {
    val modes = ModeStore(kv)
    val settings = DevSettings(kv, defaultBaseUrl)

    fun client(baseUrl: String): HirzClient = HirzClient(baseUrl, auth::idToken, engineFactory())
}
