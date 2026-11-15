package com.naitae.connect.app

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.DayRecord
import com.naitae.connect.core.model.Photo
import com.naitae.connect.core.model.VoiceNote
import com.naitae.connect.domain.Recall
import com.naitae.connect.domain.RecallEngine
import com.naitae.connect.domain.RecordDraft
import com.naitae.connect.domain.RecordEngine
import com.naitae.connect.domain.RingBuilder
import com.naitae.connect.ui.Screen
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.VoiceResult
import java.util.UUID

/* 남긴다(특별한 날 기록 · 함께 꾸미기)와 돌아본다(차에서 다시 보기) */

// ───────── 남기기 ─────────

/** 돌아가려 시동을 걸었을 때: 차가 채운 초안을 보여 주고 "오늘을 남길까요?" 를 묻는다. */
internal suspend fun NaitaeCoordinator.askRecord(draft: RecordDraft) {
    val account = data
    val topic = account.topic(draft.topicId)
    show(
        Screen.RecordAsk(
            draft = draft,
            topic = topic,
            topicChoices = RecordEngine.topicChoices(account),
            summary = topic?.let { RingBuilder.summary(account, it.id, today()) },
        ),
    )
    say("오늘 ${draft.placeName}, 어땠어요? 오늘을 남길까요?")
}

/** 초안을 기록으로 확정하고 남긴 기록 화면으로 간다. */
internal fun NaitaeCoordinator.commitRecord(topicId: String) {
    val ask = screen as? Screen.RecordAsk ?: return
    val session = memory
    if (session.committing || data.topic(topicId) == null) return
    session.committing = true
    stopAll()
    launchCommand {
        try {
            var created: DayRecord? = null
            repository.update { current ->
                val (next, record) = RecordEngine.commit(current, ask.draft, topicId)
                created = record
                next
            }
            tracker.clearDraft()
            val record = created
            // 저장하는 사이 출발했거나 시동을 껐으면 화면은 그대로 둔다(기록은 남았다).
            if (record != null && session === memory && screen is Screen.RecordAsk) {
                session.selectedTopicId = topicId
                showRecordEdit(record.id)
            }
        } finally {
            session.committing = false
        }
    }
}

/** 남긴 기록 화면: 방금 남긴 기록의 점을 나이테에서 강조한다. */
internal fun NaitaeCoordinator.showRecordEdit(recordId: String) {
    val account = data
    val record = account.records.firstOrNull { it.id == recordId }
    val topic = record?.let { account.topic(it.topicId) }
    if (record == null || topic == null) {
        showHome()
        return
    }
    show(
        Screen.RecordEdit(
            record = record,
            topic = topic,
            summary = RingBuilder.summary(account, topic.id, today(), record.id),
        ),
    )
}

/** 한마디를 말로 더한다. 누가 말했는지는 저장하지 않는다(authorId = null). */
internal fun NaitaeCoordinator.recordVoiceNote() {
    val edit = screen as? Screen.RecordEdit ?: return
    val recordId = edit.record.id
    startInteraction {
        val heard = hear(
            onListening = { on -> updateIf<Screen.RecordEdit> { it.copy(listening = on) } },
            onPartial = { text -> updateIf<Screen.RecordEdit> { it.copy(heardText = text) } },
        )
        val text = (heard as? VoiceResult.Text)?.text?.trim().orEmpty()
        if (text.isNotEmpty()) {
            repository.update { RecordEngine.addNote(it, recordId, VoiceNote(text = text, authorId = null)) }
            checkpoint()
        }
        // 받아 적은 글은 이제 기록의 한마디로 보이므로 지운다.
        updateIf<Screen.RecordEdit> { it.copy(heardText = "") }
    }
}

/** 남기기를 마치고 그 주제의 나이테가 보이는 홈으로 */
internal fun NaitaeCoordinator.closeRecord() {
    val topicId = when (val current = screen) {
        is Screen.RecordEdit -> current.topic.id
        is Screen.Decorate -> current.topic.id
        else -> return
    }
    stopAll()
    restoreLight()
    memory.selectedTopicId = topicId
    showHome()
}

// ───────── 함께 꾸미기 ─────────

