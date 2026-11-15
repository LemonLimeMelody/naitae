package com.naitae.connect.app

import com.naitae.connect.core.model.FutureLetter
import com.naitae.connect.core.model.TopicSummary
import com.naitae.connect.domain.BriefingEngine
import com.naitae.connect.domain.HandoverEngine
import com.naitae.connect.domain.HandoverSummary
import com.naitae.connect.domain.LetterEngine
import com.naitae.connect.domain.RingBuilder
import com.naitae.connect.ui.Screen
import com.naitae.connect.vehicle.SdkLog

/* 시동 흐름(ARCHITECTURE 6절의 1~7단계), 이어받기, 도착 뒤 P 에서의 반응 */

/** 시동 ON + P: 위에서부터 처음 맞는 것 하나를 한다. */
internal fun NaitaeCoordinator.startIgnitionSequence() {
    memory.sequencePending = false
    startInteraction { ignitionSequence() }
}

private suspend fun NaitaeCoordinator.ignitionSequence() {
    awaitDayReset()
    val vin = platform.signals.vin()
    checkpoint()
    if (vin != null) memory.vin = vin
    val today = today()

    if (vin != null) {
        if (data.currentCar == null) {
            // 1. 등록된 차가 없으면 지금 차를 첫 차로 등록한다. 조용히 등록만 하고 아래 단계로 이어 간다.
            val model = carModelName()
            repository.update { HandoverEngine.registerFirstCar(it, vin, model, today) }
            checkpoint()
        } else {
            // 2. 계정에 등록된 차와 VIN 이 다르면 이어받는다.
            val summary = HandoverEngine.detect(data, vin, today)
            if (summary != null) {
                val model = carModelName()
                repository.update { HandoverEngine.apply(it, summary, model, today) }
                checkpoint()
                SdkLog.sys("나이테 · 새 차(VIN 다름) → 이어받기 · 나이테 ${summary.ringCount}겹")
                memory.handover = summary
                val letter = LetterEngine.due(data, today, newCarFirstIgnition = true)
                when {
                    letter == null -> showHandover(summary)
                    // 다른 사람이 함께 탔으면 바로 열지 않고 먼저 묻는다.
                    LetterEngine.shouldAskFirst(seats) -> arriveLetter(letter)
                    else -> performLetter(letter)
                }
                return
            }
        }
    }

    // 2-1. 이어받기는 했지만 편지를 미루거나 출발해서 아직 이어받기 화면을 보여 주지 못했을 때
    val handover = memory.handover
    if (handover != null) {
        val letter = takePostponedLetter()
        if (letter != null) arriveLetter(letter) else showHandover(handover)
        return
    }

    // 3. 그곳에서 시간을 보낸 뒤 돌아가려는 시동: "오늘을 남길까요?"
    val draft = memory.recordDraft
    if (draft != null) {
        memory.recordDraft = null
        askRecord(draft)
        return
    }

    // 4. 오늘 열 편지(미뤄 둔 편지가 먼저)
    val letter = takePostponedLetter() ?: LetterEngine.due(data, today, newCarFirstIgnition = false)
    if (letter != null) {
        arriveLetter(letter)
        return
    }

    // 5. 하루 첫 시동의 브리핑
    if (BriefingEngine.shouldBrief(data, today)) {
        runBriefing(BriefingEngine.build(data, now(), seats))
    }

    // 6~7. 퇴근길 메모 다시 알림 → 길 안내 질문 또는 홈
    afterBriefing()
}

/** 미뤄 둔 편지를 꺼낸다. 그사이 열렸으면 null */
internal fun NaitaeCoordinator.takePostponedLetter(): FutureLetter? {
    val letter = memory.postponedLetter ?: return null
    memory.postponedLetter = null
    saveSession()
    return letter.takeIf { waiting -> data.letters.any { it.id == waiting.id && it.openedEpochDay == null } }
}

/** 이어받기 화면: "새 차 0km · 나이테 N겹". 이어받기 자체는 소리 없이 한다. */
internal fun NaitaeCoordinator.showHandover(summary: HandoverSummary) {
    memory.handover = null
    show(
        Screen.Handover(
            summary = summary,
            rings = handoverRings(),
            newCarOdometerKm = platform.signals.odometerKm.value,
        ),
    )
}

/**
 * 이어받는 나이테: 가장 오래된 기록이 있는 주제(HandoverSummary.ringCount 를 세는 주제와 같다).
 * 이어받기 화면을 닫으면 홈도 이 주제를 보여 준다.
 */
internal fun NaitaeCoordinator.handoverRings(): TopicSummary? {
    val account = data
    val today = today()
    val day = today.toEpochDay()
    val topicId = account.records
        .filter { it.epochDay <= day }
        .minByOrNull { it.epochDay }
        ?.topicId
        ?.takeIf { account.topic(it) != null }
        ?: account.topics.firstOrNull()?.id
        ?: return null
    memory.selectedTopicId = topicId
    return RingBuilder.summary(account, topicId, today)
}

/**
 * 도착 이벤트 뒤 기어가 P 가 됐을 때. 평소 도착과 초안만 준비한 도착은 화면을 바꾸지 않는다.
 * 미뤄 둔 편지가 있으면 그것을 먼저 보여 주고, 같은 곳의 예전 기록이 있으면 다시 보기를 연다.
 */
internal fun NaitaeCoordinator.reactToArrival() {
    if (!memory.arrivalPending) return
    memory.arrivalPending = false
    val place = memory.recallPlace
    memory.recallPlace = null
    if (!ignition || memory.sequencePending) return

    val letter = takePostponedLetter()
    if (letter != null) {
        startInteraction { arriveLetter(letter) }
        return
    }
    if (place != null) openRecall(place)
}
