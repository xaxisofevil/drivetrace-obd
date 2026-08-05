package com.ericbarone.drivetrace.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class LocationSample(
    val elapsedNs: Long,
    val wallTimeUtc: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val horizontalAccuracyM: Float?,
    val provider: String?,
)

private const val LOCATION_INTERVAL_MS = 1_000L

/** One-second GPS fixes for the duration of a drive session, via Play Services FusedLocationProvider. */
class LocationCollector(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // caller must have already checked ACCESS_FINE_LOCATION
    fun samples(startElapsedNs: Long): Flow<LocationSample> =
        callbackFlow {
            val request =
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)
                    .build()

            val callback =
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val location = result.lastLocation ?: return
                        trySend(
                            LocationSample(
                                elapsedNs = System.nanoTime() - startElapsedNs,
                                wallTimeUtc = System.currentTimeMillis(),
                                latitude = location.latitude,
                                longitude = location.longitude,
                                altitudeM = if (location.hasAltitude()) location.altitude else null,
                                speedMps = if (location.hasSpeed()) location.speed else null,
                                bearingDeg = if (location.hasBearing()) location.bearing else null,
                                horizontalAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
                                provider = location.provider,
                            ),
                        )
                    }
                }

            client.requestLocationUpdates(request, callback, context.mainLooper)
            awaitClose { client.removeLocationUpdates(callback) }
        }
}
