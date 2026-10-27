package org.openridesync.app.ble

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode

class Gen1RuntimePolicyTest {
    private fun info(type: Int?, series: String?) = BtuInfo(series, "01.00", "1",
        null, null, null, null, type, null, null, null, null)

    @Test fun newValidSeriesInheritsOemPolicyWithoutModelAllowlist() {
        assertTrue(runtimeColors(info(1, "FFFF")))
        assertFalse(runtimeColors(info(1, "03F5")))
        assertFalse(runtimeColors(info(0, "FFFF")))
        assertTrue(runtimeColors(info(1, "FF")))
        assertTrue(runtimeColors(info(1, "FFFFFFFF")))
    }

    @Test fun absentOrUnrecognizedInformationCannotGuessColorPolicy() {
        for (value in listOf(info(null, "FFFF"), info(255, "FFFF"), info(2, "FFFF"),
            info(1, null), info(1, ""), info(1, "fffF"), info(1, "FFFFFF"))) {
            val failure = assertThrows(GattFailure::class.java) { runtimeColors(value) }
            assertEquals(ErrorCode.UNSUPPORTED, failure.code)
        }
    }
}
