package dz.hirz.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dz.hirz.app.BuildConfig
import dz.hirz.app.MainActivity
import dz.hirz.app.R
import dz.hirz.app.api.HirzClient
import dz.hirz.app.companion.CompanionVault
import dz.hirz.app.companion.CompanionNotifications
import dz.hirz.app.guardian.currentLang
import dz.hirz.app.state.DataStoreKeyValueStore
import dz.hirz.app.state.DevSettings
import dz.hirz.shared.api.ReceiptState
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val ALARM_CHANNEL = "hirz_alarm"

/** A client usable outside the UI (service, worker), with the stored server address and the member's token. */
internal suspend fun backgroundClient(context: Context): HirzClient {
    val baseUrl = DevSettings(DataStoreKeyValueStore(context), BuildConfig.HIRZ_BASE_URL).baseUrl.first()
    return HirzClient(baseUrl, { force -> FirebaseAuth.getInstance().currentUser?.getIdToken(force)?.await()?.token }, OkHttp.create())
}

suspend fun currentFcmToken(): String? = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()

object AlarmNotifier {
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ALARM_CHANNEL) != null) return
        val channel = NotificationChannel(ALARM_CHANNEL, context.getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            // Effective only once the user granted Do-Not-Disturb access; otherwise Android ignores it.
            setBypassDnd(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    fun show(context: Context, push: Push.Incident) {
        ensureChannel(context)
        val (title, body) = AlarmText.of(push, currentLang())
        val intent = Intent(context, AlarmActivity::class.java)
            .putExtra("id", push.incidentId).putExtra("tier", push.tier).putExtra("asset", push.assetName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, push.incidentId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title).setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(push.incidentId.hashCode(), notification)
    }
}

/** Posts a delivered/opened receipt with retries; the server keeps the first timestamp (design §5.5). */
class ReceiptWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val state = ReceiptState.valueOf(inputData.getString("state") ?: return Result.failure())
        return runCatching { backgroundClient(applicationContext).receipt(id, state) }
            .fold({ Result.success() }, { if (runAttemptCount < 10) Result.retry() else Result.failure() })
    }

    companion object {
        fun enqueue(context: Context, incidentId: String, state: ReceiptState) {
            val request = OneTimeWorkRequestBuilder<ReceiptWorker>()
                .setInputData(Data.Builder().putString("id", incidentId).putString("state", state.name).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

class HirzMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        when (val push = PushPayload.parse(message.data)) {
            is Push.Incident -> {
                ReceiptWorker.enqueue(applicationContext, push.incidentId, ReceiptState.DELIVERED)
                if (AlarmPolicy.isAlarm(push)) AlarmNotifier.show(applicationContext, push)
            }
            is Push.CheckIn -> CompanionNotifications.showCheckIn(applicationContext, push.assetName)
            null -> Unit
        }
    }

    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (FirebaseAuth.getInstance().currentUser != null) runCatching { backgroundClient(applicationContext).setPushToken(token) }
            CompanionVault(applicationContext).credentials()?.let { creds ->
                runCatching { backgroundClient(applicationContext).setSourcePushToken(creds.sourceToken, token) }
            }
        }
    }
}

/** Shown over the lock screen for P0/P1. Opening it records "opened" for this member. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val id = intent.getStringExtra("id") ?: return finish()
        val push = Push.Incident(id, intent.getStringExtra("tier") ?: "P1", intent.getStringExtra("asset") ?: "", null)
        ReceiptWorker.enqueue(applicationContext, id, ReceiptState.OPENED)
        val (title, body) = AlarmText.of(push, currentLang())
        setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                        Text(body)
                        Button({
                            CoroutineScope(Dispatchers.IO).launch { runCatching { backgroundClient(applicationContext).claim(id) } }
                            openApp()
                        }, Modifier.fillMaxWidth()) { Text(getString(R.string.alarm_claim)) }
                        OutlinedButton({ openApp() }, Modifier.fillMaxWidth()) { Text(getString(R.string.alarm_details)) }
                    }
                }
            }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
