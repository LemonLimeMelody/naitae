package com.naitae.connect.e2e

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.data.SeedData
import com.naitae.connect.domain.LetterPhase
import com.naitae.connect.ui.Screen
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.ScenarioDay
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

/**
 * 시나리오를 가로지르는 공통 원칙: 주행 중에는 입력을 받지 않고 긴 흐름이 멈춘다, 끊긴 흐름의 자막·듣는 중 표시가 남지 않는다,
 * 가상 콘솔에서 날짜를 바꾸면 데이터와 상태가 처음으로 돌아간다. (걸음마다의 공통 확인은 E2eHarness.checkInvariants)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossCuttingE2eTest {

    private lateinit var h: E2eHarness

    @Before
    fun setUp() {
        h = E2eHarness()
    }

    @After
    fun tearDown() {
        h.close()
    }

    private fun briefingIndex(): Int? = (h.screen as? Screen.Briefing)?.activeIndex

    // ───────── 주행 중 ─────────

    @Test
    fun `브리핑을 읽는 중에 출발하면 낭독이 멈추고 홈만 보이며 입력을 받지 않는다`() {
        h.startDay(ScenarioDay.WEEKDAY)
        val today = h.coordinator.today().toEpochDay()
        h.ignition(true)
        h.awaitThat("두 번째 줄을 읽는 중") { briefingIndex() == 1 }
        assertNotNull(h.ui.voiceLine)

        var mark = h.mark()
        h.shift(GearState.DRIVE)
        assertTrue(h.ui.driving)
        assertEquals(GearState.DRIVE, h.ui.gear)
        val home = h.screenAs<Screen.Home>()
        assertNull("출발하면 자막이 바로 사라짐", h.ui.voiceLine)
        h.advance(15_000)
        assertEquals("멈춘 브리핑이 뒤에서 이어지지 않음", listOf("Home"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))
        assertEquals("출발해도 오늘 브리핑은 한 것으로 본다", today, h.data.lastBriefingEpochDay)
        assertEquals("듣기까지 가지 못했으므로 메모는 그대로", SeedData.forDay(ScenarioDay.WEEKDAY).memos.size, h.data.memos.size)

        // 주행 중의 화면 입력은 모두 무시된다
        val other = home.topics.first { it.id != home.selected?.topic?.id }
        val anyRecord = h.data.records.last()
        h.tap { selectTopic(other.id) }
        h.tap { openDecorateFor(anyRecord.id) }
        h.tap { openHome() }
        h.tap { startVoiceNote() }
        assertEquals(home.selected?.topic?.id, h.screenAs<Screen.Home>().selected?.topic?.id)
        assertEquals(listOf("Home"), h.screensSince(mark))

        // R · N 도 주행으로 본다
        h.shift(GearState.REVERSE)
        assertTrue(h.ui.driving)
        h.shift(GearState.NEUTRAL)
        assertTrue(h.ui.driving)

        // 다시 P: 입력을 받지만, 끝난 브리핑을 다시 시작하지는 않는다
        mark = h.mark()
        h.shift(GearState.PARK)
        assertFalse(h.ui.driving)
        h.advance(8_000)
        assertEquals(listOf("Home"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))
        h.tap { selectTopic(other.id) }
        assertEquals(other.id, h.screenAs<Screen.Home>().selected?.topic?.id)
    }

    @Test
    fun `메모를 듣는 중에 출발하면 듣기가 끊기고 메모를 남기지 않는다`() {
        h.startDay(ScenarioDay.WEEKDAY)
        val memos = h.data.memos
        h.ignition(true)
        h.awaitThat("듣는 중") { (h.screen as? Screen.Briefing)?.listening == true }
        h.advance(600)
        assertTrue("받아 적는 중", h.screenAs<Screen.Briefing>().heardText.isNotEmpty())

        val mark = h.mark()
        h.shift(GearState.DRIVE)
        h.screenAs<Screen.Home>()
        assertNull(h.ui.voiceLine)
        h.advance(12_000)
        assertEquals(listOf("Home"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))
        assertEquals("끊긴 듣기의 말은 메모가 되지 않음", memos.map { it.id }, h.data.memos.map { it.id })
        assertTrue(h.logged("SpeechToText.stop()"))
    }

    @Test
    fun `한마디를 듣는 중에 출발하거나 시동을 꺼도 듣는 중 표시와 자막이 남지 않는다`() {
        h.startDay(ScenarioDay.FISHING_SATURDAY)
        h.ignition(true)
        h.awaitScreen<Screen.RouteAsk>()
        h.tap { declineRoute() }
        h.screenAs<Screen.Home>()
        assertNull("나중에를 고르면 질문 낭독도 멈춤", h.ui.voiceLine)
        assertNull(h.console.state.value.guidanceTo)
        h.driveTo("낚시터")
        h.restartAt(11, 40)
        h.screenAs<Screen.RecordAsk>()
        h.tap { acceptRecord(SeedData.TOPIC_FISH) }
        val recordId = h.screenAs<Screen.RecordEdit>().record.id
        assertNull("남기기로 하면 질문 낭독이 멈춤", h.ui.voiceLine)

        // 듣는 중에 출발
        h.tap { startVoiceNote() }
        h.advance(900)
        val listening = h.screenAs<Screen.RecordEdit>()
        assertTrue(listening.listening)
        assertTrue(listening.heardText.isNotEmpty())
        h.shift(GearState.DRIVE)
        h.screenAs<Screen.Home>()
        h.advance(8_000)
        assertTrue("끊긴 한마디는 붙지 않음", h.data.records.first { it.id == recordId }.notes.isEmpty())
        assertEquals("남긴 기록의 주제가 홈에 보임", SeedData.TOPIC_FISH, h.screenAs<Screen.Home>().selected?.topic?.id)

        // P 로 돌아와 꾸미기에서 다시 열고, 색을 입힌 채 시동을 끈다
        h.shift(GearState.PARK)
        h.tap { openDecorateFor(recordId) }
        h.screenAs<Screen.Decorate>()
        h.tap { pickColor(AmbientColor.CYAN) }
        assertEquals(AmbientColor.CYAN, h.cabin.ambientColor)
        h.ignition(false)
        assertEquals(Screen.EngineOff, h.screen)
        assertEquals("시동을 끄면 미리보기 조명을 되돌림", ScenarioWalks.USUAL_CABIN, h.cabin)
        h.advance(5_000)
        assertNull(h.ui.voiceLine)

        // 이미 남긴 곳이므로 다시 묻지 않는다
        h.ignition(true)
        h.advance(3_000)
        assertFalse(h.screen is Screen.RecordAsk)
    }

    @Test
    fun `홈의 장면에서 연 꾸미기는 홈으로 돌아오고 조명을 되돌린다`() {
        h.startDay(ScenarioDay.ONE_YEAR_LATER)
        h.ignition(true)
        h.awaitScreen<Screen.RouteAsk>()
        h.tap { declineRoute() }
        val record = h.data.records.single { it.placeName == "수목원" }
        h.tap { selectTopic(SeedData.TOPIC_FAMILY) }
        h.tap { openDecorateFor(record.id) }
        assertEquals(record.id, h.screenAs<Screen.Decorate>().record.id)
        h.tap { pickColor(AmbientColor.BLUE) }
        h.tap { pickColor(AmbientColor.CYAN) }
        assertEquals(AmbientColor.CYAN, h.cabin.ambientColor)
        assertEquals(AmbientColor.CYAN, h.data.records.first { it.id == record.id }.color)
        h.tap { finishDecorate() }
        assertEquals("색을 여러 번 골라도 처음 조명으로 돌아감", ScenarioWalks.USUAL_CABIN, h.cabin)
        assertEquals(SeedData.TOPIC_FAMILY, h.screenAs<Screen.Home>().selected?.topic?.id)
    }

    // ───────── 끊긴 흐름 ─────────

    @Test
    fun `브리핑 그만을 누르면 자막이 바로 사라지고 남은 단계(길 안내 질문)로 넘어간다`() {
        h.startDay(ScenarioDay.FISHING_SATURDAY)
        val today = h.coordinator.today().toEpochDay()
        h.ignition(true)
        h.awaitThat("첫 줄을 읽는 중") { briefingIndex() == 0 }
        val mark = h.mark()
        h.tap { stopBriefing() }
        val ask = h.screenAs<Screen.RouteAsk>()
        assertEquals("낚시터", ask.schedule.place?.name)
        assertEquals("오늘은 낚시예요. 낚시터로 안내할까요?", h.ui.voiceLine)
        assertEquals(today, h.data.lastBriefingEpochDay)
        h.advance(12_000)
        assertEquals(listOf("오늘은 낚시예요. 낚시터로 안내할까요?"), h.voiceLinesSince(mark))
        assertEquals(listOf("RouteAsk"), h.screensSince(mark).filter { it != "Home" })
        assertNull(h.ui.voiceLine)
        assertNull("묻기만 하고 기다림", h.console.state.value.guidanceTo)
    }

    @Test
    fun `브리핑 도중 시동을 끄면 마친 것으로 치지 않고 다음 시동에 처음부터 다시 읽는다`() {
        h.startDay(ScenarioDay.WEEKDAY)
        h.ignition(true)
        h.awaitThat("세 번째 줄을 읽는 중") { briefingIndex() == 2 }
        h.ignition(false)
        assertEquals(Screen.EngineOff, h.screen)
        assertNull(h.ui.voiceLine)
        h.advance(10_000)
        assertEquals("꺼진 뒤에는 아무것도 읽지 않음", Screen.EngineOff, h.screen)
        assertNull(h.data.lastBriefingEpochDay)

        h.ignition(true)
        val again = h.screenAs<Screen.Briefing>()
        assertEquals(-1, again.activeIndex)
        assertEquals(5, again.briefing.items.size)
        assertEquals("좋은 아침이에요.", h.ui.voiceLine)
    }

    @Test
    fun `편지 연출 도중에 닫으면 조명 올리기가 멈추고 편지 전 조명으로 돌아간다`() {
        h.startDay(ScenarioDay.BIRTHDAY)
        h.ignition(true)
        h.screenAs<Screen.LetterArrive>()
        h.tap { openLetter() }
        h.advance(1_500)
        val open = h.screenAs<Screen.LetterOpen>()
        assertEquals(LetterPhase.WARMING, open.phase)
        assertTrue("밝아지는 중", (h.cabin.ambientBrightness ?: 0) in 30..70)

        val mark = h.mark()
        h.tap { closeLetter() }
        h.screenAs<Screen.Home>()
        h.advance(8_000)
        assertEquals(AmbientColor.WHITE, h.cabin.ambientColor)
        assertEquals("닫은 뒤에는 조명이 더 오르지 않고 원래 밝기", 30, h.cabin.ambientBrightness)
        assertEquals("온기는 그대로", 3, h.cabin.seatHeatLevel)
        assertEquals(true, h.cabin.steeringHeat)
        assertEquals(listOf("Home"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))
        assertFalse("닫은 편지는 읽히지 않음", h.logged("TextToSpeech.speak(\"아빠, 생일 축하해"))
        assertNotNull(h.data.letters.single { it.id == SeedData.LETTER_BIRTHDAY }.openedEpochDay)
    }

    @Test
    fun `편지를 도착해서 보기로 미루면 도착 뒤 P 에서 다시 보여 준다`() {
        h.startDay(ScenarioDay.BIRTHDAY)
        h.ignition(true)
        h.screenAs<Screen.LetterArrive>()
        h.tap { postponeLetter() }
        h.screenAs<Screen.Home>()
        assertNull(h.ui.voiceLine)
        assertEquals(ScenarioWalks.USUAL_CABIN, h.cabin)
        assertNull(h.data.letters.single { it.id == SeedData.LETTER_BIRTHDAY }.openedEpochDay)

        h.driveTo("회사")
        val arrive = h.screenAs<Screen.LetterArrive>()
        assertEquals(SeedData.LETTER_BIRTHDAY, arrive.letter.id)
        h.tap { openLetter() }
        h.awaitThat("낭독 끝") { (h.screen as? Screen.LetterOpen)?.phase == LetterPhase.DONE }
        h.tap { closeLetter() }
        h.screenAs<Screen.Home>()
        h.advance(500)
        assertEquals("닫으면 조명만 원래대로", 30, h.cabin.ambientBrightness)
        assertEquals(3, h.cabin.seatHeatLevel)
    }

    @Test
    fun `새 차에 다른 사람이 함께 타면 편지를 바로 열지 않고 먼저 묻는다`() {
        h.startDay(ScenarioDay.TWENTY_YEARS_LATER)
        h.console.setSeat(1, true)
        h.settle()
        h.ignition(true)
        val arrive = h.screenAs<Screen.LetterArrive>()
        assertEquals(SeedData.LETTER_NEW_CAR, arrive.letter.id)
        assertEquals("이어받기는 이미 끝남", SeedData.VIN_NEW_CAR, h.data.currentCar?.vin)
        assertEquals("묻는 동안에는 실내를 건드리지 않음", ScenarioWalks.USUAL_CABIN, h.cabin)
        h.tap { openLetter() }
        assertNotNull(h.screenAs<Screen.LetterOpen>().rings)
        h.awaitThat("낭독 끝") { (h.screen as? Screen.LetterOpen)?.phase == LetterPhase.DONE }
        h.tap { closeLetter() }
        assertEquals(20, h.screenAs<Screen.Handover>().summary.ringCount)
    }

    @Test
    fun `남기기를 나중으로 미루면 기록하지 않고 다시 묻지 않는다`() {
        h.startDay(ScenarioDay.FISHING_SATURDAY)
        val records = h.data.records
        h.ignition(true)
        h.awaitScreen<Screen.RouteAsk>()
        h.tap { acceptRoute() }
        h.driveTo("낚시터")
        h.restartAt(11, 40)
        h.screenAs<Screen.RecordAsk>()
        h.tap { declineRecord() }
        h.screenAs<Screen.Home>()
        assertNull(h.ui.voiceLine)
        assertEquals(records, h.data.records)
        h.restartAt(11, 50)
        h.advance(3_000)
        h.screenAs<Screen.Home>()
        assertEquals(records, h.data.records)
    }

    // ───────── 가상 콘솔: 날짜 바꾸기 ─────────

    @Test
    fun `같은 날짜를 다시 고르면 데이터와 상태가 그날 아침으로 돌아간다`() {
        h.startDay(ScenarioDay.WEEKDAY)
        h.ignition(true)
        h.awaitScreen<Screen.Home>()
        h.driveTo("회사")
        h.restartAt(18, 40)
        h.awaitThat("메모 카드") { h.ui.memoToast != null }
        assertTrue("하루를 보내며 데이터가 달라짐", h.data != SeedData.forDay(ScenarioDay.WEEKDAY))

        // 메모를 읽는 중에 같은 날짜를 다시 고른다
        val mark = h.mark()
        h.startDay(ScenarioDay.WEEKDAY)
        assertNull(h.ui.memoToast)
        assertNull(h.ui.voiceLine)
        assertFalse(h.console.state.value.ignitionOn)
        assertEquals("07:42", h.ui.clockLabel)
        assertEquals(12_800f, h.ui.odometerKm)
        h.advance(10_000)
        assertEquals(listOf("EngineOff"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))

        // 처음부터: 브리핑을 다시 하고, 메모도 다시 받는다
        h.ignition(true)
        assertEquals(5, h.screenAs<Screen.Briefing>().briefing.items.size)
        h.awaitScreen<Screen.Home>()
        assertEquals(listOf("내일 세차 예약"), h.data.memos.filter { it.id != SeedData.MEMO_MILK }.map { it.text })
    }

    @Test
    fun `흐름이 도는 중에 다른 날짜를 고르면 흐름이 멈추고 그날의 데이터로 바뀐다`() {
        // 낚시터에 도착해 기록 초안이 준비된 채로
        h.startDay(ScenarioDay.FISHING_SATURDAY)
        h.ignition(true)
        h.awaitScreen<Screen.RouteAsk>()
        h.tap { acceptRoute() }
        h.driveTo("낚시터")
        assertNotNull(h.coordinator.tracker.pendingDraft)

        // 생일 아침으로: 초안과 길 안내는 사라지고 편지가 도착한다
        h.startDay(ScenarioDay.BIRTHDAY)
        assertNull(h.coordinator.tracker.pendingDraft)
        assertNull(h.console.state.value.guidanceTo)
        h.ignition(true)
        h.screenAs<Screen.LetterArrive>()
        h.tap { openLetter() }
        h.advance(1_000)
        assertEquals(LetterPhase.WARMING, h.screenAs<Screen.LetterOpen>().phase)

        // 연출 도중에 일요일로: 연출이 멈추고, 편지는 열지 않은 채 데이터가 통째로 바뀐다
        var mark = h.mark()
        h.startDay(ScenarioDay.FAMILY_SUNDAY)
        h.advance(8_000)
        assertEquals(listOf("EngineOff"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))
        assertEquals(SeedData.forDay(ScenarioDay.FAMILY_SUNDAY), h.data)
        assertEquals(4, h.console.state.value.seats.count)

        // 브리핑 도중에 20년 뒤로
        h.ignition(true)
        assertEquals("시동을 걸면 실내는 평소 설정", ScenarioWalks.USUAL_CABIN, h.cabin)
        h.screenAs<Screen.Briefing>()
        h.advance(3_000)
        mark = h.mark()
        h.startDay(ScenarioDay.TWENTY_YEARS_LATER)
        h.advance(8_000)
        assertEquals(listOf("EngineOff"), h.screensSince(mark))
        assertEquals(emptyList<String>(), h.voiceLinesSince(mark))
        assertEquals(SeedData.forDay(ScenarioDay.TWENTY_YEARS_LATER), h.data)
        assertEquals(SeedData.VIN_NEW_CAR, h.console.state.value.vin)

        // 새 차의 첫 시동은 처음부터 그대로 돈다
        h.ignition(true)
        assertEquals(SeedData.LETTER_NEW_CAR, h.screenAs<Screen.LetterOpen>().letter.id)

        // 편지 도중에 평일로 돌아와도 이어받기·편지의 흔적이 남지 않는다
        h.advance(2_000)
        h.startDay(ScenarioDay.WEEKDAY)
        h.ignition(true)
        assertEquals(ScenarioWalks.USUAL_CABIN, h.cabin)
        assertEquals("그랜저", h.ui.carLabel)
        assertEquals(5, h.screenAs<Screen.Briefing>().briefing.items.size)
    }
}
