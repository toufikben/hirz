package dz.hirz.app.guardian

import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.SheetDto

/** This phone's position and accuracy, or null without permission or fix. Never asked in the background. */
expect suspend fun currentLocation(): Pair<LatLon, Double?>?

/** True while the app is on screen; the incident location reporter runs only then. */
expect fun appInForeground(): Boolean

/** Opens the system share sheet with [text]; nothing is posted without the user's own tap there. */
expect fun shareText(text: String)

expect fun copyText(text: String)

/** Opens the user's SMS app with [text] addressed to [to]; the user sends it. The app needs no SMS permission. */
expect fun composeSms(to: String?, text: String)

/** Saves [bytes] to Downloads; returns false when the platform refused. */
expect fun saveDownload(name: String, mime: String, bytes: ByteArray): Boolean

/** Device PIN, pattern or biometric before a secret is shown; true when confirmed (or the device has no lock). */
expect suspend fun confirmDeviceCredential(): Boolean

/** The incident sheet as a printable PDF (Arabic, French or English). */
expect fun sheetPdf(sheet: SheetDto, lang: String): ByteArray

/** Local wall-clock time "15:43:00" for an epoch-ms instant. */
expect fun formatClock(ms: Long): String
