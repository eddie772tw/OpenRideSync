package org.openridesync.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentTimeServerPolicyTest {
    @Test fun cccWritesRequireSelectedReadyPeerAndExactNotificationValue() {
        fun status(value: ByteArray = byteArrayOf(1, 0), selected: Boolean = true,
                   ready: Boolean = true, known: Boolean = true, prepared: Boolean = false,
                   offset: Int = 0) = ctsCccStatus(selected, ready, known, prepared, offset, value)
        assertEquals(0, status())
        assertEquals(0, status(byteArrayOf(0, 0)))
        assertEquals(8, status(selected = false))
        assertEquals(257, status(ready = false))
        assertEquals(6, status(known = false))
        assertEquals(6, status(prepared = true))
        assertEquals(7, status(offset = 1))
        assertEquals(13, status(byteArrayOf(1)))
        assertEquals(6, status(byteArrayOf(2, 0)))
        assertEquals(6, status(byteArrayOf(1, 1)))
    }
}
