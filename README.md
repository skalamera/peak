# CL600 OBD — Android Head Unit Gauge & Diagnostic App

Lightweight, landscape-first OBD-II dashboard built for a **2003 Mercedes-Benz CL600 (C215, M275 V12 biturbo)** running on aftermarket Android head units (`Android 4.4 KitKat / API 19` through `Android 14 / API 34`).

## Features

- **Analog + Digital Gauge Dashboard**:
  - **Boost / Vacuum** analog dial (PSI or BAR, with inHg vacuum sub-readout and peak-hold marker)
  - **Intake Air Temp (IAT)** analog dial (°F or °C with M275 heat-soak color zones and peak-hold marker)
  - **Coolant Temp**, **Battery Voltage**, **RPM**, plus a tappable 4th tile cycling **Engine Load %**, **Timing Advance °**, **Throttle %**, and **Vehicle Speed**
- **View & Clear Engine Fault Codes**:
  - Reads **Mode 03** (Stored), **Mode 07** (Pending), **Mode 0A** (Permanent, if supported), and **Mode 0101** (MIL status)
  - Supports both **K-Line** (`ISO 14230-4 KWP2000` / `ISO 9141-2` on 2003 CL600 ME 2.7.1) and **CAN** (`ISO 15765-4`) frame formats
  - Built-in SAE + M275 V12 fault dictionary (including Cylinders 1–12 misfires, coil pack / voltage transformer notes, IAT / intercooler pump tips, secondary air pump)
  - Safe **Clear Codes (Mode 04)** with confirmation prompt
- **Head-Unit-Resilient Bluetooth & Wi-Fi Transport**:
  - Automatically cycles through **Insecure SPP**, **Secure SPP**, **Reflection Ch 1 (Insecure/Secure)**, and **Reflection Ch 2** RFCOMM sockets
  - Manual MAC entry fallback if your head unit hides `OBDII` from Android's paired devices list
  - **Wi-Fi ELM327** TCP fallback (`192.168.0.10:35000`)
  - **Demo V12 Mode** to preview all gauges and code scanning off the car
  - **Live Diagnostics Log** + manual AT/OBD command console (`ATZ`, `ATRV`, `ATDP`, `0100`, `010B`, `03`)

## Building the APK

```bash
./gradlew testDebugUnitTest assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## Installing on Your CL600 Android Head Unit

1. **Via USB Drive or SD Card (easiest)**:
   - Copy `app/build/outputs/apk/debug/app-debug.apk` to a FAT32 USB flash drive or SD card.
   - Plug it into your head unit's USB/SD slot, open **File Manager** (or **APK Installer**), tap `app-debug.apk`, and allow installation from Unknown Sources if prompted.
2. **Via ADB over Wi-Fi** (if ADB debugging is enabled on the head unit):
   ```bash
   adb connect <head-unit-ip>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Connecting to `OBDII` in the Car

1. Turn the ignition key to **Position 2** (cluster lights ON; engine can be OFF or running).
2. On aftermarket Android head units, first pair `OBDII` inside the **head unit's built-in Bluetooth app** (usually PIN `1234` or `0000`).
3. Open **CL600 OBD** → **CONNECT**:
   - Select `OBDII` from the list (or enter its MAC address manually).
   - Leave **BT Socket Strategy** on `Auto (Try All Strategies)` and **ECU Protocol** on `Auto + K-Line Fallback`.
   - Tap **CONNECT**.
4. Check the **LOG** tab if anything stalls:
   - You'll see the exact Bluetooth socket strategy attempts and ELM327 `ATZ`/`0100` responses.
   - If the head unit's internal Bluetooth chip is locked to calls/A2DP only and rejects every RFCOMM strategy with `Service discovery failed` or `Connection refused`, a cheap **Wi-Fi ELM327** adapter works immediately via the **Wi-Fi ELM327** option.
5. **Tip for Accurate Boost**: With key ON and engine OFF, tap **ZERO BOOST (KOEO)** on the Connect tab once to lock in your local atmospheric pressure.
