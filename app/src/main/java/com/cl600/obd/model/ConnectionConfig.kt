package com.cl600.obd.model

enum class TransportType(val displayName: String) {
    USB_KLINE("USB K-Line (Galletto 1260)"),
    WIFI("Wi-Fi ELM327"),
    DEMO("Demo")
}

enum class ObdProtocol(val displayName: String, val atCommand: String) {
    AUTO_WITH_KLINE_FALLBACK("Auto K-Line (KWP Fast → 5-Baud Slow)", "ATSP0"),
    ISO_9141_3("ISO 9141-2 (5-Baud Slow Init)", "ATSP3"),
    KWP_FAST_5("ISO 14230-4 KWP2000 (Fast Init)", "ATSP5"),
    KWP_5BAUD_4("ISO 14230-4 KWP2000 (5-Baud Init)", "ATSP4"),
    CAN_11_500_6("ISO 15765-4 CAN 11/500 (Wi-Fi ELM327)", "ATSP6")
}

data class ConnectionConfig(
    val transportType: TransportType = TransportType.USB_KLINE,
    val usbDeviceName: String = "",
    val obdProtocol: ObdProtocol = ObdProtocol.AUTO_WITH_KLINE_FALLBACK,
    val wifiHost: String = "192.168.0.10",
    val wifiPort: Int = 35000,
    val manualBaroKpa: Float? = null,
    val autoConnect: Boolean = false,
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL
)
