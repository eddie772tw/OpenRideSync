package org.openridesync.app.guides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GuideUrlPolicyTest {
    @Test fun tutorialConsumesOnlyExactBridgeAndSingleFinishAction() {
        val finish = "roadsync://webview-tutorial?action=tapped_finish_button&screenName=synthetic-screen"
        org.junit.Assert.assertTrue(GuideUrlPolicy.isTutorialAction(finish))
        org.junit.Assert.assertTrue(GuideUrlPolicy.isTutorialFinish(finish))
        val other = "roadsync://webview-tutorial?action=other_action&screenName=synthetic-screen"
        org.junit.Assert.assertTrue(GuideUrlPolicy.isTutorialAction(other))
        org.junit.Assert.assertFalse(GuideUrlPolicy.isTutorialFinish(other))
        listOf("roadsync://other-host?action=tapped_finish_button", "https://webview-tutorial/?action=tapped_finish_button",
            "roadsync://user@webview-tutorial?action=tapped_finish_button", "roadsync://webview-tutorial:443?action=tapped_finish_button",
            "roadsync:webview-tutorial?action=tapped_finish_button", "roadsync://webview-tutorial\n?action=tapped_finish_button").forEach {
            org.junit.Assert.assertFalse(GuideUrlPolicy.isTutorialAction(it))
            org.junit.Assert.assertFalse(GuideUrlPolicy.isTutorialFinish(it))
        }
        org.junit.Assert.assertFalse(GuideUrlPolicy.isTutorialFinish("roadsync://webview-tutorial?action=tapped_finish_button&action=other"))
        org.junit.Assert.assertFalse(GuideUrlPolicy.isTutorialFinish("roadsync://webview-tutorial?screenName=tapped_finish_button"))
    }

    @Test fun documentOriginAllowsOnlyEquivalentHttpsAuthority() {
        val origin = GuideUrlPolicy.requireHttps("https://guide.example.invalid/start")
        listOf("https://GUIDE.example.invalid:443/next#step", "https://guide.example.invalid/other").forEach {
            org.junit.Assert.assertTrue(GuideUrlPolicy.sameOrigin(origin, it))
        }
        listOf("https://guide.example.invalid.attacker.invalid/", "https://other.example.invalid/",
            "http://guide.example.invalid/", "https://guide.example.invalid:8443/",
            "https://user@guide.example.invalid/", "intent://guide/", "data:text/html,example").forEach {
            org.junit.Assert.assertFalse(GuideUrlPolicy.sameOrigin(origin, it))
        }
    }

    @Test fun verifiedRpcDocumentRetainsPathQueryAndSupportsNewCdnOrigins() {
        val value = "https://new-cdn.example.invalid/tutorial/model%20name/index.html?v=2#step-1"
        assertEquals(value, GuideUrlPolicy.requireHttps(value).toASCIIString())
        assertEquals(443, GuideUrlPolicy.requireHttps("HTTPS://cdn.example.invalid:443/setup").port)
        assertEquals("[2001:db8::1]", GuideUrlPolicy.requireHttps("https://[2001:db8::1]/setup").host)
    }

    @Test fun invalidAuthorityOrNonHttpsCannotBecomeDocumentTrustRoot() {
        listOf(
            "http://cdn.example.invalid/", "file:///private/guide.html", "content://guide/1",
            "javascript:alert(1)", "intent://guide/#Intent;end", "//cdn.example.invalid/",
            "https:guide.html", "https:///guide.html", "https://user@cdn.example.invalid/",
            "https://@cdn.example.invalid/", "https://cdn.example.invalid:8443/",
            "https://cdn.example.invalid:/", "https://cdn.example.invalid:bad/",
            "https://cdn.example.invalid\\@other.invalid/", "https://cdn.example.invalid\n/",
            "https://cdn.example.invalid/\u0000", "https://cdn.example.invalid/\u0085",
            "https://cdn.example.invalid%2f.other.invalid/", "https://bad_host.invalid/",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { GuideUrlPolicy.requireHttps(value) }
        }
    }
}
