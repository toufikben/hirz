package dz.hirz.shared.signal

import dz.hirz.shared.asset.AssetType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Wire names follow design §3 so non-Kotlin clients and stored data never depend on Kotlin identifiers. */
class WireNamesTest {
    @Test
    fun fixSourceUsesSpecNames() {
        assertEquals("\"ble-relay\"", Json.encodeToString(FixSource.serializer(), FixSource.BLE_RELAY))
        assertEquals("\"gnss\"", Json.encodeToString(FixSource.serializer(), FixSource.GNSS))
    }

    @Test
    fun alarmUsesSpecNames() {
        assertEquals("\"powerCut\"", Json.encodeToString(AlarmKind.serializer(), AlarmKind.POWER_CUT))
        assertEquals("\"duress\"", Json.encodeToString(AlarmKind.serializer(), AlarmKind.DURESS))
    }

    @Test
    fun assetTypeIsSnakeCase() {
        assertEquals("\"special_needs\"", Json.encodeToString(AssetType.serializer(), AssetType.SPECIAL_NEEDS))
        assertEquals("\"lone_worker\"", Json.encodeToString(AssetType.serializer(), AssetType.LONE_WORKER))
    }
}
