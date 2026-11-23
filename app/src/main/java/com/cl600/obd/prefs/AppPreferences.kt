package com.cl600.obd.prefs

import android.content.Context
import android.content.SharedPreferences
import com.cl600.obd.model.BtStrategy
import com.cl600.obd.model.ConnectionConfig
import com.cl600.obd.model.ObdProtocol
import com.cl600.obd.model.TransportType
import com.cl600.obd.model.UnitSystem

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadConfig(): ConnectionConfig {
        val transportName = prefs.getString(KEY_TRANSPORT, TransportType.BLUETOOTH.name)
        val transport = runCatching { TransportType.valueOf(transportName ?: "") }
            .getOrDefault(TransportType.BLUETOOTH)

        val strategyName = prefs.getString(KEY_BT_STRATEGY, BtStrategy.AUTO_ALL.name)
        val strategy = runCatching { BtStrategy.valueOf(strategyName ?: "") }
            .getOrDefault(BtStrategy.AUTO_ALL)

        val protoName = prefs.getString(KEY_OBD_PROTOCOL, ObdProtocol.AUTO_WITH_KLINE_FALLBACK.name)
        val proto = runCatching { ObdProtocol.valueOf(protoName ?: "") }
            .getOrDefault(ObdProtocol.AUTO_WITH_KLINE_FALLBACK)

        val unitName = prefs.getString(KEY_UNIT_SYSTEM, UnitSystem.IMPERIAL.name)
        val unitSystem = runCatching { UnitSystem.valueOf(unitName ?: "") }
            .getOrDefault(UnitSystem.IMPERIAL)

        val manualBaroRaw = prefs.getFloat(KEY_MANUAL_BARO, -1f)
        val manualBaro = if (manualBaroRaw > 50f) manualBaroRaw else null

        return ConnectionConfig(
            transportType = transport,
            bluetoothAddress = prefs.getString(KEY_BT_ADDRESS, "") ?: "",
            bluetoothName = prefs.getString(KEY_BT_NAME, "") ?: "",
            btStrategy = strategy,
            obdProtocol = proto,
            wifiHost = prefs.getString(KEY_WIFI_HOST, "192.168.0.10") ?: "192.168.0.10",
            wifiPort = prefs.getInt(KEY_WIFI_PORT, 35000),
            manualBaroKpa = manualBaro,
            autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, false),
            unitSystem = unitSystem
        )
    }

    fun saveConfig(config: ConnectionConfig) {
        prefs.edit()
            .putString(KEY_TRANSPORT, config.transportType.name)
            .putString(KEY_BT_ADDRESS, config.bluetoothAddress)
            .putString(KEY_BT_NAME, config.bluetoothName)
            .putString(KEY_BT_STRATEGY, config.btStrategy.name)
            .putString(KEY_OBD_PROTOCOL, config.obdProtocol.name)
            .putString(KEY_WIFI_HOST, config.wifiHost)
            .putInt(KEY_WIFI_PORT, config.wifiPort)
            .putFloat(KEY_MANUAL_BARO, config.manualBaroKpa ?: -1f)
            .putBoolean(KEY_AUTO_CONNECT, config.autoConnect)
            .putString(KEY_UNIT_SYSTEM, config.unitSystem.name)
            .apply()
    }

    companion object {
        private const val PREF_NAME = "cl600_obd_prefs"
        private const val KEY_TRANSPORT = "transport_type"
        private const val KEY_BT_ADDRESS = "bt_address"
        private const val KEY_BT_NAME = "bt_name"
        private const val KEY_BT_STRATEGY = "bt_strategy"
        private const val KEY_OBD_PROTOCOL = "obd_protocol"
        private const val KEY_WIFI_HOST = "wifi_host"
        private const val KEY_WIFI_PORT = "wifi_port"
        private const val KEY_MANUAL_BARO = "manual_baro_kpa"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_UNIT_SYSTEM = "unit_system"
    }
}
