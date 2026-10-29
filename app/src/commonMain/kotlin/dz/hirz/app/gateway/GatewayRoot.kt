package dz.hirz.app.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dz.hirz.app.AppEnvironment
import dz.hirz.app.guardian.currentLang
import kotlinx.coroutines.launch

private fun t(lang: String, ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }

/**
 * SMS-gateway mode (design §12.3.3): a family phone with a SIM sends the circle's alerts to contacts without the
 * app and relays their replies. Pairing is by a one-time code from the circle owner.
 */
@Composable
fun GatewayRoot(env: AppEnvironment, baseUrl: String) {
    val scope = rememberCoroutineScope()
    val lang = currentLang()
    val client = remember(baseUrl) { env.client(baseUrl) }
    val paired by env.gateway.pairedCircle.collectAsState()
    var code by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t(lang, "بوابة الرسائل", "Passerelle SMS", "SMS gateway"), style = MaterialTheme.typography.headlineSmall)
        if (!env.gateway.supported) {
            Text(t(lang, "هذه النسخة لا ترسل الرسائل. ثبّت نسخة «البوابة».", "Cette version n'envoie pas de SMS. Installez la version « passerelle ».",
                "This build cannot send SMS. Install the gateway build."))
        } else if (paired == null) {
            Text(t(lang, "اطلب من مالك الدائرة رمز البوابة.", "Demandez le code passerelle au propriétaire du cercle.", "Ask the circle owner for a gateway code."))
            OutlinedTextField(code, { code = it.uppercase().take(12) }, label = { Text(t(lang, "الرمز", "Code", "Code")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button({ scope.launch { failed = env.gateway.pair(client, code.trim()).isFailure; if (!failed) env.gateway.start() } }, enabled = code.length >= 8) {
                Text(t(lang, "ربط", "Associer", "Pair"))
            }
            if (failed) Text(t(lang, "تعذّر الربط", "Échec de l'association", "Pairing failed"), color = MaterialTheme.colorScheme.error)
        } else {
            Text(t(lang, "البوابة تعمل. اترك الهاتف موصولًا بالشاحن وبه رصيد.", "La passerelle fonctionne. Laissez le téléphone branché et avec du crédit.",
                "The gateway is running. Keep the phone charging and with credit."))
            OutlinedButton({ env.gateway.start() }) { Text(t(lang, "إعادة التشغيل", "Redémarrer", "Restart")) }
            TextButton({ env.gateway.unpair() }) { Text(t(lang, "فك الربط", "Dissocier", "Unpair")) }
        }
        TextButton({ scope.launch { env.modes.set(null) } }) { Text(t(lang, "تغيير الوضع", "Changer de mode", "Change mode")) }
    }
}
