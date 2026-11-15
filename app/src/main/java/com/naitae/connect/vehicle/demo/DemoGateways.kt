package com.naitae.connect.vehicle.demo

import com.naitae.connect.core.model.Place
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.NaviGateway
import com.naitae.connect.vehicle.ProximityEvent
import com.naitae.connect.vehicle.ProximityGateway
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.VoiceInput
import com.naitae.connect.vehicle.VoiceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/* 시연 층의 연결들: 실제 Pleos 어댑터를 그대로 쓰면서 시연 패널이 넣는 것을 섞는다. */

/** 내비: 실제 NaviHelper 의 이벤트에 시연 패널이 넣은 도착을 더한다. 길 안내 요청은 실제 내비로 간다. */
internal class DemoNavi(private val real: NaviGateway, scope: CoroutineScope) : NaviGateway {

    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<NaviEvent> = _events.asSharedFlow()

    init {
        scope.launch { real.events.collect { _events.emit(it) } }
    }

    fun inject(event: NaviEvent) {
        _events.tryEmit(event)
    }

    override suspend fun requestRoute(place: Place): Result<Unit> = real.requestRoute(place)

    override suspend fun bookmarks(): List<Place> = real.bookmarks()

    override fun start() = real.start()

    override fun stop() = real.stop()
}

/**
 * 음성 입력: 시연 패널에서 넣어 둔 말(sayNext)이 있으면 다음 듣기가 그 말을 돌려주고, 없으면 실제 STT 로 듣는다.
 * 에뮬레이터의 마이크는 믿기 어려우므로, 실제 STT 가 듣는 도중에 넣은 말도 그 듣기의 답이 된다.
 */
internal class DemoVoiceInput(private val real: VoiceInput) : VoiceInput {

    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    private val queued = AtomicReference<String?>(null)

    /** 실제 STT 가 듣는 동안 시연 패널의 말을 받는 자리 */
    @Volatile
    private var waiting: CompletableDeferred<String>? = null

    fun sayNext(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        if (waiting?.complete(said) == true) return
        queued.set(said)
    }

    fun clearQueued() {
        queued.set(null)
    }

    override suspend fun listen(timeoutMs: Long): VoiceResult {
        _partial.value = ""
        queued.getAndSet(null)?.let { return typeOut(it) }

        val injected = CompletableDeferred<String>()
        waiting = injected
        try {
            val heard = coroutineScope {
                // 실제 STT 가 받아 적는 글을 그대로 비춘다(앞선 듣기의 글일 수 있는 첫 값은 버린다).
                val mirror = launch(start = CoroutineStart.UNDISPATCHED) {
                    real.partial.drop(1).collect { _partial.value = it }
                }
                val listening = async { listenReal(timeoutMs) }
                try {
                    select<VoiceResult?> {
                        listening.onAwait { it }
                        injected.onAwait { null }
                    }
                } finally {
                    mirror.cancel()
                    if (listening.isActive) {
                        real.cancel()
                        listening.cancel()
                    }
                }
            }
            if (heard != null) return heard
            _partial.value = ""
            return typeOut(injected.await())
        } finally {
            if (waiting === injected) waiting = null
        }
    }

    override fun cancel() {
        real.cancel()
    }

    private suspend fun listenReal(timeoutMs: Long): VoiceResult =
        try {
            real.listen(timeoutMs)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VoiceResult.Failed(t.message ?: t.javaClass.simpleName)
        }

    /** 넣어 둔 말을 받아 적는 것처럼 한 글자씩 보여 준 뒤 돌려준다. */
    private suspend fun typeOut(text: String): VoiceResult {
        SdkLog.sys("시연 패널 · 음성 입력 대신 넣은 말 → \"$text\"")
        delay(HEARING_LEAD_MS)
        for (end in 1..text.length) {
            _partial.value = text.substring(0, end)
            delay(TYPING_MS_PER_CHAR)
        }
        return VoiceResult.Text(text)
    }

    private companion object {
        const val HEARING_LEAD_MS = 400L
        const val TYPING_MS_PER_CHAR = 90L
    }
}

/**
 * 근접 알림: 실제 FusedLocation 의 이벤트에, 시연 패널로 도착을 넣은 곳의 '가까워짐'을 더한다.
 * 에뮬레이터에는 위치를 옮길 방법이 없으므로, 걸어 둔 장소에 도착을 넣으면 근접 알림도 함께 낸다.
 */
internal class DemoProximity(private val real: ProximityGateway, scope: CoroutineScope) : ProximityGateway {

    private val _events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ProximityEvent> = _events.asSharedFlow()

    /** 걸어 둔 장소 이름 → 반경(m). 실제 등록이 실패해도 시연 패널의 도착에는 반응한다. */
    private val watched = ConcurrentHashMap<String, Int>()

    init {
        scope.launch { real.events.collect { _events.emit(it) } }
    }

    /** 시연 패널이 도착을 넣을 때 부른다. 걸어 둔 곳이면 '가까워짐'을 낸다. */
    fun arrive(place: Place) {
        if (!watched.containsKey(place.name)) return
        SdkLog.event("시연 패널 · 근접 알림 넣음 → ${place.name} 가까워짐")
        _events.tryEmit(ProximityEvent(place.name, entered = true))
    }

    /** 시연 패널로 넣을 수 있으므로 늘 쓸 수 있다. */
    override suspend fun isSupported(): Boolean = true

    override suspend fun watch(place: Place, radiusM: Int): Result<Unit> {
        watched[place.name] = radiusM
        // 실제 위치 서비스를 쓸 수 있으면 그 결과를 돌려준다(권한을 받은 뒤 다시 걸 수 있게).
        return if (real.isSupported()) real.watch(place, radiusM) else Result.success(Unit)
    }

    override suspend fun unwatch(placeName: String) {
        watched.remove(placeName)
        real.unwatch(placeName)
    }
}
