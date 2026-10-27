package org.openridesync.app.ui

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.openridesync.app.media.*
import org.openridesync.app.trips.TripState
import org.openridesync.app.vehicles.VehicleState
import org.openridesync.app.weather.*
import org.openridesync.honda.wire.HondaWire.*
import org.openridesync.honda.wire.WeatherWire
import java.util.Locale
import java.nio.file.Files
import org.openridesync.honda.PairedVehicleStore

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HomeOverviewTest {
    private val signedIn = ShellStatus(true, "已登入", "尚未連線")
    @Test fun absentSnapshotsNeverBecomeFabricatedFacts() {
        val value = homeOverview(signedIn)
        assertEquals("尚未查詢", value.weather.value)
        assertEquals("尚未讀取", value.trip.value)
        assertNull(value.vehicle)
    }
    @Test fun signedOutSuppressesRetainedPrivateSnapshots() {
        val value = homeOverview(signedIn.copy(signedIn = false), weather = weather(),
            media = MediaPageState(signedIn = true, observing = true,
                media = MediaState(access = MediaAccess.GRANTED, selectedId = "x", title = "Private song")),
            trips = TripState(days = listOf(day(172800, 12340f))))
        assertEquals("需要登入", value.weather.value)
        assertEquals("需要登入", value.media.value)
        assertEquals("需要登入", value.trip.value)
        assertNull(value.vehicle)
    }
    @Test fun realWeatherIsClearlyALastQuerySnapshot() {
        val value = homeOverview(signedIn, weather = weather())
        assertEquals("23°C", value.weather.value)
        assertTrue(value.weather.detail.contains("上次更新"))
        assertTrue(value.weather.detail.contains("測試地點"))
    }
    @Test fun stoppedMediaObserverDoesNotClaimLivePlayback() {
        val media = MediaPageState(signedIn = true, observing = false,
            media = MediaState(access = MediaAccess.GRANTED, selectedId = "x", title = "Old song", playback = MediaPlayback.PLAYING))
        assertEquals("尚未讀取", homeOverview(signedIn, media = media).media.value)
        assertEquals("Old song", homeOverview(signedIn, media = media.copy(observing = true)).media.value)
        assertEquals("播放中", homeOverview(signedIn, media = media.copy(observing = true)).media.detail)
    }
    @Test fun recentDistanceUsesLatestLoadedDayNotAllTimeSummaryOrSelectedDetail() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val value = homeOverview(signedIn, trips = TripState(
                summary = TripsSummary.newBuilder().setTotalDistance(999999f).build(),
                days = listOf(day(172800, 12340f), day(86400, 1000f)),
                selectedDay = day(86400, 1000f), hasLoaded = true, cached = true))
            assertEquals("12.3 km", value.trip.value)
            assertTrue(value.trip.detail.contains("1970-01-03"))
            assertTrue(value.trip.detail.contains("帳號當日合計，非此車里程"))
            assertTrue(value.trip.detail.contains("快取"))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun homeDisplaysObservedPlaybackWithoutNeedingAControllerSelection() {
        val playing = MediaSessionChoice("playing", "合成播放器", "synthetic.invalid", "合成曲目", MediaPlayback.PLAYING)
        val paused = MediaSessionChoice("paused", "另一播放器", "other.invalid", "另一曲目", MediaPlayback.PAUSED)
        fun fact(vararg sessions: MediaSessionChoice) = homeOverview(signedIn, media = MediaPageState(
            signedIn = true, observing = true, media = MediaState(MediaAccess.GRANTED, sessions.toList()))).media
        assertEquals(HomeFact("合成曲目", "合成播放器 · 播放中"), fact(paused, playing))
        assertEquals(HomeFact("另一曲目", "另一播放器 · 已暫停"), fact(paused))
        assertEquals(HomeFact("合成播放器", "合成播放器 · 播放中"), fact(playing.copy(title = " ")))
        assertEquals("2 個可用播放器", fact(playing, paused.copy(playback = MediaPlayback.PLAYING)).value)
        assertTrue(fact(playing, paused.copy(playback = MediaPlayback.PLAYING)).detail.contains("多個播放器正在播放"))
        assertEquals("2 個可用播放器", fact(playing.copy(playback = MediaPlayback.PAUSED), paused).value)
        assertEquals("沒有可用播放器", fact().value)
    }
    @Test fun fullAlbumMetadataUsesOneLiveTargetAndLosesControlsOnAmbiguityOrStop() {
        val choice = MediaSessionChoice("target", "Player", "synthetic.player", "Song", MediaPlayback.PLAYING,
            artist = "Artist", album = "Album", actions = 512)
        val page = MediaPageState(signedIn = true, observing = true,
            media = MediaState(MediaAccess.GRANTED, listOf(choice)))
        val overview = homeOverview(signedIn, media = page)
        assertEquals("Song", overview.media.value)
        assertTrue(overview.media.detail.contains("Artist · Album"))
        assertEquals(choice, overview.mediaControl)
        assertNull(homeOverview(signedIn, media = page.copy(observing = false)).mediaControl)
        assertNull(homeOverview(signedIn.copy(signedIn = false), media = page).mediaControl)
        assertNull(homeOverview(signedIn, media = page.copy(media = page.media.copy(access = MediaAccess.REQUIRED))).mediaControl)
        assertNull(homeOverview(signedIn, media = page.copy(media = page.media.copy(sessions = listOf(choice,
            choice.copy(id = "other"))))).mediaControl)
    }
    @Test fun emptyLoadedRangeIsNotZeroDistance() {
        assertEquals("已載入範圍沒有紀錄", homeOverview(signedIn, trips = TripState(hasLoaded = true)).trip.value)
    }
    @Test fun catalogDetailCannotSupplyPrimaryVehicleArt() {
        val value = homeOverview(signedIn, vehicles = VehicleState(garageLoaded = true,
            detail = Model.newBuilder().setModelLabel("catalog-only").build()))
        assertNull(value.vehicle)
        assertEquals("沒有主要配對車輛", value.vehicleMessage)
    }
    @Test fun physicalFocusWinsEvenWhenTwoVehiclesShareAModel() {
        val folder = Files.createTempDirectory("home-garage-test").toFile()
        try {
            val store = PairedVehicleStore(folder)
            val model = Model.newBuilder().setModelLabel("model").setModelSeriesLabel("series").addModelYears(2025).build()
            store.upsert("00:11:22:33:44:55", "", "series", "", "First bike", model, 0, PairedVehicleStore.State.PAIRED)
            store.upsert("00:11:22:33:44:66", "", "series", "", "Focused bike", model, 2025, PairedVehicleStore.State.REPAIR_NEEDED)
            val value = homeOverview(signedIn, vehicles = VehicleState(garageLoaded = true, pairedVehicles = store.read()))
            assertEquals("model · 2025", value.vehicle?.name)
            assertEquals("00:11:22:33:44:66", value.vehicle?.savedVehicle?.mac)
            assertTrue(value.vehicleMessage.contains("不代表目前已連線或可上路"))
        } finally { folder.deleteRecursively() }
    }
    @Test fun noPrimaryKeepsCatalogArtSeparateAndSignedOutCannotUseIt() {
        val model = Model.newBuilder().setModelLabel("catalog-only")
            .addImages(Image.newBuilder().setId("synthetic-catalog-image")).build()
        val state = VehicleState(garageLoaded = true,
            catalogs = listOf(ModelCatalog.newBuilder().setModel(model).build()))
        val overview = homeOverview(signedIn, vehicles = state)
        assertTrue(overview.garageHasNoPrimary)
        assertNull(overview.vehicle)
        assertEquals(listOf(model), homeBackgroundCandidates(true, state))
        assertTrue(homeBackgroundCandidates(false, state).isEmpty())
        assertFalse(homeOverview(signedIn.copy(signedIn = false), vehicles = state).garageHasNoPrimary)
    }
    @Test fun firstPairHintRequiresEveryEligibilityCondition() {
        fun eligible(deferred: Boolean = true, signedIn: Boolean = true, loaded: Boolean = true,
            loading: Boolean = false, error: Boolean = false, count: Int = 0, dismissed: Boolean = false) =
            firstPairHintEligible(deferred, signedIn, loaded, loading, error, count, dismissed)
        assertTrue(eligible())
        assertFalse(eligible(deferred = false))
        assertFalse(eligible(signedIn = false))
        assertFalse(eligible(loaded = false))
        assertFalse(eligible(loading = true))
        assertFalse(eligible(error = true))
        assertFalse(eligible(count = 1))
        assertFalse(eligible(count = -1))
        assertFalse(eligible(dismissed = true))
    }
    private fun day(date: Long, distance: Float) = DailyTrips.newBuilder().setTripDate(date).setTotalDistance(distance).build()
    @Test fun backgroundWeatherKeepsLastSuccessWithExplicitTimeAndFailureState() {
        val state = weather().copy(locationGranted = true, loading = true, errorCode = "NETWORK", locationAtMillis = 500)
        val fact = homeOverview(signedIn, weather = state).weather
        assertEquals("23°C", fact.value)
        assertTrue(fact.detail.contains("晴"))
        assertTrue(fact.detail.contains("上次更新"))
        assertTrue(fact.detail.contains("取得的位置"))
        assertTrue(fact.detail.contains("上次成功資料"))
        assertEquals("背景更新已停用", homeOverview(signedIn, weather = HomeWeatherState(enabled = false)).weather.value)
        assertEquals("需要位置授權", homeOverview(signedIn, weather = HomeWeatherState(locationGranted = false)).weather.value)
    }
    private fun weather() = HomeWeatherState(signedIn = true, current = CurrentWeather(
        WeatherWire.Weather.getDefaultInstance(), WeatherReading(23f, 800, "測試地點", "晴", "Clear", true)), fetchedAtMillis = 1000)
}
