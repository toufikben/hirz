package dz.hirz.app.state

import dz.hirz.app.AppMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Small persistent string store; Android uses DataStore, tests and previews use [InMemoryKeyValueStore]. */
interface KeyValueStore {
    fun observe(key: String): Flow<String?>
    suspend fun put(key: String, value: String?)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val state = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun observe(key: String): Flow<String?> = state.map { it[key] }
    override suspend fun put(key: String, value: String?) {
        state.value = if (value == null) state.value - key else state.value + (key to value)
    }
}

/** Which of the three modes this install runs in (design §6). Unknown stored values read as "not chosen". */
class ModeStore(private val kv: KeyValueStore) {
    val mode: Flow<AppMode?> = kv.observe(KEY).map { stored -> AppMode.entries.firstOrNull { it.name == stored } }
    suspend fun set(mode: AppMode?) = kv.put(KEY, mode?.name)

    companion object {
        const val KEY = "mode"
    }
}

/** Developer settings: the Core base URL (a cloudflared tunnel during the P1 trial). */
class DevSettings(private val kv: KeyValueStore, private val defaultBaseUrl: String) {
    val baseUrl: Flow<String> = kv.observe(KEY).map { it ?: defaultBaseUrl }
    suspend fun setBaseUrl(url: String) = kv.put(KEY, url.trim().trimEnd('/'))

    companion object {
        const val KEY = "base_url"
    }
}
