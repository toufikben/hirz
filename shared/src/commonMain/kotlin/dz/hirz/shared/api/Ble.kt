package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/**
 * Registers a BLE collar or tag on an asset. [proofRssi] and [proofAtMs] come from the owner's own phone scanning the
 * tag in hand (proof of possession): a tag id typed from a label is not enough.
 */
@Serializable
data class TagRequest(val tagId: String, val format: String, val proofRssi: Int, val proofAtMs: Long)

/** Where the relaying phone was when it heard the tags. */
@Serializable
data class RelayFix(val position: LatLon, val accuracyM: Double, val fixTimeMs: Long)

/** [at]: the phone's fix when it heard the tag; a sighting buffered offline must carry its own. */
@Serializable
data class Sighting(val tagId: String, val rssi: Int, val seenAtMs: Long, val at: RelayFix? = null)

/** A Hirz phone's report of the registered tags it heard (the shepherd's relay, design §2). */
@Serializable
data class RelayBatch(val relay: RelayFix, val sightings: List<Sighting>)

/** [dropped]: unknown tags, rate-limited repeats and stale sightings; never says which. */
@Serializable
data class RelayResult(val accepted: Int, val dropped: Int)

/** An owner or guardian lets (or stops) a family phone relaying BLE tags. */
@Serializable
data class RelayGrant(val enabled: Boolean)
