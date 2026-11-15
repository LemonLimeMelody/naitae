package com.naitae.connect.vehicle.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.naitae.connect.core.model.Place
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.NaviGateway
import com.naitae.connect.vehicle.SdkLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 내비게이션(NaviHelper SDK): 길 안내 요청, 목적지 도착 이벤트, 즐겨찾기.
 *
 * NaviHelper 의 조회·요청 함수는 값을 돌려주지 않고 리스너 이벤트로 답한다.
 * 그래서 요청마다 '기다리는 자리'(CompletableDeferred)를 두고 해당 이벤트가 오면 채운다.
 */
internal class PleosNavi(private val context: Context) : NaviGateway {

    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<NaviEvent> = _events.asSharedFlow()

    private val lock = Any()
    private var helper: NaviHelper? = null
    private var connected: CompletableDeferred<Boolean>? = null

    /** 이 앱이 마지막으로 안내를 요청한 장소 */
    @Volatile
    private var requested: Place? = null

    /** 내비가 알려 준 현재 목적지(내비 화면에서 직접 정한 목적지도 여기에 들어온다) */
    @Volatile
    private var destination: DestinationInfo? = null

    @Volatile
    private var guiding = false

    @Volatile
    private var pendingRoute: CompletableDeferred<Result<Unit>>? = null

    @Volatile
    private var pendingBookmarks: CompletableDeferred<List<BookmarkInfo>>? = null

