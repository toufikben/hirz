package dz.hirz.app.guardian

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dz.hirz.app.HirzApplication
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.ShapeDto
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.Locale

/** OpenFreeMap "liberty" style; its attribution (OpenFreeMap, OpenMapTiles, OpenStreetMap) is shown by MapLibre. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private val ALGIERS = LatLng(36.7538, 3.0588)

private fun ColorRole.hex() = when (this) {
    ColorRole.FRESH -> "#1E8E3E"
    ColorRole.STALE -> "#E37400"
    ColorRole.SILENT -> "#80868B"
}

private fun LatLon.point(): Point = Point.fromLngLat(lon, lat)
private fun ring(points: List<LatLon>) = Polygon.fromLngLats(listOf(points.map { it.point() }))

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var onTap: (LatLon) -> Unit = {}
    var centered = false
}

@Composable
actual fun HirzMap(markers: List<MapMarker>, places: List<PlaceDto>, draft: List<LatLon>, modifier: Modifier, onTap: (LatLon) -> Unit) {
    val context = LocalContext.current
    val holder = remember { MapHolder() }
    holder.onTap = onTap
    val view = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                holder.map = map
                map.cameraPosition = CameraPosition.Builder().target(ALGIERS).zoom(12.0).build()
                map.addOnMapClickListener { p -> holder.onTap(LatLon(p.latitude, p.longitude)); true }
                map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                    style.addSource(GeoJsonSource("places"))
                    style.addSource(GeoJsonSource("accuracy"))
                    style.addSource(GeoJsonSource("assets"))
                    style.addSource(GeoJsonSource("draft"))
                    style.addLayer(FillLayer("places-fill", "places").withProperties(fillColor("#1A73E8"), fillOpacity(0.10f)))
                    style.addLayer(LineLayer("places-line", "places").withProperties(lineColor("#1A73E8"), lineWidth(2f)))
                    style.addLayer(FillLayer("accuracy-fill", "accuracy").withProperties(fillColor(get("color")), fillOpacity(0.15f)))
                    style.addLayer(
                        CircleLayer("assets-dot", "assets").withProperties(
                            circleRadius(9f), circleColor(get("color")), circleOpacity(get("fill")),
                            circleStrokeColor(get("color")), circleStrokeWidth(3f),
                        ),
                    )
                    style.addLayer(SymbolLayer("assets-glyph", "assets").withProperties(textField(get("glyph")), textSize(14f)))
                    style.addLayer(CircleLayer("draft-dot", "draft").withProperties(circleRadius(6f), circleColor("#D93025")))
                    holder.style = style
                }
            }
            onStart(); onResume()
        }
    }
    DisposableEffect(view) { onDispose { view.onPause(); view.onStop(); view.onDestroy() } }
    AndroidView({ view }, modifier) {
        val style = holder.style ?: return@AndroidView
        (style.getSource("places") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(places.map { place ->
            val shape = place.shape
            Feature.fromGeometry(when (shape) {
                is ShapeDto.Circle -> ring(accuracyRing(shape.center, shape.radiusM))
                is ShapeDto.Polygon -> ring(shape.points + shape.points.first())
            })
        }))
        (style.getSource("accuracy") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(markers.mapNotNull { m ->
            m.accuracyM?.let { acc -> Feature.fromGeometry(ring(accuracyRing(m.position, acc))).apply { addStringProperty("color", m.style.color.hex()) } }
        }))
        (style.getSource("assets") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(markers.map { m ->
            Feature.fromGeometry(m.position.point()).apply {
                addStringProperty("color", m.style.color.hex())
                addNumberProperty("fill", if (m.style.hollow) 0.0 else 1.0)
                addStringProperty("glyph", m.style.glyph ?: "")
            }
        }))
        (style.getSource("draft") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(draft.map { Feature.fromGeometry(it.point()) }))
        val first = markers.firstOrNull()
        if (!holder.centered && first != null) {
            holder.map?.cameraPosition = CameraPosition.Builder().target(LatLng(first.position.lat, first.position.lon)).zoom(15.0).build()
            holder.centered = true
        }
    }
}

actual fun currentLang(): String = Locale.getDefault().language.takeIf { it in setOf("ar", "fr", "en") } ?: "ar"

actual fun dial(number: String) {
    val context = HirzApplication.instance
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
