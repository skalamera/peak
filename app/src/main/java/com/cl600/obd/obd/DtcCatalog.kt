package com.cl600.obd.obd

import com.cl600.obd.model.DtcCategory
import com.cl600.obd.model.DtcEntry
import java.util.Locale

object DtcCatalog {

    private data class CodeInfo(
        val description: String,
        val m275Note: String? = null
    )

    private const val M275_COIL_NOTE =
        "M275 V12 note: Common causes are a failing left/right bank ignition coil rail, spark plugs, or the center ignition voltage transformer."

    private const val M275_IAT_NOTE =
        "M275 V12 note: Check the charge-air intercooler coolant pump (Bosch 010 upgrade) and IAT sensor; high IATs cause timing pull and power loss."

    private const val M275_BOOST_NOTE =
        "M275 V12 note: Inspect turbo vacuum lines, charge pipes, intercooler couplers, diverter valves, and wastegate actuators."

    private val KNOWN_CODES: Map<String, CodeInfo> = mapOf(
        // Fuel & Air Metering / Boost / IAT / Coolant
        "P0100" to CodeInfo("Mass or Volume Air Flow Circuit Malfunction"),
        "P0101" to CodeInfo("Mass or Volume Air Flow Circuit Range/Performance"),
        "P0105" to CodeInfo("Manifold Absolute Pressure (MAP) / Barometric Pressure Circuit Malfunction", M275_BOOST_NOTE),
        "P0106" to CodeInfo("Manifold Absolute Pressure (MAP) / Barometric Pressure Range/Performance", M275_BOOST_NOTE),
        "P0107" to CodeInfo("Manifold Absolute Pressure (MAP) Circuit Low Input", M275_BOOST_NOTE),
        "P0108" to CodeInfo("Manifold Absolute Pressure (MAP) Circuit High Input", M275_BOOST_NOTE),
        "P0110" to CodeInfo("Intake Air Temperature (IAT) Sensor 1 Circuit Malfunction", M275_IAT_NOTE),
        "P0111" to CodeInfo("Intake Air Temperature (IAT) Sensor 1 Circuit Range/Performance", M275_IAT_NOTE),
        "P0112" to CodeInfo("Intake Air Temperature (IAT) Sensor 1 Circuit Low Input", M275_IAT_NOTE),
        "P0113" to CodeInfo("Intake Air Temperature (IAT) Sensor 1 Circuit High Input", M275_IAT_NOTE),
        "P0115" to CodeInfo("Engine Coolant Temperature (ECT) Circuit Malfunction"),
        "P0116" to CodeInfo("Engine Coolant Temperature (ECT) Circuit Range/Performance"),
        "P0117" to CodeInfo("Engine Coolant Temperature (ECT) Circuit Low Input"),
        "P0118" to CodeInfo("Engine Coolant Temperature (ECT) Circuit High Input"),
        "P0120" to CodeInfo("Throttle/Pedal Position Sensor/Switch A Circuit Malfunction"),
        "P0121" to CodeInfo("Throttle/Pedal Position Sensor/Switch A Range/Performance"),
        "P0125" to CodeInfo("Insufficient Coolant Temperature for Closed Loop Fuel Control"),
        "P0128" to CodeInfo("Coolant Thermostat (Coolant Temperature Below Thermostat Regulating Temperature)", "Common on aging M275 thermostats stuck partially open."),

        // O2 Sensors & Fuel Trim (Banks 1 & 2)
        "P0130" to CodeInfo("O2 Sensor Circuit Malfunction (Bank 1 Sensor 1)"),
        "P0131" to CodeInfo("O2 Sensor Circuit Low Voltage (Bank 1 Sensor 1)"),
        "P0132" to CodeInfo("O2 Sensor Circuit High Voltage (Bank 1 Sensor 1)"),
        "P0133" to CodeInfo("O2 Sensor Circuit Slow Response (Bank 1 Sensor 1)"),
        "P0134" to CodeInfo("O2 Sensor Circuit No Activity Detected (Bank 1 Sensor 1)"),
        "P0135" to CodeInfo("O2 Sensor Heater Circuit Malfunction (Bank 1 Sensor 1)"),
        "P0136" to CodeInfo("O2 Sensor Circuit Malfunction (Bank 1 Sensor 2)"),
        "P0140" to CodeInfo("O2 Sensor Circuit No Activity Detected (Bank 1 Sensor 2)"),
        "P0141" to CodeInfo("O2 Sensor Heater Circuit Malfunction (Bank 1 Sensor 2)"),
        "P0150" to CodeInfo("O2 Sensor Circuit Malfunction (Bank 2 Sensor 1)"),
        "P0151" to CodeInfo("O2 Sensor Circuit Low Voltage (Bank 2 Sensor 1)"),
        "P0152" to CodeInfo("O2 Sensor Circuit High Voltage (Bank 2 Sensor 1)"),
        "P0153" to CodeInfo("O2 Sensor Circuit Slow Response (Bank 2 Sensor 1)"),
        "P0154" to CodeInfo("O2 Sensor Circuit No Activity Detected (Bank 2 Sensor 1)"),
        "P0155" to CodeInfo("O2 Sensor Heater Circuit Malfunction (Bank 2 Sensor 1)"),
        "P0156" to CodeInfo("O2 Sensor Circuit Malfunction (Bank 2 Sensor 2)"),
        "P0160" to CodeInfo("O2 Sensor Circuit No Activity Detected (Bank 2 Sensor 2)"),
        "P0161" to CodeInfo("O2 Sensor Heater Circuit Malfunction (Bank 2 Sensor 2)"),
        "P0170" to CodeInfo("Fuel Trim Malfunction (Bank 1)", "Check for intake/vacuum leaks after the turbos or failing O2 sensor."),
        "P0171" to CodeInfo("System Too Lean (Bank 1)", "Check for post-turbo boost/vacuum leaks, breather hoses, or low fuel pressure."),
        "P0172" to CodeInfo("System Too Rich (Bank 1)"),
        "P0173" to CodeInfo("Fuel Trim Malfunction (Bank 2)", "Check for intake/vacuum leaks after the turbos or failing O2 sensor."),
        "P0174" to CodeInfo("System Too Lean (Bank 2)", "Check for post-turbo boost/vacuum leaks, breather hoses, or low fuel pressure."),
        "P0175" to CodeInfo("System Too Rich (Bank 2)"),

        // Fuel Rail / Injectors / Turbocharger Boost
        "P0190" to CodeInfo("Fuel Rail Pressure Sensor Circuit Malfunction"),
        "P0191" to CodeInfo("Fuel Rail Pressure Sensor Circuit Range/Performance"),
        "P0201" to CodeInfo("Injector Circuit Malfunction - Cylinder 1"),
        "P0202" to CodeInfo("Injector Circuit Malfunction - Cylinder 2"),
        "P0203" to CodeInfo("Injector Circuit Malfunction - Cylinder 3"),
        "P0204" to CodeInfo("Injector Circuit Malfunction - Cylinder 4"),
        "P0205" to CodeInfo("Injector Circuit Malfunction - Cylinder 5"),
        "P0206" to CodeInfo("Injector Circuit Malfunction - Cylinder 6"),
        "P0207" to CodeInfo("Injector Circuit Malfunction - Cylinder 7"),
        "P0208" to CodeInfo("Injector Circuit Malfunction - Cylinder 8"),
        "P0209" to CodeInfo("Injector Circuit Malfunction - Cylinder 9"),
        "P0210" to CodeInfo("Injector Circuit Malfunction - Cylinder 10"),
        "P0211" to CodeInfo("Injector Circuit Malfunction - Cylinder 11"),
        "P0212" to CodeInfo("Injector Circuit Malfunction - Cylinder 12"),
        "P0234" to CodeInfo("Turbocharger Overboost Condition", M275_BOOST_NOTE),
        "P0235" to CodeInfo("Turbocharger Boost Sensor A Circuit Malfunction", M275_BOOST_NOTE),
        "P0236" to CodeInfo("Turbocharger Boost Sensor A Circuit Range/Performance", M275_BOOST_NOTE),
        "P0237" to CodeInfo("Turbocharger Boost Sensor A Circuit Low", M275_BOOST_NOTE),
        "P0238" to CodeInfo("Turbocharger Boost Sensor A Circuit High", M275_BOOST_NOTE),
        "P0243" to CodeInfo("Turbocharger Wastegate Solenoid A Malfunction", M275_BOOST_NOTE),
        "P0244" to CodeInfo("Turbocharger Wastegate Solenoid A Range/Performance", M275_BOOST_NOTE),
        "P0245" to CodeInfo("Turbocharger Wastegate Solenoid A Low", M275_BOOST_NOTE),
        "P0246" to CodeInfo("Turbocharger Wastegate Solenoid A High", M275_BOOST_NOTE),
        "P0299" to CodeInfo("Turbocharger Underboost Condition", M275_BOOST_NOTE),

        // V12 Ignition & Misfires (Cylinders 1-12)
        "P0300" to CodeInfo("Random/Multiple Cylinder Misfire Detected", M275_COIL_NOTE),
        "P0301" to CodeInfo("Cylinder 1 Misfire Detected (Bank 1 / Right Bank)", M275_COIL_NOTE),
        "P0302" to CodeInfo("Cylinder 2 Misfire Detected (Bank 1 / Right Bank)", M275_COIL_NOTE),
        "P0303" to CodeInfo("Cylinder 3 Misfire Detected (Bank 1 / Right Bank)", M275_COIL_NOTE),
        "P0304" to CodeInfo("Cylinder 4 Misfire Detected (Bank 1 / Right Bank)", M275_COIL_NOTE),
        "P0305" to CodeInfo("Cylinder 5 Misfire Detected (Bank 1 / Right Bank)", M275_COIL_NOTE),
        "P0306" to CodeInfo("Cylinder 6 Misfire Detected (Bank 1 / Right Bank)", M275_COIL_NOTE),
        "P0307" to CodeInfo("Cylinder 7 Misfire Detected (Bank 2 / Left Bank)", M275_COIL_NOTE),
        "P0308" to CodeInfo("Cylinder 8 Misfire Detected (Bank 2 / Left Bank)", M275_COIL_NOTE),
        "P0309" to CodeInfo("Cylinder 9 Misfire Detected (Bank 2 / Left Bank)", M275_COIL_NOTE),
        "P0310" to CodeInfo("Cylinder 10 Misfire Detected (Bank 2 / Left Bank)", M275_COIL_NOTE),
        "P0311" to CodeInfo("Cylinder 11 Misfire Detected (Bank 2 / Left Bank)", M275_COIL_NOTE),
        "P0312" to CodeInfo("Cylinder 12 Misfire Detected (Bank 2 / Left Bank)", M275_COIL_NOTE),

        "P0325" to CodeInfo("Knock Sensor 1 Circuit Malfunction (Bank 1)"),
        "P0330" to CodeInfo("Knock Sensor 2 Circuit Malfunction (Bank 2)"),
        "P0335" to CodeInfo("Crankshaft Position Sensor A Circuit Malfunction", "Classic Mercedes no-start / hot-stall symptom when CPS ages."),
        "P0340" to CodeInfo("Camshaft Position Sensor A Circuit Malfunction (Bank 1)"),
        "P0345" to CodeInfo("Camshaft Position Sensor A Circuit Malfunction (Bank 2)"),

        "P0351" to CodeInfo("Ignition Coil A Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0352" to CodeInfo("Ignition Coil B Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0353" to CodeInfo("Ignition Coil C Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0354" to CodeInfo("Ignition Coil D Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0355" to CodeInfo("Ignition Coil E Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0356" to CodeInfo("Ignition Coil F Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0357" to CodeInfo("Ignition Coil G Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0358" to CodeInfo("Ignition Coil H Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0359" to CodeInfo("Ignition Coil I Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0360" to CodeInfo("Ignition Coil J Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0361" to CodeInfo("Ignition Coil K Primary/Secondary Circuit", M275_COIL_NOTE),
        "P0362" to CodeInfo("Ignition Coil L Primary/Secondary Circuit", M275_COIL_NOTE),

        // Emissions, Secondary Air, Catalysts, EVAP
        "P0400" to CodeInfo("Exhaust Gas Recirculation Flow Malfunction"),
        "P0410" to CodeInfo("Secondary Air Injection System Malfunction", "Very common on C215/W220: check secondary air pump relay, fuse, or check valves."),
        "P0411" to CodeInfo("Secondary Air Injection System Incorrect Flow Detected"),
        "P0420" to CodeInfo("Catalyst System Efficiency Below Threshold (Bank 1)"),
        "P0422" to CodeInfo("Main Catalyst Efficiency Below Threshold (Bank 1)"),
        "P0430" to CodeInfo("Catalyst System Efficiency Below Threshold (Bank 2)"),
        "P0432" to CodeInfo("Main Catalyst Efficiency Below Threshold (Bank 2)"),
        "P0440" to CodeInfo("Evaporative Emission Control System Malfunction"),
        "P0441" to CodeInfo("Evaporative Emission Control System Incorrect Purge Flow"),
        "P0442" to CodeInfo("Evaporative Emission Control System Leak Detected (Small Leak)", "Check fuel cap seal and purge valve."),
        "P0443" to CodeInfo("Evaporative Emission Control System Purge Control Valve Circuit"),
        "P0446" to CodeInfo("Evaporative Emission Control System Vent Control Circuit"),
        "P0455" to CodeInfo("Evaporative Emission Control System Leak Detected (Large Leak)", "Check fuel cap seating first."),
        "P0456" to CodeInfo("Evaporative Emission Control System Leak Detected (Very Small Leak)"),

        // Speed, Idle, System Voltage, ECU, Transmission
        "P0500" to CodeInfo("Vehicle Speed Sensor Malfunction"),
        "P0505" to CodeInfo("Idle Control System Malfunction"),
        "P0506" to CodeInfo("Idle Control System RPM Lower Than Expected"),
        "P0507" to CodeInfo("Idle Control System RPM Higher Than Expected"),
        "P0560" to CodeInfo("System Voltage Malfunction", "Check alternator voltage regulator and trunk/starter batteries."),
        "P0562" to CodeInfo("System Voltage Low", "Check battery charge and alternator output (should be ~13.7-14.2V running)."),
        "P0563" to CodeInfo("System Voltage High", "Check alternator voltage regulator."),
        "P0600" to CodeInfo("Serial Communication Link Malfunction"),
        "P0700" to CodeInfo("Transmission Control System Malfunction (MIL Request)", "722.6 5-speed automatic; check conductor plate / ATF level / TCU connector for oil wicking."),
        "P0705" to CodeInfo("Transmission Range Sensor Circuit Malfunction"),
        "P0715" to CodeInfo("Input/Turbine Speed Sensor Circuit Malfunction", "Common 722.6 conductor plate symptom."),
        "P0720" to CodeInfo("Output Speed Sensor Circuit Malfunction"),
        "P0730" to CodeInfo("Incorrect Gear Ratio"),
        "P0740" to CodeInfo("Torque Converter Clutch Circuit Malfunction"),
        "P0741" to CodeInfo("Torque Converter Clutch Circuit Performance or Stuck Off"),

        // Common Mercedes-Benz P2xxx / P1xxx OBD-II codes
        "P1570" to CodeInfo("Mercedes DAS / Immobilizer Communication Fault"),
        "P2004" to CodeInfo("Intake Manifold Runner Control Stuck Open (Bank 1)"),
        "P2006" to CodeInfo("Intake Manifold Runner Control Stuck Closed (Bank 1)"),
        "P2036" to CodeInfo("Secondary Air Injection System Air Flow Too Low / Sensor Fault"),
        "P20D4" to CodeInfo("Load Limit Active / Boost Control Intervention"),
        "P2187" to CodeInfo("System Too Lean at Idle (Bank 1)"),
        "P2188" to CodeInfo("System Too Rich at Idle (Bank 1)"),
        "P2189" to CodeInfo("System Too Lean at Idle (Bank 2)"),
        "P2190" to CodeInfo("System Too Rich at Idle (Bank 2)"),
        "P2263" to CodeInfo("Turbocharger/Supercharger Boost System Performance", M275_BOOST_NOTE),
        "P2279" to CodeInfo("Intake Air System Leak", M275_BOOST_NOTE)
    )

    fun lookup(code: String, category: DtcCategory): DtcEntry {
        val normalized = code.trim().uppercase(Locale.US)
        val exact = KNOWN_CODES[normalized]
        if (exact != null) {
            return DtcEntry(
                code = normalized,
                category = category,
                description = exact.description,
                m275Note = exact.m275Note
            )
        }

        val fallbackDescription = when {
            normalized.startsWith("P0") -> "Generic OBD-II Powertrain Fault ($normalized)"
            normalized.startsWith("P1") -> "Mercedes-Benz Manufacturer Powertrain Fault ($normalized)"
            normalized.startsWith("P2") -> "SAE/ISO Generic Powertrain Fault ($normalized)"
            normalized.startsWith("P3") -> "Powertrain / Ignition Fault ($normalized)"
            normalized.startsWith("C") -> "Chassis System Fault ($normalized)"
            normalized.startsWith("B") -> "Body System Fault ($normalized)"
            normalized.startsWith("U") -> "CAN / Network Communication Fault ($normalized)"
            else -> "OBD-II Fault Code ($normalized)"
        }

        return DtcEntry(
            code = normalized,
            category = category,
            description = fallbackDescription,
            m275Note = null
        )
    }
}
