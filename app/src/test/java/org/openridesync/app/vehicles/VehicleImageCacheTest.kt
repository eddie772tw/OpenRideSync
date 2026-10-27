package org.openridesync.app.vehicles

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VehicleImageCacheTest {
    @Test fun samplingAndPrivateCachePreserveBoundsIdentityAndGoodFiles() {
        assertEquals(8, VehicleImageLimits.sampleSize(8192, 8192))
        assertEquals(16, VehicleImageLimits.sampleSize(32768, 1))
        assertThrows(IllegalArgumentException::class.java) { VehicleImageLimits.sampleSize(32768, 32768) }
        assertThrows(IllegalArgumentException::class.java) { VehicleImageLimits.sampleSize(-1, 100) }
        val root = Files.createTempDirectory("vehicle-image-test-").toFile()
        try {
            val cache = VehicleImageCache(root, 10)
            cache.write("synthetic-user-a", "../image/a", byteArrayOf(1, 2, 3, 4))
            assertNull(cache.read("synthetic-user-b", "../image/a"))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), cache.read("synthetic-user-a", "../image/a"))
            assertThrows(IllegalArgumentException::class.java) { cache.write("synthetic-user-a", "../image/a", ByteArray(11)) }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), cache.read("synthetic-user-a", "../image/a"))
            val first = root.walkTopDown().single { it.isFile }
            first.setLastModified(1)
            cache.write("synthetic-user-a", "second", ByteArray(4))
            assertTrue(first.exists())
            cache.write("synthetic-user-b", "third", ByteArray(4))
            assertFalse(first.exists())
            val files = root.walkTopDown().filter { it.isFile }.toList()
            assertTrue(files.sumOf(File::length) <= 10)
            assertTrue(files.all { it.name.matches(Regex("[0-9a-f]{64}\\.image")) && checkNotNull(it.parentFile).name.matches(Regex("[0-9a-f]{64}")) })
            assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
            val orphan = File(files.first().parentFile, "image-interrupted.tmp").apply { writeBytes(ByteArray(4)) }
            VehicleImageCache(root, 10)
            assertFalse(orphan.exists())
        } finally { root.deleteRecursively() }
    }
}
