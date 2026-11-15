package com.naitae.connect.e2e

import com.naitae.connect.FakeCar
import com.naitae.connect.FakeRecordedVoice
import com.naitae.connect.FakeVehicle
import com.naitae.connect.app.NaitaeCoordinator
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.data.JsonFileRepository
import com.naitae.connect.data.SeedData
import com.naitae.connect.data.SessionStore
import com.naitae.connect.domain.LetterPhase
import com.naitae.connect.ui.Screen
import com.naitae.connect.ui.UiState
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.SimulatorConsole
import com.naitae.connect.vehicle.VehiclePlatform
import com.naitae.connect.vehicle.demo.DemoPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/**
 * Pleos 모드의 시연 절차를 JVM 에서 따라간다: 가짜 차량 연결(FakeVehicle = Pleos Virtual Console 의 카드)과
 * 시연 패널(DemoConsole)로만 움직이고, 코디네이터 · 저장소 · 진행 상태 파일은 앱이 쓰는 실제 클래스다.
 * 조립은 AppContainer 가 Pleos 모드에서 하는 것과 같다(AppContainer 는 실제 SDK 를 만들므로 여기서 쓸 수 없다).
 */
class PleosDemoE2eTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class Demo(private val test: TestScope, private val dir: File, val car: FakeCar) {
        lateinit var vehicle: FakeVehicle
        lateinit var platform: VehiclePlatform
        lateinit var console: SimulatorConsole
        lateinit var coordinator: NaitaeCoordinator
        lateinit var session: SessionStore
        private lateinit var repository: JsonFileRepository
        private lateinit var repoMutex: Mutex
        private var running: Job? = null

        val history = ArrayList<UiState>()
        val ui: UiState get() = coordinator.ui.value
        val screen: Screen get() = ui.screen
        val data: NaitaeData get() = repository.data.value
        val today: LocalDate get() = platform.clock.now().toLocalDate()

        /** 앱 프로세스가 뜬다: 파일을 읽고, 차량 연결을 시작한다. */
        fun launch() {
            val scheduler = test.testScheduler
            val parent = test.backgroundScope.coroutineContext
            val job = SupervisorJob(parent[Job])
            running = job
            val scope = CoroutineScope(parent + job)
            vehicle = FakeVehicle(scope, { scheduler.currentTime }, car)

            val dataFile = File(dir, "naitae.json")
            val sessionFile = File(dir, "naitae-session.json")
            if (!dataFile.isFile) sessionFile.delete()
            session = SessionStore(sessionFile, scope, StandardTestDispatcher(scheduler))
            val saved = session.restored
            val day = saved?.scenarioDay()
            val now = saved?.scenarioNow(scheduler.currentTime)
            platform = DemoPlatform.wrap(
                real = vehicle.platform,
                scope = scope,
                resume = if (day != null && now != null) DemoPlatform.Resume(day, now) else null,
                monotonicNanos = { scheduler.currentTime * 1_000_000 },
                onClockSet = { setDay, at ->
                    session.update { it.copy(day = setDay.name, clock = at.toString(), clockSavedAtMs = scheduler.currentTime) }
                },
            )
            console = checkNotNull(platform.console)
            repository = JsonFileRepository(dataFile) { SeedData.forDay(ScenarioDay.WEEKDAY) }
            repoMutex = JsonFileRepository::class.java.getDeclaredField("mutex")
                .apply { isAccessible = true }
                .get(repository) as Mutex
            coordinator = NaitaeCoordinator(platform, repository, FakeRecordedVoice(available = false), scope, session)
            coordinator.start()
            settle()
            platform.signals.start()
            platform.navi.start()
            advance(100)
        }

        /** 앱 프로세스가 죽는다(차는 그대로다). */
        fun kill() {
            settle()
            running?.cancel()
            test.testScheduler.runCurrent()
        }

        /** 지금 할 수 있는 일을 모두 돌린다. 저장소의 파일 쓰기(Dispatchers.IO)가 끝나 돌아오는 것까지 기다린다. */
        fun settle() {
            test.testScheduler.runCurrent()
            var spins = 0
            while (repoMutex.isLocked) {
                Thread.sleep(1)
                test.testScheduler.runCurrent()
                if (++spins > 10_000) fail("저장소 저장이 끝나지 않음")
            }
            if (history.lastOrNull() != ui) history += ui
        }

        fun advance(ms: Long) {
            var left = ms
            while (left > 0) {
                val step = minOf(STEP_MS, left)
                test.testScheduler.advanceTimeBy(step)
                settle()
                left -= step
            }
        }

