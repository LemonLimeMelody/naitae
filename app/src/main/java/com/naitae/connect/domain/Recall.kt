package com.naitae.connect.domain

import com.naitae.connect.core.model.DayRecord
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Topic
import java.time.LocalDate

/* 돌아본다: 같은 장소에 다시 오면 그날의 기록을 차가 먼저 보여 준다 (계약) */

data class Recall(val record: DayRecord, val topic: Topic, val yearsAgo: Int) {
    /** "1년 전 오늘, 여기서" */
    val title: String get() = if (yearsAgo >= 1) "${yearsAgo}년 전 오늘, 여기서" else "지난번, 여기서"
}

object RecallEngine {
    /** 같은 장소의 예전 기록 중 해가 바뀐 기록을 우선해 가장 최근 것을 고른다. 오늘 기록은 제외. 없으면 null. */
    fun find(data: NaitaeData, placeName: String, today: LocalDate): Recall? = TODO("domain")
}
