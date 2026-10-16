package com.ericbarone.drivetrace.obd

/**
 * Every vehicle DriveTrace knows how to log, each with its own [PidCatalog]. The server/Room
 * data model already supports multiple vehicles (SessionEntity.vehicleProfile is a per-session
 * field, not a global constant), this enum is what actually lets the app pick between them
 * rather than hardcoding one.
 *
 * [performanceCatalog] is a second, smaller PID set for the same physical car, swappable while a
 * session is running (see PidScheduler's catalog selector). It is a logging mode of the car, not
 * a second car: a drive can move between the two catalogs and stays one session.
 */
enum class VehicleProfile(
    val displayName: String,
    val catalog: PidCatalog,
    val performanceCatalog: PidCatalog? = null,
) {
    MAZDA_6_2020("2020 Mazda 6 2.5T", MazdaPidCatalog, MazdaPerformancePidCatalog),
    SUBARU_OUTBACK_2014("2014 Subaru Outback 2.5i", SubaruPidCatalog),
}
