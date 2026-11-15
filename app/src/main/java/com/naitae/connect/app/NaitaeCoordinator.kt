package com.naitae.connect.app

import android.util.Log
import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.Memo
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.data.NaitaeRepository
import com.naitae.connect.data.SeedData
import com.naitae.connect.domain.LetterPerformer
import com.naitae.connect.domain.MemoEngine
import com.naitae.connect.domain.RingBuilder
import com.naitae.connect.domain.ScheduleEngine
import com.naitae.connect.domain.TripEffect
import com.naitae.connect.domain.TripSignal
import com.naitae.connect.domain.TripTracker
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.ProximityEvent
import com.naitae.connect.vehicle.RecordedVoicePlayer
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.SeatOccupancy
import com.naitae.connect.vehicle.SimState
import com.naitae.connect.vehicle.VehiclePlatform
import com.naitae.connect.vehicle.VoiceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 「나이테」의 상태 기계 (docs/ARCHITECTURE.md 6절).
 *
 * 차의 신호(시동·기어·주행거리·좌석·도착·근접)와 계정 원본을 보고 어떤 화면을 띄우고 무엇을 읽을지 정한다.
 * 화면 상태(UiState)는 이 클래스만 바꾸고, 모든 변경은 메인 디스패처에서 한다.
 *
 * 흐름별 본문은 같은 패키지의 확장 함수로 나눠 두었다.
 *  - CoordinatorIgnition.kt : 시동 흐름(1~7단계), 이어받기, 도착 뒤 반응
 *  - CoordinatorBriefing.kt : 브리핑, 메모 다시 알림, 길 안내 질문
 *  - CoordinatorRecord.kt   : 남기기, 함께 꾸미기, 차에서 다시 보기
 *  - CoordinatorLetter.kt   : 미래 편지
 */
