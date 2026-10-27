package org.openridesync.app.ble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Synthetic key bytes and addresses; no Bluetooth API, OEM storage, or production alias is used. */
@RunWith(AndroidJUnit4::class)
class BleKeyStoreTest {
    @Test fun wrapsUpdatesBindsIdentityAndPreservesCorruptionAndLostKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "ble-key-instrumentation-").toFile()
        val alias = "org.openridesync.ble.gen1.test.${UUID.randomUUID()}"
        val systemStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val first = "02:AA:BB:CC:DD:01"
        val second = "02:AA:BB:CC:DD:02"
        val initial = ByteArray(16) { (it + 1).toByte() }
        val updated = ByteArray(16) { (it + 33).toByte() }
        fun path(mac: String): File {
            val digest = MessageDigest.getInstance("SHA-256").digest(mac.uppercase(Locale.ROOT).toByteArray(Charsets.US_ASCII))
            return File(directory, digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) } + ".key")
        }
        fun fails(expected: BleKeyError, block: () -> Unit) {
            try { block(); fail("expected closed key-store failure") }
            catch (error: BleKeyStoreException) {
                assertEquals(expected, error.code)
                assertEquals(expected.name, error.message)
                assertNull(error.cause)
            }
        }
        try {
            val store = BleKeyStore(directory, alias)
            assertNull(store.read(first))
            assertFalse(systemStore.containsAlias(alias))
            fails(BleKeyError.INVALID_INPUT) { store.prepareForWrite("invalid") }
            store.prepareForWrite(first)
            assertTrue(systemStore.containsAlias(alias))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            fails(BleKeyError.INVALID_INPUT) { store.write("invalid", initial) }
            fails(BleKeyError.INVALID_INPUT) { store.write(first, ByteArray(15)) }
            store.write(first.lowercase(Locale.ROOT), initial)
            assertArrayEquals(initial, BleKeyStore(directory, alias).read(first))
            assertNull(store.read(second))
            val firstBytes = Files.readAllBytes(path(first).toPath())
            store.prepareForWrite(first)
            assertArrayEquals(firstBytes, Files.readAllBytes(path(first).toPath()))
            assertEquals(52, firstBytes.size)
            assertFalse(firstBytes.toList().windowed(16).any { it == initial.toList() })
            store.write(first, updated)
            assertArrayEquals(updated, store.read(first))
            assertFalse(firstBytes.contentEquals(Files.readAllBytes(path(first).toPath())))
            store.write(second, initial)
            Files.write(path(second).toPath(), Files.readAllBytes(path(first).toPath()))
            fails(BleKeyError.CORRUPT) { store.read(second) } // AAD prevents swapping MAC-bound records.
            val corrupted = Files.readAllBytes(path(second).toPath())
            fails(BleKeyError.CORRUPT) { store.prepareForWrite(second) }
            fails(BleKeyError.CORRUPT) { store.write(second, updated) }
            assertArrayEquals(corrupted, Files.readAllBytes(path(second).toPath()))
            Files.write(path(second).toPath(), byteArrayOf(1, 2))
            fails(BleKeyError.CORRUPT) { store.prepareForWrite(second) }
            fails(BleKeyError.CORRUPT) { store.write(second, initial) }
            assertArrayEquals(byteArrayOf(1, 2), Files.readAllBytes(path(second).toPath()))
            val preserved = Files.readAllBytes(path(first).toPath())
            val blocked = File(directory, "test-owned-blocked-directory").apply { writeBytes(byteArrayOf(7)) }
            fails(BleKeyError.IO) { BleKeyStore(blocked, alias).prepareForWrite(first) }
            assertArrayEquals(byteArrayOf(7), blocked.readBytes())
            systemStore.deleteEntry(alias)
            fails(BleKeyError.KEY_UNAVAILABLE) { store.prepareForWrite(first) }
            fails(BleKeyError.KEY_UNAVAILABLE) { store.read(first) }
            fails(BleKeyError.KEY_UNAVAILABLE) { store.write(first, initial) }
            fails(BleKeyError.KEY_UNAVAILABLE) { store.write("02:00:00:00:00:03", initial) }
            assertFalse(systemStore.containsAlias(alias))
            assertArrayEquals(preserved, Files.readAllBytes(path(first).toPath()))
        } finally {
            // Explicit test-owned alias/directory only, never the production alias or sibling files.
            systemStore.deleteEntry(alias)
            directory.listFiles()?.forEach {
                assertEquals(directory.canonicalFile, it.parentFile?.canonicalFile)
                assertTrue(it.delete())
            }
            assertTrue(directory.delete())
        }
    }
}
