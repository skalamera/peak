package com.cl600.obd.model

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val detail: String) : ConnectionState()
    data class Initializing(val detail: String) : ConnectionState()
    data class Connected(val deviceName: String, val protocol: String) : ConnectionState()
    object Demo : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    fun isActiveOrConnecting(): Boolean =
        this is Connecting || this is Initializing || this is Connected || this is Demo

    fun isStreaming(): Boolean = this is Connected || this is Demo
}
