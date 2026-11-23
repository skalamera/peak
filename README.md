# CL600 OBD — Android Head Unit Gauge & Diagnostic App

Lightweight, landscape-first OBD-II dashboard built for a **2003 Mercedes-Benz CL600 (C215, M275 V12 biturbo)** running on aftermarket Android head units (`Android 4.4 KitKat / API 19` through `Android 14 / API 34`).

## Features

- **Analog + Digital Gauge Dashboard**:
  - **Boost / Vacuum** analog dial (`-15..25 PSI` / `-1.0..2.0 BAR`, `20 PSI` warning threshold, inHg vacuum sub-readout, and resettable peak hold)
  - **Intake Air Temp (IAT)** analog dial (°F or °C with M275 heat-soak color zones and resettable peak hold)
  - **Water/Methanol Injection** indicator + **Estimated Output** pill (`HP` / `LB-FT` tuned for M275 Stage 1)
  - **Coolant Temp**, **Battery Voltage**, **RPM**, plus a tappable 4th tile cycling **Est. Power**, **Est. Torque**, **Engine Load %**, **Timing Advance °**, **Throttle %**, and **Vehicle Speed**
- **Direct USB K-Line Support (Galletto 1260 FTDI Cable)**:
  - Talks directly over USB Serial (`10400 baud, 8N1`) to the 2003 CL600 Bosch **ME 2.7.1** ECU without needing an ELM327 interpreter or head-unit Bluetooth stack
  - Supports **KWP2000 Fast Init** (`25ms LOW / 25ms HIGH` + `C1 33 F1 81 66`) and **ISO 9141-2 5-Baud Slow Init** (`0x33` wakeup, `0x55/KW1/KW2`, `~KW2`, `0xCC`)
  - Automatically strips half-duplex K-Line local echoes, enforces `P3` inter-message timing, sets FTDI 1ms low-latency timer, and maintains `0105` keep-alive heartbeats
- **View & Clear Engine Fault Codes**:
  - Reads **Mode 03** (Stored), **Mode 07** (Pending), **Mode 0A** (Permanent, if supported on Wi-Fi ELM327), and **Mode 0101** (MIL status)
  - Built-in SAE + M275 V12 fault dictionary (Cylinders 1–12 misfires, coil pack / voltage transformer notes, IAT / intercooler pump tips, secondary air pump)
  - Safe **Clear Codes (Mode 04)** with confirmation prompt
- **Wi-Fi ELM327 & Demo V12 Modes**:
  - **Wi-Fi ELM327** TCP fallback (`192.168.0.10:35000`) bound directly to the Android Wi-Fi network
  - **Demo V12 Mode** to preview all gauges and code scanning off the car
  - **Live Diagnostics Log** + manual raw OBD hex / AT command console

## Building the APK

```bash
./gradlew testDebugUnitTest assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## Connecting Your Galletto 1260 Cable in the Car

1. Plug the **Galletto 1260 EOBD** USB end directly into one of the head unit's rear **4-pin USB data pigtails** (avoid charge-only USB pigtails or unpowered multi-port hubs).
2. Plug the OBD2 connector firmly into the car's OBD2 port under the dash and turn the ignition key to **Position 2** (cluster lights ON; engine OFF or running).
3. Open **CL600 OBD** → **CONNECT**:
   - Select **USB K-Line (Galletto 1260)** (default).
   - Tap **SCAN USB** if needed to confirm `FTDI` (`VID:0403`) is detected, then tap **CONNECT** and grant the Android USB permission popup.
4. Check the **LOG** tab to view the exact `Sync: 55 XX YY`, `Ack: ... CC` or `KWP StartComm` handshake and live hex frames.
5. **Tip for Accurate Boost**: With key ON and engine OFF, tap **ZERO BOOST (KOEO)** on the Connect tab once to lock in your local atmospheric pressure.
