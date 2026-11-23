package com.cl600.obd.obd

import com.cl600.obd.model.DtcEntry

data class ObdInitResult(
    val adapterVersion: String,
    val activeProtocol: String,
    val isCanProtocol: Boolean,
    val supportedPids: Set<String>,
    val baroKpa: Float?,
    val milOn: Boolean,
    val dtcCountReported: Int
)

data class ObdPollSnapshot(
    val mapKpa: Float? = null,
    val baroKpa: Float? = null,
    val iatCelsius: Float? = null,
    val coolantCelsius: Float? = null,
    val voltage: Float? = null,
    val rpm: Int? = null,
    val loadPercent: Float? = null,
    val timingAdvanceDeg: Float? = null,
    val throttlePercent: Float? = null,
    val speedKmh: Int? = null,
    val milOn: Boolean? = null,
    val dtcCountReported: Int? = null
)

interface ObdSession {
    val isConnected: Boolean
    val isDirectKline: Boolean
    var verbosePollLogging: Boolean

    suspend fun initialize(): ObdInitResult
    suspend fun pollCycle(cycleIndex: Long): ObdPollSnapshot
    suspend fun scanDtcs(): Triple<Boolean, Int, List<DtcEntry>>
    suspend fun clearDtcs(): Boolean
    suspend fun sendRawCommand(command: String): String
    suspend fun keepAliveIfIdle(): Boolean
    fun close()
}
