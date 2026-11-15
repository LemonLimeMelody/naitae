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
    ): Pair<NaitaeData, Memo> {
        val createdAt = now.atZone(NAITAE_ZONE).toInstant().toEpochMilli()
        var id = "memo-$createdAt"
        var n = 1
        while (data.memos.any { it.id == id }) {
            n++
            id = "memo-$createdAt-$n"
        }
        val memo = Memo(
            id = id,
            text = text.trim(),
            authorId = authorId,
            createdAtEpochMs = createdAt,
            remind = remind,
            personal = personal,
        )
        return data.copy(memos = data.memos + memo) to memo
    }

    /**
     * 브리핑이 아닌 시동(같은 날 두 번째 이후)에서 다시 알려 줄 메모.
     * EveningIgnition(afterHour) 메모 중 오늘 브리핑은 했고 아직 다시 알리지 않았으며 지금 시각이 afterHour 이후인 것.
     * 동승자가 있으면 personal 메모는 뺀다.
     */
    fun dueAtIgnition(data: NaitaeData, now: LocalDateTime, seats: SeatOccupancy): List<Memo> {
        // 오늘 브리핑 전이면 메모는 브리핑에서 먼저 읽는다. 브리핑 뒤 낮에 들어온 퇴근길 메모도 저녁 시동에 알려 준다.
        if (data.lastBriefingEpochDay != now.toLocalDate().toEpochDay()) return emptyList()
        return data.memos
            .filter { memo ->
                val remind = memo.remind
                !memo.done &&
                    remind is MemoRemind.EveningIgnition &&
                    memo.remindedEpochDay == null &&
                    now.hour >= remind.afterHour &&
                    !(memo.personal && seats.hasPassenger)
            }
            .sortedBy { it.createdAtEpochMs }
    }

    /** 그 장소에 가까워졌을 때 알려 줄 메모(AtPlace) */
    fun dueAtPlace(data: NaitaeData, placeName: String): List<Memo> =
        data.memos
            .filter { memo ->
                val remind = memo.remind
                !memo.done &&
                    memo.remindedEpochDay == null &&
                    remind is MemoRemind.AtPlace &&
                    remind.place.name == placeName
            }
            .sortedBy { it.createdAtEpochMs }

    /** 다시 알림을 마쳤다고 기록한다. */
    fun markReminded(data: NaitaeData, memoIds: List<String>, now: LocalDateTime): NaitaeData {
        if (memoIds.isEmpty()) return data
        val day = now.toLocalDate().toEpochDay()
        val ids = memoIds.toSet()
        return data.copy(memos = data.memos.map { if (it.id in ids) it.copy(remindedEpochDay = day) else it })
    }

    /** 근접 알림을 걸어 둘 장소와 반경(아직 끝나지 않은 AtPlace 메모) */
    fun placesToWatch(data: NaitaeData): List<Pair<Place, Int>> {
        // 같은 장소의 메모가 여럿이면 가장 넓은 반경 하나만 건다.
        val byPlace = LinkedHashMap<String, Pair<Place, Int>>()
        for (memo in data.memos) {
            val remind = memo.remind
            if (memo.done || memo.remindedEpochDay != null || remind !is MemoRemind.AtPlace) continue
            val known = byPlace[remind.place.name]
            if (known == null || known.second < remind.radiusM) {
                byPlace[remind.place.name] = remind.place to remind.radiusM
            }
        }
        return byPlace.values.toList()
    }

    /** 다시 알려 줄 때 읽을 문장. 예: "엄마 메모예요. 우유 사기." */
    fun spoken(data: NaitaeData, memo: Memo): String {
        val mine = memo.authorId == data.owner.id
        val author = data.member(memo.authorId)?.name
        val head = if (mine || author == null) "남겨 둔 메모예요." else "$author 메모예요."
        val body = if (memo.remind is MemoRemind.EveningIgnition) shortenForEvening(memo.text) else memo.text.trim()
        return "$head ${Ko.sentence(body)}"
    }

    /**
     * 퇴근길에 다시 알릴 때는 이미 퇴근길이므로 "퇴근길에" 같은 때를 나타내는 말을 빼고 짧게 읽는다.
     * "퇴근길에 우유 사 오기" → "우유 사기"
     */
    private fun shortenForEvening(text: String): String {
        var t = text.trim()
        for (prefix in EVENING_PREFIXES) {
            if (t.startsWith(prefix) && t.length > prefix.length) {
                t = t.removePrefix(prefix).trim()
                break
            }
        }
        for (suffix in BRING_SUFFIXES) {
            if (t.endsWith(suffix)) {
                t = t.removeSuffix(suffix) + " 사기"
                break
            }
        }
        return t
    }

    private val EVENING_PREFIXES = listOf("퇴근길에", "퇴근할 때", "집에 올 때", "오는 길에")
    private val BRING_SUFFIXES = listOf(" 사 오기", " 사오기", " 사 와 주기", " 사 와")
}
