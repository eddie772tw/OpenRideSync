package org.openridesync.app.media

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FavoriteMusicTest {
    @Test fun sameChoiceClearsAndUninstalledOrUncataloguedChoicesCannotReplaceSavedChoice() {
        val spotify = "com.spotify.music"
        val vlc = "org.videolan.vlc"
        assertEquals(spotify, favoriteMusicSelection(null, spotify, listOf(spotify, vlc)))
        assertEquals(vlc, favoriteMusicSelection(spotify, vlc, listOf(spotify, vlc)))
        assertNull(favoriteMusicSelection(spotify, spotify, listOf(spotify)))
        assertNull(favoriteMusicSelection("removed.invalid", null, emptyList()))
        assertThrows(IllegalArgumentException::class.java) { favoriteMusicSelection(spotify, vlc, listOf(spotify)) }
        assertThrows(IllegalArgumentException::class.java) { favoriteMusicSelection(spotify, "unknown.invalid", listOf("unknown.invalid")) }
    }

    @Test fun productionCatalogRemainsVisibleOnAndroid30WithoutAllPackagesPermission() {
        val source = File("src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(source)
        val android = "http://schemas.android.com/apk/res/android"
        val queries = document.getElementsByTagName("queries").item(0) as org.w3c.dom.Element
        val packages = queries.getElementsByTagName("package")
        val visible = (0 until packages.length).map { (packages.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
        assertEquals(12, preferredMusicPackages.distinct().size)
        assertTrue(visible.containsAll(preferredMusicPackages))
        val permissions = document.getElementsByTagName("uses-permission")
        assertFalse((0 until permissions.length).any {
            (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") == "android.permission.QUERY_ALL_PACKAGES"
        })
    }
}
