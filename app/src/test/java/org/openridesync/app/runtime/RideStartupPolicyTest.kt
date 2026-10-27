package org.openridesync.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RideStartupPolicyTest {
    @Test fun warningRequiresMissingPhoneNumberPermissionOrUnhandledHereConsent() {
        val expected = listOf(false, true, false, true, true, true, false, true)
        var index = 0
        for (state in listOf(false, true)) for (numbers in listOf(false, true)) for (here in listOf(false, true))
            assertEquals(expected[index++], needsRideStartupWarning(state, numbers, here))
    }
}
