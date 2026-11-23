package com.cl600.obd.obd

import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

interface KlineSerialPort {
    val displayName: String
    val vendorId: Int
    val productId: Int
    val isOpen: Boolean

    fun open()
    fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int)
    fun setLatencyTimer(latencyMs: Int): Boolean
    fun setBreak(enabled: Boolean)
    fun setRTS(enabled: Boolean)
    fun setDTR(enabled: Boolean)
    fun purgeHwBuffers(purgeWrite: Boolean, purgeRead: Boolean)
    fun write(data: ByteArray, timeoutMs: Int)
    fun read(dest: ByteArray, timeoutMs: Int): Int
    fun close()
}

class AndroidUsbKlinePort(
    private val usbManager: UsbManager,
    private val driver: UsbSerialDriver
) : KlineSerialPort {

    private var serialPort: UsbSerialPort? = null

    override val vendorId: Int
        get() = driver.device.vendorId

    override val productId: Int
        get() = driver.device.productId

    override val displayName: String =
        driver.javaClass.simpleName.removeSuffix("SerialDriver") +
            " (VID:%04X PID:%04X)".format(driver.device.vendorId, driver.device.productId)

    override val isOpen: Boolean
        get() = serialPort?.isOpen == true

    override fun open() {
        close()
        val device = driver.device
        val connection = usbManager.openDevice(device)
            ?: throw IOException("Could not open USB device ${device.deviceName} (permission missing or device busy)")
        val port = driver.ports.firstOrNull()
            ?: throw IOException("USB serial driver exposes no ports")
        port.open(connection)
        serialPort = port
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        val port = serialPort ?: throw IOException("USB serial port is not open")
        port.setParameters(baudRate, dataBits, stopBits, parity)
    }

    override fun setLatencyTimer(latencyMs: Int): Boolean {
        val port = serialPort ?: return false
        return if (port is FtdiSerialDriver.FtdiSerialPort) {
            runCatching {
                port.setLatencyTimer(latencyMs)
                true
            }.getOrDefault(false)
        } else {
            false
        }
    }

    override fun setBreak(enabled: Boolean) {
        val port = serialPort ?: throw IOException("USB serial port is not open")
        port.setBreak(enabled)
    }

    override fun setRTS(enabled: Boolean) {
        val port = serialPort ?: return
        runCatching { port.rts = enabled }
    }

    override fun setDTR(enabled: Boolean) {
        val port = serialPort ?: return
        runCatching { port.dtr = enabled }
    }

    override fun purgeHwBuffers(purgeWrite: Boolean, purgeRead: Boolean) {
        val port = serialPort ?: return
        runCatching { port.purgeHwBuffers(purgeWrite, purgeRead) }
    }

    override fun write(data: ByteArray, timeoutMs: Int) {
        val port = serialPort ?: throw IOException("USB serial port is not open")
        port.write(data, timeoutMs)
    }

    override fun read(dest: ByteArray, timeoutMs: Int): Int {
        val port = serialPort ?: throw IOException("USB serial port is not open")
        return port.read(dest, timeoutMs)
    }

    override fun close() {
        val port = serialPort
        serialPort = null
        try {
            port?.close()
        } catch (_: Exception) {
        }
    }
}
