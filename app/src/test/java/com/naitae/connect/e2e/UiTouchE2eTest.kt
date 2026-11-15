package com.naitae.connect.e2e

import com.naitae.connect.core.model.AmbientColor
import com.naitae.connect.data.SeedData
import com.naitae.connect.ui.Screen
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.ScenarioDay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 실제 화면(MainActivity)에 터치 이벤트를 넣어 본다: 가상 콘솔의 버튼과 차량 화면의 버튼이 정말로
 * SimulatorConsole · NaitaeActions 에 이어져 있는지, 주행 중 잠금이 터치를 막는지 확인한다.
 * 누를 곳은 화면에 보이는 글자로 찾는다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTouchE2eTest {

    private lateinit var h: E2eHarness

    @Before
    fun setUp() {
        assumeTrue(!System.getProperty(E2eHarness.SCREENSHOT_DIR_PROPERTY).isNullOrBlank())
        h = E2eHarness(withUi = true)
    }

    @After
    fun tearDown() {
        if (::h.isInitialized) h.close()
    }

    @Test
    fun `콘솔과 화면의 버튼을 눌러 브리핑을 멈추고 주제를 고르며 주행 중에는 눌리지 않는다`() {
        h.startDay(ScenarioDay.WEEKDAY)
        h.touchText("시동")
        assertTrue(h.console.state.value.ignitionOn)
        h.screenAs<Screen.Briefing>()
        h.advance(3_000)

        h.touchText("그만")
        h.screenAs<Screen.Home>()
        assertNull(h.ui.voiceLine)

        h.touchText("골프")
        assertEquals(SeedData.TOPIC_GOLF, h.screenAs<Screen.Home>().selected?.topic?.id)

        // 주행 중: 같은 자리를 눌러도 바뀌지 않는다
        h.touchText("기어 D")
        assertEquals(GearState.DRIVE, h.gear)
        assertTrue(h.ui.driving)
        h.touchText("낚시")
        h.touchText("최근 장면")
        assertEquals(SeedData.TOPIC_GOLF, h.screenAs<Screen.Home>().selected?.topic?.id)

        // P 로 돌아오면 다시 눌린다
        h.touchText("기어 P")
        assertFalse(h.ui.driving)
        h.touchText("낚시")
        assertEquals(SeedData.TOPIC_FISH, h.screenAs<Screen.Home>().selected?.topic?.id)

        // 최근 장면을 누르면 그 장면의 꾸미기가 열리고, 색을 누르면 실내 조명이 바뀐다
        val latest = h.screenAs<Screen.Home>().selected?.latest
        h.touchText("최근 장면")
        assertEquals(latest?.id, h.screenAs<Screen.Decorate>().record.id)
        h.touchText(AmbientColor.CYAN.label)
        assertEquals(AmbientColor.CYAN, h.cabin.ambientColor)
        h.touchText("완료")
        h.screenAs<Screen.Home>()
        assertEquals(ScenarioWalks.USUAL_CABIN, h.cabin)

        h.touchText("시동 끄기")
        assertEquals(Screen.EngineOff, h.screen)
    }

    @Test
    fun `토요일을 화면의 버튼만 눌러 끝까지 지난다`() {
        h.startDay(ScenarioDay.WEEKDAY)
        h.touchText(ScenarioDay.FISHING_SATURDAY.label)
        h.advance(E2eHarness.DAY_SETTLE_MS)
        assertEquals(ScenarioDay.FISHING_SATURDAY, h.console.state.value.day)
        assertEquals(SeedData.forDay(ScenarioDay.FISHING_SATURDAY), h.data)
        val recordsBefore = h.data.records.size

        h.touchText("시동")
        h.awaitScreen<Screen.RouteAsk>()
        // "안내해 줘" 는 콘솔의 '다음에 할 말'에도 있다. 왼쪽(차량 화면)의 버튼을 누른다.
        h.touchText("안내해 줘")
        h.screenAs<Screen.Home>()
        assertEquals("낚시터", h.console.state.value.guidanceTo?.name)

        // 콘솔의 목적지 버튼 → 달려서 도착
        h.touchText("낚시터")
        h.awaitThat("도착 뒤 P", 10_000) { h.gear == GearState.PARK && h.logged("onDestinationArrived(낚시터)") }
        h.advance(2_000)
        h.screenAs<Screen.Home>()

        h.touchText("시동 끄기")
        h.advance(1_000)
        h.touchText("+1시간")
        h.advance(1_000)
        h.touchText("시동")
        h.screenAs<Screen.RecordAsk>()
        h.touchText("남기기")
        h.screenAs<Screen.RecordEdit>()
        assertEquals(recordsBefore + 1, h.data.records.size)

        h.touchText("한마디 남기기")
        h.awaitThat("한마디") { (h.screen as? Screen.RecordEdit)?.record?.notes?.isNotEmpty() == true }
        h.advance(300)
        assertEquals("올해 첫 감성돔", h.screenAs<Screen.RecordEdit>().record.notes.single().text)

        h.touchText("사진 · 꾸미기")
        h.screenAs<Screen.Decorate>()
        h.touchText("+ 사진 (휴대폰 · QR)")
        assertEquals(1, h.screenAs<Screen.Decorate>().record.photos.size)
        h.touchText(AmbientColor.BLUE.label)
        assertEquals(AmbientColor.BLUE, h.cabin.ambientColor)
        h.touchText("완료")
        h.screenAs<Screen.RecordEdit>()
        assertEquals(ScenarioWalks.USUAL_CABIN, h.cabin)
        h.touchText("완료")
        assertEquals(SeedData.TOPIC_FISH, h.screenAs<Screen.Home>().selected?.topic?.id)
    }

    @Test
    fun `손잡이를 누르면 가상 콘솔이 접히고 다시 펴진다`() {
        h.startDay(ScenarioDay.WEEKDAY)
        assertTrue(h.findNodes("가상 콘솔").isNotEmpty())
        h.touchText("›")
        assertTrue(h.findNodes("가상 콘솔").isEmpty())
        h.touchText("‹")
        assertTrue(h.findNodes("가상 콘솔").isNotEmpty())
        // 접었다 펴도 콘솔은 그대로 움직인다
        h.touchText("시동")
        h.screenAs<Screen.Briefing>()
    }
}