class NaitaeCoordinator(
    internal val platform: VehiclePlatform,
    internal val repository: NaitaeRepository,
    internal val recorded: RecordedVoicePlayer,
    private val scope: CoroutineScope,
) : NaitaeActions {

    private val _ui = MutableStateFlow(UiState(platform = platform.kind))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 낭독·연출·듣기처럼 길게 이어지는 일의 부모. stopAll() 이 자식을 한꺼번에 끊는다. */
    private val flows = SupervisorJob(scope.coroutineContext[Job])

    /** 저장·조명처럼 짧지만 순서가 지켜져야 하는 일을 한 줄로 세운다. */
    private val commandLock = Mutex()

    /** 메모 다시 알림이 겹쳐 읽히지 않게 한다. */
    private val memoTurn = Mutex()

    private val watcher = PlaceWatcher(platform.proximity)
    private var toastTimer: Job? = null
    private var dayReset: Job? = null
    private var started = false
    private var shownDay: LocalDate? = null
    private var resetDay: ScenarioDay? = null

    /** 꾸미기에서 고를 색. 차가 알려 주기 전에는 가상 콘솔이 지원하는 4색으로 둔다. */
    private var ambientColors: List<AmbientColor> = AmbientColor.values().toList()

    internal val performer = LetterPerformer(platform.cabin, platform.voiceOut, recorded)
    internal val light = AmbientKeeper(platform.cabin)

    internal var memory = Transient()
        private set
    internal var tracker: TripTracker = newTracker()
        private set

    /** 지금 돌고 있는 긴 흐름(브리핑·편지·다시 보기 …). 한 번에 하나만 돈다. */
    internal var interaction: Job? = null
        private set

    /** stopAll() 이 불릴 때마다 늘어난다. 끊긴 흐름의 뒷정리가 새 흐름의 자막·듣기 표시를 지우지 않게 하는 표식. */
    internal var epoch = 0
        private set
    internal var ignition = false
        private set
    internal var gear = GearState.UNKNOWN
        private set
    internal var seats = SeatOccupancy()
        private set

    internal val data: NaitaeData get() = repository.data.value
    internal val screen: Screen get() = _ui.value.screen
    private val driving: Boolean get() = _ui.value.driving

    internal fun now(): LocalDateTime = platform.clock.now()
    internal fun today(): LocalDate = now().toLocalDate()

    // ───────── 시작: 신호 구독 ─────────

    /** 한 번만 부른다(Application.onCreate). 차량 연결이 아직 시작되지 않았어도 흐름만 걸어 둔다. */
    fun start() {
        if (started) return
        started = true
        val signals = platform.signals
        // StateFlow 는 구독하자마자 지금 값을 주므로, 시동 신호를 가장 나중에 걸어 다른 값이 먼저 자리 잡게 한다.
        collect("cabin", platform.cabin.state) { cabin -> _ui.update { it.copy(cabin = cabin) } }
        collect("data", repository.data) { onData(it) }
        collect("seats", signals.seats) { value ->
            seats = value
            feed(TripSignal.Seats(value))
        }
        collect("odometer", signals.odometerKm) { km ->
            if (km != null) feed(TripSignal.Odometer(km))
            _ui.update { it.copy(odometerKm = km) }
        }
        collect("gear", signals.gear) { onGear(it) }
        collect("navi", platform.navi.events) { onNavi(it) }
        collect("proximity", platform.proximity.events) { onProximity(it) }
        platform.console?.let { console ->
            collect("day", console.state.map { scenarioStart(it) }.distinctUntilChanged()) { (day, atStart) ->
                if (day != resetDay || atStart) {
                    resetDay = day
                    val job = scope.launch { guarded("day") { resetForDay(day) } }
                    dayReset = job
                    job.join()
                }
            }
        }
        collect("ignition", signals.ignitionOn) { onIgnition(it) }
        scope.launch {
            while (isActive) {
                guarded("clock") { onClockTick() }
                delay(CLOCK_TICK_MS)
            }
        }
    }

    private fun <T> collect(tag: String, flow: Flow<T>, handler: suspend (T) -> Unit) {
        scope.launch { flow.collect { value -> guarded(tag) { handler(value) } } }
    }

    /**
     * 가상 콘솔에서 날짜를 고른 순간인지. setDay 는 시각을 그날의 시작 시각으로 맞추므로,
     * 같은 날짜를 다시 골라 처음부터 시작하는 것도 이것으로 알아챈다.
     */
    private fun scenarioStart(state: SimState): Pair<ScenarioDay, Boolean> =
        state.day to (state.now == SeedData.dateTimeOf(state.day))

    // ───────── 차의 신호 ─────────

    private fun onIgnition(on: Boolean) {
        if (on == ignition) return
        ignition = on
        refreshClock()
        if (!on) {
            onIgnitionOff()
            return
        }
        SdkLog.sys("나이테 · 시동 ON")
        refreshAmbientColors()
        memory.route = null
        memory.recordDraft = null
        feed(TripSignal.IgnitionOn(now()))
        if (gear == GearState.PARK) {
            startIgnitionSequence()
        } else {
            // P 가 아니면 화면 흐름을 미뤄 두었다가 P 가 되면 시작한다.
            memory.sequencePending = true
            showHome()
        }
    }

    private fun onIgnitionOff() {
        SdkLog.sys("나이테 · 시동 OFF")
        val leaving = screen
        val running = interaction
        stopAll()
        feed(TripSignal.IgnitionOff(now()))
        memory.sequencePending = false
        // 시동을 끄면 브리핑은 마친 것으로 치지 않는다. 다음 시동에 처음부터 다시 읽는다.
        settleBriefing(markDone = false)
        leave(leaving, running)
        clearToast()
        show(Screen.EngineOff)
    }

    private fun onGear(next: GearState) {
        val before = gear
        gear = next
        feed(TripSignal.Gear(next))
        _ui.update { it.copy(gear = next, driving = isMoving(next)) }
        refreshClock()
        if (!ignition) return
        when {
            !isMoving(before) && isMoving(next) -> onLeftPark()
            next == GearState.PARK && before != GearState.PARK -> onEnteredPark()
        }
    }

    /** 출발: 낭독·연출을 멈추고 입력 화면을 닫는다. */
    private fun onLeftPark() {
        val leaving = screen
        val running = interaction
        stopAll()
        // 브리핑 도중에 출발해도 하루 한 번의 브리핑은 한 것으로 본다(퇴근길 메모가 저녁 시동에 이어지도록).
        settleBriefing(markDone = true)
        leave(leaving, running)
        showHome()
    }

    private fun onEnteredPark() {
        if (memory.sequencePending) startIgnitionSequence() else reactToArrival()
    }

    /** 화면을 떠날 때의 뒷정리(출발·시동 끔 공통): 편지 조명과 미리보기 조명을 되돌리고, 못 본 편지는 미뤄 둔다. */
    private fun leave(leaving: Screen, running: Job?) {
        when (leaving) {
            is Screen.LetterOpen -> endLetter(leaving.letter, running, keepLight = false)
            is Screen.LetterArrive -> memory.postponedLetter = leaving.letter
            is Screen.Handover -> releaseLetterLight()
            is Screen.RecordEdit -> memory.selectedTopicId = leaving.topic.id
            is Screen.Decorate -> memory.selectedTopicId = leaving.topic.id
            is Screen.RecallView -> memory.selectedTopicId = leaving.recall.topic.id
            else -> Unit
        }
        memory.route = null
        restoreLight()
    }

    private fun onNavi(event: NaviEvent) {
        if (event !is NaviEvent.Arrived) return
        // 도착은 바로 TripTracker 에 알리되, 화면 반응은 기어가 P 로 돌아온 뒤에 한다.
        memory.arrivalPending = true
        feed(TripSignal.Arrived(event, now()))
        if (gear == GearState.PARK) reactToArrival()
    }

    private fun onProximity(event: ProximityEvent) {
        if (!event.entered) return
        val memos = MemoEngine.dueAtPlace(data, event.placeName)
        if (memos.isEmpty()) return
        SdkLog.sys("나이테 · ${event.placeName} 근처 → 장소 메모 ${memos.size}건")
        // 카드는 바로 띄우고, 읽는 것은 지금 읽던 것이 끝난 뒤에 한다.
        showToast(memos.first())
        val before = interaction
        scope.launch(flows) {
            guarded("memo") {
                memoTurn.withLock {
                    before?.join()
                    remindMemos(memos)
                }
            }
        }
    }

    private fun onData(data: NaitaeData) {
        _ui.update { it.copy(carLabel = carLabel(data), screen = refreshed(it.screen, data)) }
        syncWatches()
    }

    private fun onClockTick() {
        refreshClock()
        val day = today()
        if (shownDay != null && shownDay != day && screen is Screen.Home) showHome()
        shownDay = day
    }

    private fun refreshClock() {
        val label = now().format(CLOCK_FORMAT)
        _ui.update { if (it.clockLabel == label) it else it.copy(clockLabel = label) }
    }

    /** TripTracker 에 신호를 넣고, 돌려준 할 일을 기억해 둔다(화면 반응은 시동 흐름과 도착 뒤 P 에서 한다). */
    internal fun feed(signal: TripSignal) {
        val effects = try {
            tracker.onSignal(signal)
        } catch (e: Throwable) {
            report("trip", e)
            emptyList<TripEffect>()
        }
        for (effect in effects) {
            when (effect) {
                is TripEffect.AskRoute -> memory.route = effect.schedule
                is TripEffect.AskRecord -> memory.recordDraft = effect.draft
                is TripEffect.RecallAt -> memory.recallPlace = effect.placeName
                is TripEffect.QuietArrival -> SdkLog.sys("나이테 · ${effect.placeName} 평소 도착 → 화면 없음")
                is TripEffect.DraftReady -> SdkLog.sys("나이테 · ${effect.draft.placeName} 기록 초안 준비 → 묻지 않음")
            }
        }
    }

    private fun newTracker(): TripTracker = TripTracker(
        { repository.data.value },
        { memory.vin ?: repository.data.value.currentCar?.vin.orEmpty() },
    )

    // ───────── 시뮬레이터: 날짜 바꾸기 ─────────

    /** 가상 콘솔에서 날짜를 고르면 그날 아침까지의 데이터로 바꾸고 이동·임시 상태를 모두 비운다. */
    private suspend fun resetForDay(day: ScenarioDay) {
        SdkLog.sys("나이테 · '${day.label}' 아침까지의 데이터로 바꿈")
        stopAll()
        clearToast()
        memory = Transient()
        light.forget()
        // 끊긴 편지 연출이 기억해 둔 '편지 전 조명'도 버린다(조명은 건드리지 않는다).
        performer.finish(keepLight = true)
        tracker = newTracker()
        feed(TripSignal.Gear(platform.signals.gear.value))
        platform.signals.odometerKm.value?.let { feed(TripSignal.Odometer(it)) }
        feed(TripSignal.Seats(platform.signals.seats.value))
        show(Screen.EngineOff)
        repository.replace(SeedData.forDay(day))
        if (ignition && screen is Screen.EngineOff && interaction == null) showHome()
    }

    /** 날짜를 바꾸는 중이면 끝날 때까지 기다린다(옛 데이터로 시동 흐름이 돌지 않게). */
    internal suspend fun awaitDayReset() {
        dayReset?.join()
    }

    // ───────── 화면 상태 바꾸기 ─────────

    internal fun show(screen: Screen) {
        _ui.update { it.copy(screen = screen) }
    }

    internal fun updateScreen(transform: (Screen) -> Screen) {
        _ui.update { it.copy(screen = transform(it.screen)) }
    }

    internal fun showHome() {
        shownDay = today()
        show(homeScreen(data))
    }

    private fun homeScreen(data: NaitaeData): Screen.Home {
        val today = today()
        val selectedId = memory.selectedTopicId?.takeIf { data.topic(it) != null }
            ?: data.records.maxByOrNull { it.epochDay }?.topicId?.takeIf { data.topic(it) != null }
            ?: data.topics.firstOrNull()?.id
        return Screen.Home(
            topics = data.topics,
            selected = selectedId?.let { RingBuilder.summary(data, it, today) },
            upcoming = ScheduleEngine.upcoming(data, today),
        )
    }

    /** 계정 원본이 바뀌면 지금 화면이 들고 있는 기록·요약을 새 값으로 바꾼다. */
    private fun refreshed(screen: Screen, data: NaitaeData): Screen = when (screen) {
        is Screen.Home -> homeScreen(data)
        is Screen.RecordEdit -> {
            val record = data.records.firstOrNull { it.id == screen.record.id }
            val topic = record?.let { data.topic(it.topicId) }
            if (record == null || topic == null) {
                screen
            } else {
                screen.copy(
                    record = record,
                    topic = topic,
                    summary = RingBuilder.summary(data, topic.id, today(), record.id),
                )
            }
        }
        is Screen.Decorate -> {
            val record = data.records.firstOrNull { it.id == screen.record.id }
            val topic = record?.let { data.topic(it.topicId) }
            if (record == null || topic == null) {
                screen
            } else {
                screen.copy(record = record, topic = topic, members = sharedMembers(data, topic.sharedWith))
            }
        }
        else -> screen
    }

    internal fun sharedMembers(data: NaitaeData, ids: List<String>) = ids.mapNotNull { data.member(it) }

    private fun carLabel(data: NaitaeData): String =
        data.currentCar?.model ?: platform.console?.state?.value?.carModel.orEmpty()

    /** 새 차의 이름: 가상 콘솔이 알려 주는 이름, 없으면 "새 현대차" */
    internal fun carModelName(): String =
        platform.console?.state?.value?.carModel?.takeIf { it.isNotBlank() } ?: DEFAULT_CAR_MODEL

    internal fun decorateColors(): List<AmbientColor> = ambientColors

    /** 실내 조명이 지원하는 색을 다시 묻는다(차량 연결 뒤에야 알 수 있다). */
    internal fun refreshAmbientColors() {
        scope.launch {
            guarded("capabilities") {
                val colors = platform.cabin.capabilities().ambientColors
                if (colors.isNotEmpty() && colors != ambientColors) {
                    ambientColors = colors
                    updateScreen { if (it is Screen.Decorate) it.copy(colors = colors) else it }
                }
            }
        }
    }

    // ───────── 메모 카드 ─────────

    internal fun showToast(memo: Memo) {
        _ui.update { it.copy(memoToast = memo, memoToastAuthor = data.member(memo.authorId)?.name) }
        toastTimer?.cancel()
        toastTimer = scope.launch {
            delay(TOAST_MS)
            _ui.update { if (it.memoToast?.id == memo.id) it.copy(memoToast = null, memoToastAuthor = null) else it }
        }
    }

    private fun clearToast() {
        toastTimer?.cancel()
        toastTimer = null
        _ui.update { it.copy(memoToast = null, memoToastAuthor = null) }
    }

    // ───────── 근접 알림 ─────────

    /** 아직 끝나지 않은 장소 메모의 장소에만 근접 알림을 걸어 둔다. */
    private fun syncWatches() {
        scope.launch { guarded("proximity") { watcher.sync(MemoEngine.placesToWatch(data)) } }
    }

    /** 위치 권한을 받은 뒤 다시 건다(권한이 없을 때 실패한 등록을 되살린다). */
    fun retryWatches() {
        syncWatches()
    }

    // ───────── 긴 흐름: 시작·멈춤·말하기·듣기 ─────────

    /** 앞선 흐름을 멈추고 새 흐름을 시작한다. */
    internal fun startInteraction(block: suspend () -> Unit): Job {
        stopAll()
        // 본문이 돌기 전에 interaction 에 먼저 담아 둔다(메인 즉시 디스패처는 launch 안에서 바로 돌기 때문).
        val job = scope.launch(flows, CoroutineStart.LAZY) { guarded("flow") { block() } }
        interaction = job
        job.start()
        return job
    }

    /**
     * 낭독·연출·듣기·녹음 재생을 모두 멈춘다.
     * 자막과 듣는 중 표시는 여기서 바로 지운다(끊긴 흐름의 뒷정리가 늦게 돌아도 화면에 남지 않게).
     */
    internal fun stopAll() {
        epoch++
        flows.cancelChildren()
        interaction = null
        quietly("voiceOut.stop") { platform.voiceOut.stop() }
        quietly("voiceIn.cancel") { platform.voiceIn.cancel() }
        quietly("recorded.stop") { recorded.stop() }
        _ui.update { it.copy(voiceLine = null, screen = it.screen.quiet()) }
    }

    /** 저장·조명처럼 끊기면 안 되는 짧은 일. 부른 순서대로 하나씩 돈다. */
    internal fun launchCommand(block: suspend () -> Unit) {
        scope.launch { commandLock.withLock { guarded("command") { block() } } }
    }

    /** 앞서 맡긴 저장이 모두 끝날 때까지 기다린다. */
    internal suspend fun settle() {
        commandLock.withLock { }
    }

    /** 흐름이 끊겼으면 여기서 멈춘다. 차량 쪽 호출이 취소를 모르고 돌아와도 다음 화면으로 넘어가지 않게 한다. */
    internal suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
    }

    /** 읽는 문장을 자막에 올리고 다 읽을 때까지 기다린다. */
    internal suspend fun say(text: String) {
        if (text.isBlank()) return
        checkpoint()
        val mine = epoch
        _ui.update { it.copy(voiceLine = text) }
        try {
            val result = platform.voiceOut.speak(text)
            checkpoint()
            // 음성 출력을 쓸 수 없으면 자막을 읽을 시간만큼 기다린다.
            if (result.isFailure) delay(readingTimeMs(text))
        } finally {
            if (epoch == mine) _ui.update { it.copy(voiceLine = null) }
        }
    }

    /** 녹음한 목소리를 재생하는 동안 그 말을 자막에 올린다. 파일이 없으면 false. */
    internal suspend fun playRecorded(asset: String, caption: String): Boolean {
        checkpoint()
        val mine = epoch
        _ui.update { it.copy(voiceLine = caption) }
        try {
            val played = recorded.play(asset)
            checkpoint()
            return played
        } finally {
            if (epoch == mine) _ui.update { it.copy(voiceLine = null) }
        }
    }

    /** 한 번 듣는다. 듣는 중 표시와 실시간 글은 화면마다 다르므로 넘겨받은 함수로 반영한다. */
    internal suspend fun hear(onListening: (Boolean) -> Unit, onPartial: (String) -> Unit): VoiceResult {
        checkpoint()
        val mine = epoch
        onListening(true)
        try {
            val result = coroutineScope {
                // 앞선 듣기의 글이 남아 있을 수 있어 첫 값은 버린다.
                val mirror = launch { platform.voiceIn.partial.drop(1).collect { onPartial(it) } }
                try {
                    platform.voiceIn.listen()
                } finally {
                    mirror.cancel()
                }
            }
            checkpoint()
            return result
        } finally {
            if (epoch == mine) onListening(false)
        }
    }

    internal suspend fun guarded(tag: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            report(tag, e)
        }
    }

    private inline fun quietly(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            report(tag, e)
        }
    }

    /** 한 흐름이 실패해도 앱은 계속 돈다. 가상 콘솔 로그에 남겨 바로 보이게 한다. */
    internal fun report(tag: String, error: Throwable) {
        Log.e(LOG_TAG, "나이테 흐름 오류($tag)", error)
        SdkLog.sys("나이테 · 오류($tag): ${error.message ?: error.javaClass.simpleName}")
    }

    // ───────── 사용자의 조작 (NaitaeActions) ─────────

    /** 주행 중이거나 시동이 꺼져 있으면 화면 입력을 받지 않는다. */
    private inline fun whenParked(block: () -> Unit) {
        if (ignition && !driving) block()
    }

    override fun selectTopic(topicId: String) {
        whenParked {
            if (data.topic(topicId) != null) {
                memory.selectedTopicId = topicId
                if (screen is Screen.Home) showHome()
            }
        }
    }

    override fun openHome() {
        whenParked {
            // 어느 화면에서 홈으로 가든 그 화면의 닫기와 같은 뒷정리를 거친다.
            when (screen) {
                is Screen.Briefing -> stopBriefing()
                is Screen.RouteAsk -> declineRoute()
                is Screen.RecordAsk -> declineRecord()
                is Screen.RecordEdit, is Screen.Decorate -> finishRecord()
                is Screen.RecallView -> closeRecall()
                is Screen.LetterArrive -> postponeLetter()
                is Screen.LetterOpen -> closeLetter()
                is Screen.Handover -> dismissHandover()
                is Screen.Home, Screen.EngineOff -> {
                    stopAll()
                    showHome()
                }
            }
        }
    }

    override fun stopBriefing() {
        whenParked { endBriefingEarly() }
    }

    override fun acceptRoute() {
        whenParked { startRoute() }
    }

    override fun declineRoute() {
        whenParked {
            if (screen is Screen.RouteAsk) {
                stopAll()
                memory.route = null
                showHome()
            }
        }
    }

    override fun acceptRecord(topicId: String) {
        whenParked { commitRecord(topicId) }
    }

    override fun declineRecord() {
        whenParked {
            if (screen is Screen.RecordAsk) {
                stopAll()
                tracker.clearDraft()
                showHome()
            }
        }
    }

    override fun startVoiceNote() {
        whenParked { recordVoiceNote() }
    }

    override fun openDecorate() {
        whenParked {
            val edit = screen
            if (edit is Screen.RecordEdit) decorate(edit.record.id)
        }
    }

    override fun openDecorateFor(recordId: String) {
        whenParked { decorate(recordId) }
    }

    override fun pickColor(color: AmbientColor) {
        whenParked { paint(color) }
    }

    override fun addPhoto() {
        whenParked { attachPhoto() }
    }

    override fun finishDecorate() {
        whenParked { closeDecorate() }
    }

    override fun finishRecord() {
        whenParked { closeRecord() }
    }

    override fun playRecallVoice() {
        whenParked { replayRecall() }
    }

    override fun closeRecall() {
        whenParked { endRecall() }
    }

    override fun openLetter() {
        whenParked { unfoldLetter() }
    }

    override fun postponeLetter() {
        whenParked { putOffLetter() }
    }

    override fun closeLetter() {
        whenParked { foldLetter() }
    }

    override fun dismissHandover() {
        whenParked {
            if (screen is Screen.Handover) {
                releaseLetterLight()
                showHome()
            }
        }
    }

    override fun dismissMemoToast() {
        clearToast()
    }

    /** 미리보기 조명(다시 보기·꾸미기)을 원래대로. 기억해 둔 것이 없으면 아무 일도 하지 않는다. */
    internal fun restoreLight() {
        launchCommand { light.restore() }
    }

    private companion object {
        const val LOG_TAG = "Naitae"
        const val DEFAULT_CAR_MODEL = "새 현대차"
        const val CLOCK_TICK_MS = 20_000L
        const val TOAST_MS = 12_000L
        val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun isMoving(gear: GearState): Boolean = gear != GearState.PARK && gear != GearState.UNKNOWN

        /** 음성 출력이 실패했을 때 자막을 보여 줄 시간 */
        fun readingTimeMs(text: String): Long = (900L + 80L * text.length).coerceAtMost(7_000L)

        /** 듣는 중·재생 중 표시를 끈 화면 */
        fun Screen.quiet(): Screen = when (this) {
            is Screen.Briefing -> if (listening) copy(listening = false) else this
            is Screen.RecordEdit -> if (listening || heardText.isNotEmpty()) copy(listening = false, heardText = "") else this
            is Screen.RecallView -> if (playingVoice) copy(playingVoice = false) else this
            else -> this
        }
    }
}
