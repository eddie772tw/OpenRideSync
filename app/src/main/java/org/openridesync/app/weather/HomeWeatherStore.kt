package org.openridesync.app.weather

import android.util.Base64
import org.json.JSONObject
import org.openridesync.honda.wire.WeatherWire
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** One private bounded snapshot: the point is never a log field or a scheduled-job extra. */
internal data class HomeWeatherSnapshot(
    val location: WeatherLocation,
    val locationAtMillis: Long,
    val current: CurrentWeather? = null,
    val fetchedAtMillis: Long? = null,
    val lastAttemptMillis: Long? = null,
    val weatherLocationAtMillis: Long? = null,
    val weatherLastKnown: Boolean = false,
    val errorCode: String? = null,
)

internal class HomeWeatherStore(private val directory: File) {
    private fun file(uid: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$hash.json")
    }

    fun read(uid: String): HomeWeatherSnapshot? {
        val target = file(uid)
        if (!target.exists()) return null
        val bytes = FileInputStream(target).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, LIMIT + 1 - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
                if (out.size() > LIMIT) throw IOException("Weather snapshot limit")
            }
            out.toByteArray()
        }
        if (bytes.size > LIMIT) throw IOException("Weather snapshot limit")
        try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("version") == 1)
            val locationAt = json.getLong("locationAt").also { require(it >= 0) }
            val location = WeatherLocation(json.getDouble("latitude"), json.getDouble("longitude"),
                json.getBoolean("lastKnown"), null)
            val current = if (json.has("weather")) currentWeather(WeatherWire.Weather.parseFrom(
                Base64.decode(json.getString("weather"), Base64.NO_WRAP))) else null
            val fetchedAt = if (json.has("fetchedAt")) json.getLong("fetchedAt").also { require(it >= 0) } else null
            require((current == null) == (fetchedAt == null))
            return HomeWeatherSnapshot(location, locationAt, current, fetchedAt,
                if (json.has("lastAttempt")) json.getLong("lastAttempt").also { require(it >= 0) } else null,
                if (current != null) json.getLong("weatherLocationAt").also { require(it >= 0) } else null,
                json.optBoolean("weatherLastKnown", false),
                if (json.has("errorCode")) json.getString("errorCode").also { require(it in homeWeatherErrorCodes) } else null)
        } catch (error: Exception) { throw IOException("Invalid weather snapshot", error) }
    }

    fun write(uid: String, value: HomeWeatherSnapshot, check: () -> Unit) {
        check()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Weather directory unavailable")
        val json = JSONObject().put("version", 1).put("latitude", value.location.latitude)
            .put("longitude", value.location.longitude).put("lastKnown", value.location.lastKnown)
            .put("locationAt", value.locationAtMillis)
        value.current?.let { json.put("weather", Base64.encodeToString(it.raw.toByteArray(), Base64.NO_WRAP)) }
        value.fetchedAtMillis?.let { json.put("fetchedAt", it) }
        value.lastAttemptMillis?.let { json.put("lastAttempt", it) }
        value.weatherLocationAtMillis?.let { json.put("weatherLocationAt", it) }
        json.put("weatherLastKnown", value.weatherLastKnown)
        value.errorCode?.let { require(it in homeWeatherErrorCodes); json.put("errorCode", it) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > LIMIT) throw IOException("Weather snapshot limit")
        val target = file(uid)
        val pending = File(target.path + ".new")
        try {
            FileOutputStream(pending).use { output -> output.write(bytes); output.fd.sync() }
            check() // A stopped JobService/auth replacement cannot publish its pending snapshot.
            // AtomicFile.finishWrite only logs rename failure. NIO reports failure and replaces
            // atomically on Android/Linux and Windows host tests; never delete the good file first.
            Files.move(pending.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { pending.delete() }
    }

    fun clear() {
        directory.listFiles()?.forEach {
            if (it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.json(?:\\.bak|\\.new)?")) && !it.delete())
                throw IOException("Weather snapshot deletion failed")
        }
    }

    companion object { private const val LIMIT = 256 * 1024 }
}

internal val homeWeatherErrorCodes = io.grpc.Status.Code.values().map { it.name }.toSet() + setOf(
    "AUTH_REQUIRED", "LOCATION_PERMISSION", "LOCATION_DISABLED", "LOCATION_UNAVAILABLE", "IO", "NETWORK", "TIMEOUT", "UNKNOWN")
