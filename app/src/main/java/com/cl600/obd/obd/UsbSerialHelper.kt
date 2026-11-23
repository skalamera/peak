package com.cl600.obd.obd

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.Locale

data class UsbDeviceUiItem(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String,
    val manufacturerName: String,
    val driverName: String,
    val hasPermission: Boolean,
    val isFtdi: Boolean
) {
    val displayTitle: String
        get() = productName.ifBlank {
            if (isFtdi) "Galletto FTDI USB-Serial" else "$driverName USB-Serial"
        }

    val vidPidLabel: String
        get() = String.format(Locale.US, "VID:%04X PID:%04X", vendorId, productId)
}

object UsbSerialHelper {

    const val ACTION_USB_PERMISSION = "com.cl600.obd.USB_PERMISSION"
    const val FTDI_VENDOR_ID = 0x0403

    fun findSupportedDrivers(context: Context): List<UsbSerialDriver> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        val driversByDevice = LinkedHashMap<String, UsbSerialDriver>()

        for (driver in UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)) {
            driversByDevice[driver.device.deviceName] = driver
        }

        // Custom FTDI fallback for Galletto clones with non-standard PIDs under FTDI VID 0x0403
        val customFtdiTable = ProbeTable()
        var hasCustomFtdiPid = false
        for (device in usbManager.deviceList.values) {
            if (device.vendorId == FTDI_VENDOR_ID && !driversByDevice.containsKey(device.deviceName)) {
                customFtdiTable.addProduct(device.vendorId, device.productId, FtdiSerialDriver::class.java)
                hasCustomFtdiPid = true
            }
        }
        if (hasCustomFtdiPid) {
            for (driver in UsbSerialProber(customFtdiTable).findAllDrivers(usbManager)) {
                driversByDevice.putIfAbsent(driver.device.deviceName, driver)
            }
        }

        return driversByDevice.values.sortedWith(
            compareByDescending<UsbSerialDriver> { it.device.vendorId == FTDI_VENDOR_ID }
                .thenBy { it.device.deviceName }
        )
    }

    fun listUsbDevices(context: Context): Pair<List<UsbDeviceUiItem>, List<UsbDevice>> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return Pair(emptyList(), emptyList())

        val rawDevices = usbManager.deviceList.values.sortedBy { it.deviceName }
        val items = findSupportedDrivers(context).map { driver ->
            val dev = driver.device
            val prod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dev.productName?.trim().orEmpty()
            } else {
                ""
            }
            val mfg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dev.manufacturerName?.trim().orEmpty()
            } else {
                ""
            }
            UsbDeviceUiItem(
                deviceName = dev.deviceName,
                vendorId = dev.vendorId,
                productId = dev.productId,
                productName = prod,
                manufacturerName = mfg,
                driverName = driver.javaClass.simpleName.removeSuffix("SerialDriver"),
                hasPermission = usbManager.hasPermission(dev),
                isFtdi = dev.vendorId == FTDI_VENDOR_ID
            )
        }
        return Pair(items, rawDevices)
    }

    fun requestPermission(context: Context, device: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }
}
