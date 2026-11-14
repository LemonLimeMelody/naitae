package com.naitae.connect.domain

import com.naitae.connect.core.model.FutureLetter
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.vehicle.CabinControl
import com.naitae.connect.vehicle.RecordedVoicePlayer
import com.naitae.connect.vehicle.SeatOccupancy
import com.naitae.connect.vehicle.VoiceOutput
import java.time.LocalDate

/* 건넨다: 미래 편지 (계약) */

enum class LetterPhase { WARMING, READING, DONE }

object LetterEngine {
    /**
     * 지금 열 편지. openEpochDay 가 오늘이거나 지났고 아직 열지 않은 것 중 가장 이른 것.
     * newCarFirstIgnition 이면 openOnNewCar 편지를 먼저 고른다. openOnNewCar 편지는 새 차 첫 시동이 아니면 고르지 않는다.
     */
    fun due(data: NaitaeData, today: LocalDate, newCarFirstIgnition: Boolean): FutureLetter? = TODO("domain")

    /** 다른 사람이 함께 탔으면 바로 열지 않고 먼저 묻는다. */
    fun shouldAskFirst(seats: SeatOccupancy): Boolean = TODO("domain")

    fun markOpened(data: NaitaeData, letterId: String, today: LocalDate): NaitaeData = TODO("domain")

    /** "지우 · 도윤 · 엄마" */
    fun fromNames(data: NaitaeData, letter: FutureLetter): String = TODO("domain")
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
    /** 연출을 끝까지 수행한다. 취소되면 바로 멈춘다. */
    suspend fun perform(letter: FutureLetter, onPhase: (LetterPhase) -> Unit): Unit = TODO("domain")

    /** 실내 조명을 연출 전 상태로 되돌린다. keepLight 면 그대로 둔다(새 차 첫 시동의 보랏빛). */
    suspend fun finish(keepLight: Boolean = false): Unit = TODO("domain")
}
