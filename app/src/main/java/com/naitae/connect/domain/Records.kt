package com.naitae.connect.domain

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.DayRecord
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Photo
import com.naitae.connect.core.model.Topic
import com.naitae.connect.core.model.VoiceNote

/* 남긴다: 특별한 날 기록 · 함께 꾸미기 (계약) */
object RecordEngine {
    /** 초안에 주제가 없을 때 사용자가 고를 주제 목록(차주의 주제) */
    fun topicChoices(data: NaitaeData): List<Topic> = data.topics.filter { it.ownerId == data.owner.id }

    /** 초안을 기록으로 확정한다. topicId 는 초안의 주제 또는 사용자가 고른 주제. */
    fun commit(data: NaitaeData, draft: RecordDraft, topicId: String): Pair<NaitaeData, DayRecord> {
        // 같은 날 같은 곳의 기록은 하나만 둔다(두 번 눌러도 겹치지 않게).
        val existing = data.records.firstOrNull { it.epochDay == draft.epochDay && it.placeName == draft.placeName }
        if (existing != null) return data to existing

        val record = DayRecord(
            id = recordId(draft.epochDay, draft.placeName),
            topicId = topicId,
            epochDay = draft.epochDay,
            placeName = draft.placeName,
            place = draft.place,
            distanceKm = draft.distanceKm,
            firstVisit = draft.firstVisit,
            seatCount = draft.seatCount,
            scheduleId = draft.scheduleId,
            vin = draft.vin,
        )
        return data.copy(records = data.records + record) to record
    }

    fun addNote(data: NaitaeData, recordId: String, note: VoiceNote): NaitaeData =
        edit(data, recordId) { it.copy(notes = it.notes + note) }

    fun addPhoto(data: NaitaeData, recordId: String, photo: Photo): NaitaeData =
        edit(data, recordId) { it.copy(photos = it.photos + photo) }

    fun setColor(data: NaitaeData, recordId: String, color: AmbientColor): NaitaeData =
        edit(data, recordId) { it.copy(color = color) }

    /** 날짜와 장소로 정해지는 기록 id. 가상 데이터(SeedData)도 같은 규칙을 쓴다. */
    internal fun recordId(epochDay: Long, placeName: String): String =
        "rec-$epochDay-${placeName.trim().replace(Regex("\\s+"), "_")}"

    private fun edit(data: NaitaeData, recordId: String, change: (DayRecord) -> DayRecord): NaitaeData {
        if (data.records.none { it.id == recordId }) return data
        return data.copy(records = data.records.map { if (it.id == recordId) change(it) else it })
    }
}
