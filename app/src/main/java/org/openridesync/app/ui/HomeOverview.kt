package org.openridesync.app.ui

import org.openridesync.app.media.*
import org.openridesync.app.trips.TripState
import org.openridesync.app.trips.TripFormat
import org.openridesync.app.vehicles.VehicleState
import org.openridesync.app.vehicles.modelName
import org.openridesync.app.vehicles.imageId
import org.openridesync.app.weather.HomeWeatherState
import org.openridesync.app.weather.WeatherKind
import org.openridesync.honda.PairedVehicleStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Already-observed facts only. No data reader, asset loader or permission operation lives here. */
internal data class HomeOverview(
    val weather: HomeFact = HomeFact("尚未查詢", "開啟天氣後可自行查詢"),
    val media: HomeFact = HomeFact("尚未讀取", "開啟音樂查看手機播放器"),
    val trip: HomeFact = HomeFact("尚未讀取", "開啟騎乘紀錄後可自行載入"),
    val vehicle: HomeVehicle? = null,
    val vehicleMessage: String = "尚未讀取車庫",
    val mediaControl: MediaSessionChoice? = null,
    val mediaResult: DispatchResult? = null,
    val weatherKind: WeatherKind = WeatherKind.UNKNOWN,
    val weatherPlace: String = "",
    val tripDate: String = "",
    val garageHasNoPrimary: Boolean = false,
)
internal data class HomeFact(val value: String, val detail: String)
internal data class HomeVehicle(val name: String, val savedVehicle: PairedVehicleStore.Vehicle)

/** Decorative catalog choices carry no saved pairing or physical vehicle identity. */
internal fun homeBackgroundCandidates(signedIn: Boolean, vehicles: VehicleState) =
    if (signedIn) vehicles.catalogs.map { it.model }.filter { imageId(it) != null } else emptyList()

