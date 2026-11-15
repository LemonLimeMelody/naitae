package com.naitae.connect.app

import com.naitae.connect.core.model.FutureLetter
import com.naitae.connect.domain.LetterEngine
import com.naitae.connect.domain.LetterPhase
import com.naitae.connect.ui.Screen
import com.naitae.connect.vehicle.SdkLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/* 건넨다: 미래 편지 */

/** "편지가 도착했어요": 열기 / 도착해서 보기 */
internal suspend fun NaitaeCoordinator.arriveLetter(letter: FutureLetter) {
    SdkLog.sys("나이테 · 미래 편지 도착")
    show(Screen.LetterArrive(letter = letter, fromNames = LetterEngine.fromNames(data, letter)))
    say("편지가 도착했어요.")
}

/**
 * 편지 연출: 온기와 조명은 LetterPerformer 가 맡고, 낭독이 시작되면 글자를 한 자씩 보여 준다.
 * 닫기 전까지 화면은 그대로 둔다.
 */
internal suspend fun NaitaeCoordinator.performLetter(letter: FutureLetter) {
    show(
        Screen.LetterOpen(
            letter = letter,
            fromNames = LetterEngine.fromNames(data, letter),
            phase = LetterPhase.WARMING,
            shownText = "",
            // 새 차 첫 시동의 편지는 이어받는 나이테와 함께 보여 준다.
            rings = if (memory.handover != null) handoverRings() else null,
        ),
    )
    val phase = MutableStateFlow(LetterPhase.WARMING)
    try {
        coroutineScope {
            val reveal = launch {
                phase.first { it != LetterPhase.WARMING }
                for (count in 1..letter.text.length) {
                    updateIf<Screen.LetterOpen> { it.copy(shownText = letter.text.take(count)) }
                    delay(REVEAL_MS_PER_CHAR)
                }
            }
            try {
                performer.perform(letter) { next ->
                    phase.value = next
                    updateIf<Screen.LetterOpen> { it.copy(phase = next) }
                }
            } finally {
                reveal.cancel()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // 연출이 실패해도 편지 글은 끝까지 보여 준다.
        report("letter", e)
    }
    checkpoint()
    // 낭독이 글자보다 먼저 끝났으면 남은 글자를 한꺼번에 보여 준다.
    updateIf<Screen.LetterOpen> { it.copy(phase = LetterPhase.DONE, shownText = letter.text) }
}

/** 열기 */
internal fun NaitaeCoordinator.unfoldLetter() {
    val arrived = screen as? Screen.LetterArrive ?: return
    startInteraction { performLetter(arrived.letter) }
}

/** 도착해서 보기: 다음 도착 뒤 P 에서 다시 보여 준다. */
internal fun NaitaeCoordinator.putOffLetter() {
    val arrived = screen as? Screen.LetterArrive ?: return
    stopAll()
    memory.postponedLetter = arrived.letter
    saveSession()
    showHome()
}

/** 닫기: 실내 조명만 원래대로. 새 차 첫 시동의 편지였으면 보랏빛을 그대로 두고 이어받기 화면으로 간다. */
internal fun NaitaeCoordinator.foldLetter() {
    val open = screen as? Screen.LetterOpen ?: return
    val running = interaction
    stopAll()
    val handover = memory.handover
    endLetter(open.letter, running, keepLight = handover != null)
    if (handover != null) showHandover(handover) else showHome()
}

/**
 * 편지를 끝낸다(닫기·출발·시동 끔 공통). 연출이 완전히 멈춘 뒤에 조명을 되돌리고, 열어 본 편지로 기록한다.
 */
internal fun NaitaeCoordinator.endLetter(letter: FutureLetter, running: Job?, keepLight: Boolean) {
    val today = today()
    launchCommand {
        if (running != null) withTimeoutOrNull(PERFORM_STOP_WAIT_MS) { running.join() }
        // keepLight(새 차 첫 시동의 편지): 이어받기 화면이 보이는 동안은 보랏빛을 그대로 둔다.
        // 편지 전 조명의 기억은 버리지 않고 두었다가 이어받기 화면을 떠날 때(releaseLetterLight) 되돌린다.
        if (!keepLight) performer.finish()
        repository.update { LetterEngine.markOpened(it, letter.id, today) }
    }
}

/**
 * 이어받기 화면을 떠날 때(나이테 보기·출발·시동 끔): 편지가 남겨 둔 보랏빛을 편지 전 조명으로 되돌린다.
 * 남겨 둔 것이 없으면 아무 일도 하지 않는다. 온기는 그대로 둔다.
 */
internal fun NaitaeCoordinator.releaseLetterLight() {
    launchCommand { performer.finish() }
}

private const val REVEAL_MS_PER_CHAR = 60L
private const val PERFORM_STOP_WAIT_MS = 1_500L
