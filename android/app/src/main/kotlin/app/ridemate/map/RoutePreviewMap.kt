package app.ridemate.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ridemate.R
import app.ridemate.core.model.LatLng
import app.ridemate.ui.components.InlineBanner
import app.ridemate.ui.components.RmTone
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * A small, non-interactive route preview on the trip screen: pickup dot, drop square and a dashed straight line
 * (not a road route), the camera fitted to both. Tapping it opens the pin picker to adjust ([onAdjust]). If the map
 * cannot load it shows "Map unavailable · type the address" instead; the trip is never blocked.
 */
@Composable
fun RoutePreviewMap(
    config: MapConfig,
    pickup: LatLng,
    drop: LatLng,
    pickupLabel: String,
    dropLabel: String,
    onAdjust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var unavailable by remember(config) { mutableStateOf(!config.enabled || MapLibreInit.isBroken) }
    if (unavailable) {
        Box(modifier.testTag(MapTags.UNAVAILABLE)) { InlineBanner(stringResource(R.string.map_unavailable), tone = RmTone.WARN) }
        return
    }
    val description = stringResource(R.string.map_route_description, pickupLabel, dropLabel)
    val pickupColor = MaterialTheme.colorScheme.primary.toArgb()
    val dropColor = MaterialTheme.colorScheme.onSurface.toArgb()
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(MaterialTheme.shapes.large)
            .testTag(MapTags.ROUTE_PREVIEW)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onAdjust)
            .semantics { contentDescription = description },
    ) {
        Box {
            MapLibreView(
                styleUrl = config.styleUrl,
                configure = { m ->
                    m.uiSettings.setAllGesturesEnabled(false)
                    m.uiSettings.isAttributionEnabled = true
                    m.addOnMapClickListener {
                        onAdjust()
                        true
                    }
                },
                onReady = { m, style ->
                    drawRoute(style, pickup, drop, pickupColor, dropColor)
                    val bounds = LatLngBounds.Builder().include(pickup.toMl()).include(drop.toMl()).build()
                    try {
                        m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64))
                    } catch (e: RuntimeException) {
                        m.moveCamera(CameraUpdateFactory.newLatLngZoom(pickup.toMl(), 14.0))
                    }
                },
                onUnavailable = { unavailable = true },
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            Surface(
                shape = MaterialTheme.shapes.small,
                tonalElevation = 2.dp,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Text(
                    stringResource(R.string.map_route_adjust),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private const val ROUTE_SOURCE = "rm-route"
private const val ENDS_SOURCE = "rm-route-ends"
private const val DROP_SOURCE = "rm-route-drop"
private const val DROP_ICON = "rm-drop-square"

private fun drawRoute(style: Style, pickup: LatLng, drop: LatLng, pickupColor: Int, dropColor: Int) {
    val a = Point.fromLngLat(pickup.lng, pickup.lat)
    val b = Point.fromLngLat(drop.lng, drop.lat)
    style.addSource(GeoJsonSource(ROUTE_SOURCE, Feature.fromGeometry(LineString.fromLngLats(listOf(a, b)))))
    style.addSource(GeoJsonSource(ENDS_SOURCE, Feature.fromGeometry(a)))
    style.addSource(GeoJsonSource(DROP_SOURCE, Feature.fromGeometry(b)))
    style.addImage(DROP_ICON, squareBitmap(dropColor))
    style.addLayer(
        LineLayer("rm-route-line", ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(dropColor),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
    style.addLayer(
        CircleLayer("rm-route-pickup", ENDS_SOURCE).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(pickupColor),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
        ),
    )
    style.addLayer(
        SymbolLayer("rm-route-drop", DROP_SOURCE).withProperties(
            PropertyFactory.iconImage(DROP_ICON),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
}

/** The drop marker: a filled square with a white border, so pickup and drop differ by shape, not colour alone. */
private fun squareBitmap(color: Int): Bitmap {
    val size = 36
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { this.color = android.graphics.Color.WHITE })
    canvas.drawRect(5f, 5f, size - 5f, size - 5f, Paint().apply { this.color = color })
    return bitmap
}
