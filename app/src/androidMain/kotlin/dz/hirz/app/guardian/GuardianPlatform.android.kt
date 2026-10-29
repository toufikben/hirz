package dz.hirz.app.guardian

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dz.hirz.app.HirzApplication
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.SheetDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val app: Context get() = HirzApplication.instance

actual suspend fun currentLocation(): Pair<LatLon, Double?>? {
    if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) return null
    return runCatching {
        val loc = LocationServices.getFusedLocationProviderClient(app)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await() ?: return null
        LatLon(loc.latitude, loc.longitude) to (if (loc.hasAccuracy()) loc.accuracy.toDouble() else null)
    }.getOrNull()
}

actual fun appInForeground(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}

actual fun shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

actual fun composeSms(to: String?, text: String) {
    val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + (to ?: ""))).putExtra("sms_body", text)
    app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

actual fun copyText(text: String) {
    (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Hirz", text))
}

actual fun saveDownload(name: String, mime: String, bytes: ByteArray): Boolean = runCatching {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return shareFile(name, mime, bytes)
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hirz")
    }
    val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
    true
}.getOrDefault(false)

/** Android 8–9 have no MediaStore Downloads: the file goes through the share sheet (save to Drive, email, Bluetooth…). */
private fun shareFile(name: String, mime: String, bytes: ByteArray): Boolean = runCatching {
    val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
    val file = java.io.File(dir, name).apply { writeBytes(bytes) }
    val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    app.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    true
}.getOrDefault(false)

actual suspend fun confirmDeviceCredential(): Boolean {
    val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    if (!keyguard.isDeviceSecure) return true
    val result = CompletableDeferred<Boolean>()
    CredentialActivity.pending = result
    app.startActivity(Intent(app, CredentialActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return result.await()
}

/** Hosts the system lock-screen check and reports the outcome to [confirmDeviceCredential]. */
class CredentialActivity : Activity() {
    companion object { @Volatile var pending: CompletableDeferred<Boolean>? = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent(null, null)
        if (intent == null) finishWith(true) else startActivityForResult(intent, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        finishWith(resultCode == RESULT_OK)
    }

    private fun finishWith(ok: Boolean) {
        pending?.complete(ok); pending = null
        finish()
    }
}

actual fun sheetPdf(sheet: SheetDto, lang: String): ByteArray {
    fun t(ar: String, fr: String, en: String) = when (lang) { "ar" -> ar; "fr" -> fr; else -> en }
    val lines = buildList {
        add("Hirz — ${sheet.assetName}")
        sheet.todayClothes?.let { add(t("اللباس اليوم: $it", "Vêtements aujourd'hui : $it", "Wearing today: $it")) }
        sheet.lastFix?.let { f ->
            // No coordinates on paper: the police pack carries them, with hashes (design §5.8).
            add(t("آخر موقع", "Dernière position", "Last position") + ": ${formatClock(f.fixTimeMs)} ±${f.accuracyM?.toInt() ?: "?"} m")
        }
        sheet.incident.reasons.forEach { add("• " + ReasonText.render(it, lang)) }
        add(t("الأجهزة", "Appareils", "Devices") + ": " + sheet.sources.joinToString { listOfNotNull(it.kind, it.model, it.imei).joinToString(" ") })
        add(t("أرقام الطوارئ", "Numéros d'urgence", "Emergency numbers") + ": " + sheet.emergencyNumbers.joinToString { it.number })
        add(t("مواقع آخر 30 دقيقة", "Positions des 30 dernières minutes", "Fixes in the last 30 minutes") + ": ${sheet.track.size}")
    }
    val doc = PdfDocument()
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
    val width = 595; val height = 842; val margin = 36
    val layout = StaticLayout.Builder.obtain(lines.joinToString("\n"), 0, lines.joinToString("\n").length, paint, width - 2 * margin)
        .setAlignment(if (lang == "ar") Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL).build()
    var line = 0
    var pageNo = 1
    while (line < layout.lineCount) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo++).create())
        val top = layout.getLineTop(line)
        var last = line
        while (last < layout.lineCount && layout.getLineBottom(last) - top <= height - 2 * margin) last++
        if (last == line) last = line + 1
        page.canvas.save()
        page.canvas.translate(margin.toFloat(), (margin - top).toFloat())
        page.canvas.clipRect(0, top, width - 2 * margin, layout.getLineBottom(last - 1))
        layout.draw(page.canvas)
        page.canvas.restore()
        doc.finishPage(page)
        line = last
    }
    return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
}

actual fun formatClock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(ms))
