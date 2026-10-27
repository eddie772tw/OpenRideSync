package org.openridesync.app.vehicles

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object VehicleImageLimits {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_DECODED_PIXELS = 1024L * 1024
    const val MAX_DIMENSION = 2048

    fun sampleSize(width: Int, height: Int): Int {
        require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= 64L * 1024 * 1024)
        var sample = 1
        while (((width + sample - 1) / sample).toLong() * ((height + sample - 1) / sample) > MAX_DECODED_PIXELS ||
            (width + sample - 1) / sample > MAX_DIMENSION || (height + sample - 1) / sample > MAX_DIMENSION) sample *= 2
        return sample
    }
}

/** Encoded bytes only. Call off main; all instances share the short filesystem critical section. */
internal class VehicleImageCache(private val directory: File, private val maxBytes: Long = 32L * 1024 * 1024) {
    init { synchronized(lock) { trim() } }
    private fun target(uid: String, imageId: String) = File(File(directory, hash(uid)), hash(imageId) + ".image")

    fun read(uid: String, imageId: String): ByteArray? = synchronized(lock) {
        val file = target(uid, imageId)
        if (!file.isFile) return@synchronized null
        if (file.length() !in 1..VehicleImageLimits.MAX_BYTES.toLong()) {
            file.delete()
            return@synchronized null
        }
        val bytes = file.readBytes()
        require(bytes.size in 1..VehicleImageLimits.MAX_BYTES)
        file.setLastModified(System.currentTimeMillis())
        trim()
        bytes
    }

    /** Caller validates both image identity and decoding before replacing a good entry. */
    fun write(uid: String, imageId: String, bytes: ByteArray) = synchronized(lock) {
        require(bytes.size in 1..VehicleImageLimits.MAX_BYTES && bytes.size <= maxBytes)
        val file = target(uid, imageId)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File.createTempFile("image-", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { it.write(bytes); it.flush(); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            file.setLastModified(System.currentTimeMillis())
            trim()
        } finally { temporary.delete() }
    }

    fun discard(uid: String, imageId: String) = synchronized(lock) { target(uid, imageId).delete() }

    private fun trim() {
        val stored = directory.listFiles().orEmpty().filter { it.isDirectory }
            .flatMap { it.listFiles().orEmpty().filter { file -> file.isFile } }
        // No writer runs concurrently under this lock; leftovers are from interrupted writes.
        stored.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        val files = stored.filter { it.name.endsWith(".image") }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.path })
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= maxBytes) break
            val bytes = file.length()
            if (file.delete()) size -= bytes
        }
    }

    private companion object {
        val lock = Any()
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
