package dz.hirz.app.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.util.AtomicFile
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dz.hirz.app.MainActivity
import dz.hirz.app.R
import dz.hirz.app.api.HirzApiException
import dz.hirz.app.push.backgroundClient
import dz.hirz.shared.api.CheckInReply
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.signal.FixSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

actual fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

data class CompanionCredentials(val sourceId: String, val sourceToken: String, val pinSalt: String?, val circleName: String, val assetName: String)

/** The companion's secret token, kept in encrypted preferences (Android Keystore master key). */
class CompanionVault(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "hirz_companion",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun credentials(): CompanionCredentials? {
        val id = prefs.getString("sourceId", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        return CompanionCredentials(id, token, prefs.getString("salt", null), prefs.getString("circle", "")!!, prefs.getString("asset", "")!!)
    }

    fun save(c: CompanionCredentials) = prefs.edit()
        .putString("sourceId", c.sourceId).putString("token", c.sourceToken).putString("salt", c.pinSalt)
        .putString("circle", c.circleName).putString("asset", c.assetName).apply()

    fun clear() = prefs.edit().clear().apply()
}

/** One process-wide queue and sender; signals survive restarts in a file (design §6.2). */
object CompanionEngine {
    private val mutex = Mutex()
    private var queue: SignalQueue? = null
    val cadence = CompanionCadence()
    val sosConfirm = SosConfirm()

    /** AtomicFile: a crash mid-write keeps the previous complete queue instead of a truncated one (P1 review #10). */
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "companion-queue.json"))

    private fun load(context: Context): SignalQueue =
        queue ?: SignalQueue.fromJson(runCatching { String(file(context).readFully()) }.getOrNull()).also { queue = it }

    private fun persist(context: Context) {
        val f = file(context)
        val stream = f.startWrite()
        runCatching { stream.write(load(context).toJson().toByteArray()); f.finishWrite(stream) }.onFailure { f.failWrite(stream) }
    }

    suspend fun enqueue(context: Context, s: IngestSignal) = mutex.withLock {
        load(context).add(s)
        persist(context)
    }

    /** Uploads queued signals batch by batch; stops at the first failure and keeps the rest for later. */
    suspend fun flush(context: Context) {
        val creds = CompanionVault(context).credentials() ?: return
        val client = backgroundClient(context)
        while (true) {
            val batch = mutex.withLock { load(context).nextBatch() }
            if (batch.isEmpty()) return
            val attempt = runCatching { client.ingest(creds.sourceToken, IngestBatch(batch)) }
            val rejectedForGood = (attempt.exceptionOrNull() as? HirzApiException)?.status in setOf(400, 413, 422)
            if (attempt.isFailure && !rejectedForGood) return
            // A batch the server rejects as malformed is dropped, or it would block every later signal (P1 review #15).
            attempt.getOrNull()?.let { cadence.serverSaysHighRate(it.highRate, System.currentTimeMillis()) }
            mutex.withLock {
                load(context).acknowledge(batch.map { it.clientId }.toSet())
                persist(context)
            }
        }
    }

    suspend fun sendNow(context: Context, s: IngestSignal) {
        enqueue(context, s)
        flush(context)
    }

    fun battery(context: Context): Pair<Int?, Boolean?> {
        val bm = context.getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } to bm.isCharging
    }
}

object CompanionNotifications {
    const val CHANNEL = "hirz_companion"
    const val CHECKIN_ID = 7_002
    const val SOS_CONFIRM_ID = 7_003

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.companion_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(context, CompanionActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    /** The permanent notice that location is shared; it carries the SOS action (design §6.2, §8.5). */
    fun sharing(context: Context): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.companion_sharing))
            .setContentText(CompanionVault(context).credentials()?.circleName.orEmpty())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.sos_action), action(context, CompanionActionReceiver.SOS, 1))
            .build()
    }

    /** Second step of the notification SOS: one more tap within 10 s sends it. */
    fun showSosConfirm(context: Context) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.sos_confirm))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(10_000)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.sos_confirm_action), action(context, CompanionActionReceiver.SOS, 4))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(SOS_CONFIRM_ID, n)
    }

    fun showCheckIn(context: Context, @Suppress("UNUSED_PARAMETER") assetName: String) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.checkin_title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.checkin_yes), action(context, CompanionActionReceiver.CHECKIN_OK, 2))
            .addAction(0, context.getString(R.string.checkin_no), action(context, CompanionActionReceiver.CHECKIN_HELP, 3))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(CHECKIN_ID, n)
    }
}

