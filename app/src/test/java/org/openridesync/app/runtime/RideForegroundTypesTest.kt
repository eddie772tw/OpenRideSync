package org.openridesync.app.runtime

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RideForegroundTypesTest {
    @Test fun optionalWeatherRequiresPermissionLocationAndVisibleApp() {
        for (granted in listOf(false, true)) for (enabled in listOf(false, true)) for (visible in listOf(false, true)) {
            val types = rideForegroundTypes(granted, enabled, visible)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            assertEquals(if (granted && enabled && visible) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
                types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }
    }

    @Test fun backgroundVoiceRequiresMicrophonePermissionAndForegroundPromotion() {
        for (microphone in listOf(false, true)) for (visible in listOf(false, true)) {
            val types = rideForegroundTypes(true, true, visible, microphone)
            assertEquals(if (microphone && visible) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
                types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            assertEquals(if (visible) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
                types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            rideForegroundTypes(false, false, false, true))
    }
}
