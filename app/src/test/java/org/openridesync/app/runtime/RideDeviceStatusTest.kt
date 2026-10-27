package org.openridesync.app.runtime

import org.junit.Assert.*
import org.junit.Test

class RideDeviceStatusTest {
    @Test fun currentSsidRemovesOnlyTheAndroidWrapperAndRejectsMissingOrUnsafeNames() {
        assertEquals("Home Wi-Fi", wifiSsid("\"Home Wi-Fi\""))
        assertEquals("Home Wi-Fi", wifiSsid("  Home Wi-Fi  "))
        assertEquals(" Home ", wifiSsid("\" Home \"")) // Spaces inside a quoted SSID belong to its name.
        assertEquals("a\"b", wifiSsid("\"a\"b\""))
        assertEquals("網路", wifiSsid("\"網路\""))
        assertEquals("x".repeat(32), wifiSsid("\"${"x".repeat(32)}\""))
        for (raw in listOf(null, "", " ", "\"\"", "\" \"", "<unknown ssid>", "\"<UNKNOWN SSID>\"",
            "name\n", "\tname", "name\u0000", "name\u007f", "name\u0085", "name\u202e")) assertNull(wifiSsid(raw))
    }

    @Test fun operatorNamesAreTrimmedWithoutTreatingTheirQuotesAsAnSsidWrapper() {
        assertEquals("Carrier", signalName("  Carrier  "))
        assertEquals("業者 5G", signalName("業者 5G"))
        assertEquals("\"Carrier\"", signalName("\"Carrier\""))
        for (raw in listOf(null, "", " ", "Carrier\r", "Carrier\u0000", "Carrier\u2066")) assertNull(signalName(raw))
    }

    @Test fun missingSsidExplainsTheApplicablePrerequisiteWithoutReplacingObservedStrength() {
        assertEquals(SignalNameIssue.PERMISSION_REQUIRED, missingWifiNameIssue(false, false))
        assertEquals(SignalNameIssue.PERMISSION_REQUIRED, missingWifiNameIssue(false, true))
        assertEquals(SignalNameIssue.LOCATION_OFF, missingWifiNameIssue(true, false))
        assertEquals(SignalNameIssue.UNAVAILABLE, missingWifiNameIssue(true, true))
        assertEquals(SignalNameIssue.UNAVAILABLE, missingWifiNameIssue(true, null))
        val signal = RideSignal(SignalAvailability.READY, 3, nameIssue = missingWifiNameIssue(false, true))
        assertEquals(3, signal.level)
        assertEquals("3/4", signalText(signal))
        assertNull(signal.name)
    }

    @Test fun zeroBatteryIsRealAndInvalidMeasurementsAreNeverMadeIntoZero() {
        assertEquals(0, batteryPercentage(0, 100))
        assertEquals(100, batteryPercentage(100, 100))
        assertEquals(25, batteryPercentage(50, 200))
        assertEquals(100, batteryPercentage(Int.MAX_VALUE, Int.MAX_VALUE))
        for ((level, scale) in listOf(-1 to 100, 1 to 0, 101 to 100, 0 to -1))
            assertNull(batteryPercentage(level, scale))
    }

    @Test fun missingSignalIsDifferentFromObservedWeakSignalAndInactiveSourcesIgnoreOldLevels() {
        assertEquals("未知", signalText(RideSignal()))
        assertEquals("強度未知", signalText(RideSignal(SignalAvailability.READY)))
        assertEquals("0/4", signalText(RideSignal(SignalAvailability.READY, 0)))
        assertEquals("4/4", signalText(RideSignal(SignalAvailability.READY, 4)))
        assertEquals("強度未知", signalText(RideSignal(SignalAvailability.READY, 5)))
        assertEquals("已關閉", signalText(RideSignal(SignalAvailability.OFF, 4)))
        assertEquals("無 SIM", signalText(RideSignal(SignalAvailability.NO_SIM, 4)))
        assertEquals("未連線", signalText(RideSignal(SignalAvailability.DISCONNECTED, 4)))
        assertEquals("不支援", signalText(RideSignal(SignalAvailability.UNSUPPORTED, 4)))
    }
}
