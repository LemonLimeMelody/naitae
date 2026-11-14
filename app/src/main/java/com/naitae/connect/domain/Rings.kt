package com.naitae.connect.domain

import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.RingView
import com.naitae.connect.core.model.TopicSummary
import java.time.LocalDate

/* 나이테 계산 (계약). 1년 = 한 겹, 기록 = 그해 겹의 점. */
object RingBuilder {
    /**
     * 주제의 첫 기록이 있는 해(없으면 올해)부터 올해까지 한 해에 한 겹.
     * 올해 겹은 complete=false, progress=올해가 지난 비율. 점의 각도는 그날이 그해에서 차지하는 위치(0~360).
     * highlightRecordId 의 점은 isNew=true.
     */
    fun rings(
        data: NaitaeData,
        topicId: String,
        today: LocalDate,
        highlightRecordId: String? = null,
    ): List<RingView> = TODO("domain")

    /** 화면 요약: 겹, 통계 3개(다녀온 날 / 처음 간 곳 / 함께한 날), 최근 기록 */
    fun summary(
        data: NaitaeData,
        topicId: String,
        today: LocalDate,
        highlightRecordId: String? = null,
    ): TopicSummary = TODO("domain")
}
