package com.naitae.connect.domain

import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Place
import com.naitae.connect.core.model.Schedule
import com.naitae.connect.core.model.SceneKind
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.SeatOccupancy
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 이동 추적 (계약). 차의 신호를 받아 '무엇을 할 때인지'를 알려 준다. 순수 로직이라 단위 테스트한다.
 *
 * 규칙
 *  - 시동: 일정에 장소가 있는 날이면 출발 전에 길 안내를 물어본다(AskRoute). 자동으로 시작하지 않는다.
 *  - 도착: 평소 도착(자주 가는 곳)은 화면 없이 지나간다(QuietArrival).
 *          특별한 날(오늘 주제별 일정의 장소이거나 처음 간 곳)에 도착하면 기록 초안만 조용히 준비한다(DraftReady).
 *          같은 장소에 예전 기록이 있으면 '차에서 다시 보기'를 연다(RecallAt).
 *  - 그곳에서 시간을 보내고 다시 시동을 걸면(P) "오늘을 남길까요?" 를 묻는다(AskRecord).
 */

data class RecordDraft(
    val topicId: String?,
    val placeName: String,
    val place: Place?,
    val epochDay: Long,
    val distanceKm: Float,
    val firstVisit: Boolean,
    val seatCount: Int,
    val scheduleId: String?,
    val vin: String,
)

sealed interface TripSignal {
    data class IgnitionOn(val now: LocalDateTime) : TripSignal
    data class IgnitionOff(val now: LocalDateTime) : TripSignal
    data class Gear(val gear: GearState) : TripSignal
    data class Odometer(val km: Float) : TripSignal
    data class Seats(val seats: SeatOccupancy) : TripSignal
    data class Arrived(val event: NaviEvent.Arrived, val now: LocalDateTime) : TripSignal

    /** 사용자가 길 안내를 받기로 했다 */
    data class RouteAccepted(val place: Place) : TripSignal
}

sealed interface TripEffect {
    data class AskRoute(val schedule: Schedule) : TripEffect
    data class QuietArrival(val topicId: String?, val placeName: String, val distanceKm: Float) : TripEffect
    data class DraftReady(val draft: RecordDraft) : TripEffect
    data class AskRecord(val draft: RecordDraft) : TripEffect
    data class RecallAt(val placeName: String) : TripEffect
}

