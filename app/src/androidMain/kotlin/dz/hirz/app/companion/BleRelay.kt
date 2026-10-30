package dz.hirz.app.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dz.hirz.app.api.HirzClient
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.RelayBatch
import dz.hirz.shared.api.RelayFix
import dz.hirz.shared.ble.BleFrames

private val EDDYSTONE = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")
private const val APPLE = 0x004C

/**
 * The relay on Android: scans for open beacon frames during [RelayDuty] windows, keeps the circle's tags in a
 * [RelayBuffer], and uploads them with the phone's last fix. Off unless the person turned it on in the app.
 */
object BleRelay {
    private val buffer = RelayBuffer(emptySet())
    private var scanning = false
    private var knownAtMs = 0L
    @Volatile var lastFix: RelayFix? = null

    fun enabled(context: Context): Boolean = context.getSharedPreferences("hirz_relay", Context.MODE_PRIVATE).getBoolean("on", false)

    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences("hirz_relay", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()

    fun seenRecently(): Int = buffer.size

    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val id = record.getServiceData(EDDYSTONE)?.let(BleFrames::eddystone)
                ?: record.getManufacturerSpecificData(APPLE)?.let(BleFrames::iBeacon) ?: return
            synchronized(buffer) { buffer.offer(id, result.rssi, System.currentTimeMillis()) }
        }
    }

    /** One step of the relay, called every few seconds from the location service. */
    @SuppressLint("MissingPermission")
    suspend fun tick(context: Context, client: HirzClient, sourceToken: String, nowMs: Long) {
        if (!enabled(context) || !permitted(context)) { stop(context); return }
        if (nowMs - knownAtMs > 30 * 60_000L) runCatching { client.relayTags(sourceToken) }.onSuccess { tags ->
            synchronized(buffer) { buffer.setKnown(tags.toSet()) }; knownAtMs = nowMs
        }
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        if (RelayDuty.scanning(nowMs) && !scanning) {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback); scanning = true
        } else if (!RelayDuty.scanning(nowMs) && scanning) {
            scanner.stopScan(callback); scanning = false
        }
        val fix = lastFix ?: return
        val batch = synchronized(buffer) { buffer.nextBatch() }
        if (batch.isEmpty()) return
        runCatching { client.relaySightings(sourceToken, RelayBatch(fix, batch)) }.onSuccess {
            synchronized(buffer) { buffer.acknowledge(batch.size) }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        if (!scanning) return
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
    }

    fun onFix(at: LatLon, accuracyM: Double?, timeMs: Long) { lastFix = RelayFix(at, accuracyM ?: 50.0, timeMs) }
}