    @Volatile
    private var bookmarkCache: List<BookmarkInfo> = emptyList()

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            SdkLog.event("NaviHelper.onRouteStarted → ${routeStartInfo.name} · ${formatMeters(routeStartInfo.distance)}")
            pendingRoute?.complete(Result.success(Unit))
            announceGuidance(routeStartInfo.name)
            refreshDestination()
        }

        override fun onRoutingCompleted() {
            SdkLog.event("NaviHelper.onRoutingCompleted")
            pendingRoute?.complete(Result.success(Unit))
            announceGuidance(requested?.name ?: destination?.name.orEmpty())
        }

        override fun onRouteCancelled() {
            SdkLog.event("NaviHelper.onRouteCancelled")
            endGuidance()
        }

        // SDK 에서 사용 중단 예고된 이벤트지만, 아직 이 이벤트로 안내 종료를 알리는 내비 앱에 대비해 받아 둔다.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onRouteEnded() {
            SdkLog.event("NaviHelper.onRouteEnded")
            endGuidance()
        }

        override fun onDestinationChanged() {
            SdkLog.event("NaviHelper.onDestinationChanged")
            refreshDestination()
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            SdkLog.event("NaviHelper.onDestinationInfo → ${destinationInfo.name}")
            destination = destinationInfo
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            val arrived = toArrived(destinationArrivedInfo)
            SdkLog.event(
                "NaviHelper.onDestinationArrived → ${arrived.name} · ${formatMeters(destinationArrivedInfo.distance)} · ${arrived.durationMin}분",
            )
            // 도착으로 안내가 끝난 것이므로, 뒤따라오는 onRouteEnded 는 '안내 중지'로 다시 알리지 않는다.
            guiding = false
            requested = null
            destination = null
            _events.tryEmit(arrived)
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            SdkLog.event("NaviHelper.onBookmarkInfo → ${bookmarkInfoList.size}곳")
            bookmarkCache = bookmarkInfoList
            pendingBookmarks?.complete(bookmarkInfoList)
        }

        override fun onError(errorCode: NaviErrorCode) {
            SdkLog.event("NaviHelper.onError → ${errorCode.name}")
            pendingRoute?.complete(Result.failure(IllegalStateException("경로 요청 실패: ${errorCode.name}")))
        }
    }

    override fun start() {
        ensureStarted()
    }

    override fun stop() {
        val old = synchronized(lock) {
            val current = helper
            helper = null
            connected = null
            current
        } ?: return
        pendingRoute?.complete(Result.failure(IllegalStateException("내비 연결을 닫음")))
        safeSdk("NaviHelper.removeListener()") { old.removeListener(listener) }
        safeSdk("NaviHelper.release()") {
            old.release()
            SdkLog.api("NaviHelper.release()")
        }
        guiding = false
    }

    override suspend fun requestRoute(place: Place): Result<Unit> {
        val navi = readyHelper()
            ?: return Result.failure(IllegalStateException("내비게이션 서비스에 연결되지 않음"))
        // 즐겨찾기에서 고른 장소면 그 POI 정보를 같이 보낸다. 아니면 좌표와 이름만으로 요청한다.
        val bookmark = bookmarkCache.firstOrNull { it.name == place.name && distanceMeters(it.latitude, it.longitude, place.lat, place.lon) < SAME_PLACE_M }
        val route = RouteInfo(
            // 위도·경도 순서를 헷갈리지 않도록 이름을 붙여 넘긴다.
            latitude = place.lat,
            longitude = place.lon,
            poiName = place.name,
            poiId = bookmark?.poiId.orEmpty(),
            address = bookmark?.address.orEmpty(),
            poiSubId = bookmark?.poiSubId.orEmpty(),
        )
        val waiter = CompletableDeferred<Result<Unit>>()
        pendingRoute = waiter
        requested = place
        // 이미 다른 곳으로 안내 중이었어도 새 목적지의 안내 시작을 다시 알리도록 한다.
        guiding = false
        try {
            navi.requestRoute(route)
            SdkLog.api(
                "NaviHelper.requestRoute(${place.name} · 위도 ${formatCoord(place.lat)}, 경도 ${formatCoord(place.lon)})",
            )
        } catch (t: Throwable) {
            pendingRoute = null
            requested = null
            SdkLog.sys("NaviHelper.requestRoute() 실패 · ${t.brief()}")
            return Result.failure(t)
        }
        val outcome = withTimeoutOrNull(ROUTE_WAIT_MS) { waiter.await() }
        pendingRoute = null
        if (outcome == null) {
            // 내비 앱에 따라 시작 이벤트를 주지 않을 수 있다. 요청 자체는 전달됐으므로 성공으로 돌려준다.
            SdkLog.sys("경로 요청 전달됨(내비의 시작 이벤트는 아직 없음)")
            return Result.success(Unit)
        }
        if (outcome.isFailure) requested = null
        return outcome
    }

    override suspend fun bookmarks(): List<Place> {
        val navi = readyHelper() ?: return emptyList()
        val waiter = CompletableDeferred<List<BookmarkInfo>>()
        pendingBookmarks = waiter
        val asked = safeSdk("NaviHelper.getBookmarkInfo()") {
            navi.getBookmarkInfo()
            SdkLog.api("NaviHelper.getBookmarkInfo()")
        }
        val answer = if (asked) withTimeoutOrNull(BOOKMARK_WAIT_MS) { waiter.await() } else null
        pendingBookmarks = null
        if (answer == null) SdkLog.sys("즐겨찾기 응답 없음 → 마지막으로 받은 목록 ${bookmarkCache.size}곳 사용")
        return (answer ?: bookmarkCache)
            .filter { it.name.isNotBlank() }
            .map { Place(name = it.name, lat = it.latitude, lon = it.longitude) }
            .distinctBy { it.name }
    }

    // ───────── 연결 ─────────

    private fun ensureStarted(): CompletableDeferred<Boolean>? {
        synchronized(lock) {
            connected?.let { return it }
            val gate = CompletableDeferred<Boolean>()
            val created = try {
                NaviHelper(context)
            } catch (t: Throwable) {
                SdkLog.sys("NaviHelper 생성 실패 · ${t.brief()}")
                return null
            }
            // 연결 콜백이 initialize() 안에서 바로 불려도 맞게 동작하도록 먼저 넣어 둔다.
            helper = created
            connected = gate
            val initializing = safeSdk("NaviHelper.initialize()") {
                created.initialize(
                    { onConnected(created, gate) },
                    { error -> onConnectFailed(created, gate, error) },
                )
                SdkLog.api("NaviHelper.initialize(onConnected, onFailed)")
            }
            if (!initializing) {
                helper = null
                connected = null
                return null
            }
            return gate
        }
    }

    private fun onConnected(created: NaviHelper, gate: CompletableDeferred<Boolean>) {
        SdkLog.sys("내비게이션 서비스 연결됨")
        safeSdk("NaviHelper.addListener()") {
            created.addListener(listener)
            SdkLog.api("NaviHelper.addListener(listener)")
        }
        gate.complete(true)
        refreshDestination()
    }

    private fun onConnectFailed(created: NaviHelper, gate: CompletableDeferred<Boolean>, error: Throwable?) {
        SdkLog.sys("내비게이션 서비스 연결 실패 · ${error?.brief() ?: "원인 모름"}")
        // 다음 요청에서 새로 연결을 시도할 수 있게 비워 둔다.
        synchronized(lock) {
            if (helper === created) {
                helper = null
                connected = null
            }
        }
        gate.complete(false)
        safeSdk("NaviHelper.release()") { created.release() }
    }

    private suspend fun readyHelper(): NaviHelper? {
        val gate = ensureStarted() ?: return null
        val ok = withTimeoutOrNull(CONNECT_WAIT_MS) { gate.await() } ?: false
        if (!ok) return null
        return synchronized(lock) { helper }
    }

    // ───────── 이벤트 처리 ─────────

    private fun announceGuidance(name: String) {
        if (guiding) return
        guiding = true
        _events.tryEmit(NaviEvent.GuidanceStarted(name.ifBlank { requested?.name.orEmpty() }))
    }

    private fun endGuidance() {
        if (!guiding) return
        guiding = false
        // 새 경로를 요청하는 중이면, 이 이벤트는 바뀌기 전 경로가 끝났다는 뜻이므로 요청한 장소는 남겨 둔다.
        if (pendingRoute == null) {
            requested = null
            destination = null
        }
        _events.tryEmit(NaviEvent.GuidanceStopped)
    }

    /** 도착 정보에는 좌표가 없어서, 목적지 정보를 미리 받아 둔다. */
    private fun refreshDestination() {
        val navi = synchronized(lock) { helper } ?: return
        safeSdk("NaviHelper.getDestinationInfo()") {
            navi.getDestinationInfo()
            SdkLog.api("NaviHelper.getDestinationInfo()")
        }
    }

    private fun toArrived(info: DestinationArrivedInfo): NaviEvent.Arrived {
        val asked = requested
        val known = destination
        // 이 앱이 요청한 장소로 도착했으면 앱이 쓰는 이름·좌표를 그대로 돌려준다(기록·일정과 이름이 맞도록).
        val sameAsAsked = asked != null &&
            (known == null || distanceMeters(known.latitude, known.longitude, asked.lat, asked.lon) < SAME_PLACE_M)
        val name = when {
            sameAsAsked && asked != null -> asked.name
            info.name.isNotBlank() -> info.name
            known != null && known.name.isNotBlank() -> known.name
            else -> "목적지"
        }
        val lat = if (sameAsAsked && asked != null) asked.lat else known?.latitude
        val lon = if (sameAsAsked && asked != null) asked.lon else known?.longitude
        return NaviEvent.Arrived(
            name = name,
            lat = lat,
            lon = lon,
            // SDK 단위: 거리 m, 시간 초
            distanceKm = info.distance / 1000f,
            durationMin = (info.duration + 30) / 60,
        )
    }

    private fun formatMeters(meters: Int): String = String.format(Locale.KOREA, "%.1fkm", meters / 1000f)

    private fun formatCoord(value: Double): String = String.format(Locale.US, "%.5f", value)

    /** 가까운 두 지점 사이의 거리(m). 같은 장소인지 가릴 때만 쓰므로 평면 근사로 충분하다. */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val metersPerDegree = 111_320.0
        val dy = (lat2 - lat1) * metersPerDegree
        val dx = (lon2 - lon1) * metersPerDegree * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val CONNECT_WAIT_MS = 6_000L
        const val ROUTE_WAIT_MS = 5_000L
        const val BOOKMARK_WAIT_MS = 3_000L
        const val SAME_PLACE_M = 300.0
    }
}
