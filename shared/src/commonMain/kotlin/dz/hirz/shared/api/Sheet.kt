package dz.hirz.shared.api

import dz.hirz.shared.asset.AssetType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What the child wears today; shown only on the same local day (Algiers time). */
@Serializable
data class TodayRequest(val clothes: String)

@Serializable
data class SheetSourceDto(val kind: String, val model: String? = null, val imei: String? = null)

/**
 * Everything a guardian needs on one screen during an incident, and to read out to the police (design §5.8).
 * It never contains the family code word; the app shows that separately after re-authentication.
 */
@Serializable
data class SheetDto(
    val incident: IncidentDto,
    val assetName: String,
    val assetType: AssetType,
    val descriptors: JsonObject = JsonObject(emptyMap()),
    val todayClothes: String? = null,
    val hasPhoto: Boolean = false,
    /** Shown only when the owner chose to put it on the police sheet. */
    val condition: dz.hirz.shared.asset.Condition? = null,
    val lastFix: PositionDto? = null,
    val lastFixAgeS: Long? = null,
    val track: List<PositionDto> = emptyList(),
    val sources: List<SheetSourceDto> = emptyList(),
    val emergencyNumbers: List<EmergencyNumberDto> = emptyList(),
    /** When the active public share link expires; the link itself is shown only once, at creation. */
    val shareActiveUntilMs: Long? = null,
)
