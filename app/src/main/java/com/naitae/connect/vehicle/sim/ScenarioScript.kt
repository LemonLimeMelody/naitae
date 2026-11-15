package com.naitae.connect.vehicle.sim

import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SimulatorConsole
import kotlinx.coroutines.delay

/**
 * 발표 시나리오를 처음부터 끝까지 자동으로 넣는다.
 * 가상 콘솔만 부른다(차 쪽 신호만 넣음). 화면·음성·기록은 앱이 신호를 보고 스스로 움직인다.
 * 중간에 멈추려면 이 함수를 돌리는 코루틴을 취소하면 된다.
 */
object ScenarioScript {

    /** 날짜를 바꾼 뒤 앱이 그날의 데이터를 다시 채울 틈 */
    private const val DAY_SETTLE_MS = 1_500L

    /** 하루 첫 시동의 브리핑(인사 → 항목 낭독 → 메모 받기)이 끝날 때까지 */
    private const val BRIEFING_MS = 26_000L

    /** 브리핑에 이어 "○○로 안내할까요?"까지 */
    private const val BRIEFING_WITH_ROUTE_MS = 24_000L

    /** 1년 뒤 아침: 출발 전에 잠깐 */
    private const val BEFORE_DEPARTURE_MS = 12_000L

    /** 출발 → 도착 → P, 그리고 조용한 도착을 확인할 틈 */
    private const val ARRIVAL_MS = 4_000L

    /** 시동을 끄고 시각을 바꾼 뒤 다시 걸기까지 */
    private const val ENGINE_OFF_MS = 1_500L

    /** 퇴근길 메모 카드와 낭독 */
    private const val EVENING_MEMO_MS = 9_000L

    /** "오늘을 남길까요?" → 한마디 받기 */
    private const val RECORD_MS = 18_000L

    /** 남기기 + 함께 꾸미기(사진·색 → 실내 조명) */
    private const val RECORD_DECORATE_MS = 22_000L

    /** "1년 전 오늘, 여기서" 다시 보기 */
    private const val RECALL_MS = 16_000L

    /** 편지 도착 → 열기 → 연출·낭독 */
    private const val LETTER_MS = 22_000L

    /** 이어받기 → 편지 낭독 → "새 차 0km · 나이테 스무 겹" */
    private const val HANDOVER_MS = 28_000L

    private const val PLACE_OFFICE = "회사"
    private const val PLACE_PIER = "낚시터"
    private const val PLACE_ARBORETUM = "수목원"

    suspend fun play(console: SimulatorConsole, step: (String) -> Unit) {
        weekday(console, step)
        fishingSaturday(console, step)
        familySunday(console, step)
        oneYearLater(console, step)
        birthday(console, step)
        twentyYearsLater(console, step)
        step("시나리오 끝")
    }

    private suspend fun weekday(console: SimulatorConsole, step: (String) -> Unit) {
        startDay(console, step, ScenarioDay.WEEKDAY, "평일 아침 07:42")
        step("첫 시동 · 오늘 일정과 메모 브리핑")
        console.ignition(true)
        delay(BRIEFING_MS)

        arrive(console, step, PLACE_OFFICE, "회사 도착 · 평소 도착이라 화면 없음")

        step("퇴근길 18:40 · 다시 시동 · 엄마 메모")
        restartAt(console, 18, 40)
        delay(EVENING_MEMO_MS)
        console.ignition(false)
    }

    private suspend fun fishingSaturday(console: SimulatorConsole, step: (String) -> Unit) {
        startDay(console, step, ScenarioDay.FISHING_SATURDAY, "토요일 새벽 05:03")
        step("첫 시동 · 브리핑 뒤 \"낚시터로 안내할까요?\"")
        console.ignition(true)
        delay(BRIEFING_WITH_ROUTE_MS)

        arrive(console, step, PLACE_PIER, "낚시터 도착 · 처음 간 곳 · 묻지 않음")

        step("11:40 돌아가는 길 · 다시 시동 · \"오늘을 남길까요?\"")
        restartAt(console, 11, 40)
        delay(RECORD_MS)
        console.ignition(false)
    }

    private suspend fun familySunday(console: SimulatorConsole, step: (String) -> Unit) {
        startDay(console, step, ScenarioDay.FAMILY_SUNDAY, "일요일 10:20 · 가족 넷이 탑승")
        step("첫 시동 · 브리핑 뒤 \"수목원으로 안내할까요?\"")
        console.ignition(true)
        delay(BRIEFING_WITH_ROUTE_MS)

        arrive(console, step, PLACE_ARBORETUM, "수목원 도착")

        step("16:30 돌아가는 길 · 다시 시동 · 남기고 함께 꾸미기")
        restartAt(console, 16, 30)
        delay(RECORD_DECORATE_MS)
        console.ignition(false)
    }

    private suspend fun oneYearLater(console: SimulatorConsole, step: (String) -> Unit) {
        startDay(console, step, ScenarioDay.ONE_YEAR_LATER, "1년 뒤 같은 날 10:31")
        step("시동 · 수목원으로 출발")
        console.ignition(true)
        delay(BEFORE_DEPARTURE_MS)

        arrive(console, step, PLACE_ARBORETUM, "수목원에 다시 도착 · \"1년 전 오늘, 여기서\"")
        delay(RECALL_MS)
        console.ignition(false)
    }

    private suspend fun birthday(console: SimulatorConsole, step: (String) -> Unit) {
        startDay(console, step, ScenarioDay.BIRTHDAY, "아빠 생일 아침 07:38")
        step("시동 · 미래 편지가 도착")
        console.ignition(true)
        delay(LETTER_MS)
        console.ignition(false)
    }

    private suspend fun twentyYearsLater(console: SimulatorConsole, step: (String) -> Unit) {
        startDay(console, step, ScenarioDay.TWENTY_YEARS_LATER, "20년 뒤 · 선물 받은 새 차")
        step("새 차의 첫 시동 · 이어받기와 편지")
        console.ignition(true)
        delay(HANDOVER_MS)
    }

    // ───────── 공통 동작 ─────────

    private suspend fun startDay(console: SimulatorConsole, step: (String) -> Unit, day: ScenarioDay, label: String) {
        step(label)
        console.setDay(day)
        delay(DAY_SETTLE_MS)
    }

    private suspend fun arrive(console: SimulatorConsole, step: (String) -> Unit, placeName: String, label: String) {
        val place = console.places.firstOrNull { it.name == placeName }
        if (place == null) {
            step("$placeName 을(를) 찾지 못해 건너뜀")
            return
        }
        step(label)
        console.arrive(place)
        delay(ARRIVAL_MS)
    }

    /** 그곳에서 시간을 보낸 뒤: 시동을 끄고, 시각을 옮기고, 다시 건다. */
    private suspend fun restartAt(console: SimulatorConsole, hour: Int, minute: Int) {
        console.ignition(false)
        delay(ENGINE_OFF_MS)
        console.setClock(hour, minute)
        delay(ENGINE_OFF_MS)
        console.ignition(true)
    }
}
