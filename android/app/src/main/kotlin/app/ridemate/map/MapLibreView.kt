package app.ridemate.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * MapLibre is initialised lazily, on the first map shown, never at app start. A failure (no native library for
 * this ABI, out of memory) is remembered for the process so later screens go straight to the typed-address
 * fallback.
 */
object MapLibreInit {
    @Volatile
    private var state: Boolean? = null

    fun ensure(context: Context): Boolean {
        state?.let { return it }
        synchronized(this) {
            state?.let { return it }
            val ok = try {
                MapLibre.getInstance(context.applicationContext)
                true
            } catch (t: Throwable) {
                false
            }
            state = ok
            return ok
        }
    }

    /** The map failed to render this process (native library missing); later maps show the fallback at once. */
    fun markBroken() {
        state = false
    }

    val isBroken: Boolean get() = state == false
}

/**
 * A lifecycle-correct Compose wrapper around [MapView]: forwards start/resume/pause/stop to the view, destroys it
 * when it leaves composition, loads [styleUrl] and reports [MapLoadResult] within [MapConfig.STYLE_LOAD_TIMEOUT_MS].
 * [onReady] runs once the style has loaded; [onUnavailable] once on init failure, style failure or timeout.
 */
@Composable
fun MapLibreView(
    styleUrl: String,
    onReady: (MapLibreMap, Style) -> Unit,
    onUnavailable: (MapUnavailableReason) -> Unit,
    modifier: Modifier = Modifier,
    configure: (MapLibreMap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val currentConfigure by rememberUpdatedState(configure)
    val styleLoaded = remember(styleUrl) { CompletableDeferred<Pair<MapLibreMap, Style>?>() }

    val mapView: MapView? = remember {
        if (!MapLibreInit.ensure(context)) {
            null
        } else {
            try {
                MapView(context).apply { onCreate(null) }
            } catch (t: Throwable) {
                MapLibreInit.markBroken()
                null
            }
        }
    }

    if (mapView == null) {
        LaunchedEffect(Unit) { currentOnUnavailable(MapUnavailableReason.INIT_FAILED) }
        return
    }

    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    DisposableEffect(mapView, styleUrl) {
        val failListener = MapView.OnDidFailLoadingMapListener { _ -> styleLoaded.complete(null) }
        mapView.addOnDidFailLoadingMapListener(failListener)
        mapView.getMapAsync { map ->
            currentConfigure(map)
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style -> styleLoaded.complete(map to style) }
        }
        onDispose { mapView.removeOnDidFailLoadingMapListener(failListener) }
    }

    LaunchedEffect(styleLoaded) {
        var ready: Pair<MapLibreMap, Style>? = null
        val result = awaitMapStyle { styleLoaded.await()?.also { ready = it } != null }
        val loaded = ready
        if (result == MapLoadResult.Ready && loaded != null) {
            currentOnReady(loaded.first, loaded.second)
        } else if (result is MapLoadResult.Unavailable) {
            currentOnUnavailable(result.reason)
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )
}
