package com.naitae.connect.e2e

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.core.os.HandlerCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.naitae.connect.app.AppContainer
import com.naitae.connect.app.MainActivity
import com.naitae.connect.app.NaitaeApp
import com.naitae.connect.app.NaitaeCoordinator
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.data.JsonFileRepository
import com.naitae.connect.data.SeedData
import com.naitae.connect.ui.NaitaeActions
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.vehicle.CabinState
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.SimulatorConsole
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import kotlin.coroutines.CoroutineContext

/**
 * e2e 검증용 틀. 앱이 실제로 쓰는 객체 그래프(NaitaeApp → AppContainer → SimPlatform · JsonFileRepository ·
 * NaitaeCoordinator)를 그대로 띄우고, 가상 콘솔(SimulatorConsole)과 사용자의 조작(NaitaeActions)으로만 움직인다.
 *
 * 시간: 코디네이터와 시뮬레이터의 delay 는 모두 메인 디스패처(메인 루퍼)에 걸리므로, Robolectric 의 멈춰 있는
 * 메인 루퍼를 [STEP_MS] 씩 앞으로 돌려(advance) 실제로 기다리지 않고 그대로 재현한다. 저장소의 파일 쓰기만
 * Dispatchers.IO 에서 도는데, 저장이 끝나 메인으로 돌아올 때까지 가상 시간을 세워 두고 기다린다(pump).
 *
 * withUi = true 면 실제 MainActivity 를 띄워 Compose 화면까지 함께 돌리고 캡처를 남긴다.
 * Robolectric 은 프레임마다 그리지는 않으므로, 화면 상태가 바뀔 때마다 배치(layout)를 직접 마치고 캡처할 때만 그린다.
 */
