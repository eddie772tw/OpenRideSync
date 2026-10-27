package org.openridesync.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentTimeServerCompatibilityTest {
    @Test fun unselectedPeerAlwaysReceivesTheSameAttAuthorizationError() {
        // 0x08 is the ATT value, independent of when Android added a named constant.
        // Readiness and malformed writes must not weaken or replace this boundary.
        for (ready in listOf(false, true)) {
            for (known in listOf(false, true)) {
                for (prepared in listOf(false, true)) {
                    for (offset in listOf(-1, 0, 1)) {
                        for (value in listOf(byteArrayOf(), byteArrayOf(1),
                            byteArrayOf(0, 0), byteArrayOf(1, 0), byteArrayOf(2, 0))) {
                            assertEquals(0x08, ctsCccStatus(
                                selected = false, ready = ready, known = known,
                                prepared = prepared, offset = offset, value = value,
                            ))
                        }
                    }
                }
            }
        }
    }
}
