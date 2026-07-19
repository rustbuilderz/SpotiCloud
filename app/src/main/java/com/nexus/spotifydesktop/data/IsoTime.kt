package com.nexus.spotifydesktop.data

/** Parse ISO-8601 timestamps (with or without Z / offset) to epoch millis. */
fun parseIsoToEpochMs(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val normalized = when {
            raw.endsWith("Z") || raw.contains("+") || raw.count { it == '-' } > 2 -> raw
            else -> "${raw}Z"
        }
        java.time.Instant.parse(normalized).toEpochMilli()
    }.getOrNull()
}