internal class E2eHarness(
    private val withUi: Boolean = false,
    /** 가상 콘솔을 접어 차량 화면이 전체 너비를 쓰게 한다(실제 차의 배치). withUi 일 때만 */
    foldConsole: Boolean = false,
    /** 캡처를 남길 하위 폴더(없으면 docs/screenshots 바로 아래) */
    private val shotFolder: String? = null,
) {

    val app: NaitaeApp = ApplicationProvider.getApplicationContext()
    val container: AppContainer = app.container
    val coordinator: NaitaeCoordinator = container.coordinator
    val actions: NaitaeActions = coordinator
    val console: SimulatorConsole = checkNotNull(container.platform.console) { "시뮬레이터로 실행되지 않음" }

    private val looper = shadowOf(Looper.getMainLooper())
    private val repoMutex: Mutex = JsonFileRepository::class.java.getDeclaredField("mutex")
        .apply { isAccessible = true }
        .get(container.repository) as Mutex
    private var activity: ActivityController<MainActivity>? = null
    private var roots: List<ViewRootForTest> = emptyList()

    /** 지나온 화면 상태(바뀔 때마다 한 장). 화면 순서·자막·조명 변화를 여기서 꺼내 본다. */
    val history = ArrayList<UiState>()

    private var voiceLine: String? = null
    private var voiceSince = 0L
    private var listeningSince: Long? = null

    val ui: UiState get() = coordinator.ui.value
    val screen: Screen get() = ui.screen
    val data: NaitaeData get() = container.repository.data.value
    val cabin: CabinState get() = container.platform.cabin.state.value
    val gear: GearState get() = console.state.value.gear

    init {
        // 프레임을 가상 시계에 맞춰 16ms 마다 그린다(기본값은 프레임마다 시계를 당겨 delay 와 어긋난다).
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(FRAME_MS))
        SdkLog.clear()
        if (withUi) {
            useThisTestsFrameClock()
            activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        } else {
            container.hold(HOLDER)
        }
        pump()
        sample()
        if (withUi && foldConsole) {
            touchText("›")
            if (findNodes("가상 콘솔").isNotEmpty()) fail("가상 콘솔이 접히지 않음")
        }
    }

    /**
     * Compose 는 프레임 시계(AndroidUiDispatcher.Main)를 프로세스에서 한 번만 만들어 첫 테스트의 Choreographer 를 붙잡고 있는데,
     * Robolectric 은 테스트마다 Choreographer 를 새로 만든다. 그대로 두면 두 번째 테스트부터 프레임이 오지 않아 화면이 첫 장면에서 멈춘다.
     * 그래서 이 테스트의 Choreographer 로 같은 종류의 디스패처·프레임 시계를 새로 만들어 화면(Recomposer)에 넘긴다.
     * (생성자가 private 이라 리플렉션으로 만든다. 기기에서와 똑같이 재구성이 프레임 안에서, 배치·그리기 전에 돈다.)
     */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun useThisTestsFrameClock() {
        WindowRecomposerPolicy.setFactory { rootView ->
            rootView.createLifecycleAwareWindowRecomposer(uiContextForThisTest())
        }
    }

    private fun uiContextForThisTest(): CoroutineContext {
        val choreographer = Choreographer.getInstance()
        val constructor = AndroidUiDispatcher::class.java
            .getDeclaredConstructor(Choreographer::class.java, Handler::class.java)
            .apply { isAccessible = true }
        val dispatcher = constructor.newInstance(choreographer, HandlerCompat.createAsync(Looper.getMainLooper()))
        return dispatcher + dispatcher.frameClock
    }

    fun close() {
        activity?.pause()?.stop()?.destroy()
        if (!withUi) container.release(HOLDER)
        container.scope.cancel()
        pump()
    }

    // ───────── 시간 ─────────

    /** 가상 시계(ms) */
    fun nowMs(): Long = SystemClock.uptimeMillis()

    /** 지금 할 수 있는 일을 모두 돌린다. 파일 저장이 끝나 메인으로 돌아오는 것까지 기다린다(가상 시간은 그대로). */
    fun pump() {
        looper.idle()
        var spins = 0
        while (repoMutex.isLocked) {
            Thread.sleep(1)
            looper.idle()
            if (++spins > 10_000) fail("저장소 저장이 끝나지 않음")
        }
    }

    /** 가상 시간을 ms 만큼 흘린다. */
    fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(STEP_MS, left)
            looper.idleFor(Duration.ofMillis(step))
            pump()
            sample()
            left -= step
        }
    }

    fun awaitThat(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        var waited = 0L
        settle()
        while (!condition()) {
            if (waited >= timeoutMs) fail("기다리던 일이 일어나지 않음: $what\n${describe()}")
            advance(STEP_MS)
            waited += STEP_MS
        }
    }

    inline fun <reified S : Screen> awaitScreen(timeoutMs: Long = 60_000): S {
        awaitThat("${S::class.simpleName} 화면", timeoutMs) { screen is S }
        return screen as S
    }

    inline fun <reified S : Screen> screenAs(): S {
        val now = screen
        if (now !is S) fail("${S::class.simpleName} 화면이어야 하는데 ${now.kind}\n${describe()}")
        return now as S
    }

    /** 사용자의 조작·콘솔 입력 뒤에 부른다: 바로 따라오는 일을 돌리고 상태를 적는다. */
    fun settle() {
        pump()
        sample()
    }

    /** 사용자의 조작 하나 */
    fun tap(action: NaitaeActions.() -> Unit) {
        actions.action()
        settle()
    }

    // ───────── 콘솔 ─────────

    /** 가상 콘솔에서 날짜를 고르고, 그날 아침의 데이터로 바뀐 것을 확인한다. */
    fun startDay(day: ScenarioDay) {
        console.setDay(day)
        advance(DAY_SETTLE_MS)
        assertEquals("날짜를 고르면 시동은 꺼져 있다", Screen.EngineOff, screen)
        assertEquals("그날 아침까지의 데이터", SeedData.forDay(day), data)
        assertEquals(day, console.state.value.day)
    }

    fun ignition(on: Boolean) {
        console.ignition(on)
        settle()
    }

    fun shift(gear: GearState) {
        console.gear(gear)
        settle()
    }

    /**
     * 그곳에서 시간을 보낸 뒤: 시동을 끄고, 시각을 옮기고, 다시 건다(ScenarioScript.restartAt 과 같다).
     * 다시 걸기 직전의 mark 를 돌려준다.
     */
    fun restartAt(hour: Int, minute: Int): Int {
        ignition(false)
        assertEquals(Screen.EngineOff, screen)
        advance(ENGINE_OFF_MS)
        console.setClock(hour, minute)
        advance(ENGINE_OFF_MS)
        val mark = mark()
        ignition(true)
        return mark
    }

    /** 그 장소까지 달려 도착하고 P 에 놓일 때까지 기다린다. */
    fun driveTo(placeName: String) {
        val place = console.places.first { it.name == placeName }
        console.arrive(place)
        settle()
        assertEquals("달리는 중", true, ui.driving)
        awaitThat("$placeName 도착 뒤 P", 10_000) { gear == GearState.PARK && !ui.driving }
    }

    // ───────── 기록 ─────────

    fun mark(): Int = history.size

    fun screensSince(mark: Int): List<String> = history.drop(mark).map { it.screen.kind }.squeezed()

    fun voiceLinesSince(mark: Int): List<String> = history.drop(mark).mapNotNull { it.voiceLine }.squeezed()

    fun <T> seenSince(mark: Int, pick: (UiState) -> T?): List<T> = history.drop(mark).mapNotNull(pick).squeezed()

    fun logged(text: String): Boolean = SdkLog.lines.value.any { it.text.contains(text) }

    fun describe(): String = buildString {
        append("화면=").append(screen.kind)
        append(" 자막=").append(ui.voiceLine)
        append(" 주행=").append(ui.driving).append(" 기어=").append(gear)
        append(" 시각=").append(ui.clockLabel).append(" 실내=").append(cabin)
        append("\n지나온 화면: ").append(screensSince(0))
        append("\n최근 로그:\n  ").append(SdkLog.lines.value.takeLast(25).joinToString("\n  ") { it.text })
    }

    private fun sample() {
        val now = ui
        if (history.lastOrNull() != now) {
            history += now
            // 화면 상태가 바뀔 때마다 배치까지 마쳐 본다(배치 중의 오류가 그 자리에서 드러나게).
            layoutNow()
        }
        checkInvariants(now)
    }

    /** 어느 순간에나 지켜져야 하는 규칙(공통 원칙). 걸음마다 확인한다. */
    private fun checkInvariants(now: UiState) {
        val clock = SystemClock.uptimeMillis()
        val moving = gear != GearState.PARK
        if (now.gear != gear) fail("화면의 기어(${now.gear})가 차의 기어($gear)와 다름\n${describe()}")
        if (now.driving != moving) fail("기어 $gear 인데 driving=${now.driving}\n${describe()}")
        val listening = now.screen.listening
        if (now.driving) {
            if (now.screen !is Screen.Home) fail("주행 중에는 홈만 보여야 함\n${describe()}")
            if (listening) fail("주행 중에 듣는 중 표시가 남음\n${describe()}")
        }
        if (now.screen is Screen.EngineOff) {
            if (now.voiceLine != null) fail("시동이 꺼졌는데 자막이 남음\n${describe()}")
            if (now.memoToast != null) fail("시동이 꺼졌는데 메모 카드가 남음\n${describe()}")
        }
        // 같은 자막·듣는 중 표시가 지나치게 오래 남아 있으면 멈춘 흐름의 뒷정리가 빠진 것이다.
        if (now.voiceLine != voiceLine) {
            voiceLine = now.voiceLine
            voiceSince = clock
        } else if (voiceLine != null && clock - voiceSince > VOICE_STUCK_MS) {
            fail("자막이 ${clock - voiceSince}ms 째 그대로: $voiceLine\n${describe()}")
        }
        if (!listening) {
            listeningSince = null
        } else {
            val since = listeningSince ?: clock.also { listeningSince = it }
            if (clock - since > LISTEN_STUCK_MS) fail("듣는 중 표시가 ${clock - since}ms 째 켜져 있음\n${describe()}")
        }
    }

    // ───────── 화면 캡처 ─────────

    /**
     * 지금 화면을 1920×1080 PNG 로 남긴다(withUi 일 때만). 화면 전환 애니메이션이 끝나도록 settleMs 만큼 흘린 뒤에 찍는다.
     */
    fun shot(name: String, settleMs: Long = 600) {
        val controller = activity ?: return
        val dir = System.getProperty(SCREENSHOT_DIR_PROPERTY)?.takeIf { it.isNotBlank() } ?: return
        advance(settleMs)
        val decor = controller.get().window.decorView
        flushTraversals(decor)
        val target = File(if (shotFolder == null) File(dir) else File(dir, shotFolder), "$name.png")
        target.parentFile?.mkdirs()
        decor.captureRoboImage(target.absolutePath)
        settle()
    }

    /**
     * 캡처(Espresso)는 메인 루퍼가 한가해질 때까지 기다리는데, 예약된 화면 갱신(traversal)이 남아 있으면 그 뒤의 일이
     * 모두 막혀(sync barrier) 영영 끝나지 않는다. 멈춰 둔 Choreographer 는 시계가 가야 프레임을 내므로,
     * 남은 갱신이 없어질 때까지 한 프레임씩 흘려보낸다.
     */
    private fun flushTraversals(decor: View) {
        val root: Any = ReflectionHelpers.callInstanceMethod(decor, "getViewRootImpl")
        repeat(MAX_FLUSH_FRAMES) {
            if (!ReflectionHelpers.getField<Boolean>(root, "mTraversalScheduled")) return
            looper.idleFor(Duration.ofMillis(FRAME_MS))
            pump()
        }
        if (ReflectionHelpers.getField<Boolean>(root, "mTraversalScheduled")) fail("화면 갱신이 끝나지 않아 캡처할 수 없음")
    }

    // ───────── 실제 터치 ─────────

    /** 화면에 그 글자(또는 접근성 설명)가 보이는 곳들. 눌리는 영역으로 합쳐진 단위로 찾는다. */
    fun findNodes(text: String): List<SemanticsNode> {
        checkNotNull(activity) { "화면 없이 띄운 틀" }
        layoutNow()
        return composeRoots()
            .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
            .filter { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == text } == true
            }
    }

    /**
     * 그 글자가 보이는 곳의 한가운데를 손가락으로 누른다(창에 터치 이벤트를 넣는다).
     * 같은 글자가 여럿이면 가장 왼쪽 것(차량 화면이 가상 콘솔의 왼쪽에 있다).
     */
    fun touchText(text: String) {
        advance(TOUCH_SETTLE_MS)
        val node = findNodes(text).minByOrNull { it.boundsInWindow.left }
            ?: throw AssertionError("화면에 '$text' 가 없음\n${describe()}")
        val center = node.boundsInWindow.center
        touch(center.x, center.y)
    }

    fun touch(x: Float, y: Float) {
        val decor = checkNotNull(activity).get().window.decorView
        val downAt = SystemClock.uptimeMillis()
        send(decor, MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0))
        advance(3 * FRAME_MS)
        send(decor, MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
        advance(TOUCH_SETTLE_MS)
    }

    private fun send(decor: View, event: MotionEvent) {
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        decor.dispatchTouchEvent(event)
        event.recycle()
    }

    /**
     * Robolectric 은 프레임마다 화면을 그리지 않아서(캡처할 때만 그린다) Compose 의 배치가 그리기를 기다리며 밀려 있다.
     * 걸음마다 배치를 직접 마쳐, 누를 곳의 위치가 지금 화면과 같게 하고 배치 중의 오류도 그 자리에서 드러나게 한다.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun layoutNow() {
        if (activity == null) return
        composeRoots().forEach { it.measureAndLayoutForTest() }
    }

    /** 액티비티 안의 Compose 화면 뿌리(한 번 찾으면 그대로 쓴다) */
    private fun composeRoots(): List<ViewRootForTest> {
        if (roots.isNotEmpty()) return roots
        val found = ArrayList<ViewRootForTest>()
        fun walk(v: View) {
            if (v is ViewRootForTest) found += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        activity?.let { walk(it.get().window.decorView) }
        roots = found
        return found
    }

    companion object {
        const val SCREENSHOT_DIR_PROPERTY = "naitae.screenshot.dir"
        const val STEP_MS = 20L
        const val FRAME_MS = 16L
        private const val MAX_FLUSH_FRAMES = 20

        /** 누른 뒤 화면이 따라올 틈(재구성 → 배치까지 몇 프레임) */
        const val TOUCH_SETTLE_MS = 100L

        /** ScenarioScript 와 같은 틈 */
        const val DAY_SETTLE_MS = 1_500L
        const val ENGINE_OFF_MS = 1_500L

        /** 시뮬레이터의 한 문장 낭독은 길어야 9초, 한 번 듣기는 길어야 몇 초다. */
        const val VOICE_STUCK_MS = 12_000L
        const val LISTEN_STUCK_MS = 10_000L
        private const val HOLDER = "e2e"
    }
}

internal val Screen.kind: String get() = this::class.simpleName.orEmpty()

internal val Screen.listening: Boolean
    get() = when (this) {
        is Screen.Briefing -> listening
        is Screen.RecordEdit -> listening
        else -> false
    }

/** 이어지는 같은 값을 하나로 줄인다. */
internal fun <T> List<T>.squeezed(): List<T> {
    val out = ArrayList<T>()
    for (value in this) if (out.isEmpty() || out.last() != value) out += value
    return out
}
