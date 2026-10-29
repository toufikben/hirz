package dz.hirz.app.state

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.hirzPrefs by preferencesDataStore(name = "hirz")

/** Non-secret settings (mode, server address). Secrets (companion token) live in [CompanionVault]. */
class DataStoreKeyValueStore(private val context: Context) : KeyValueStore {
    override fun observe(key: String): Flow<String?> = context.hirzPrefs.data.map { it[stringPreferencesKey(key)] }
    override suspend fun put(key: String, value: String?) {
        context.hirzPrefs.edit { prefs ->
            if (value == null) prefs.remove(stringPreferencesKey(key)) else prefs[stringPreferencesKey(key)] = value
        }
    }
}
