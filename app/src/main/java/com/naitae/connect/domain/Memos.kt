package com.naitae.connect.domain

import com.naitae.connect.core.model.Memo
import com.naitae.connect.core.model.MemoRemind
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.core.model.Place
import com.naitae.connect.vehicle.SeatOccupancy
import java.time.LocalDateTime

/* 메모: 말로 남기고, 정해 둔 때(퇴근길 시동)·장소에서 다시 알려 준다 (계약) */
object MemoEngine {
    /** 말로 남긴 메모를 더한다. 새 데이터와 만든 메모를 돌려준다. */
    fun add(
        data: NaitaeData,
        text: String,
        authorId: String,
        now: LocalDateTime,
        remind: MemoRemind = MemoRemind.NextIgnition,
        personal: Boolean = false,
    ): Pair<NaitaeData, Memo> = TODO("domain")

    /**
     * 브리핑이 아닌 시동(같은 날 두 번째 이후)에서 다시 알려 줄 메모.
     * EveningIgnition(afterHour) 메모 중 오늘 브리핑은 했고 아직 다시 알리지 않았으며 지금 시각이 afterHour 이후인 것.
     * 동승자가 있으면 personal 메모는 뺀다.
     */
    fun dueAtIgnition(data: NaitaeData, now: LocalDateTime, seats: SeatOccupancy): List<Memo> = TODO("domain")

    /** 그 장소에 가까워졌을 때 알려 줄 메모(AtPlace) */
    fun dueAtPlace(data: NaitaeData, placeName: String): List<Memo> = TODO("domain")

    /** 다시 알림을 마쳤다고 기록한다. */
    fun markReminded(data: NaitaeData, memoIds: List<String>, now: LocalDateTime): NaitaeData = TODO("domain")

    /** 근접 알림을 걸어 둘 장소와 반경(아직 끝나지 않은 AtPlace 메모) */
    fun placesToWatch(data: NaitaeData): List<Pair<Place, Int>> = TODO("domain")

    /** 다시 알려 줄 때 읽을 문장. 예: "엄마 메모예요. 우유 사기." */
    fun spoken(data: NaitaeData, memo: Memo): String = TODO("domain")
}
