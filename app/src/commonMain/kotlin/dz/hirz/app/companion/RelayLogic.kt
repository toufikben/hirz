package dz.hirz.app.companion

import dz.hirz.shared.api.Sighting

/**
 * The shepherd's phone as a relay (design §2, decision §14c): it hears BLE collars and uploads sightings when it has a
 * network. Only tags registered in its own circle ever leave the phone; one sighting per tag per [throttleMs]; when
 * offline, sightings wait in order and the oldest go first when full.
 */
class RelayBuffer(private var known: Set<String>, private val throttleMs: Long = 30_000, private val capacity: Int = 2_000) {
    private val items = ArrayDeque<Sighting>()
    private val lastSeen = mutableMapOf<String, Long>()

    val size get() = items.size

    fun setKnown(tags: Set<String>) { known = tags }

    /** True when the sighting was kept for upload. */
    fun offer(tagId: String, rssi: Int, seenAtMs: Long): Boolean {
        if (tagId !in known) return false
        val last = lastSeen[tagId]
        if (last != null && seenAtMs - last < throttleMs) return false
        lastSeen[tagId] = seenAtMs
        items.addLast(Sighting(tagId, rssi, seenAtMs))
        while (items.size > capacity) items.removeFirst()
        return true
    }

    fun nextBatch(max: Int = 200): List<Sighting> = items.take(max)

    /** Drops the first [n] sightings, once the server took them. */
    fun acknowledge(n: Int) = repeat(minOf(n, items.size)) { items.removeFirst() }
}

/** Scanning 10 s of every minute: enough to hear a herd that grazes slowly, a fraction of the battery. */
object RelayDuty {
    const val EVERY_MS = 60_000L
    const val WINDOW_MS = 10_000L

    fun scanning(nowMs: Long): Boolean = nowMs % EVERY_MS < WINDOW_MS
}
