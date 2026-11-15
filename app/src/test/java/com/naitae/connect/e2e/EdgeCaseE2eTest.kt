package com.naitae.connect.e2e

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.core.model.NaitaeData
import com.naitae.connect.data.SeedData
import com.naitae.connect.domain.LetterPhase
import com.naitae.connect.ui.Screen
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.sim.ScenarioScript
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 시나리오의 곁길: 사용자가 기대와 다르게 누르거나, 흐름 도중에 차의 상태가 바뀌는 경우.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EdgeCaseE2eTest {

    private lateinit var h: E2eHarness

    @Before
    fun setUp() {
        h = E2eHarness()
    }

    @After
    fun tearDown() {
        h.close()
    }

    // ───────── 남기기 ─────────

    @Test
    fun `주제가 없는 처음 간 곳은 주제를 골라 남기고 여러 번 눌러도 한 번만 남는다`() = ScenarioWalks.firstVisitWithoutTopic(h)

    @Test
    fun `남길지 묻는 중에 답 없이 출발하면 나중에로 보고 다시 묻지 않는다`() {
        h.startDay(ScenarioDay.FISHING_SATURDAY)
        val records = h.data.records
        h.ignition(true)
        h.awaitScreen<Screen.RouteAsk>()
        // 길 안내 질문에도 답하지 않고 출발한다
        h.driveTo("낚시터")
        assertNull("답하지 않았으므로 길 안내는 시작한 적이 없음", h.console.state.value.guidanceTo)
        assertFalse(h.logged("NaviHelper.requestRoute"))
        h.restartAt(11, 40)
        h.screenAs<Screen.RecordAsk>()
        h.shift(GearState.DRIVE)
        h.screenAs<Screen.Home>()
        assertNull(h.ui.voiceLine)
        h.advance(3_000)
        h.shift(GearState.PARK)
        h.advance(3_000)
        h.screenAs<Screen.Home>()
        val mark = h.restartAt(12, 10)
        h.advance(5_000)
        assertEquals(listOf("Home"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))
        assertEquals(records, h.data.records)
    }

    // ───────── 홈으로 ─────────

    @Test
    fun `어느 화면에서든 홈으로 가면 그 화면의 닫기와 같은 뒷정리를 한다`() {
        // 브리핑 → 홈: 그만과 같다(브리핑은 한 것으로 기록)
        h.startDay(ScenarioDay.FISHING_SATURDAY)
        h.ignition(true)
        h.screenAs<Screen.Briefing>()
        h.tap { openHome() }
        // 길 안내 질문 → 홈: 괜찮아요와 같다
        h.screenAs<Screen.RouteAsk>()
        assertEquals(h.coordinator.today().toEpochDay(), h.data.lastBriefingEpochDay)
        h.tap { openHome() }
        h.screenAs<Screen.Home>()
        assertNull(h.ui.voiceLine)
        assertNull(h.console.state.value.guidanceTo)

        // 남길지 묻기 → 홈: 나중에와 같다
        h.driveTo("낚시터")
        h.restartAt(11, 40)
        h.screenAs<Screen.RecordAsk>()
        h.tap { openHome() }
        h.screenAs<Screen.Home>()
        assertNull(h.coordinator.tracker.pendingDraft)

        // 편지 도착 → 홈: 도착해서 보기와 같다. 편지 → 홈: 닫기와 같다(조명 되돌림, 열어 본 편지)
        h.startDay(ScenarioDay.BIRTHDAY)
        h.ignition(true)
        h.screenAs<Screen.LetterArrive>()
        h.tap { openHome() }
        h.screenAs<Screen.Home>()
        assertNull(h.data.letters.single { it.id == SeedData.LETTER_BIRTHDAY }.openedEpochDay)
        h.restartAt(7, 45)
        h.screenAs<Screen.LetterArrive>()
        h.tap { openLetter() }
        h.awaitThat("낭독 중") { (h.screen as? Screen.LetterOpen)?.phase == LetterPhase.READING }
        h.tap { openHome() }
        h.screenAs<Screen.Home>()
        h.advance(500)
        assertEquals(30, h.cabin.ambientBrightness)
        assertNotNull(h.data.letters.single { it.id == SeedData.LETTER_BIRTHDAY }.openedEpochDay)

        // 다시 보기 → 홈: 닫기와 같다
        h.startDay(ScenarioDay.ONE_YEAR_LATER)
        h.ignition(true)
        h.awaitScreen<Screen.RouteAsk>()
        h.driveTo("수목원")
        h.screenAs<Screen.RecallView>()
        h.advance(1_000)
        assertEquals(AmbientColor.PURPLE, h.cabin.ambientColor)
        h.tap { openHome() }
        h.screenAs<Screen.Home>()
        assertNull(h.ui.voiceLine)
        assertEquals(ScenarioWalks.USUAL_CABIN, h.cabin)
    }

    // ───────── 편지 ─────────

    @Test
    fun `편지 낭독 중에 시동을 끄면 낭독이 멈추고 조명을 되돌리며 열어 본 편지로 남는다`() {
        h.startDay(ScenarioDay.BIRTHDAY)
        h.ignition(true)
        h.tap { openLetter() }
        h.awaitThat("낭독 중") { (h.screen as? Screen.LetterOpen)?.phase == LetterPhase.READING }
        h.advance(1_000)
        h.ignition(false)
        assertEquals(Screen.EngineOff, h.screen)
        h.advance(3_000)
        assertEquals("조명만 편지 전으로", 30, h.cabin.ambientBrightness)
        assertEquals(3, h.cabin.seatHeatLevel)
        assertTrue(h.logged("TextToSpeech.stop()"))
        assertNotNull(h.data.letters.single { it.id == SeedData.LETTER_BIRTHDAY }.openedEpochDay)

        // 다음 시동은 편지 없이 그날의 브리핑으로 이어진다
        h.ignition(true)
        h.screenAs<Screen.Briefing>()
    }

    @Test
    fun `새 차의 편지 도중에 출발하면 조명을 되돌리고 이어받기 화면은 다음 시동에 보여 준다`() {
        h.startDay(ScenarioDay.TWENTY_YEARS_LATER)
        h.ignition(true)
        h.screenAs<Screen.LetterOpen>()
        h.advance(2_000)
        assertEquals(AmbientColor.PURPLE, h.cabin.ambientColor)

        h.shift(GearState.DRIVE)
        h.screenAs<Screen.Home>()
        h.advance(6_000)
        assertEquals("출발하면 조명은 원래대로", AmbientColor.WHITE, h.cabin.ambientColor)
        assertEquals(30, h.cabin.ambientBrightness)
        assertEquals("핸들 온기는 그대로", true, h.cabin.steeringHeat)
        assertEquals(SeedData.VIN_NEW_CAR, h.data.currentCar?.vin)

        h.shift(GearState.PARK)
        h.advance(2_000)
        h.restartAt(11, 40)
        val handover = h.screenAs<Screen.Handover>()
        assertEquals(20, handover.summary.ringCount)
        h.tap { dismissHandover() }
        h.screenAs<Screen.Home>()
        assertEquals(2, h.data.cars.size)
    }

    @Test
    fun `이어받기 화면을 떠나면 편지의 보랏빛을 편지 전 조명으로 되돌린다`() {
        // 나이테 보기로 닫을 때
        h.startDay(ScenarioDay.TWENTY_YEARS_LATER)
        h.ignition(true)
        h.awaitThat("낭독 끝") { (h.screen as? Screen.LetterOpen)?.phase == LetterPhase.DONE }
        h.tap { closeLetter() }
        h.screenAs<Screen.Handover>()
        h.advance(3_000)
        assertEquals("이어받기 화면에서는 보랏빛 그대로", AmbientColor.PURPLE, h.cabin.ambientColor)
        assertEquals(100, h.cabin.ambientBrightness)
        h.tap { dismissHandover() }
        h.screenAs<Screen.Home>()
        h.advance(500)
        assertEquals(AmbientColor.WHITE, h.cabin.ambientColor)
        assertEquals(30, h.cabin.ambientBrightness)
        assertEquals("핸들 온기는 그대로", true, h.cabin.steeringHeat)

        // 이어받기 화면에서 바로 출발할 때
        h.startDay(ScenarioDay.TWENTY_YEARS_LATER)
        h.ignition(true)
        h.awaitThat("낭독 끝") { (h.screen as? Screen.LetterOpen)?.phase == LetterPhase.DONE }
        h.tap { closeLetter() }
        h.screenAs<Screen.Handover>()
        h.shift(GearState.DRIVE)
        h.screenAs<Screen.Home>()
        h.advance(500)
        assertEquals(AmbientColor.WHITE, h.cabin.ambientColor)
        assertEquals(30, h.cabin.ambientBrightness)
        assertEquals(true, h.cabin.steeringHeat)
    }

    // ───────── 저장 ─────────

    @Test
    fun `바뀐 계정 원본은 앱 내부 파일에 그대로 저장된다`() {
        h.startDay(ScenarioDay.WEEKDAY)
        h.ignition(true)
        h.awaitScreen<Screen.Home>()
        val file = File(h.app.filesDir, "naitae.json")
        assertTrue(file.isFile)
        val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }
        val saved = json.decodeFromString(NaitaeData.serializer(), file.readText(Charsets.UTF_8))
        assertEquals(h.data, saved)
        assertTrue(saved.memos.any { it.text == "내일 세차 예약" })
    }

    // ───────── 발표 스크립트 ─────────

    @Test
    fun `발표 스크립트를 손대지 않고 끝까지 돌리면 날마다 기대한 화면에 이른다`() {
        val steps = ArrayList<Pair<String, Int>>()
        val job = h.container.scope.launch { ScenarioScript.play(h.console) { label -> steps += label to h.mark() } }
        h.awaitThat("시나리오 끝", 600_000) { job.isCompleted }
        h.settle()

        // 단계의 끝에 넣은 시동 끔은 다음 단계의 첫 장면으로 잡힌다(단계 이름을 먼저 알리고 화면이 뒤따르므로).
        fun screensOf(labelPart: String): List<String> {
            val index = steps.indexOfFirst { it.first.contains(labelPart) }
            assertTrue("단계 없음: $labelPart (${steps.map { it.first }})", index >= 0)
            val from = steps[index].second
            val to = steps.getOrNull(index + 1)?.second ?: h.history.size
            return h.history.subList(from, to).map { it.screen.kind }.squeezed()
        }

        // 평일
        assertEquals(listOf("Briefing", "Home"), screensOf("오늘 일정과 메모 브리핑"))
        assertEquals(listOf("Home"), screensOf("회사 도착"))
        assertEquals(listOf("EngineOff", "Home"), screensOf("퇴근길 18:40"))
        // 토요일
        assertEquals(listOf("Briefing", "RouteAsk"), screensOf("낚시터로 안내할까요").filter { it != "Home" })
        assertEquals(listOf("Home"), screensOf("낚시터 도착"))
        assertEquals(listOf("EngineOff", "RecordAsk"), screensOf("11:40 돌아가는 길"))
        // 일요일
        assertEquals(listOf("Briefing", "RouteAsk"), screensOf("수목원으로 안내할까요").filter { it != "Home" })
        assertEquals(listOf("EngineOff", "RecordAsk"), screensOf("16:30 돌아가는 길"))
        // 1년 뒤
        assertEquals(listOf("Home", "RecallView"), screensOf("수목원에 다시 도착"))
        // 생일
        assertEquals(listOf("LetterArrive"), screensOf("미래 편지가 도착"))
        // 20년 뒤
        assertEquals(listOf("LetterOpen"), screensOf("새 차의 첫 시동"))
        assertEquals(LetterPhase.DONE, h.screenAs<Screen.LetterOpen>().phase)
        assertEquals("시나리오 끝", steps.last().first)
    }
}
