package com.naitae.connect.domain

import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Place
import com.naitae.connect.core.model.Schedule
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.SeatOccupancy
import java.time.LocalDateTime

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

    fun onSignal(signal: TripSignal): List<TripEffect> = TODO("domain")

    /** 사용자가 남기거나 나중으로 미루면 초안을 비운다. */
    fun clearDraft() {
        pendingDraft = null
    }
}
