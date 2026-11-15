package com.naitae.connect.domain

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.DayRecord
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.RingView
import com.naitae.connect.core.model.SceneDot
import com.naitae.connect.core.model.Topic
import com.naitae.connect.core.model.TopicStat
import com.naitae.connect.core.model.TopicSummary
import java.time.LocalDate

/* 나이테 계산 (계약). 1년 = 한 겹, 기록 = 그해 겹의 점. */
object RingBuilder {
    private const val MIN_THICKNESS = 0.5f
    private const val MAX_THICKNESS = 1.5f

    /** 기록 한 건이 더하는 두께 */
    private const val THICKNESS_PER_RECORD = 0.07f

    /** 함께 달린 1,000km 가 더하는 두께 */
    private const val THICKNESS_PER_1000_KM = 0.3f

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
    ): List<RingView> {
        val records = topicRecords(data, topicId, today)
        val byYear = records.groupBy { LocalDate.ofEpochDay(it.epochDay).year }
        val firstYear = byYear.keys.minOrNull() ?: today.year

        return (firstYear..today.year).mapIndexed { index, year ->
            val ofYear = byYear[year].orEmpty()
            val complete = year < today.year
            RingView(
                year = year,
                index = index,
                complete = complete,
                progress = if (complete) 1f else today.dayOfYear.toFloat() / today.lengthOfYear(),
                thickness = thickness(ofYear),
                color = mostUsedColor(ofYear),
                vin = carOfYear(data, ofYear, year),
                scenes = ofYear.map { record ->
                    val date = LocalDate.ofEpochDay(record.epochDay)
                    SceneDot(
                        recordId = record.id,
                        angleDeg = (date.dayOfYear - 1) * 360f / date.lengthOfYear(),
                        firstVisit = record.firstVisit,
                        isNew = record.id == highlightRecordId,
                    )
                },
            )
        }
    }

    /** 화면 요약: 겹, 통계 3개(다녀온 날 / 처음 간 곳 / 함께한 날), 최근 기록 */
    fun summary(
        data: NaitaeData,
        topicId: String,
        today: LocalDate,
        highlightRecordId: String? = null,
    ): TopicSummary {
        val records = topicRecords(data, topicId, today)
        // 주제를 못 찾아도 화면이 죽지 않게 빈 주제로 대신한다.
        val topic = data.topic(topicId) ?: Topic(id = topicId, name = "", ownerId = data.owner.id)
        val days = records.map { it.epochDay }.distinct().size
        val firstPlaces = records.count { it.firstVisit }
        val togetherDays = records.filter { it.together }.map { it.epochDay }.distinct().size
        return TopicSummary(
            topic = topic,
            rings = rings(data, topicId, today, highlightRecordId),
            stats = listOf(
                TopicStat("다녀온 날", "${days}일"),
                TopicStat("처음 간 곳", "${firstPlaces}곳"),
                TopicStat("함께한 날", "${togetherDays}일"),
            ),
            latest = records.lastOrNull(),
        )
    }

    /**
     * 이어받는 겹 수. 다 자란 겹(지난해까지)과, 올해 겹은 기록이 하나라도 새겨졌을 때만 센다.
     * 예: 2026~2045년 기록을 2046년에 이어받으면 스무 겹(스물한 번째 겹은 새 차에서 자라기 시작).
     */
    internal fun grownRingCount(rings: List<RingView>): Int = rings.count { it.complete || it.scenes.isNotEmpty() }

    /** 이 주제의 오늘까지의 기록, 날짜순 */
    private fun topicRecords(data: NaitaeData, topicId: String, today: LocalDate): List<DayRecord> {
        val day = today.toEpochDay()
        return data.records
            .filter { it.topicId == topicId && it.epochDay <= day }
            .sortedWith(compareBy<DayRecord> { it.epochDay }.thenBy { it.id })
    }

    /** 그해 함께한 시간(기록 수와 달린 거리)에 비례한 두께 */
    private fun thickness(records: List<DayRecord>): Float {
        val km = records.sumOf { it.distanceKm.toDouble() }.toFloat()
        val raw = MIN_THICKNESS + records.size * THICKNESS_PER_RECORD + km / 1000f * THICKNESS_PER_1000_KM
        return raw.coerceIn(MIN_THICKNESS, MAX_THICKNESS)
    }

    /** 그해 가장 많이 고른 색. 같은 수면 더 최근에 고른 색. 고른 색이 없으면 null */
    private fun mostUsedColor(records: List<DayRecord>): AmbientColor? {
        var best: AmbientColor? = null
        var bestCount = 0
        var bestLatest = Long.MIN_VALUE
        for ((color, ofColor) in records.filter { it.color != null }.groupBy { it.color }) {
            val latest = ofColor.maxOf { it.epochDay }
            if (ofColor.size > bestCount || (ofColor.size == bestCount && latest > bestLatest)) {
                best = color
                bestCount = ofColor.size
                bestLatest = latest
            }
        }
        return best
    }

    /** 그해 기록을 함께한 차. 기록이 없는 해는 그해에 타던 차. */
    private fun carOfYear(data: NaitaeData, records: List<DayRecord>, year: Int): String? {
        val byVin = records.groupingBy { it.vin }.eachCount()
        val mostUsed = byVin.entries.maxByOrNull { it.value }?.key
        if (mostUsed != null) return mostUsed
        return data.cars.lastOrNull { car ->
            val startYear = LocalDate.ofEpochDay(car.startEpochDay).year
            val retiredDay = car.retiredEpochDay
            val endYear = if (retiredDay == null) Int.MAX_VALUE else LocalDate.ofEpochDay(retiredDay).year
            year in startYear..endYear
        }?.vin
    }
}
