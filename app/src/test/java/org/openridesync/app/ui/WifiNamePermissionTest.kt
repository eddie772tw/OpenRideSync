package org.openridesync.app.ui

import android.Manifest
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WifiNamePermissionTest {
    @Test fun weatherAndRecoveryOnlyRequestApproximateLocationAndExplainManualFallback() {
        assertArrayEquals(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), AppAccess.LOCATION.permissions())
        assertTrue(AppAccess.LOCATION.explanation.contains("60 秒"))
        assertTrue(AppAccess.LOCATION.explanation.contains("手動連線"))
        assertTrue(AppAccess.LOCATION.explanation.contains("不要求精確位置或背景位置權限"))
        assertTrue(AppAccess.LOCATION.explanation.contains("不保存座標"))
    }
    @Test fun approximateWeatherRemainsIndependentAndSsidRequestsFineTogetherWithCoarse() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(AppAccess.LOCATION.granted(app))
        assertFalse(AppAccess.WIFI_NAME.granted(app))
        assertArrayEquals(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            AppAccess.WIFI_NAME.requestPermissions(app))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(AppAccess.WIFI_NAME.granted(app))
    }
}
