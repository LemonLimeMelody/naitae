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
    fun today(data: NaitaeData, today: LocalDate): List<Schedule> = TODO("domain")

    /** 내일부터 withinDays 일 안의 주제별 일정(topicId 가 있는 것), 가까운 순 */
    fun upcoming(data: NaitaeData, today: LocalDate, withinDays: Int = 7): List<Dday> = TODO("domain")

    /** 오늘 일정 중 장소가 있고 아직 다녀오지 않은 첫 일정 → 출발 전에 길 안내를 물어볼 대상 */
    fun routeCandidate(data: NaitaeData, today: LocalDate): Schedule? = TODO("domain")
}
