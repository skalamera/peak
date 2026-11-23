package com.cl600.obd.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.cl600.obd.model.BtStrategy
import com.cl600.obd.model.LogLevel
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.UUID

class BluetoothObdTransport(
    private val adapter: BluetoothAdapter,
    private val deviceAddress: String,
    private val deviceLabel: String,
    private val strategy: BtStrategy
) : ObdTransport {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    override val displayName: String =
        if (deviceLabel.isNotBlank()) "$deviceLabel ($deviceAddress)" else deviceAddress

    private var socket: BluetoothSocket? = null
    private var channel: StreamCommandChannel? = null

    override val isConnected: Boolean
        get() = channel?.isOpen == true && socket?.isConnected == true

    @SuppressLint("MissingPermission")
    override suspend fun connect(logger: (LogLevel, String) -> Unit) {
        close()

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(deviceAddress.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid Bluetooth MAC address: $deviceAddress", e)
        }

        try {
            if (adapter.isDiscovering) {
                logger(LogLevel.INFO, "Cancelling active Bluetooth discovery before connect...")
                adapter.cancelDiscovery()
                delay(200L)
            }
        } catch (se: SecurityException) {
            logger(LogLevel.WARN, "Could not cancel BT discovery (permission): ${se.message}")
        } catch (_: Exception) {
        }

        val strategiesToTry: List<BtStrategy> = if (strategy == BtStrategy.AUTO_ALL) {
            listOf(
                BtStrategy.INSECURE_SPP,
                BtStrategy.SECURE_SPP,
                BtStrategy.REFLECTION_INSECURE_CH1,
                BtStrategy.REFLECTION_SECURE_CH1,
                BtStrategy.REFLECTION_CH2
            )
        } else {
            listOf(strategy)
        }

        var lastError: Throwable? = null
        for ((index, strat) in strategiesToTry.withIndex()) {
            logger(
                LogLevel.INFO,
                "BT attempt ${index + 1}/${strategiesToTry.size}: ${strat.displayName} -> $displayName"
            )
            var candidateSocket: BluetoothSocket? = null
            try {
                candidateSocket = createSocket(device, strat)
                candidateSocket.connect()

                logger(LogLevel.INFO, "Bluetooth RFCOMM connected using ${strat.displayName}")
                socket = candidateSocket
                channel = StreamCommandChannel(
                    inputStream = candidateSocket.inputStream,
                    outputStream = candidateSocket.outputStream,
                    onCloseResources = {
                        try {
                            candidateSocket.close()
                        } catch (_: Exception) {
                        }
                    }
                )
                return
            } catch (e: SecurityException) {
                closeQuietly(candidateSocket)
                throw IOException("Bluetooth permission denied: ${e.message}", e)
            } catch (t: Throwable) {
                lastError = t
                logger(
                    LogLevel.WARN,
                    "Strategy ${strat.displayName} failed: ${t.javaClass.simpleName}: ${t.message}"
                )
                closeQuietly(candidateSocket)
                if (index < strategiesToTry.lastIndex) {
                    delay(250L)
                }
            }
        }

        val detail = lastError?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "Unknown socket failure"
        throw IOException(
            "All Bluetooth connection strategies failed for $displayName ($detail). " +
                "Check pairing in the head unit's Bluetooth app or try Wi-Fi OBD.",
            lastError
        )
    }

    @SuppressLint("MissingPermission")
    private fun createSocket(device: BluetoothDevice, strat: BtStrategy): BluetoothSocket {
        return when (strat) {
            BtStrategy.INSECURE_SPP, BtStrategy.AUTO_ALL ->
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)

            BtStrategy.SECURE_SPP ->
                device.createRfcommSocketToServiceRecord(SPP_UUID)

            BtStrategy.REFLECTION_INSECURE_CH1 -> {
                val method = device.javaClass.getMethod(
                    "createInsecureRfcommSocket",
                    Int::class.javaPrimitiveType
                )
                method.invoke(device, 1) as BluetoothSocket
            }

            BtStrategy.REFLECTION_SECURE_CH1 -> {
                val method = device.javaClass.getMethod(
                    "createRfcommSocket",
                    Int::class.javaPrimitiveType
                )
                method.invoke(device, 1) as BluetoothSocket
            }

            BtStrategy.REFLECTION_CH2 -> {
                val method = device.javaClass.getMethod(
                    "createRfcommSocket",
                    Int::class.javaPrimitiveType
                )
                method.invoke(device, 2) as BluetoothSocket
            }
        }
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String {
        val activeChannel = channel ?: throw IOException("Bluetooth channel is not connected")
        return activeChannel.sendCommand(command, timeoutMs)
    }

    override fun close() {
        channel?.close()
        channel = null
        closeQuietly(socket)
        socket = null
    }

    private fun closeQuietly(sock: BluetoothSocket?) {
        try {
            sock?.close()
        } catch (_: Exception) {
        }
    }
}
