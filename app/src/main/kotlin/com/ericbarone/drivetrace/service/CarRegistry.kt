package com.ericbarone.drivetrace.service

import android.content.Context
import com.ericbarone.drivetrace.obd.VehicleProfile
import com.ericbarone.drivetrace.ui.PREFS_NAME
import com.ericbarone.drivetrace.ui.PREF_VEHICLE_PROFILE
import org.json.JSONArray
import org.json.JSONObject

private const val PREF_KNOWN_CARS = "known_cars_json"

/** What the head unit says about the car, as sent by the automation caller. Blank means unknown. */
data class CarIdentity(
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val vehicleId: String = "",
) {
    val isEmpty: Boolean get() = make.isBlank() && model.isBlank() && year.isBlank() && vehicleId.isBlank()

    fun label(): String =
        listOf(make, model, year).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "unknown car" }
}

data class CarResolution(
    val profile: VehicleProfile,
    /** How the profile was chosen: registry, known-model, bootstrap or unassigned. */
    val source: String,
)

/**
 * The cars DriveTrace has been told about, kept in SharedPreferences as JSON rather than Room: the
 * database is on destructive migration, and a registry that vanishes on a schema bump would quietly
 * turn every known car back into an unassigned one.
 *
 * Identity arrives from the head unit via the automation intents. It is a make/model/year plus a
 * head-unit-generated vehicle id, not a VIN, and whether that id is stable across drives is not
 * yet confirmed, so a make/model/year match counts as the same car when the id disagrees.
 */
object CarRegistry {

    private data class Entry(
        val identity: CarIdentity,
        val profileName: String,
        val firstSeenMs: Long,
        val lastSeenMs: Long,
    )

    /**
     * Picks the profile for [identity] and records the car. Returns null when the identity carries
     * nothing to go on, in which case the caller keeps whatever profile the app has selected.
     *
     * Order: an existing registry entry; a model DriveTrace has a dedicated profile for; the
     * currently selected profile, but only for the very first car ever registered (the migration
     * path for the one car that was logging before identity existed); otherwise [VehicleProfile.GENERIC].
     * A matched or newly learned car also becomes the app's selected profile, so the Setup screen
     * agrees with what the automation just started.
     */
    @Synchronized
    fun resolve(context: Context, identity: CarIdentity): CarResolution? {
        if (identity.isEmpty) return null
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val entries = load(prefs).toMutableList()
        val now = System.currentTimeMillis()

        val matchIndex = entries.indexOfFirst { sameCar(it.identity, identity) }
        val resolution: CarResolution
        if (matchIndex >= 0) {
            val existing = entries[matchIndex]
            val merged = mergeIdentity(existing.identity, identity)
            entries[matchIndex] = existing.copy(identity = merged, lastSeenMs = now)
            resolution = CarResolution(profileFor(existing.profileName), "registry")
        } else {
            val known = knownModelProfile(identity)
            val selected = prefs.getString(PREF_VEHICLE_PROFILE, null)
                ?.let { name -> VehicleProfile.entries.find { it.name == name } }
            resolution = when {
                known != null -> CarResolution(known, "known-model")
                entries.isEmpty() && selected != null && selected != VehicleProfile.GENERIC ->
                    CarResolution(selected, "bootstrap")
                else -> CarResolution(VehicleProfile.GENERIC, "unassigned")
            }
            entries.add(Entry(identity, resolution.profile.name, now, now))
        }

        prefs.edit()
            .putString(PREF_KNOWN_CARS, serialize(entries))
            .putString(PREF_VEHICLE_PROFILE, resolution.profile.name)
            .commit()
        return resolution
    }

    private fun profileFor(name: String): VehicleProfile =
        VehicleProfile.entries.find { it.name == name } ?: VehicleProfile.GENERIC

    private fun knownModelProfile(identity: CarIdentity): VehicleProfile? {
        val make = identity.make.trim().lowercase()
        val model = identity.model.trim().lowercase()
        return when {
            make == "mazda" && model.removePrefix("mazda").trim() == "6" -> VehicleProfile.MAZDA_6_2020
            make == "subaru" && model.contains("outback") -> VehicleProfile.SUBARU_OUTBACK_2014
            else -> null
        }
    }

    private fun sameCar(a: CarIdentity, b: CarIdentity): Boolean {
        if (a.vehicleId.isNotBlank() && a.vehicleId == b.vehicleId) return true
        val aKey = triple(a)
        return aKey.isNotBlank() && aKey == triple(b)
    }

    private fun triple(i: CarIdentity): String =
        if (i.make.isBlank() || i.model.isBlank()) {
            ""
        } else {
            "${i.make}|${i.model}|${i.year}".trim().lowercase()
        }

    /** Keeps what the stored entry knew and fills any gap, preferring the newest non-blank value. */
    private fun mergeIdentity(old: CarIdentity, new: CarIdentity) = CarIdentity(
        make = new.make.ifBlank { old.make },
        model = new.model.ifBlank { old.model },
        year = new.year.ifBlank { old.year },
        vehicleId = new.vehicleId.ifBlank { old.vehicleId },
    )

    private fun load(prefs: android.content.SharedPreferences): List<Entry> {
        val raw = prefs.getString(PREF_KNOWN_CARS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Entry(
                    identity = CarIdentity(
                        make = o.optString("make"),
                        model = o.optString("model"),
                        year = o.optString("year"),
                        vehicleId = o.optString("vehicleId"),
                    ),
                    profileName = o.optString("profile"),
                    firstSeenMs = o.optLong("firstSeenMs"),
                    lastSeenMs = o.optLong("lastSeenMs"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serialize(entries: List<Entry>): String =
        JSONArray().apply {
            for (e in entries) {
                put(
                    JSONObject()
                        .put("make", e.identity.make)
                        .put("model", e.identity.model)
                        .put("year", e.identity.year)
                        .put("vehicleId", e.identity.vehicleId)
                        .put("profile", e.profileName)
                        .put("firstSeenMs", e.firstSeenMs)
                        .put("lastSeenMs", e.lastSeenMs),
                )
            }
        }.toString()
}
