package com.cl600.obd.model

enum class DtcCategory(val label: String) {
    STORED("STORED"),
    PENDING("PENDING"),
    PERMANENT("PERMANENT")
}

data class DtcEntry(
    val code: String,
    val category: DtcCategory,
    val description: String,
    val m275Note: String? = null
)
