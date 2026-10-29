package dz.hirz.app.guardian

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PlaceDto

/** One asset on the map: a true-to-scale accuracy ring plus a marker whose style encodes freshness. */
data class MapMarker(val id: String, val name: String, val position: LatLon, val accuracyM: Double?, val style: MarkerStyle)

/**
 * OpenStreetMap via MapLibre (design §7). [draft] shows the place being drawn. [onTap] receives map taps
 * in explicit lat/lon (never a positional pair).
 */
@Composable
expect fun HirzMap(
    markers: List<MapMarker>,
    places: List<PlaceDto>,
    draft: List<LatLon>,
    modifier: Modifier,
    onTap: (LatLon) -> Unit,
)

/** Device language reduced to the three supported ones; anything else uses Arabic, the default (design §6). */
expect fun currentLang(): String

/** Opens the phone dialler with [number]; never calls by itself. */
expect fun dial(number: String)
