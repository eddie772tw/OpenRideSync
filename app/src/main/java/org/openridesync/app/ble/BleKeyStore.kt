package org.openridesync.app.ble

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class BleKeyError { INVALID_INPUT, CORRUPT, KEY_UNAVAILABLE, IO }
class BleKeyStoreException(val code: BleKeyError) : Exception(code.name)

/** App-owned Gen1 keys only. No OEM access, logging, backup, or automatic recovery/deletion. */
class BleKeyStore internal constructor(private val directory: File, private val alias: String) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "ble-gen1-keys"), ALIAS)

    /** Reject known local failures before a vehicle changes its key. No existing entry is changed. */
    fun prepareForWrite(mac: String): Unit = synchronized(lock) {
        closed {
            val identity = normalize(mac)
            if (target(identity).exists()) read(identity)?.fill(0)
            // A handle alone does not establish that an existing provider key is usable.
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, wrappingKey(true))
                doFinal(ByteArray(16))
            }
            if (!directory.isDirectory && !directory.mkdirs()) fail(BleKeyError.IO)
            val probe = File.createTempFile("ble-key-probe-", ".tmp", directory)
            try {
                FileOutputStream(probe).use { stream -> stream.write(0); stream.fd.sync() }
            } finally { Files.deleteIfExists(probe.toPath()) }
            Unit
        }
    }

    fun read(mac: String): ByteArray? = synchronized(lock) {
        closed {
            val identity = normalize(mac)
            val target = target(identity)
            if (!target.exists()) return@closed null
            if (!target.isFile || target.length() != FILE_BYTES.toLong()) fail(BleKeyError.CORRUPT)
            val bytes = Files.readAllBytes(target.toPath())
            if (bytes.size != FILE_BYTES) fail(BleKeyError.CORRUPT)
            val input = ByteBuffer.wrap(bytes)
            if (input.int != MAGIC || input.int != VERSION) fail(BleKeyError.CORRUPT)
            val iv = ByteArray(12).also { input.get(it) }
            val ciphertext = ByteArray(32).also { input.get(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(false), GCMParameterSpec(128, iv))
            cipher.updateAAD(identity.toByteArray(Charsets.US_ASCII))
            try {
                cipher.doFinal(ciphertext).also { if (it.size != 16) fail(BleKeyError.CORRUPT) }
            } catch (_: AEADBadTagException) { fail(BleKeyError.CORRUPT) }
        }
    }

    fun write(mac: String, key: ByteArray): Unit = synchronized(lock) {
        closed {
            val identity = normalize(mac)
            if (key.size != 16) fail(BleKeyError.INVALID_INPUT)
            val target = target(identity)
            // A valid existing key may be updated. Corruption/key loss requires explicit recovery.
            if (target.exists()) read(identity)?.fill(0)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(true))
            cipher.updateAAD(identity.toByteArray(Charsets.US_ASCII))
            val ciphertext = cipher.doFinal(key)
            if (cipher.iv.size != 12 || ciphertext.size != 32) fail(BleKeyError.KEY_UNAVAILABLE)
            val bytes = ByteBuffer.allocate(FILE_BYTES).putInt(MAGIC).putInt(VERSION)
                .put(cipher.iv).put(ciphertext).array()
            if (!directory.isDirectory && !directory.mkdirs()) fail(BleKeyError.IO)
            val temporary = File.createTempFile("ble-key-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary.toPath()) }
            Unit
        }
    }

    private fun wrappingKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as? SecretKey ?: fail(BleKeyError.KEY_UNAVAILABLE)
        if (!create) fail(BleKeyError.KEY_UNAVAILABLE)
        if (directory.exists()) {
            val files = directory.listFiles() ?: fail(BleKeyError.IO)
            if (files.any { it.name.matches(Regex("[0-9a-f]{64}\\.key")) }) fail(BleKeyError.KEY_UNAVAILABLE)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun normalize(mac: String): String {
        if (!mac.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}"))) fail(BleKeyError.INVALID_INPUT)
        return mac.uppercase(Locale.ROOT)
    }
    private fun target(identity: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
        return File(directory, "$name.key").also { if (Files.isSymbolicLink(it.toPath())) fail(BleKeyError.CORRUPT) }
    }
    private fun fail(code: BleKeyError): Nothing = throw BleKeyStoreException(code)
    private inline fun <T> closed(action: () -> T): T = try { action() }
    catch (error: BleKeyStoreException) { throw error }
    catch (_: IOException) { fail(BleKeyError.IO) }
    catch (_: GeneralSecurityException) { fail(BleKeyError.KEY_UNAVAILABLE) }
    catch (_: RuntimeException) { fail(BleKeyError.KEY_UNAVAILABLE) }

    companion object {
        private const val ALIAS = "org.openridesync.ble.gen1.wrap.v1"
        private const val MAGIC = 0x4f52424b
        private const val VERSION = 1
        private const val FILE_BYTES = 52
        // One app process; serialize alias creation and atomic updates across store instances.
        private val lock = Any()
    }
}
