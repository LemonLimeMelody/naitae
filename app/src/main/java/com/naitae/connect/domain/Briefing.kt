package com.naitae.connect.domain

import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.vehicle.SeatOccupancy
import java.time.LocalDate
import java.time.LocalDateTime

/* 챙긴다: 하루 첫 시동의 일정·메모 음성 브리핑 (계약: 공개 시그니처 유지) */

enum class BriefingKind { SCHEDULE, MEMO, DDAY }

data class BriefingItem(
    val kind: BriefingKind,
    /** 화면 꼬리표: "일정", "메모", "D-2" */
    val tag: String,
    val title: String,
    val sub: String? = null,
    /** 이 줄을 읽어 줄 문장 */
    val spoken: String,
    val scheduleId: String? = null,
    val memoId: String? = null,
)

data class Briefing(
    /** "11월 12일 목요일" */
    val dateLabel: String,
    val greeting: String,
    val items: List<BriefingItem>,
    /** 브리핑 끝에 "메모할 것 있으세요?" 를 물을지 */
    val askMemo: Boolean = true,
)

object BriefingEngine {
    const val ASK_MEMO = "메모할 것 있으세요?"

    /** 오늘 아직 브리핑하지 않았으면 true (하루 첫 시동에 한 번) */
    fun shouldBrief(data: NaitaeData, today: LocalDate): Boolean = TODO("domain")

    /**
     * 오늘 일정(시간순) → 메모 → 7일 안의 주제별 일정 D-day 순으로 만든다.
     * 동승자가 있으면 personal 메모는 넣지 않는다.
     */
    fun build(data: NaitaeData, now: LocalDateTime, seats: SeatOccupancy): Briefing = TODO("domain")

    /** 브리핑을 마쳤다고 기록한다(lastBriefingEpochDay, 읽어 준 메모의 briefedEpochDay). */
    fun markDone(data: NaitaeData, briefing: Briefing, today: LocalDate): NaitaeData = TODO("domain")
}
