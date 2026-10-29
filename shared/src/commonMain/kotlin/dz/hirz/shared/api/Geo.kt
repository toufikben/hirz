package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/** The only coordinate shape on the wire: explicit names, never positional arrays (v2 WKT/Leaflet lesson). */
@Serializable
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "longitude out of range: $lon" }
    }
}
