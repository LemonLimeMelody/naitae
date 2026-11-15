package com.naitae.connect.domain

import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Schedule
import java.time.LocalDate

/* 일정 (계약) */

data class Dday(val schedule: Schedule, val daysLeft: Int) {
    val tag: String get() = if (daysLeft == 0) "오늘" else "D-$daysLeft"
}

object ScheduleEngine {
    /** 오늘 일정, 시간순(시간 없는 일정은 뒤) */
    fun today(data: NaitaeData, today: LocalDate): List<Schedule> {
        val day = today.toEpochDay()
        return data.schedules
            .filter { it.epochDay == day }
            .sortedWith(byTime)
    }

    /** 내일부터 withinDays 일 안의 주제별 일정(topicId 가 있는 것), 가까운 순 */
    fun upcoming(data: NaitaeData, today: LocalDate, withinDays: Int = 7): List<Dday> {
        val day = today.toEpochDay()
        return data.schedules
            .filter { it.topicId != null && it.epochDay > day && it.epochDay <= day + withinDays }
            .sortedWith(compareBy<Schedule> { it.epochDay }.then(byTime))
            .map { Dday(it, (it.epochDay - day).toInt()) }
    }

    /** 오늘 일정 중 장소가 있고 아직 다녀오지 않은 첫 일정 → 출발 전에 길 안내를 물어볼 대상 */
    fun routeCandidate(data: NaitaeData, today: LocalDate): Schedule? {
        val day = today.toEpochDay()
        return this.today(data, today).firstOrNull { schedule ->
            val place = schedule.place ?: return@firstOrNull false
            // 오늘 그곳의 기록이 이미 있으면 다녀온 것으로 본다.
            data.records.none { it.epochDay == day && (it.scheduleId == schedule.id || it.placeName == place.name) }
        }
    }

    /** 시간 있는 일정이 먼저, 그 안에서는 이른 순. 같으면 제목순(결과가 늘 같도록). */
    private val byTime: Comparator<Schedule> =
        compareBy<Schedule> { Ko.minutesOf(it.time) ?: Int.MAX_VALUE }.thenBy { it.title }
}
