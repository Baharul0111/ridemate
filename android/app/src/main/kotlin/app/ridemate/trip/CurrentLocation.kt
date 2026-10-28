package app.ridemate.trip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.ridemate.core.model.LatLng
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** The outcome of asking for the phone's position. */
sealed interface LocationFix {
    data class Found(val point: LatLng) : LocationFix

    /** Location (while in use) is not allowed: the pickup is typed. */
    data object PermissionDenied : LocationFix

    /** Allowed, but no position came (location off, indoors, timeout): the pickup is typed. */
    data object Unavailable : LocationFix
}

/** The pickup's default: where the phone is now (goal 1). Only while-in-use location is ever requested. */
interface CurrentLocationSource {
    fun hasPermission(): Boolean

    suspend fun current(): LocationFix
}

/**
 * [CurrentLocationSource] over the fused location provider: one balanced-power fix (a cached one up to a minute
 * old is fine), else the last known location, within [timeoutMs].
 */
class FusedCurrentLocationSource(
    private val context: Context,
    private val timeoutMs: Long = TIMEOUT_MS,
) : CurrentLocationSource {

    override fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission") // checked by hasPermission() right above every call
    override suspend fun current(): LocationFix {
        if (!hasPermission()) return LocationFix.PermissionDenied
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cancel = CancellationTokenSource()
        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(MAX_AGE_MS)
                .setDurationMillis(timeoutMs)
                .build()
            val location = withTimeoutOrNull(timeoutMs) { client.getCurrentLocation(request, cancel.token).await() }
                ?: withTimeoutOrNull(LAST_KNOWN_TIMEOUT_MS) { client.lastLocation.await() }
            if (location == null) LocationFix.Unavailable else LocationFix.Found(LatLng(location.latitude, location.longitude))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            LocationFix.PermissionDenied
        } catch (e: Exception) {
            LocationFix.Unavailable
        } finally {
            cancel.cancel()
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val TIMEOUT_MS: Long = 8_000
        private const val LAST_KNOWN_TIMEOUT_MS: Long = 2_000
        private const val MAX_AGE_MS: Long = 60_000
    }
}
