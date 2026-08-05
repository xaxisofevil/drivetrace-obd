package com.ericbarone.drivetrace.export

import android.content.Context
import com.ericbarone.drivetrace.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STOICH_AFR_GASOLINE = 14.7
private const val GASOLINE_DENSITY_G_PER_L = 745.0
private const val LITERS_PER_GALLON = 3.78541

data class TripSummary(val distanceKm: Double?, val overallMpg: Double?)

/**
 * Quick on-device version of the PC script's compute_overall_mpg: total distance / total fuel
 * burned, not an average of instantaneous readings, so idle time correctly pulls the number
 * toward zero rather than being excluded. A simplified estimate: unlike the PC script, this
 * doesn't gate on commanded equivalence ratio being near stoichiometric, so it's rougher,
 * intended for an at-a-glance number on the Session complete screen, not for real analysis.
 * Run the PC script against the exported CSV for the careful version.
 */
suspend fun computeTripSummary(context: Context, sessionId: Long): TripSummary =
    withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).sessionDao()
        val locations = dao.getLocations(sessionId)
        val measurements = dao.getMeasurements(sessionId)

        var distanceKm = 0.0
        for (i in 1 until locations.size) {
            val prev = locations[i - 1]
            val curr = locations[i]
            val prevSpeed = prev.speedMps ?: continue
            val currSpeed = curr.speedMps ?: continue
            val dtS = (curr.elapsedNs - prev.elapsedNs) / 1e9
            if (dtS <= 0 || dtS > 30) continue // skip gaps (reconnects, GPS dropouts)
            distanceKm += (prevSpeed + currSpeed) / 2.0 * dtS / 1000.0
        }

        val mafSamples = measurements
            .filter { it.canonicalName == "Mass Air Flow" && it.valueNumeric != null && it.qualityFlag == "OK" }
            .sortedBy { it.elapsedNs }

        var totalGallons = 0.0
        for (i in 1 until mafSamples.size) {
            val prev = mafSamples[i - 1]
            val curr = mafSamples[i]
            val dtHours = (curr.elapsedNs - prev.elapsedNs) / 1e9 / 3600.0
            if (dtHours <= 0 || dtHours > 30.0 / 3600.0) continue
            val fuelGS = (prev.valueNumeric!!) / STOICH_AFR_GASOLINE
            val fuelGalHr = (fuelGS * 3600) / (GASOLINE_DENSITY_G_PER_L * LITERS_PER_GALLON)
            totalGallons += fuelGalHr * dtHours
        }

        val distance = if (locations.isNotEmpty()) distanceKm else null
        val mpg = if (totalGallons > 0 && distance != null) {
            (distance * 0.621371) / totalGallons
        } else {
            null
        }
        TripSummary(distanceKm = distance, overallMpg = mpg)
    }
