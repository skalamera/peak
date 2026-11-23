package com.cl600.obd.obd

import com.cl600.obd.model.LogLevel

interface ObdTransport {
    val displayName: String
    val isConnected: Boolean

    suspend fun connect(logger: (LogLevel, String) -> Unit)
    suspend fun sendCommand(command: String, timeoutMs: Long = 2500L): String
    fun close()
}
