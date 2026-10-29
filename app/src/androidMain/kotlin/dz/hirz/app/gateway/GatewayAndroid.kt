package dz.hirz.app.gateway

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dz.hirz.app.BuildConfig
import dz.hirz.app.GatewayPort
import dz.hirz.app.R
import dz.hirz.app.api.HirzClient
import dz.hirz.app.platformPushToken
import dz.hirz.app.push.backgroundClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** The gateway's secret token, in encrypted preferences. */
class GatewayVault(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "hirz_gateway",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun token(): String? = prefs.getString("token", null)
    fun circleId(): String? = prefs.getString("circle", null)
    fun save(token: String, circleId: String) = prefs.edit().putString("token", token).putString("circle", circleId).apply()
    fun clear() = prefs.edit().clear().apply()
}

private val SMS_PERMISSIONS = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)

fun hasSmsPermissions(context: Context) = SMS_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

class AndroidGatewayPort(private val activity: Activity) : GatewayPort {
    private val vault = GatewayVault(activity.applicationContext)
    override val supported: Boolean = BuildConfig.FLAVOR == "gateway"
    private val circle = MutableStateFlow(vault.circleId())
    override val pairedCircle: StateFlow<String?> = circle

    override suspend fun pair(client: HirzClient, code: String): Result<Unit> = runCatching {
        val r = client.gatewayPair(code, platformPushToken())
        vault.save(r.token, r.circleId)
        circle.value = r.circleId
    }

    override fun start() {
        if (!supported) return
        if (!hasSmsPermissions(activity)) ActivityCompat.requestPermissions(activity, SMS_PERMISSIONS, 41)
        GatewayService.start(activity.applicationContext)
    }

    override fun unpair() {
        activity.stopService(Intent(activity, GatewayService::class.java))
        vault.clear()
        circle.value = null
    }
}

/**
 * Drains the circle's outbox on push and every 60 s, heartbeats every 5 minutes. Each item is sent with
 * `sendMultipartTextMessage`; its result is the union of every part's sent result (design §12.3.3).
 */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingParts = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private var lastHeartbeat: Long? = null

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("part")?.let { pendingParts.remove(it)?.complete(resultCode) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, sentReceiver, IntentFilter(SENT_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (GatewayVault(this).token() == null || !hasSmsPermissions(this)) { stopSelf(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        if (runCatching { ServiceCompat.startForeground(this, 7_010, notification(), type) }.isFailure) { stopSelf(); return START_NOT_STICKY }
        if (loop == null) loop = scope.launch { run() }
        kicks.trySend(Unit)
        return START_STICKY
    }

    private suspend fun run() {
        val client = backgroundClient(this)
        while (scope.isActive) {
            val token = GatewayVault(this).token() ?: break
            val now = System.currentTimeMillis()
            if (GatewaySchedule.heartbeatDue(lastHeartbeat, now) && runCatching { client.gatewayHeartbeat(token) }.isSuccess) lastHeartbeat = now
            runCatching { client.gatewayOutbox(token) }.getOrNull()?.forEach { item ->
                val outcome = send(item.id, item.to, item.body)
                runCatching { client.gatewayResult(token, item.id, outcome.status, outcome.error) }
            }
            withTimeoutOrNull(GatewaySchedule.POLL_MS) { kicks.receive() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun send(id: Long, to: String, body: String): SmsOutcome {
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = sms.divideMessage(body)
        val waits = parts.indices.map { i -> "$id-$i".also { pendingParts[it] = CompletableDeferred() } }
        val intents = ArrayList(waits.mapIndexed { i, key ->
            PendingIntent.getBroadcast(this, (id * 10 + i).toInt(), Intent(SENT_ACTION).setPackage(packageName).putExtra("part", key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        })
        val started = runCatching { sms.sendMultipartTextMessage(to, null, parts, intents, null) }
        if (started.isFailure) { waits.forEach { pendingParts.remove(it) }; return SmsOutcome("failed", "send_exception") }
        val results = waits.map { key -> withTimeoutOrNull(60_000) { pendingParts[key]?.await() } ?: 1 }
        return SmsOutcome.of(results)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL.also { ensureChannel() })
        .setSmallIcon(android.R.drawable.sym_action_email)
        .setContentTitle(getString(R.string.gateway_running))
        .setOngoing(true)
        .build()

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.gateway_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sentReceiver) }
        loop = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "hirz_gateway"
        private const val SENT_ACTION = "dz.hirz.app.SMS_SENT"
        private val kicks = Channel<Unit>(Channel.CONFLATED)
        @Volatile private var loop: kotlinx.coroutines.Job? = null

        fun start(context: Context) {
            if (GatewayVault(context).token() != null && hasSmsPermissions(context)) {
                ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
            }
        }

        /** A `sms_pending` push: fetch the outbox now. */
        fun kick(context: Context) { kicks.trySend(Unit); start(context) }
    }
}

/** Every received SMS goes to the server unfiltered; the server decides whether it is a contact's reply. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val token = GatewayVault(context).token() ?: return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent).map { m ->
            InboundPart(m.originatingAddress.orEmpty(), m.messageBody.orEmpty(), m.timestampMillis)
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = backgroundClient(context.applicationContext)
                GatewayInbox.assemble(parts).forEach { msg -> runCatching { client.gatewayInbox(token, msg.from, msg.body, msg.receivedAtMs) } }
            } finally {
                pending.finish()
            }
        }
    }
}