/** 꾸미기 화면: 이 주제를 공유받은 사람들과, 실내 조명이 지원하는 색 */
internal fun NaitaeCoordinator.decorate(recordId: String) {
    val account = data
    val record = account.records.firstOrNull { it.id == recordId } ?: return
    val topic = account.topic(record.topicId) ?: return
    // 남긴 기록 화면에서 열었으면 그리로, 홈의 장면에서 열었으면 홈으로 돌아간다.
    val from = screen
    if (from !is Screen.Decorate) memory.decorateBackToEdit = from is Screen.RecordEdit
    stopAll()
    refreshAmbientColors()
    show(
        Screen.Decorate(
            record = record,
            topic = topic,
            members = sharedMembers(account, topic.sharedWith),
            colors = decorateColors(),
        ),
    )
}

/** 고른 색을 이 장면의 색으로 남기고, 실내 조명으로 바로 입혀 본다. */
internal fun NaitaeCoordinator.paint(color: AmbientColor) {
    val decorating = screen as? Screen.Decorate ?: return
    val recordId = decorating.record.id
    launchCommand {
        repository.update { RecordEngine.setColor(it, recordId, color) }
        light.preview(color, DECORATE_BRIGHTNESS)
    }
}

/** 사진을 더한다(프로토타입은 주제의 장면 그림). 올린 사람은 공유받은 첫 사람, 없으면 차주. */
internal fun NaitaeCoordinator.attachPhoto() {
    val decorating = screen as? Screen.Decorate ?: return
    val account = data
    val topic = decorating.topic
    val addedBy = topic.sharedWith.firstOrNull { account.member(it) != null } ?: account.owner.id
    val photo = Photo(id = "photo-${UUID.randomUUID()}", scene = topic.scene, addedBy = addedBy)
    val recordId = decorating.record.id
    launchCommand { repository.update { RecordEngine.addPhoto(it, recordId, photo) } }
}

/** 꾸미기를 마치면 실내 조명을 원래대로 되돌리고, 열었던 곳으로 돌아간다. */
internal fun NaitaeCoordinator.closeDecorate() {
    val decorating = screen as? Screen.Decorate ?: return
    restoreLight()
    if (memory.decorateBackToEdit) {
        showRecordEdit(decorating.record.id)
    } else {
        memory.selectedTopicId = decorating.topic.id
        showHome()
    }
}

// ───────── 차에서 다시 보기 ─────────

/** 같은 곳에 다시 왔을 때: 그날의 색으로 실내 조명을 바꾸고 그날의 목소리를 들려준다. */
internal fun NaitaeCoordinator.openRecall(placeName: String) {
    val recall = RecallEngine.find(data, placeName, today()) ?: return
    SdkLog.sys("나이테 · $placeName 다시 도착 → ${recall.title}")
    startInteraction {
        show(Screen.RecallView(recall = recall))
        light.preview(recall.record.color ?: AmbientColor.PURPLE, RECALL_BRIGHTNESS)
        checkpoint()
        playRecallNote(recall)
    }
}

/** 첫 한마디를 들려준다. 녹음이 있으면 녹음을, 없으면 읽어 준다. */
private suspend fun NaitaeCoordinator.playRecallNote(recall: Recall) {
    val note = recall.record.notes.firstOrNull() ?: return
    val mine = epoch
    updateIf<Screen.RecallView> { it.copy(playingVoice = true) }
    try {
        val asset = note.audioAsset
        val played = asset != null && playRecorded(asset, note.text)
        if (!played) say(note.text)
    } finally {
        if (epoch == mine) updateIf<Screen.RecallView> { it.copy(playingVoice = false) }
    }
}

/** 목소리 다시 듣기 */
internal fun NaitaeCoordinator.replayRecall() {
    val view = screen as? Screen.RecallView ?: return
    startInteraction { playRecallNote(view.recall) }
}

/** 다시 보기를 닫으면 실내 조명을 원래대로 되돌린다. */
internal fun NaitaeCoordinator.endRecall() {
    val view = screen as? Screen.RecallView ?: return
    stopAll()
    restoreLight()
    memory.selectedTopicId = view.recall.topic.id
    showHome()
}

private const val DECORATE_BRIGHTNESS = 90
private const val RECALL_BRIGHTNESS = 95
