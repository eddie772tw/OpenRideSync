package org.openridesync.app.auth

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Explicit diagnostic probe only: no credentials, account requests, or response bodies. */
class NativeNetworkProbeTest {
    @Test fun inspectNativeHttpsTransportWhenRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeNetwork") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (host in listOf("www.googleapis.com", "identitytoolkit.googleapis.com", "securetoken.googleapis.com", "api.honda-roadsync.com")) {
            var connection: HttpsURLConnection? = null
            val result = Bundle().apply { putString("probe_host", host) }
            try {
                result.putBoolean("dns_resolved", InetAddress.getAllByName(host).isNotEmpty())
                connection = URL("https://$host/").openConnection() as HttpsURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "HEAD"
                result.putInt("https_status", connection.responseCode)
            } catch (error: Exception) {
                result.putString("failure", AuthErrors.map(error).summary)
            } finally {
                connection?.disconnect()
            }
            instrumentation.sendStatus(0, result)
        }
    }
}
