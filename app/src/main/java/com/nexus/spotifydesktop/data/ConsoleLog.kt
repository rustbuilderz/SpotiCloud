package com.nexus.spotifydesktop.data

enum class ConsoleLevel {
    Debug,
    Info,
    Ok,
    Warn,
    Error,
    RateLimit,
}

data class ConsoleLine(
    val id: Long,
    val atMs: Long,
    val level: ConsoleLevel,
    val tag: String,
    val message: String,
)