        fun awaitThat(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
            var waited = 0L
            settle()
            while (!condition()) {
                if (waited >= timeoutMs) fail("기다리던 일이 일어나지 않음: $what\n화면=${screen.kind} 지나온 화면=${screensSince(0)}")
                advance(STEP_MS)
                waited += STEP_MS
            }
        }

        inline fun <reified S : Screen> awaitScreen(): S {
            awaitThat("${S::class.simpleName} 화면") { screen is S }
            return screen as S
        }

        fun tap(action: NaitaeCoordinator.() -> Unit) {
            coordinator.action()
            settle()
        }

        /** Virtual Console 의 Seat Occupancy 카드: 운전석에 앉는다 / 내린다. 탑승 신호가 가라앉을 때까지 기다린다. */
        fun board() {
            vehicle.sit(true)
            advance(SETTLE_MS)
        }

        fun leave() {
            vehicle.sit(false)
            advance(SETTLE_MS)
            assertEquals(Screen.EngineOff, screen)
        }

        /** Virtual Console 로 기어 D → 시연 패널로 도착 → Virtual Console 로 기어 P */
        fun driveTo(placeName: String) {
            vehicle.shift(GearState.DRIVE)
            advance(200)
            console.arrive(console.places.first { it.name == placeName })
            advance(200)
            assertEquals("도착을 넣어도 기어는 그대로", GearState.DRIVE, car.gear)
            assertTrue(ui.driving)
            vehicle.shift(GearState.PARK)
            advance(200)
        }

        /** 지금 화면부터 센다. */
        fun mark(): Int = (history.size - 1).coerceAtLeast(0)

        fun screensSince(mark: Int): List<String> = history.drop(mark).map { it.screen.kind }.squeezed()

