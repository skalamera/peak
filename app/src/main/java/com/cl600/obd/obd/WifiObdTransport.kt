package com.cl600.obd.obd

import com.cl600.obd.model.LogLevel
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class WifiObdTransport(
    private val host: String,
    private val port: Int
) : ObdTransport {

    override val displayName: String = "Wi-Fi $host:$port"

    private var socket: Socket? = null
    private var channel: StreamCommandChannel? = null

    override val isConnected: Boolean
        get() = channel?.isOpen == true && socket?.isClosed == false

    override suspend fun connect(logger: (LogLevel, String) -> Unit) {
        close()
        logger(LogLevel.INFO, "Connecting TCP socket to $host:$port ...")
        val tcpSocket = Socket()
        try {
            tcpSocket.tcpNoDelay = true
            tcpSocket.connect(InetSocketAddress(host.trim(), port), 5000)
            socket = tcpSocket
            channel = StreamCommandChannel(
                inputStream = tcpSocket.getInputStream(),
                outputStream = tcpSocket.getOutputStream(),
                onCloseResources = {
                    try {
                        tcpSocket.close()
                    } catch (_: Exception) {
                    }
                }
            )
            logger(LogLevel.INFO, "Connected to $displayName")
        } catch (e: IOException) {
            try {
                tcpSocket.close()
            } catch (_: Exception) {
            }
            throw IOException("Wi-Fi OBD connection to $host:$port failed: ${e.message}", e)
        }
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String {
        val activeChannel = channel ?: throw IOException("Wi-Fi OBD socket is not connected")
        return activeChannel.sendCommand(command, timeoutMs)
    }

    override fun close() {
        channel?.close()
        channel = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
