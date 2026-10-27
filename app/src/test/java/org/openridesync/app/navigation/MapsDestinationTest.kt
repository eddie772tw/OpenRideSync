package org.openridesync.app.navigation

import android.app.Application
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MapsDestinationTest {
    @Test fun destinationIsEncodedAndPinnedToMaps() {
        val intent = checkNotNull(mapsDestinationIntent(" 台北車站 &mode=w#test "))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MAPS_PACKAGE, intent.`package`)
        assertEquals("google.navigation:q=%E5%8F%B0%E5%8C%97%E8%BB%8A%E7%AB%99%20%26mode%3Dw%23test&mode=d", intent.dataString)
    }
    @Test fun emptyControlAndOversizedInputCannotLaunch() {
        listOf("", "  ", "test\naddress", "\u0000address", "x".repeat(513)).forEach { assertNull(mapsDestinationIntent(it)) }
        assertNotNull(mapsDestinationIntent("25.0478,121.5170"))
        assertNotNull(mapsDestinationIntent("x".repeat(512)))
    }
}
