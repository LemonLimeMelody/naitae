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
    fun topicChoices(data: NaitaeData): List<Topic> = TODO("domain")

    /** 초안을 기록으로 확정한다. topicId 는 초안의 주제 또는 사용자가 고른 주제. */
    fun commit(data: NaitaeData, draft: RecordDraft, topicId: String): Pair<NaitaeData, DayRecord> = TODO("domain")

    fun addNote(data: NaitaeData, recordId: String, note: VoiceNote): NaitaeData = TODO("domain")

    fun addPhoto(data: NaitaeData, recordId: String, photo: Photo): NaitaeData = TODO("domain")

    fun setColor(data: NaitaeData, recordId: String, color: AmbientColor): NaitaeData = TODO("domain")
}
