package com.naitae.connect.e2e

import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 실제 MainActivity(NaitaeAppScreen = 차량 화면 + 가상 콘솔)를 1920×1080 가로 화면으로 띄운 채 같은 시나리오를 걷고,
 * 지나가는 화면마다 PNG 를 docs/screenshots 에 남긴다. 화면이 실제로 그려지므로 Compose 쪽의 실행 중 오류도 여기서 걸린다.
 *
 * 저장 위치는 시스템 속성 naitae.screenshot.dir (app/build.gradle.kts 가 testDebugUnitTest 에만 넣는다).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
open class ScreenshotE2eTest {

    private lateinit var h: E2eHarness

    internal open fun newHarness(): E2eHarness = E2eHarness(withUi = true)

    @Before
    fun setUp() {
        assumeTrue(
            "화면 캡처 위치(naitae.screenshot.dir)가 없으면 건너뜀",
            !System.getProperty(E2eHarness.SCREENSHOT_DIR_PROPERTY).isNullOrBlank(),
        )
        h = newHarness()
    }

    @After
    fun tearDown() {
        if (::h.isInitialized) h.close()
    }

    @Test
    fun `평일 화면`() = ScenarioWalks.weekday(h)

    @Test
    fun `토요일 화면`() = ScenarioWalks.fishingSaturday(h)

    @Test
    fun `일요일 화면`() = ScenarioWalks.familySunday(h)

    @Test
    fun `1년 뒤 화면`() = ScenarioWalks.oneYearLater(h)

    @Test
    fun `생일 아침 화면`() = ScenarioWalks.birthday(h)

    @Test
    fun `20년 뒤 화면`() = ScenarioWalks.twentyYearsLater(h)

    @Test
    fun `주제를 고르는 남기기 화면`() = ScenarioWalks.firstVisitWithoutTopic(h)
}

/**
 * 같은 시나리오를 가상 콘솔을 접은 채로 걷는다. 차량 화면이 1920×1080 전체를 쓰는, 실제 차(콘솔 없음)와 같은 배치다.
 * 캡처는 docs/screenshots/fullwidth 에 같은 이름으로 남긴다.
 */
class FullWidthScreenshotE2eTest : ScreenshotE2eTest() {
    override fun newHarness(): E2eHarness = E2eHarness(withUi = true, foldConsole = true, shotFolder = "fullwidth")
}
