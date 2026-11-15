package com.naitae.connect.app

import com.naitae.connect.core.model.Memo
import com.naitae.connect.core.model.MemoRemind
import com.naitae.connect.core.model.Schedule
import com.naitae.connect.domain.Briefing
import com.naitae.connect.domain.BriefingEngine
import com.naitae.connect.domain.MemoEngine
import com.naitae.connect.domain.TripSignal
import com.naitae.connect.ui.Screen
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.VoiceResult

/* 챙긴다: 하루 첫 시동의 브리핑, 메모 다시 알림, 길 안내 질문 */

/** 인사 → 항목을 하나씩 → "메모할 것 있으세요?" → 듣기 → 마쳤다고 기록 */
internal suspend fun NaitaeCoordinator.runBriefing(briefing: Briefing) {
    memory.briefing = briefing
    SdkLog.sys("나이테 · 하루 첫 시동 → 브리핑 ${briefing.items.size}줄")
    show(Screen.Briefing(briefing = briefing, activeIndex = -1))
    say(briefing.greeting)
    for ((index, item) in briefing.items.withIndex()) {
        updateIf<Screen.Briefing> { it.copy(activeIndex = index) }
        say(item.spoken)
    }
    if (briefing.askMemo) askMemo(briefing)

    memory.briefing = null
    val today = today()
    repository.update { BriefingEngine.markDone(it, briefing, today) }
    checkpoint()
}

/** 화면 입력 없이 말로 메모를 받는다. "그만"이나 "없어요"는 메모로 남기지 않는다. */
private suspend fun NaitaeCoordinator.askMemo(briefing: Briefing) {
    updateIf<Screen.Briefing> { it.copy(activeIndex = briefing.items.size, heardText = "") }
    say(BriefingEngine.ASK_MEMO)
    val heard = hear(
        onListening = { on -> updateIf<Screen.Briefing> { it.copy(listening = on) } },
        onPartial = { text -> updateIf<Screen.Briefing> { it.copy(heardText = text) } },
    )
    val text = (heard as? VoiceResult.Text)?.text?.trim().orEmpty()
    if (text.isEmpty() || isDecline(text)) return

    // "퇴근"이 들어간 말은 퇴근길 시동에 한 번 더 알려 주고, 나머지는 다음 하루 첫 시동의 브리핑에서 읽는다.
    val remind: MemoRemind = if (text.contains("퇴근")) MemoRemind.EveningIgnition() else MemoRemind.NextIgnition
    val now = now()
    var saved: Memo? = null
    repository.update { current ->
        val (next, memo) = MemoEngine.add(current, text, current.owner.id, now, remind)
        saved = memo
        next
    }
    checkpoint()
    val memo = saved
    updateIf<Screen.Briefing> { it.copy(heardText = text, savedMemo = memo) }
    say("메모했어요.")
}

private val DECLINES = setOf(
    "아니", "아니요", "아니오", "아뇨", "없어", "없어요", "없습니다", "됐어", "됐어요", "괜찮아", "괜찮아요",
)

/** "그만", "없어요"처럼 메모가 아닌 대답 */
private fun isDecline(text: String): Boolean {
    val plain = text.filter { it.isLetterOrDigit() }
    return (plain.startsWith("그만") && plain.length <= 5) || plain in DECLINES
}

/**
 * 브리핑을 읽는 중이었으면 흐름에서 뗀다. markDone 이면 오늘 브리핑은 한 것으로 기록한다
 * (그만 버튼·출발). 시동을 끈 경우에는 기록하지 않아 다음 시동에 다시 읽는다.
 */
internal fun NaitaeCoordinator.settleBriefing(markDone: Boolean) {
    val briefing = memory.briefing ?: return
    memory.briefing = null
    if (!markDone) return
    val today = today()
    launchCommand { repository.update { BriefingEngine.markDone(it, briefing, today) } }
}

/** 그만 버튼: 낭독을 멈추되 오늘 브리핑은 한 것으로 기록하고, 남은 단계(메모 다시 알림·길 안내 질문)로 넘어간다. */
internal fun NaitaeCoordinator.endBriefingEarly() {
    if (screen !is Screen.Briefing) return
    stopAll()
    settleBriefing(markDone = true)
    startInteraction {
        settle()
        afterBriefing()
    }
}

/** 6~7단계: 퇴근길 시동에 다시 알릴 메모가 있으면 카드와 낭독, 그다음 길 안내를 묻거나 홈으로 */
internal suspend fun NaitaeCoordinator.afterBriefing() {
    val due = MemoEngine.dueAtIgnition(data, now(), seats)
    val route = memory.route
    showHome()
    if (due.isNotEmpty()) remindMemos(due)
    if (route != null) askRoute(route)
}

/** 메모 카드를 띄우고 읽은 뒤, 다시 알림을 마쳤다고 기록한다. */
internal suspend fun NaitaeCoordinator.remindMemos(memos: List<Memo>) {
    for (memo in memos) {
        showToast(memo)
        say(MemoEngine.spoken(data, memo))
        val now = now()
        repository.update { MemoEngine.markReminded(it, listOf(memo.id), now) }
        checkpoint()
    }
}

/** "오늘은 낚시예요. 낚시터로 안내할까요?" 묻기만 한다. 길 안내는 acceptRoute 에서만 시작한다. */
private suspend fun NaitaeCoordinator.askRoute(schedule: Schedule) {
    val place = schedule.place ?: return
    show(Screen.RouteAsk(schedule = schedule, topic = data.topic(schedule.topicId)))
    say("오늘은 ${Josa.ieyo(schedule.title)}. ${Josa.ro(place.name)} 안내할까요?")
}

/** 사용자가 안내를 받기로 했을 때만 내비에 경로를 요청한다. */
internal fun NaitaeCoordinator.startRoute() {
    val ask = screen as? Screen.RouteAsk ?: return
    val place = ask.schedule.place ?: return
    stopAll()
    memory.route = null
    feed(TripSignal.RouteAccepted(place))
    launchCommand {
        val result = platform.navi.requestRoute(place)
        if (result.isFailure) SdkLog.sys("나이테 · ${place.name} 길 안내를 시작하지 못함")
    }
    showHome()
}
