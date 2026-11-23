package com.cl600.obd.model

enum class LogLevel(val tag: String) {
    INFO("INFO"),
    TX("TX"),
    RX("RX"),
    WARN("WARN"),
    ERROR("ERR")
}

data class LogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val message: String
)
