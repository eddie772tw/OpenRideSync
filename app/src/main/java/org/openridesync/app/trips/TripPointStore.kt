package org.openridesync.app.trips

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOpenHelper
import org.openridesync.honda.wire.PipelineWire
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Durable, account-owned complete points. Call off Main; failures never discard pending data. */
internal class TripPointStore(context: Context, private val uid: String) : AutoCloseable {
    private val root = context.noBackupFilesDir.absoluteFile
    private val canonicalRoot = root.canonicalFile
    private val directory = File(File(root, "trip-points"), MessageDigest.getInstance("SHA-256")
        .digest(uid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) })
    private val file = File(directory, "points.db")
    private val context = context.applicationContext
    private var helper: SQLiteOpenHelper? = null
    private var opened = false
    private var closed = false

    init { require(uid.isNotBlank()); checkPath(false) }

    @Synchronized fun append(point: PipelineWire.Location) {
        val bytes = encoded(point)
        val database = requireNotNull(database(true))
        database.beginTransaction()
        try {
            val previous = database.rawQuery("SELECT time, length(payload), " +
                "CASE WHEN length(payload) BETWEEN 1 AND $MAX_PAYLOAD THEN payload ELSE NULL END " +
                "FROM trip_points WHERE uuid = ?", arrayOf(point.uuid)).use {
                if (!it.moveToFirst()) null else {
                    if (it.getLong(1) !in 1..MAX_PAYLOAD.toLong() || it.isNull(2)) corrupt()
                    val stored = it.getBlob(2)
                    if (stored.contentEquals(bytes) && it.getLong(0) != point.time) corrupt()
                    stored
                }
            }
            if (previous != null) {
                require(previous.contentEquals(bytes)) { "Trip point UUID already contains different data" }
            } else {
                if (count(database) >= MAX_POINTS) throw SQLiteFullException("Trip point queue is full")
                database.compileStatement("INSERT INTO trip_points(uuid, time, payload) VALUES (?, ?, ?)").use {
                    it.bindString(1, point.uuid); it.bindLong(2, point.time); it.bindBlob(3, bytes)
                    if (it.executeInsert() < 0) throw SQLiteException("Trip point insert failed")
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    @Synchronized fun batch(): List<PipelineWire.Location> {
        val database = database(false) ?: return emptyList()
        return database.rawQuery("SELECT uuid, time, length(payload), " +
            "CASE WHEN length(payload) BETWEEN 1 AND $MAX_PAYLOAD THEN payload ELSE NULL END " +
            "FROM trip_points ORDER BY time, uuid LIMIT 100", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getLong(2) !in 1..MAX_PAYLOAD.toLong() || cursor.isNull(3)) corrupt()
                    val point = try { PipelineWire.Location.parseFrom(cursor.getBlob(3)).also(::validate) }
                        catch (_: Exception) { corrupt() }
                    if (point.uuid != cursor.getString(0) || point.time != cursor.getLong(1)) corrupt()
                    add(point)
                }
            }
        }
    }

    @Synchronized fun count(): Int = database(false)?.let(::count) ?: 0

    /** Acknowledges only the captured complete records, never another write with the same identity. */
    @Synchronized fun acknowledge(points: List<PipelineWire.Location>) {
        val captured = points.map { it.uuid to encoded(it) }
        if (captured.isEmpty()) { check(!closed); return }
        val database = database(false) ?: return
        database.beginTransaction()
        try {
            database.compileStatement("DELETE FROM trip_points WHERE uuid = ? AND payload = ?").use { delete ->
                for ((uuid, bytes) in captured) {
                    delete.clearBindings(); delete.bindString(1, uuid); delete.bindBlob(2, bytes)
                    delete.executeUpdateDelete()
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    private fun count(database: SQLiteDatabase): Int = database.rawQuery("SELECT count(*) FROM trip_points", null).use {
        if (!it.moveToFirst()) corrupt()
        val count = it.getLong(0)
        if (count !in 0..MAX_POINTS.toLong()) corrupt()
        count.toInt()
    }

    private fun encoded(point: PipelineWire.Location): ByteArray {
        validate(point)
        require(point.serializedSize in 1..MAX_PAYLOAD) { "Trip point payload exceeds storage bound" }
        return point.toByteArray()
    }
    private fun validate(point: PipelineWire.Location) {
        fun canonical(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        require(canonical(point.uuid) && canonical(point.tripUuid) && canonical(point.appSessionId)) { "Invalid trip point identity" }
        require(point.userId == uid) { "Trip point belongs to another account" }
        require(point.time in 0..MAX_TIME && point.deviceTime in 0..MAX_TIME && point.serverTime in 0..MAX_TIME) { "Invalid trip point time" }
        require(point.hasLatitude() && point.hasLongitude() && point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90f..90f && point.longitude in -180f..180f) { "Invalid trip point coordinate" }
        require(listOf(point.accuracy, point.speed, point.speedAccuracy, point.distance, point.bearing, point.bearingAccuracy,
            point.altitude, point.verticalAccuracy).all { it.isFinite() }) { "Invalid trip point measurement" }
    }

    private fun database(create: Boolean): SQLiteDatabase? {
        check(!closed) { "Trip point store is closed" }
        checkPath(create)
        if (!file.exists() && !create) {
            if (opened) throw SQLiteException("Trip point database disappeared")
            return null
        }
        val source = helper ?: object : SQLiteOpenHelper(context, file.absolutePath, null, 1,
            DatabaseErrorHandler { corrupt() }) {
            override fun onCreate(database: SQLiteDatabase) {
                database.execSQL("CREATE TABLE trip_points(uuid TEXT PRIMARY KEY NOT NULL CHECK(length(uuid) = 36), " +
                    "time INTEGER NOT NULL CHECK(typeof(time) = 'integer' AND time BETWEEN 0 AND $MAX_TIME), " +
                    "payload BLOB NOT NULL CHECK(typeof(payload) = 'blob' AND length(payload) BETWEEN 1 AND $MAX_PAYLOAD))")
                database.execSQL("CREATE INDEX trip_points_order ON trip_points(time, uuid)")
            }
            override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                throw SQLiteException("Unsupported trip point database version")
            }
            override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                throw SQLiteException("Unsupported trip point database version")
            }
            override fun onOpen(database: SQLiteDatabase) {
                database.rawQuery("PRAGMA quick_check(1)", null).use {
                    if (!it.moveToFirst() || it.getString(0) != "ok") corrupt()
                }
            }
        }.also { helper = it }
        return source.writableDatabase.also { opened = true }
    }

    private fun checkPath(create: Boolean) {
        if (Files.isSymbolicLink(root.toPath()) || root.canonicalFile != canonicalRoot || !root.isDirectory)
            throw IOException("Invalid trip point storage root")
        val parent = directory.parentFile!!
        for (path in listOf(parent, directory)) {
            if (Files.isSymbolicLink(path.toPath()) || path.canonicalFile.parentFile !=
                (if (path == parent) canonicalRoot else parent.canonicalFile)) throw IOException("Invalid trip point storage path")
            if (path.exists() && !path.isDirectory) throw IOException("Invalid trip point storage directory")
            if (create && !path.isDirectory && !path.mkdir()) throw IOException("Trip point directory creation failed")
        }
        for (name in listOf("points.db", "points.db-journal", "points.db-wal", "points.db-shm")) {
            val path = File(directory, name)
            if (Files.isSymbolicLink(path.toPath()) || path.canonicalFile.parentFile != directory.canonicalFile ||
                path.exists() && !path.isFile) throw IOException("Invalid trip point database path")
        }
    }

    @Synchronized override fun close() { if (!closed) { closed = true; helper?.close() } }
    private fun corrupt(): Nothing = throw SQLiteDatabaseCorruptException("Invalid trip point database")
    private companion object {
        const val MAX_PAYLOAD = 1_048_576
        const val MAX_POINTS = 50_000
        const val MAX_TIME = 253_402_300_799_999L
    }
}
