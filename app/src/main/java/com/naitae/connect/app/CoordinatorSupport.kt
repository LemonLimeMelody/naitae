package com.naitae.connect.app

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.FutureLetter
import com.naitae.connect.core.model.Place
import com.naitae.connect.core.model.Schedule
import com.naitae.connect.domain.Briefing
import com.naitae.connect.domain.HandoverSummary
import com.naitae.connect.domain.RecordDraft
import com.naitae.connect.ui.Screen
import com.naitae.connect.vehicle.CabinControl
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.ProximityGateway
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/* 코디네이터가 쓰는 작은 부품들 */

/**
 * 코디네이터의 임시 상태(저장하지 않음). 시뮬레이터에서 날짜를 바꾸면 통째로 새로 만든다.
 */
internal class Transient {
    /** 이번 시동에서 읽은 차대번호 */
    var vin: String? = null

    /** 홈에서 고른 주제 */
    var selectedTopicId: String? = null

    /** 시동은 걸렸지만 P 가 아니어서 미뤄 둔 시동 흐름 */
    var sequencePending = false

    /** TripTracker 가 이번 시동에 낸 길 안내 질문 대상 */
    var route: Schedule? = null

    /** TripTracker 가 이번 시동에 낸 "오늘을 남길까요?" 의 초안 */
    var recordDraft: RecordDraft? = null

    /** 도착 이벤트는 왔지만 아직 P 가 아니어서 미뤄 둔 화면 반응 */
    var arrivalPending = false

    /** 도착한 곳에 예전 기록이 있을 때, P 가 되면 다시 보기를 열 장소 */
    var recallPlace: String? = null

    /** 읽고 있는 브리핑(끝나거나 멈추면 null) */
    var briefing: Briefing? = null

    /** "도착해서 보기"로 미뤄 둔 편지 */
    var postponedLetter: FutureLetter? = null

    /** 이어받기는 했지만 아직 이어받기 화면을 보여 주지 않았을 때의 요약 */
    var handover: HandoverSummary? = null

    /** 꾸미기를 마치면 남긴 기록 화면으로 돌아갈지(홈에서 열었으면 홈으로) */
    var decorateBackToEdit = false

    /** 기록을 확정하는 중(두 번 눌러도 한 번만 남기도록) */
    var committing = false
}

/** 지금 화면이 S 일 때만 바꾼다. 흐름이 도는 사이 화면이 바뀌었으면 아무 일도 하지 않는다. */
internal inline fun <reified S : Screen> NaitaeCoordinator.updateIf(crossinline transform: (S) -> Screen) {
    updateScreen { current -> if (current is S) transform(current) else current }
}

/**
 * 다시 보기·꾸미기에서 잠깐 바꾸는 실내 조명. 바꾸기 전 상태를 기억했다가 되돌린다.
 * (편지의 조명은 LetterPerformer 가 따로 기억한다.)
 */
internal class AmbientKeeper(private val cabin: CabinControl) {
    private val mutex = Mutex()
    private var before: CabinState? = null
    private var shown: AmbientColor? = null

    /** 조명을 바꾼다. 처음 바꿀 때의 상태만 기억한다(색을 여러 번 골라도 원래 조명으로 돌아가게). */
    suspend fun preview(color: AmbientColor, brightness: Int) {
        mutex.withLock {
            if (before == null) before = cabin.state.value
            shown = color
            cabin.setAmbient(color, brightness)
        }
    }

    /** 기억해 둔 조명으로 되돌린다. 기억이 없으면 아무 일도 하지 않는다. */
    suspend fun restore() {
        mutex.withLock {
            val remembered = before
            val used = shown
            before = null
            shown = null
            if (remembered != null) {
                // 바꾸기 전 색을 모르면 쓰던 색 그대로 밝기만 되돌린다(모르면 끔).
                val color = remembered.ambientColor ?: used
                if (color != null) {
                    withContext(NonCancellable) { cabin.setAmbient(color, remembered.ambientBrightness ?: 0) }
                }
            }
        }
    }

    /** 시나리오를 처음부터 다시 시작할 때: 기억만 버린다. */
    fun forget() {
        before = null
        shown = null
    }
}

/** 장소 메모의 근접 알림을 계정 원본에 맞춰 걸고 푼다. */
internal class PlaceWatcher(private val gateway: ProximityGateway) {
    private val mutex = Mutex()
    private val watched = HashMap<String, Int>()

    suspend fun sync(wanted: List<Pair<Place, Int>>) {
        mutex.withLock {
            if (wanted.isEmpty() && watched.isEmpty()) return
            if (!gateway.isSupported()) return
            val wantedNames = wanted.map { it.first.name }.toSet()
            for (name in watched.keys.toList()) {
                if (name !in wantedNames) {
                    gateway.unwatch(name)
                    watched.remove(name)
                }
            }
            for ((place, radiusM) in wanted) {
                if (watched[place.name] == radiusM) continue
                // 권한이 없으면 실패한다. 걸린 것만 적어 두고, 실패한 것은 다음 기회에 다시 건다.
                if (gateway.watch(place, radiusM).isSuccess) watched[place.name] = radiusM
            }
        }
    }
}

/** 받침에 따라 달라지는 조사 */
internal object Josa {
    /** "낚시터로" / "수목원으로" (ㄹ 받침은 "로") */
    fun ro(word: String): String {
        val jong = finalConsonant(word)
        return word + if (jong == null || jong == NONE || jong == RIEUL) "로" else "으로"
    }

    /** "미팅이에요" / "낚시예요" */
    fun ieyo(word: String): String {
        val jong = finalConsonant(word)
        return word + if (jong == null || jong == NONE) "예요" else "이에요"
    }

    /** 마지막 글자의 받침 번호(없으면 0). 한글이 아니면 null */
    private fun finalConsonant(word: String): Int? {
        val last = word.trim().lastOrNull() ?: return null
        if (last.code !in HANGUL_FIRST..HANGUL_LAST) return null
        return (last.code - HANGUL_FIRST) % FINALS
    }

    private const val HANGUL_FIRST = 0xAC00
    private const val HANGUL_LAST = 0xD7A3
    private const val FINALS = 28
    private const val NONE = 0
    private const val RIEUL = 8
}
