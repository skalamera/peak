package com.cl600.obd.obd

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.cl600.obd.model.LogLevel
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class WifiObdTransport(
    private val host: String,
    private val port: Int,
    private val context: Context? = null
) : ObdTransport {

    override val displayName: String = "Wi-Fi $host:$port"

    private var socket: Socket? = null
    private var channel: StreamCommandChannel? = null

    override val isConnected: Boolean
        get() = channel?.isOpen == true && socket?.isClosed == false

    override suspend fun connect(logger: (LogLevel, String) -> Unit) {
        close()
        logger(LogLevel.INFO, "Connecting TCP socket to $host:$port ...")
        val tcpSocket = createWifiBoundSocket(logger)
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

    private fun createWifiBoundSocket(logger: (LogLevel, String) -> Unit): Socket {
        val ctx = context ?: return Socket()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                @Suppress("DEPRECATION")
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val bound = runCatching { network.socketFactory.createSocket() }.getOrNull()
                        if (bound != null) {
                            logger(LogLevel.INFO, "Binding TCP socket explicitly to Android Wi-Fi network")
                            return bound
                        }
                    }
                }
            }
        }
        return Socket()
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
