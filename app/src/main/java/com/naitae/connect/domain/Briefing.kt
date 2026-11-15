package com.naitae.connect.domain

import com.naitae.connect.core.model.Memo
import com.naitae.connect.core.model.MemoRemind
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Schedule
import com.naitae.connect.core.model.SceneKind
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

    private const val TAG_SCHEDULE = "일정"
    private const val TAG_MEMO = "메모"
    private const val DDAY_WITHIN_DAYS = 7

    /** 오늘 아직 브리핑하지 않았으면 true (하루 첫 시동에 한 번) */
    fun shouldBrief(data: NaitaeData, today: LocalDate): Boolean =
        data.lastBriefingEpochDay != today.toEpochDay()

    /**
     * 오늘 일정(시간순) → 메모 → 7일 안의 주제별 일정 D-day 순으로 만든다.
     * 동승자가 있으면 personal 메모는 넣지 않는다.
     */
    fun build(data: NaitaeData, now: LocalDateTime, seats: SeatOccupancy): Briefing {
        val today = now.toLocalDate()
        val items = ArrayList<BriefingItem>()
        ScheduleEngine.today(data, today).forEach { items += scheduleItem(data, it) }
        memosToBrief(data, seats).forEach { items += memoItem(data, it) }
        ScheduleEngine.upcoming(data, today, DDAY_WITHIN_DAYS).forEach { items += ddayItem(it) }

        val hello = greeting(now.hour)
        return Briefing(
            dateLabel = Ko.dateLabel(today),
            greeting = if (items.isEmpty()) "$hello 오늘은 일정과 메모가 없어요." else hello,
            items = items,
            askMemo = true,
        )
    }

    /** 브리핑을 마쳤다고 기록한다(lastBriefingEpochDay, 읽어 준 메모의 briefedEpochDay). */
    fun markDone(data: NaitaeData, briefing: Briefing, today: LocalDate): NaitaeData {
        val day = today.toEpochDay()
        val readMemoIds = briefing.items.mapNotNull { it.memoId }.toSet()
        return data.copy(
            lastBriefingEpochDay = day,
            memos = data.memos.map { if (it.id in readMemoIds) it.copy(briefedEpochDay = day) else it },
        )
    }

    private fun greeting(hour: Int): String = when (hour) {
        in 4..11 -> "좋은 아침이에요."
        in 12..17 -> "좋은 오후예요."
        in 18..21 -> "좋은 저녁이에요."
        else -> "늦은 밤이에요."
    }

    /**
     * 브리핑에서 읽을 메모(남긴 순).
     *  - 다음 시동·장소 메모: 아직 브리핑에서 읽지 않은 것
     *  - 퇴근길 메모: 퇴근길 다시 알림이 끝나기 전까지(전날 퇴근길에 차를 타지 않았으면 다음 날 아침에도 읽음)
     */
    private fun memosToBrief(data: NaitaeData, seats: SeatOccupancy): List<Memo> =
        data.memos
            .filter { memo ->
                if (memo.done) return@filter false
                if (memo.personal && seats.hasPassenger) return@filter false
                when (memo.remind) {
                    is MemoRemind.EveningIgnition -> memo.remindedEpochDay == null
                    is MemoRemind.NextIgnition, is MemoRemind.AtPlace -> memo.briefedEpochDay == null
                }
            }
            .sortedBy { it.createdAtEpochMs }

    private fun scheduleItem(data: NaitaeData, schedule: Schedule): BriefingItem {
        val place = schedule.place
        val title = buildString {
            if (schedule.time != null) append(schedule.time).append(' ')
            append(schedule.title)
            if (place != null) append(" · ").append(place.name)
        }
        val timeSpoken = Ko.spokenTime(schedule.time)
        val topic = data.topic(schedule.topicId)
        // 낚시·나들이처럼 주제의 장소로 떠나는 날은 "오늘은 낚시 가는 날이에요." 로 읽는다(본업 일정은 제외).
        val outing = place != null && topic != null && topic.scene != SceneKind.WORK
        val spoken = when {
            outing && place != null && timeSpoken != null ->
                "오늘은 ${schedule.title} 가는 날이에요. $timeSpoken, ${Ko.josa(place.name, "이에요", "예요")}."
            outing -> "오늘은 ${schedule.title} 가는 날이에요."
            timeSpoken != null -> "$timeSpoken, ${Ko.josa(schedule.title, "이", "가")} 있어요."
            else -> "오늘은 ${Ko.josa(schedule.title, "이", "가")} 있어요."
        }
        return BriefingItem(
            kind = BriefingKind.SCHEDULE,
            tag = TAG_SCHEDULE,
            title = title,
            sub = if (place != null) "출발 전에 길 안내를 물어봄" else null,
            spoken = spoken,
            scheduleId = schedule.id,
        )
    }

    private fun memoItem(data: NaitaeData, memo: Memo): BriefingItem {
        val mine = memo.authorId == data.owner.id
        val author = data.member(memo.authorId)?.name
        val title = if (mine || author == null) memo.text else "$author: ${memo.text}"
        val spokenHead = if (mine || author == null) "내 메모" else "$author 메모"
        val sub = when (val remind = memo.remind) {
            is MemoRemind.EveningIgnition -> "퇴근길 시동 때 다시 알려 줌"
            is MemoRemind.AtPlace -> "${remind.place.name} 근처에서 다시 알려 줌"
            is MemoRemind.NextIgnition -> null
        }
        return BriefingItem(
            kind = BriefingKind.MEMO,
            tag = TAG_MEMO,
            title = title,
            sub = sub,
            spoken = "$spokenHead, ${Ko.sentence(memo.text)}",
            memoId = memo.id,
        )
    }

    private fun ddayItem(dday: Dday): BriefingItem {
        val schedule = dday.schedule
        val place = schedule.place
        val weekday = Ko.weekday(LocalDate.ofEpochDay(schedule.epochDay))
        val detail = listOfNotNull(schedule.time, place?.name).joinToString(" ")
        val title = if (detail.isEmpty()) "$weekday ${schedule.title}" else "$weekday ${schedule.title} · $detail"
        return BriefingItem(
            kind = BriefingKind.DDAY,
            tag = dday.tag,
            title = title,
            sub = if (place != null) "그날 시동 때 길 안내를 물어봄" else null,
            spoken = "$weekday ${schedule.title}까지 ${Ko.dayCount(dday.daysLeft)} 남았어요.",
            scheduleId = schedule.id,
        )
    }
}