/** Notification actions: SOS from the permanent notice, and check-in answers. */
class CompanionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    // The notification action asks for one confirming tap first (P1 minor #19).
                    SOS -> when (CompanionEngine.sosConfirm.tap(System.currentTimeMillis())) {
                        SosConfirm.Step.ARMED -> CompanionNotifications.showSosConfirm(app)
                        SosConfirm.Step.SEND -> {
                            app.getSystemService(NotificationManager::class.java).cancel(CompanionNotifications.SOS_CONFIRM_ID)
                            CompanionEngine.cadence.sosPressed(System.currentTimeMillis())
                            CompanionEngine.sendNow(app, CompanionSignals.sos())
                        }
                    }
                    CHECKIN_OK -> CompanionEngine.sendNow(app, CompanionSignals.checkIn(CheckInReply.OK))
                    CHECKIN_HELP -> {
                        CompanionEngine.cadence.sosPressed(System.currentTimeMillis())
                        CompanionEngine.sendNow(app, CompanionSignals.sos())
                    }
                }
                app.getSystemService(NotificationManager::class.java).cancel(CompanionNotifications.CHECKIN_ID)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val SOS = "dz.hirz.app.SOS"
        const val CHECKIN_OK = "dz.hirz.app.CHECKIN_OK"
        const val CHECKIN_HELP = "dz.hirz.app.CHECKIN_HELP"
    }
}

/**
 * Foreground location service. Fixes come from the fused provider (or the platform provider on phones without
 * Google services); a heartbeat goes out every 60 s even without a fix, so "alive" and "dead" stay distinct.
 */
class LocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var relayLoop: Job? = null
    private var fused: LocationCallback? = null
    private var platformListener: android.location.LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (CompanionVault(this).credentials() == null || !hasLocationPermission(this)) { stopSelf(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val started = runCatching { ServiceCompat.startForeground(this, 7_001, CompanionNotifications.sharing(this), type) }
        if (started.isFailure) { stopSelf(); return START_NOT_STICKY }
        if (loop == null) loop = scope.launch { run() }
        if (relayLoop == null) relayLoop = scope.launch { relay() }
        return START_STICKY
    }

    private suspend fun run() {
        var subscribedMs = -1L
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val interval = CompanionEngine.cadence.intervalMs(now)
            if (interval != subscribedMs && subscribe(interval)) subscribedMs = interval
            val (battery, charging) = CompanionEngine.battery(this)
            CompanionEngine.sendNow(this, CompanionSignals.heartbeat(battery, charging, now))
            delay(interval)
        }
    }

    /** The shepherd relay, every 5 s: scans in its duty windows, uploads what it heard with the last fix. */
    private suspend fun relay() {
        val client = dz.hirz.app.push.backgroundClient(this)
        while (scope.isActive) {
            CompanionVault(this).credentials()?.let { creds -> runCatching { BleRelay.tick(this, client, creds.sourceToken, System.currentTimeMillis()) } }
            delay(5_000)
        }
    }

    /** False when location is not (yet) granted, so the loop retries instead of sending heartbeats only (P1 review #6). */
    @SuppressLint("MissingPermission")
    private fun subscribe(intervalMs: Long): Boolean {
        if (!hasLocationPermission(this)) return false
        unsubscribe()
        val hasPlay = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        if (hasPlay) {
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) { result.locations.forEach(::onFix) }
            }
            fused = callback
            LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs).setMinUpdateIntervalMillis(intervalMs / 2).build(),
                callback, Looper.getMainLooper(),
            )
        } else {
            val listener = android.location.LocationListener { onFix(it) }
            platformListener = listener
            getSystemService(LocationManager::class.java).requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
        }
        return true
    }

    private fun unsubscribe() {
        fused?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) }
        platformListener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        fused = null; platformListener = null
    }

    private fun onFix(l: Location) {
        BleRelay.onFix(LatLon(l.latitude, l.longitude), l.accuracy.toDouble().takeIf { l.hasAccuracy() }, l.time)
        val (battery, charging) = CompanionEngine.battery(this)
        val source = if (l.provider == LocationManager.GPS_PROVIDER || l.provider == "fused") FixSource.GNSS else FixSource.WIFI
        val signal = CompanionSignals.fix(
            LatLon(l.latitude, l.longitude), l.accuracy.toDouble().takeIf { l.hasAccuracy() }, l.time, source,
            l.speed.takeIf { l.hasSpeed() }?.times(3.6), l.bearing.toDouble().takeIf { l.hasBearing() }, battery, charging,
        )
        scope.launch { CompanionEngine.sendNow(this@LocationService, signal) }
    }

    override fun onDestroy() {
        unsubscribe()
        BleRelay.stop(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Starts only with location permission: Android 14 kills a location FGS started without it (P1 review #1). */
        fun start(context: Context) {
            if (hasLocationPermission(context)) ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
        }
    }
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Restarts sharing after a reboot when this phone is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (CompanionVault(context).credentials() != null) LocationService.start(context)
        // The gateway build restarts its SMS relay too; in the Play build there is never a gateway token.
        dz.hirz.app.gateway.GatewayService.start(context)
    }
}
