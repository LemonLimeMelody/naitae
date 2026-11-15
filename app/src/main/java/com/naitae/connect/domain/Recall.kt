package com.naitae.connect.domain

import com.naitae.connect.core.model.DayRecord
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Topic
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/* 돌아본다: 같은 장소에 다시 오면 그날의 기록을 차가 먼저 보여 준다 (계약) */

data class Recall(val record: DayRecord, val topic: Topic, val yearsAgo: Int) {
    /** "1년 전 오늘, 여기서" */
    val title: String get() = if (yearsAgo >= 1) "${yearsAgo}년 전 오늘, 여기서" else "지난번, 여기서"
}

object RecallEngine {
    /** 같은 장소의 예전 기록 중 해가 바뀐 기록을 우선해 가장 최근 것을 고른다. 오늘 기록은 제외. 없으면 null. */
    fun find(data: NaitaeData, placeName: String, today: LocalDate): Recall? {
        val day = today.toEpochDay()
        val candidates = data.records
            .filter { it.placeName == placeName && it.epochDay < day }
            .mapNotNull { record ->
                // 주제가 지워진 기록은 보여 줄 나이테가 없으므로 건너뛴다.
                val topic = data.topic(record.topicId) ?: return@mapNotNull null
                Recall(record, topic, yearsBetween(record.epochDay, today))
            }
        if (candidates.isEmpty()) return null
        // "N년 전"이라고 말할 수 있는(만 1년 이상 지난) 기록을 먼저, 그 안에서 가장 최근 것.
        val yearsOld = candidates.filter { it.yearsAgo >= 1 }
        val pool = if (yearsOld.isNotEmpty()) yearsOld else candidates
        return pool.maxByOrNull { it.record.epochDay }
    }

    /** 만으로 몇 해가 지났는지 */
    private fun yearsBetween(epochDay: Long, today: LocalDate): Int =
        ChronoUnit.YEARS.between(LocalDate.ofEpochDay(epochDay), today).toInt().coerceAtLeast(0)
}