/** Missing data is never turned into zero kilometres, sunny weather or a ready vehicle. */
internal fun homeOverview(status: ShellStatus, weather: HomeWeatherState? = null,
    media: MediaPageState? = null, trips: TripState? = null, vehicles: VehicleState? = null): HomeOverview {
    if (!status.signedIn) return HomeOverview(
        weather = HomeFact("需要登入", "登入後可自行查詢天氣"),
        media = HomeFact("需要登入", "登入後查看系統媒體狀態"),
        trip = HomeFact("需要登入", "登入後可自行載入騎乘紀錄"), vehicleMessage = "登入後查看已配對車輛")
    val reading = weather?.takeIf { it.signedIn }?.current?.weather
    fun weatherTime(value: Long) = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(value))
    val weatherFact = if (reading != null) HomeFact(reading.temperature(requireNotNull(weather).unit),
        listOf(reading.description.ifBlank { "天氣狀況未提供" }, reading.place,
            weather.fetchedAtMillis?.let { "上次更新 ${weatherTime(it)}" }.orEmpty(),
            weather.locationAtMillis?.let { "使用 ${weatherTime(it)} 取得的位置" }.orEmpty(),
            if (weather.lastKnownLocation) "上次已知位置" else "",
            if (weather.loading) "更新中" else "",
            if (weather.errorCode != null || weather.fetchedAtMillis?.let { System.currentTimeMillis() - it >= 3_600_000L } == true)
                "上次成功資料，更新待完成" else "",
            if (weather.storageError) "天氣保存未完成" else "").filter { it.isNotBlank() }.joinToString(" · "))
    else when {
        weather?.enabled == false -> HomeFact("背景更新已停用", "到天氣頁可重新開啟；停用會清除已保存的位置及天氣")
        weather?.locationGranted == false -> HomeFact("需要位置授權", "到天氣頁查看說明；未授權時無法取得所在地天氣")
        weather?.loading == true -> HomeFact("更新中", "正在背景讀取 Honda 天氣，完成後會自動顯示")
        weather?.errorCode != null -> HomeFact("更新未完成", "請到天氣頁檢查定位、網路與登入；背景工作會依系統條件執行")
        else -> HomeFact("尚未查詢", "有登入與位置授權時自動更新；背景使用最近一次前景取得的位置，不持續定位")
    }
    val activeMedia = media?.takeIf { it.signedIn && it.observing }?.media
    // Home has a separate observer, not the music page's controller selection. Display an
    // unambiguous observed session without selecting a token or issuing playback commands.
    val observedMedia = activeMedia?.sessions?.let { sessions ->
        sessions.filter { it.playback == MediaPlayback.PLAYING }.singleOrNull() ?: sessions.singleOrNull()
    }
    val mediaFact = when {
        activeMedia == null -> HomeFact("尚未讀取", "目前沒有前景媒體觀察")
        activeMedia.access == MediaAccess.REQUIRED -> HomeFact("需要媒體存取權", "到音樂頁查看授權方式")
        activeMedia.access == MediaAccess.UNAVAILABLE -> HomeFact("媒體服務無法使用", "到音樂頁查看狀態")
        activeMedia.selectedId != null -> HomeFact(activeMedia.title?.takeIf { it.isNotBlank() } ?: "曲名未提供",
            listOfNotNull(activeMedia.artist?.takeIf { it.isNotBlank() }, activeMedia.album?.takeIf { it.isNotBlank() },
                playbackLabel(activeMedia.playback)).joinToString(" · "))
        observedMedia != null -> HomeFact(observedMedia.title?.takeIf { it.isNotBlank() } ?: observedMedia.label,
            listOfNotNull(observedMedia.artist?.takeIf { it.isNotBlank() }, observedMedia.album?.takeIf { it.isNotBlank() },
                observedMedia.label, playbackLabel(observedMedia.playback)).joinToString(" · "))
        activeMedia.sessions.isEmpty() -> HomeFact("沒有可用播放器", "系統目前未提供媒體工作階段")
        else -> HomeFact("${activeMedia.sessions.size} 個可用播放器",
            if (activeMedia.sessions.count { it.playback == MediaPlayback.PLAYING } > 1) "多個播放器正在播放；到音樂頁選擇控制對象"
            else "目前沒有唯一播放中的工作階段；到音樂頁選擇控制對象")
    }
    val day = trips?.days?.filter { it.tripDate > 0 }?.maxByOrNull { it.tripDate }
    val tripFact = if (day != null) HomeFact(TripFormat.distance(day.totalDistance),
        "已載入的最近騎乘日 · ${Instant.ofEpochSecond(day.tripDate).atOffset(ZoneOffset.ofTotalSeconds(requireNotNull(trips).offsetMillis / 1000)).format(DateTimeFormatter.ISO_LOCAL_DATE)}\n" +
            "帳號當日合計，非此車里程" + if (trips.cached) " · 快取資料" else "")
    else HomeFact(when { trips?.busy == true -> "讀取中"; trips?.errorCode != null -> "讀取未完成"
        trips?.hasLoaded == true -> "已載入範圍沒有紀錄"; else -> "尚未讀取" }, "首頁不會查詢或開始記錄騎乘")
    val focused = vehicles?.pairedVehicles?.singleOrNull { it.inFocus }
    return HomeOverview(weatherFact, mediaFact, tripFact,
        focused?.let { HomeVehicle(listOf(modelName(it.model), it.year.takeIf { year -> year > 0 }?.toString())
            .filterNotNull().joinToString(" · "), it) }, when {
            vehicles?.garageError == true -> "車庫讀取未完成"
            vehicles?.garageLoading == true -> "正在讀取本機車庫"
            focused != null -> "主要車輛 · 已保存配對資料，不代表目前已連線或可上路"
            vehicles?.garageLoaded == true -> "沒有主要配對車輛"
            else -> "尚未讀取車庫"
        }, mediaControl = activeMedia?.homeControlTarget(), mediaResult = media?.result.takeIf { activeMedia != null },
        weatherKind = reading?.kind ?: WeatherKind.UNKNOWN, weatherPlace = reading?.place.orEmpty(),
        tripDate = day?.let { Instant.ofEpochSecond(it.tripDate)
            .atOffset(ZoneOffset.ofTotalSeconds(requireNotNull(trips).offsetMillis / 1000))
            .format(DateTimeFormatter.ISO_LOCAL_DATE) }.orEmpty(),
        garageHasNoPrimary = vehicles?.garageLoaded == true && vehicles.garageError == false && focused == null)
}

private fun playbackLabel(playback: MediaPlayback): String = when (playback) {
    MediaPlayback.PLAYING -> "播放中"; MediaPlayback.PAUSED -> "已暫停"
    MediaPlayback.STOPPED -> "已停止"; MediaPlayback.BUFFERING -> "緩衝中"
    MediaPlayback.CONNECTING -> "連接中"; MediaPlayback.ERROR -> "播放錯誤"
    MediaPlayback.NONE -> "沒有播放內容"; else -> "播放狀態未提供"
}

/** Show only after the local garage positively confirms no saved pairing. */
internal fun firstPairHintEligible(deferredGuide: Boolean, signedIn: Boolean,
    garageLoaded: Boolean, garageLoading: Boolean, garageError: Boolean,
    pairedCount: Int, dismissed: Boolean): Boolean =
    deferredGuide && signedIn && garageLoaded && !garageLoading && !garageError &&
        pairedCount == 0 && !dismissed
