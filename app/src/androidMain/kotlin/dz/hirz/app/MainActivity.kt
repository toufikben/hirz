package dz.hirz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dz.hirz.app.auth.FirebaseAuthPort
import dz.hirz.app.state.DataStoreKeyValueStore
import io.ktor.client.engine.okhttp.OkHttp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val env = AppEnvironment(
            kv = DataStoreKeyValueStore(applicationContext),
            auth = FirebaseAuthPort { this },
            companion = AndroidCompanionPort(this),
            defaultBaseUrl = BuildConfig.HIRZ_BASE_URL,
            engineFactory = { OkHttp.create() },
        )
        setContent { App(env) }
    }
}
