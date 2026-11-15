package com.naitae.connect.domain

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.FutureLetter
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.vehicle.CabinControl
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.RecordedVoicePlayer
import com.naitae.connect.vehicle.SeatOccupancy
import com.naitae.connect.vehicle.VoiceOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

/* 건넨다: 미래 편지 (계약) */

enum class LetterPhase { WARMING, READING, DONE }

object LetterEngine {
    /**
     * 지금 열 편지. openEpochDay 가 오늘이거나 지났고 아직 열지 않은 것 중 가장 이른 것.
     * newCarFirstIgnition 이면 openOnNewCar 편지를 먼저 고른다. openOnNewCar 편지는 새 차 첫 시동이 아니면 고르지 않는다.
     */
    fun due(data: NaitaeData, today: LocalDate, newCarFirstIgnition: Boolean): FutureLetter? {
        val day = today.toEpochDay()
        val waiting = data.letters
            .filter { it.openedEpochDay == null && it.openEpochDay <= day }
            .sortedWith(compareBy<FutureLetter> { it.openEpochDay }.thenBy { it.id })
        if (newCarFirstIgnition) {
            waiting.firstOrNull { it.openOnNewCar }?.let { return it }
        }
        return waiting.firstOrNull { !it.openOnNewCar }
    }

    /** 다른 사람이 함께 탔으면 바로 열지 않고 먼저 묻는다. */
    fun shouldAskFirst(seats: SeatOccupancy): Boolean = seats.hasPassenger

    fun markOpened(data: NaitaeData, letterId: String, today: LocalDate): NaitaeData {
        val day = today.toEpochDay()
        return data.copy(letters = data.letters.map { if (it.id == letterId) it.copy(openedEpochDay = day) else it })
    }

    /** "지우 · 도윤 · 엄마" */
    fun fromNames(data: NaitaeData, letter: FutureLetter): String =
        letter.fromIds.mapNotNull { data.member(it)?.name }.joinToString(" · ")
}

/**
 * 편지 연출: 온기(시트·핸들)·공조 → 실내 조명을 0 에서 rampSeconds 동안 단계적으로 밝힘 → 낭독(녹음이 있으면 녹음, 없으면 TTS).
 * 끝나면(닫기·출발) 실내 조명만 원래 설정으로 되돌리고, 온기와 공조는 그대로 둔다.
 */
class LetterPerformer(
    private val cabin: CabinControl,
    private val voice: VoiceOutput,
    private val recorded: RecordedVoicePlayer,
) {
    /** 연출 전에 기억해 둔 실내 상태. 연출 중이 아니면 null */
    private var before: CabinState? = null

    /** 연출에 쓴 조명 색(연출 전 색을 모를 때 되돌리는 데 쓴다) */
    private var letterColor: AmbientColor? = null

    /** 연출을 끝까지 수행한다. 취소되면 바로 멈춘다. */
    suspend fun perform(letter: FutureLetter, onPhase: (LetterPhase) -> Unit) {
        val effect = letter.effect
        // 실내에 손대기 전에 지금 상태를 기억한다. 앞선 연출을 되돌리지 않은 채 다시 불리면 처음 기억을 지킨다.
        if (before == null) before = cabin.state.value
        letterColor = effect.color

        onPhase(LetterPhase.WARMING)
        if (effect.seatHeatLevel > 0) cabin.setSeatHeat(effect.seatHeatLevel.coerceIn(0, 3))
        if (effect.steeringHeat) cabin.setSteeringHeat(true)
        if (effect.cabinTempC != null || effect.fanLevel != null) cabin.setClimate(effect.cabinTempC, effect.fanLevel)

        // 조명은 꺼진 밝기에서 시작해 단계적으로 올린다(차에는 '서서히' 기능이 없어 앱이 나눠 올린다).
        val target = effect.brightness.coerceIn(0, 100)
        val stepDelayMs = effect.rampSeconds.coerceAtLeast(0) * 1000L / RAMP_STEPS
        cabin.setAmbient(effect.color, 0)
        for (step in 1..RAMP_STEPS) {
            delay(stepDelayMs)
            cabin.setAmbient(effect.color, target * step / RAMP_STEPS)
        }

        onPhase(LetterPhase.READING)
        val asset = letter.audioAsset
        val played = if (asset != null) playRecorded(asset) else false
        if (!played) voice.speak(letter.text)
        onPhase(LetterPhase.DONE)
    }

    /** 실내 조명을 연출 전 상태로 되돌린다. keepLight 면 그대로 둔다(새 차 첫 시동의 보랏빛). */
    suspend fun finish(keepLight: Boolean = false) {
        // 기억해 둔 상태가 없으면 연출 중이 아니거나 이미 끝낸 것이다. 두 번 불려도 아무 일도 하지 않는다.
        val remembered = before ?: return
        val usedColor = letterColor
        before = null
        letterColor = null

        // 출발해서 연출이 취소된 뒤에 불려도 끝까지 되돌린다.
        withContext(NonCancellable) {
            voice.stop()
            recorded.stop()
            if (!keepLight) {
                // 조명만 되돌린다. 시트·핸들 온기와 공조는 그대로 둔다.
                val color = remembered.ambientColor ?: usedColor
                if (color != null) cabin.setAmbient(color, remembered.ambientBrightness ?: 0)
            }
        }
    }

    /** 녹음 파일이 없거나 재생에 실패하면 false (TTS 로 대신 읽는다) */
    private suspend fun playRecorded(asset: String): Boolean =
        try {
            recorded.play(asset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    private companion object {
        const val RAMP_STEPS = 10
    }
}
