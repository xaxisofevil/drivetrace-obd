package com.ericbarone.drivetrace.obd

/**
 * Every vehicle DriveTrace knows how to log, each with its own [PidCatalog]. The server/Room
 * data model already supports multiple vehicles (SessionEntity.vehicleProfile is a per-session
 * field, not a global constant), this enum is what actually lets the app pick between them
 * rather than hardcoding one.
 */
enum class VehicleProfile(val displayName: String, val catalog: PidCatalog) {
    MAZDA_6_2020("2020 Mazda 6 2.5T", MazdaPidCatalog),
    SUBARU_OUTBACK_2014("2014 Subaru Outback 2.5i", SubaruPidCatalog),
    // Same physical car as MAZDA_6_2020, a deliberately different catalog (see
    // MazdaPerformancePidCatalog), not a second vehicle. A distinct profile rather than a
    // checkbox because the catalog choice has to be decided before the session starts and
    // threaded straight into PidScheduler same as any other profile; a mid-session toggle
    // would need the scheduler to support swapping catalogs live, which it doesn't.
    MAZDA_6_2020_PERF("2020 Mazda 6 2.5T (Performance Logging)", MazdaPerformancePidCatalog),
}
