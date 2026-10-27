package org.openridesync.app.trips

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openridesync.honda.wire.PipelineWire
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

/** Native SQLite in an isolated UUID temporary root; no production queue or real location is read. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TripPointStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var root: File
    private val uid = "synthetic-account"
    private val trip = UUID.randomUUID().toString()
    private val session = UUID.randomUUID().toString()

    @Before fun setup() {
        root = temporary.newFolder(UUID.randomUUID().toString())
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = root
        }
    }
    private fun point(id: Long, time: Long = 1000) = PipelineWire.Location.newBuilder()
        .setUuid(UUID(0, id).toString()).setUserId(uid).setTripUuid(trip).setAppSessionId(session)
        .setTime(time).setLatitude(25f).setLongitude(121f).setAccuracy(3f).setSpeed(2f).setSpeedAccuracy(1f)
        .setDistance(4f).setBearing(90f).setBearingAccuracy(2f).setAltitude(10f).setVerticalAccuracy(4f)
        .setDeviceTime(2000).setProvider("synthetic").setPlatform("android").setAppVersionCode(1)
        .setVehicleSeriesCode("synthetic-series").setVehicleInfoSeriesCodeRaw("synthetic-raw")
        .setVehicleInfoVehicleName("Synthetic vehicle").setOffsetFromUtc(28800).setIsCoarseLocation(true).build()
    private fun databaseFile(): File = root.walkTopDown().single { it.isFile && it.name == "points.db" }

    @Test fun unopenedReadsCreateNoQueueAndCompletePointsSurviveSameAccountReopenInSeparateAccountPaths() {
        val first = point(1)
        TripPointStore(context, uid).use { store ->
            assertEquals(0, store.count()); assertTrue(store.batch().isEmpty())
            assertTrue(root.listFiles().orEmpty().isEmpty())
            store.append(first)
            assertTrue(databaseFile().absolutePath.startsWith(root.absolutePath + File.separator))
            assertFalse(databaseFile().absolutePath.contains(uid))
        }
        TripPointStore(context, "another-synthetic-account").use { other ->
            assertEquals(0, other.count())
            other.append(first.toBuilder().setUserId("another-synthetic-account").build())
            assertEquals(1, other.count())
        }
        TripPointStore(context, uid).use { reopened ->
            assertEquals(listOf(first), reopened.batch())
            assertArrayEquals(first.toByteArray(), reopened.batch().single().toByteArray())
        }
        assertEquals(2, root.walkTopDown().count { it.name == "points.db" && it.isFile })
    }

    @Test fun oldestHundredHaveStableUuidTiesAndAcknowledgementRetainsNewPointsAndDifferentBytes() {
        val points = (1L..102L).map { point(it, 1000 + it % 2) }
        TripPointStore(context, uid).use { store ->
            points.reversed().forEach(store::append)
            val captured = store.batch()
            val expected = points.sortedWith(compareBy<PipelineWire.Location> { it.time }.thenBy { it.uuid }).take(100)
            assertEquals(expected, captured)
            val newPoint = point(103, 0)
            store.append(newPoint)
            store.acknowledge(listOf(captured.first().toBuilder().setDistance(99f).build()))
            assertEquals(103, store.count())
            store.acknowledge(captured)
            val remaining = (points - expected.toSet()) + newPoint
            assertEquals(remaining.map { it.uuid }.toSet(), store.batch().map { it.uuid }.toSet())
            store.acknowledge(captured)
            assertEquals(3, store.count())
        }
    }

    @Test fun duplicateIsIdempotentAndConflictInvalidPointOrFailedAcknowledgeNeverDeletesStoredData() {
        val first = point(1)
        TripPointStore(context, uid).use { store ->
            store.append(first); store.append(first)
            assertEquals(1, store.count())
            assertThrows(IllegalArgumentException::class.java) { store.append(first.toBuilder().setDistance(99f).build()) }
            for (bad in listOf(first.toBuilder().setUserId("foreign").build(),
                first.toBuilder().setUuid("1-1-1-1-1").build(), first.toBuilder().setTripUuid(" ").build(),
                first.toBuilder().setAppSessionId("not-a-uuid").build(), first.toBuilder().setTime(-1).build(),
                first.toBuilder().setDeviceTime(Long.MAX_VALUE).build(), first.toBuilder().clearLatitude().build(),
                first.toBuilder().setLatitude(Float.NaN).build(), first.toBuilder().setLongitude(181f).build(),
                first.toBuilder().setSpeed(Float.POSITIVE_INFINITY).build(),
                first.toBuilder().setProvider("x".repeat(1_048_577)).build())) {
                assertThrows(IllegalArgumentException::class.java) { store.append(bad) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.acknowledge(listOf(first, point(2).toBuilder().setUserId("foreign").build()))
            }
            assertEquals(listOf(first), store.batch())
        }
    }

    @Test fun perAccountFullQueueRejectsNewPointWithoutDroppingOldDataButStillAllowsIdempotentRetry() {
        val first = point(0)
        TripPointStore(context, uid).use { it.append(first) }
        SQLiteDatabase.openDatabase(databaseFile().absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.beginTransaction()
            try {
                database.compileStatement("INSERT INTO trip_points(uuid, time, payload) VALUES (?, ?, ?)").use { insert ->
                    for (id in 1L until 50_000L) {
                        val next = point(id, 1000 + id)
                        insert.clearBindings(); insert.bindString(1, next.uuid); insert.bindLong(2, next.time)
                        insert.bindBlob(3, next.toByteArray()); insert.executeInsert()
                    }
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
        TripPointStore(context, uid).use { store ->
            assertEquals(50_000, store.count())
            store.append(first)
            assertThrows(SQLiteFullException::class.java) { store.append(point(50_000)) }
            assertEquals(50_000, store.count())
            assertEquals(first, store.batch().first())
            store.acknowledge(listOf(first))
            assertEquals(49_999, store.count())
            store.append(point(50_000))
            assertEquals(50_000, store.count())
        }
    }

    @Test fun corruptDatabaseAndFutureSchemaAreReportedWithoutFileDeletionOrRecovery() {
        TripPointStore(context, uid).use { it.append(point(1)) }
        val file = databaseFile()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }
        TripPointStore(context, uid).use { store -> assertThrows(SQLiteException::class.java) { store.count() } }
        assertTrue(file.isFile)
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            assertEquals(2, database.version)
            database.rawQuery("SELECT count(*) FROM trip_points", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        }
        val corrupt = "Synthetic corrupt database".repeat(12).toByteArray()
        file.writeBytes(corrupt)
        TripPointStore(context, uid).use { store -> assertThrows(SQLiteException::class.java) { store.batch() } }
        assertTrue(file.isFile)
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test fun invalidStorageDirectoryAndClosedOwnerCannotMutatePendingData() {
        val store = TripPointStore(context, uid)
        store.append(point(1)); store.close()
        assertThrows(IllegalStateException::class.java) { store.count() }
        assertThrows(IllegalStateException::class.java) { store.append(point(2)) }
        TripPointStore(context, uid).use { assertEquals(listOf(point(1)), it.batch()) }
        val blocked = temporary.newFolder(UUID.randomUUID().toString())
        File(blocked, "trip-points").writeText("Synthetic obstruction")
        val blockedContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = blocked }
        assertThrows(IOException::class.java) { TripPointStore(blockedContext, uid) }
        assertEquals("Synthetic obstruction", File(blocked, "trip-points").readText())
    }
}