class TripTracker(
    private val data: () -> NaitaeData,
    private val vin: () -> String,
) {
    /** 도착해서 준비해 둔, 아직 묻지 않은 기록 초안 */
    var pendingDraft: RecordDraft? = null
        private set

    /** 가장 최근 좌석 신호의 착석 수(운전자 포함, 신호가 없으면 1) */
    private var seatCount: Int = 1
    private var odometerKm: Float? = null

    /** 마지막 시동 때의 누적 주행거리. 도착 이벤트에 거리가 없을 때 이번 이동 거리를 구하는 기준. */
    private var odometerAtIgnition: Float? = null

    /** 지금 세워 둔 곳(마지막으로 도착한 곳). 그곳을 떠나면 null */
    private var parkedPlaceName: String? = null

    /** 도착한 뒤에 시동을 다시 걸었는지. 그 뒤에 기어가 주행으로 바뀌면 그곳을 떠난 것이다. */
    private var ignitionSinceArrival: Boolean = false

    /** 길 안내를 받기로 한 목적지(도착 이벤트에 좌표가 없을 때 장소 정보로 쓴다) */
    private var routeTarget: Place? = null

    /** 이미 길 안내를 물어본 일정("epochDay:일정 id"). 같은 날 같은 일정은 한 번만 묻는다. */
    private val askedRoutes = HashSet<String>()

    /** 오늘 이미 도착한 곳("epochDay:장소 이름"). 다녀온 곳으로는 다시 안내를 묻지 않는다. */
    private val arrivedPlaces = HashSet<String>()

    private var lastNow: LocalDateTime? = null

    fun onSignal(signal: TripSignal): List<TripEffect> = when (signal) {
        is TripSignal.IgnitionOn -> onIgnitionOn(signal.now)
        is TripSignal.IgnitionOff -> {
            touch(signal.now)
            emptyList()
        }
        is TripSignal.Gear -> {
            onGear(signal.gear)
            emptyList()
        }
        is TripSignal.Odometer -> {
            onOdometer(signal.km)
            emptyList()
        }
        is TripSignal.Seats -> {
            seatCount = signal.seats.count.coerceAtLeast(1)
            emptyList()
        }
        is TripSignal.Arrived -> onArrived(signal.event, signal.now)
        is TripSignal.RouteAccepted -> {
            routeTarget = signal.place
            emptyList()
        }
    }

    /** 사용자가 남기거나 나중으로 미루면 초안을 비운다. */
    fun clearDraft() {
        pendingDraft = null
    }

    /** 시나리오를 처음부터 다시 시작할 때(시뮬레이터에서 날짜를 바꿀 때) 이동 상태를 모두 비운다. */
    internal fun reset() {
        pendingDraft = null
        odometerAtIgnition = null
        parkedPlaceName = null
        ignitionSinceArrival = false
        routeTarget = null
        askedRoutes.clear()
        arrivedPlaces.clear()
        lastNow = null
    }

    private fun onIgnitionOn(now: LocalDateTime): List<TripEffect> {
        touch(now)
        val current = data()
        val today = now.toLocalDate()
        odometerAtIgnition = odometerKm
        ignitionSinceArrival = true

        val draft = pendingDraft
        if (draft != null) {
            val alreadyRecorded = current.records.any { it.epochDay == draft.epochDay && it.placeName == draft.placeName }
            val stillThere = parkedPlaceName == draft.placeName && draft.vin == vin()
            if (alreadyRecorded || !stillThere) {
                pendingDraft = null
            } else {
                // 그곳에서 시간을 보내고 돌아가려는 시동: 이때만 묻는다. 길 안내 질문과 겹치지 않게 이것 하나만 낸다.
                return listOf(TripEffect.AskRecord(draft))
            }
        }

        val candidate = ScheduleEngine.routeCandidate(current, today) ?: return emptyList()
        val placeName = candidate.place?.name ?: return emptyList()
        val day = today.toEpochDay()
        if (arrivedKey(day, placeName) in arrivedPlaces) return emptyList()
        if (!askedRoutes.add("$day:${candidate.id}")) return emptyList()
        return listOf(TripEffect.AskRoute(candidate))
    }

    private fun onGear(gear: GearState) {
        val moving = gear == GearState.DRIVE || gear == GearState.REVERSE
        if (moving && ignitionSinceArrival && parkedPlaceName != null) {
            // 물음에 답하지 않고 출발했으면 '나중에'로 본다. 다음 시동에 다시 묻지 않는다.
            parkedPlaceName = null
            pendingDraft = null
        }
    }

    private fun onOdometer(km: Float) {
        val before = odometerKm
        // 누적 주행거리가 줄었다면 다른 차이거나 시나리오를 다시 시작한 것이다.
        if (before != null && km < before - ODOMETER_BACKWARD_KM) reset()
        odometerKm = km
        if (odometerAtIgnition == null) odometerAtIgnition = km
    }

    private fun onArrived(event: NaviEvent.Arrived, now: LocalDateTime): List<TripEffect> {
        touch(now)
        val current = data()
        val today = now.toLocalDate()
        val day = today.toEpochDay()

        val known = knownPlace(current, event, today)
        val placeName = known?.name ?: event.name.trim()
        val lat = event.lat
        val lon = event.lon
        val place = known ?: if (lat != null && lon != null) Place(placeName, lat, lon) else null
        val distanceKm = tripDistance(event)

        // 다른 곳에 도착했으면 앞선 곳의 초안은 더 묻지 않는다.
        val waiting = pendingDraft
        if (waiting != null && waiting.placeName != placeName) pendingDraft = null

        parkedPlaceName = placeName
        ignitionSinceArrival = false
        routeTarget = null
        arrivedPlaces.add(arrivedKey(day, placeName))

        val schedulesHere = ScheduleEngine.today(current, today).filter { it.place?.name == placeName }
        val topicSchedule = schedulesHere.firstOrNull { it.topicId != null }
        val schedule = topicSchedule ?: schedulesHere.firstOrNull()
        val placeTopic = current.topics.firstOrNull { topic -> topic.places.any { it.name == placeName } }
        val topicId = topicSchedule?.topicId ?: placeTopic?.id

        // 같은 날 같은 곳에 초안이 이미 있으면 그대로 둔다(도착 이벤트가 두 번 와도 거리가 0 으로 덮이지 않게).
        val sameDraft = pendingDraft
        if (sameDraft != null && sameDraft.epochDay == day && sameDraft.placeName == placeName) {
            return listOf(TripEffect.DraftReady(sameDraft))
        }

        // 오늘 이미 남긴 곳이면 조용히 지나간다.
        if (current.records.any { it.epochDay == day && it.placeName == placeName }) {
            return listOf(TripEffect.QuietArrival(topicId, placeName, distanceKm))
        }

        // 평소 도착: 본업의 장소(회사)처럼 날마다 가는 곳은 오늘 그곳의 주제별 일정이 없으면 묻지도 열지도 않는다.
        val routine = topicSchedule == null && placeTopic != null && placeTopic.scene == SceneKind.WORK
        if (routine) return listOf(TripEffect.QuietArrival(topicId, placeName, distanceKm))

        // 같은 곳에 예전 기록이 있으면 다시 보기가 먼저다. 이때는 초안을 준비하지 않는다.
        if (RecallEngine.find(current, placeName, today) != null) {
            return listOf(TripEffect.RecallAt(placeName))
        }

        val firstVisit = current.records.none { it.placeName == placeName && it.epochDay < day }
        val special = topicSchedule != null || firstVisit
        if (!special) return listOf(TripEffect.QuietArrival(topicId, placeName, distanceKm))

        val draft = RecordDraft(
            topicId = topicId,
            placeName = placeName,
            place = place,
            epochDay = day,
            distanceKm = distanceKm,
            firstVisit = firstVisit,
            seatCount = seatCount,
            scheduleId = schedule?.id,
            vin = vin(),
        )
        pendingDraft = draft
        return listOf(TripEffect.DraftReady(draft))
    }

    /** 도착 이벤트의 거리, 없으면 마지막 시동 뒤 늘어난 누적 주행거리 */
    private fun tripDistance(event: NaviEvent.Arrived): Float {
        val fromEvent = event.distanceKm
        if (fromEvent != null) return fromEvent.coerceAtLeast(0f)
        val start = odometerAtIgnition
        val end = odometerKm
        return if (start != null && end != null) (end - start).coerceAtLeast(0f) else 0f
    }

    /**
     * 도착한 곳을 앱이 아는 장소(오늘 일정의 장소, 주제의 장소, 길 안내 목적지, 예전 기록의 장소)와 맞춘다.
     * 이름이 같으면 그 장소, 내비가 다른 이름을 주더라도 좌표가 가까우면 같은 장소로 본다.
     */
    private fun knownPlace(current: NaitaeData, event: NaviEvent.Arrived, today: LocalDate): Place? {
        val name = event.name.trim()
        val places = ArrayList<Place>()
        ScheduleEngine.today(current, today).forEach { schedule -> schedule.place?.let { places.add(it) } }
        current.topics.forEach { places.addAll(it.places) }
        routeTarget?.let { places.add(it) }
        current.records.forEach { record -> record.place?.let { places.add(it) } }

        places.firstOrNull { it.name == name }?.let { return it }
        val lat = event.lat ?: return null
        val lon = event.lon ?: return null
        return places.firstOrNull { distanceM(it.lat, it.lon, lat, lon) <= SAME_PLACE_RADIUS_M }
    }

    /** 시각이 거꾸로 갔으면(시뮬레이터에서 같은 날을 다시 시작) 앞선 이동 상태를 버린다. */
    private fun touch(now: LocalDateTime) {
        val last = lastNow
        if (last != null && now.isBefore(last)) reset()
        lastNow = now
    }

    private fun arrivedKey(epochDay: Long, placeName: String): String = "$epochDay:$placeName"

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private companion object {
        const val SAME_PLACE_RADIUS_M = 300.0
        const val EARTH_RADIUS_M = 6_371_000.0
        const val ODOMETER_BACKWARD_KM = 0.5f
    }
}
