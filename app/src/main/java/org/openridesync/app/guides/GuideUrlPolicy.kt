package org.openridesync.app.guides

import java.net.URI

/** Applies to document URLs supplied by verified Honda RPCs, not arbitrary user input. */
internal object GuideUrlPolicy {
    fun isTutorialAction(value: String): Boolean {
        if (value.any { it.isISOControl() }) return false
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { return false }
        return !uri.isOpaque && uri.scheme.equals("roadsync", ignoreCase = true) &&
            uri.host.equals("webview-tutorial", ignoreCase = true) && uri.rawUserInfo == null && uri.port == -1
    }

    fun isTutorialFinish(value: String): Boolean {
        if (!isTutorialAction(value)) return false
        val actions = URI(value).rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "action" }
        if (actions.size != 1) return false
        val action = try { java.net.URLDecoder.decode(actions.single().substringAfter('=', ""), "UTF-8") }
            catch (_: IllegalArgumentException) { return false }
        return action == "tapped_finish_button"
    }

    fun sameOrigin(document: URI, value: String): Boolean {
        val target = try { requireHttps(value) } catch (_: IllegalArgumentException) { return false }
        return document.scheme.equals(target.scheme, ignoreCase = true) &&
            document.host.equals(target.host, ignoreCase = true) &&
            (if (document.port == -1) 443 else document.port) == (if (target.port == -1) 443 else target.port)
    }

    fun requireHttps(value: String): URI {
        require(value.none { it.isISOControl() }) { "invalid guide URL" }
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) {
            throw IllegalArgumentException("invalid guide URL")
        }
        require(uri.isAbsolute && !uri.isOpaque && uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            !uri.rawAuthority.endsWith(":")) { "invalid guide URL" }
        return uri
    }
}