        companion object {
            const val STEP_MS = 20L

            /** 탑승 신호가 가라앉는 1.5초 + 여유 */
            const val SETTLE_MS = 1_700L
        }
    }

    private fun demo(car: FakeCar = FakeCar(), body: Demo.() -> Unit) = runTest {
        SdkLog.clear()
        Demo(this, folder.root, car).body()
    }

    private fun logged(text: String): Boolean = SdkLog.lines.value.any { it.text.contains(text) }

    // ───────── 첫 실행과 탑승 ─────────

    @Test
    fun `첫 실행에 운전석에 앉으면 평일 브리핑이 나오고 이어받기는 뜨지 않는다`() = demo {
        launch()

        assertEquals("운전석이 비어 있으면 깨어나지 않는다", Screen.EngineOff, screen)
        assertEquals("기기 시각이 아니라 시나리오의 첫날", LocalDate.of(2026, 11, 12), today)
        assertEquals("가상 데이터의 첫 차가 지금 차", car.vin, data.currentCar?.vin)
        assertEquals(SeedData.withFirstCarVin(SeedData.forDay(ScenarioDay.WEEKDAY), car.vin!!), data)
        assertTrue(data.records.isNotEmpty() && data.records.all { it.vin == car.vin })

        vehicle.sit(true)
        advance(1_400)
        assertEquals("탑승 신호가 가라앉기 전", Screen.EngineOff, screen)
        advance(300)
        val briefing = awaitScreen<Screen.Briefing>()
        assertEquals(5, briefing.briefing.items.size)
        awaitScreen<Screen.Home>()

        assertEquals(listOf("EngineOff", "Briefing", "Home"), screensSince(0))
        assertEquals("좋은 아침이에요.", vehicle.spoken.first())
        assertEquals(today.toEpochDay(), data.lastBriefingEpochDay)
        assertEquals(1, data.cars.size)
    }

    @Test
    fun `VIN 을 못 읽는 차에서도 첫 탑승은 브리핑이고 운전석 센서가 없으면 연결된 때 시작한다`() = demo(
        FakeCar(vin = null, driverSeatSensor = false),
    ) {
        launch()

        awaitScreen<Screen.Briefing>()
        assertEquals(SeedData.forDay(ScenarioDay.WEEKDAY).cars, data.cars)
        assertFalse(history.any { it.screen is Screen.Handover })
    }

    @Test
    fun `넣어 둔 말이 브리핑 끝의 메모가 된다`() = demo {
        launch()
        console.sayNext("내일 세차 예약")

        board()
        awaitScreen<Screen.Home>()

        assertTrue(data.memos.any { it.text == "내일 세차 예약" && it.authorId == data.owner.id })
        assertEquals("메모했어요.", vehicle.spoken.last())
        assertEquals("마이크는 열지 않았다", 0, vehicle.voiceIn.listens)
    }

    @Test
    fun `잠깐 흔들린 운전석 신호와 주행 중의 빈자리 신호로는 흐름이 다시 돌지 않는다`() = demo {
        launch()
        board()
        awaitScreen<Screen.Home>()
        val mark = mark()

        vehicle.sit(false)
        advance(600)
        vehicle.sit(true)
        advance(3_000)
        vehicle.shift(GearState.DRIVE)
        vehicle.sit(false)
        advance(5_000)

        assertEquals(listOf("Home"), screensSince(mark))
        assertFalse(logged("시동 OFF"))

        // P 에 놓았는데도 비어 있으면 그때 내린 것으로 본다.
        vehicle.shift(GearState.PARK)
        advance(Demo.SETTLE_MS)
        assertEquals(Screen.EngineOff, screen)
    }

    // ───────── 평일 저녁 ─────────

    @Test
    fun `회사에 다녀와 저녁에 다시 타면 엄마 메모를 다시 알려 준다`() = demo {
        launch()
        board()
        awaitScreen<Screen.Home>()

        driveTo("회사")
        assertTrue("평소 도착은 조용히", logged("회사 평소 도착"))
        assertTrue(screen is Screen.Home)
        leave()
        console.setClock(18, 40)
        val mark = mark()
        board()

        awaitThat("메모 카드") { ui.memoToast != null }
        assertEquals(SeedData.MEMO_MILK, ui.memoToast?.id)
        awaitThat("메모 낭독") { vehicle.spoken.last() == "엄마 메모예요. 우유 사기." }
        assertEquals("브리핑은 다시 하지 않는다", listOf("EngineOff", "Home"), screensSince(mark))
    }

    // ───────── 토요일: 도착 → 내렸다 다시 탐 → 남기기 ─────────

    @Test
    fun `특별한 곳에 도착을 넣고 내렸다 다시 타면 오늘을 남길지 묻는다`() = demo {
        launch()
        console.setDay(ScenarioDay.FISHING_SATURDAY)
        advance(500)
        assertEquals(SeedData.withFirstCarVin(SeedData.forDay(ScenarioDay.FISHING_SATURDAY), car.vin!!), data)

        board()
        awaitScreen<Screen.RouteAsk>()
        tap { acceptRoute() }
        assertTrue("길 안내 요청은 실제 내비로 간다", vehicle.log.contains("route:낚시터"))

        driveTo("낚시터")
        assertTrue(logged("낚시터 기록 초안 준비"))
        assertTrue("도착할 때는 묻지 않는다", screen is Screen.Home)
        leave()
        console.setClock(11, 40)
        board()

        val ask = awaitScreen<Screen.RecordAsk>()
        assertEquals("낚시터", ask.draft.placeName)
        assertEquals(32f, ask.draft.distanceKm)
        assertTrue(ask.draft.firstVisit)
        assertEquals(car.vin, ask.draft.vin)

        tap { acceptRecord(SeedData.TOPIC_FISH) }
        val edit = awaitScreen<Screen.RecordEdit>()
        assertEquals(car.vin, edit.record.vin)
        assertFalse(history.any { it.screen is Screen.Handover })
    }

    @Test
    fun `운전석이 빈 채로 도착을 넣어도 다시 타면 묻는다`() = demo {
        launch()
        console.setDay(ScenarioDay.FISHING_SATURDAY)
        advance(500)

        // 기어가 이미 P: 그 자리에 도착해 세운 것으로 처리한다.
        console.arrive(SeedData.PIER)
        advance(200)
        assertEquals(Screen.EngineOff, screen)
        board()

        assertEquals("낚시터", awaitScreen<Screen.RecordAsk>().draft.placeName)
    }

    // ───────── 날짜 바꾸기와 VIN ─────────

    @Test
    fun `날짜를 바꾸면 차가 알려 준 VIN 으로 데이터를 다시 만들고 이어받기 없이 그날의 흐름이 돈다`() = demo {
        launch()
        board()
        awaitScreen<Screen.Home>()
        val mark = mark()

        console.setDay(ScenarioDay.BIRTHDAY)
        advance(500)

        assertEquals(LocalDate.of(2027, 11, 23), today)
        assertEquals(SeedData.withFirstCarVin(SeedData.forDay(ScenarioDay.BIRTHDAY), car.vin!!), data)
        assertTrue("타고 있는 채로 바꾸면 홈에 머문다", screen is Screen.Home)

        leave()
        board()
        awaitScreen<Screen.LetterArrive>()
        assertFalse(history.drop(mark).any { it.screen is Screen.Handover || it.screen is Screen.Briefing })
    }

    @Test
    fun `20년 뒤에는 등록된 차를 그대로 두어 이어받기와 새 차의 편지가 돈다`() = demo {
        launch()
        console.setDay(ScenarioDay.TWENTY_YEARS_LATER)
        vehicle.odometer(0f)
        advance(500)
        assertEquals("등록된 차는 가상 데이터의 그랜저", SeedData.VIN_FIRST_CAR, data.currentCar?.vin)

        board()
        val open = awaitScreen<Screen.LetterOpen>()
        assertEquals(SeedData.LETTER_NEW_CAR, open.letter.id)
        awaitThat("편지 낭독 끝") { (screen as? Screen.LetterOpen)?.phase == LetterPhase.DONE }
        tap { closeLetter() }

        val handover = awaitScreen<Screen.Handover>()
        assertEquals(20, handover.summary.ringCount)
        assertEquals(0f, handover.newCarOdometerKm)
        assertEquals(car.vin, data.currentCar?.vin)
        assertNotNull(data.cars.first { it.vin == SeedData.VIN_FIRST_CAR }.retiredEpochDay)
        assertNotNull(data.letters.first { it.id == SeedData.LETTER_NEW_CAR }.openedEpochDay)

        // 다시 타도 이어받기와 편지는 한 번뿐이다.
        tap { dismissHandover() }
        val mark = mark()
        leave()
        board()
        advance(2_000)
        assertFalse(history.drop(mark).any { it.screen is Screen.Handover || it.screen is Screen.LetterOpen })
    }

    @Test
    fun `VIN 을 못 읽는 차에서도 20년 뒤에는 정해 둔 새 차 VIN 으로 이어받는다`() = demo(FakeCar(vin = null)) {
        launch()
        console.setDay(ScenarioDay.TWENTY_YEARS_LATER)
        advance(500)

        board()
        awaitScreen<Screen.LetterOpen>()

        assertEquals(SeedData.VIN_NEW_CAR, data.currentCar?.vin)
    }

    // ───────── 프로세스가 다시 뜸 ─────────

    @Test
    fun `도착한 뒤 앱이 죽었다 다시 떠도 준비해 둔 기록 초안으로 이어 간다`() = demo {
        launch()
        console.setDay(ScenarioDay.FISHING_SATURDAY)
        advance(500)
        board()
        awaitScreen<Screen.RouteAsk>()
        tap { acceptRoute() }
        driveTo("낚시터")
        leave()
        console.setClock(11, 40)
        advance(60_000)
        val before = data
        val clockBefore = platform.clock.now()

        kill()
        launch()

        assertEquals("데이터를 다시 만들지 않는다", before, data)
        assertEquals(ScenarioDay.FISHING_SATURDAY, console.state.value.day)
        assertFalse("시연 시계는 이어서 흐른다", platform.clock.now().isBefore(clockBefore))
        assertEquals(LocalDate.of(2026, 11, 14), today)
        assertEquals("낚시터", coordinator.tracker.pendingDraft?.placeName)
        assertEquals(Screen.EngineOff, screen)

        val mark = mark()
        board()
        val ask = awaitScreen<Screen.RecordAsk>()
        assertEquals("낚시터", ask.draft.placeName)
        assertEquals(32f, ask.draft.distanceKm)
        assertEquals("브리핑도 길 안내 질문도 다시 하지 않는다", listOf("EngineOff", "RecordAsk"), screensSince(mark))

        // 남기고 나면 다음 실행은 초안 없이 시작한다.
        tap { acceptRecord(SeedData.TOPIC_FISH) }
        awaitScreen<Screen.RecordEdit>()
        kill()
        launch()
        assertNull(coordinator.tracker.pendingDraft)
    }

    @Test
    fun `미뤄 둔 편지도 다음 실행으로 이어진다`() = demo {
        launch()
        console.setDay(ScenarioDay.BIRTHDAY)
        advance(500)
        board()
        awaitScreen<Screen.LetterArrive>()
        tap { postponeLetter() }
        assertEquals(SeedData.LETTER_BIRTHDAY, session.state.postponedLetterId)
        leave()

        kill()
        launch()

        assertEquals(SeedData.LETTER_BIRTHDAY, coordinator.memory.postponedLetter?.id)
    }
}
