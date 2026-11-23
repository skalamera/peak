package com.cl600.obd.model

enum class TransportType(val displayName: String) {
    BLUETOOTH("Bluetooth"),
    WIFI("Wi-Fi"),
    DEMO("Demo")
}

enum class BtStrategy(val displayName: String) {
    AUTO_ALL("Auto (Try All Strategies)"),
    INSECURE_SPP("Insecure RFCOMM (SPP UUID)"),
    SECURE_SPP("Secure RFCOMM (SPP UUID)"),
    REFLECTION_INSECURE_CH1("Reflection Insecure (Ch 1)"),
    REFLECTION_SECURE_CH1("Reflection Secure (Ch 1)"),
    REFLECTION_CH2("Reflection Fallback (Ch 2)")
}

enum class ObdProtocol(val displayName: String, val atCommand: String) {
    AUTO_WITH_KLINE_FALLBACK("Auto + K-Line Fallback (2003 CL600)", "ATSP0"),
    KWP_FAST_5("ISO 14230-4 KWP Fast (ATSP5)", "ATSP5"),
    ISO_9141_3("ISO 9141-2 (ATSP3)", "ATSP3"),
    KWP_5BAUD_4("ISO 14230-4 KWP 5-Baud (ATSP4)", "ATSP4"),
    CAN_11_500_6("ISO 15765-4 CAN 11/500 (ATSP6)", "ATSP6")
}

data class ConnectionConfig(
    val transportType: TransportType = TransportType.BLUETOOTH,
    val bluetoothAddress: String = "",
    val bluetoothName: String = "",
    val btStrategy: BtStrategy = BtStrategy.AUTO_ALL,
    val obdProtocol: ObdProtocol = ObdProtocol.AUTO_WITH_KLINE_FALLBACK,
    val wifiHost: String = "192.168.0.10",
    val wifiPort: Int = 35000,
    val manualBaroKpa: Float? = null,
    val autoConnect: Boolean = false,
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL
)
