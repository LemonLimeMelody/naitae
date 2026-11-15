package com.naitae.connect.vehicle.demo

import com.naitae.connect.FakeCar
import com.naitae.connect.FakeVehicle
import com.naitae.connect.data.SeedData
import com.naitae.connect.vehicle.GearState
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.PlatformKind
import com.naitae.connect.vehicle.ProximityEvent
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SimulatorConsole
import com.naitae.connect.vehicle.VehiclePlatform
import com.naitae.connect.vehicle.VoiceResult
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Pleos 모드의 시연 층: 실제 플랫폼은 그대로 두고 날짜·도착·말·근접만 채운다. */
class DemoPlatformTest {

    private class Rig(test: TestScope, car: FakeCar = FakeCar(), resume: DemoPlatform.Resume? = null) {
        val vehicle = FakeVehicle(test.backgroundScope, { test.testScheduler.currentTime }, car)
        val clockSets = ArrayList<Pair<ScenarioDay, LocalDateTime>>()
        val platform: VehiclePlatform = DemoPlatform.wrap(
            real = vehicle.platform,
            scope = test.backgroundScope,
            resume = resume,
            monotonicNanos = { test.testScheduler.currentTime * 1_000_000 },
            onClockSet = { day, at -> clockSets += day to at },
        )
        val console: SimulatorConsole = checkNotNull(platform.console)
    }

    @Test
    fun `실제 플랫폼의 종류와 신호 실내 음성 출력은 그대로다`() = runTest {
        val rig = Rig(this)

        assertEquals(PlatformKind.PLEOS, rig.platform.kind)
        assertSame(rig.vehicle.cabin, rig.platform.cabin)
        assertSame(rig.vehicle.voiceOut, rig.platform.voiceOut)
        assertSame(rig.vehicle.signals.gear, rig.platform.signals.gear)
        assertSame(rig.vehicle.signals.ignitionOn, rig.platform.signals.ignitionOn)
        assertFalse("시연 패널은 차의 신호를 넣지 않는다", rig.console.controlsVehicle)
    }

    @Test
    fun `시계는 시나리오 날짜에서 시작해 흐르고 날짜와 시각을 바꿀 수 있다`() = runTest {
        val rig = Rig(this)
        val start = SeedData.dateTimeOf(ScenarioDay.WEEKDAY)

        assertEquals("기기 시각이 아니라 시나리오의 첫날", start, rig.platform.clock.now())
        advanceTimeBy(90_000)
        assertEquals(start.plusSeconds(90), rig.platform.clock.now())

        rig.console.setClock(18, 40)
        assertEquals(start.toLocalDate().atTime(18, 40), rig.platform.clock.now())
        assertEquals(start.toLocalDate().atTime(18, 40), rig.console.state.value.now)

        rig.console.setDay(ScenarioDay.BIRTHDAY)
        assertEquals(SeedData.dateTimeOf(ScenarioDay.BIRTHDAY), rig.platform.clock.now())
        assertEquals(ScenarioDay.BIRTHDAY, rig.console.state.value.day)

        // 정할 때마다(처음 포함) 알려 주어 다음 실행이 이어 갈 수 있다.
        assertEquals(
            listOf(
                ScenarioDay.WEEKDAY to start,
                ScenarioDay.WEEKDAY to start.toLocalDate().atTime(18, 40),
                ScenarioDay.BIRTHDAY to SeedData.dateTimeOf(ScenarioDay.BIRTHDAY),
            ),
            rig.clockSets,
        )
    }

    @Test
    fun `지난 실행의 날짜와 시각에서 이어 간다`() = runTest {
        val at = LocalDateTime.of(2026, 11, 14, 11, 40)
        val rig = Rig(this, resume = DemoPlatform.Resume(ScenarioDay.FISHING_SATURDAY, at))

        assertEquals(at, rig.platform.clock.now())
        assertEquals(ScenarioDay.FISHING_SATURDAY, rig.console.state.value.day)
        assertTrue(rig.clockSets.isEmpty())
    }

    @Test
    fun `도착을 넣으면 그 장소의 거리로 도착 이벤트가 오고 기어는 그대로다`() = runTest {
        val rig = Rig(this)
        val events = ArrayList<NaviEvent>()
        backgroundScope.launch { rig.platform.navi.events.collect { events += it } }
        rig.vehicle.signals.start()
        rig.vehicle.shift(GearState.DRIVE)
        runCurrent()

        rig.console.arrive(SeedData.PIER)
        // 실제 내비가 보낸 이벤트도 같은 흐름으로 온다.
        rig.vehicle.navi.emit(NaviEvent.GuidanceStopped)
        runCurrent()

        val arrived = events.first() as NaviEvent.Arrived
        assertEquals(SeedData.PIER.name, arrived.name)
        assertEquals(32f, arrived.distanceKm)
        assertEquals(SeedData.PIER.lat, arrived.lat)
        assertEquals(NaviEvent.GuidanceStopped, events.last())
        assertEquals(GearState.DRIVE, rig.vehicle.car.gear)
        assertEquals(GearState.DRIVE, rig.platform.signals.gear.value)
    }

    @Test
    fun `넣어 둔 말이 다음 듣기의 답이 되고 없으면 실제 STT 로 듣는다`() = runTest {
        val rig = Rig(this)

        rig.console.sayNext("내일 세차 예약")
        assertEquals(VoiceResult.Text("내일 세차 예약"), rig.platform.voiceIn.listen())
        assertEquals("마이크를 열지 않았다", 0, rig.vehicle.voiceIn.listens)
        assertEquals("받아 적은 글이 보인다", "내일 세차 예약", rig.platform.voiceIn.partial.value)

        rig.vehicle.voiceIn.answers += "마이크로 한 말"
        assertEquals(VoiceResult.Text("마이크로 한 말"), rig.platform.voiceIn.listen())
        assertEquals(1, rig.vehicle.voiceIn.listens)
    }

    @Test
    fun `실제 STT 가 듣는 도중에 넣은 말도 그 듣기의 답이 된다`() = runTest {
        val rig = Rig(this)

        val result = async { rig.platform.voiceIn.listen() }
        runCurrent()
        assertEquals(1, rig.vehicle.voiceIn.listens)
        rig.console.sayNext("올해 첫 감성돔")

        assertEquals(VoiceResult.Text("올해 첫 감성돔"), result.await())
        assertEquals("실제 듣기는 닫았다", 1, rig.vehicle.voiceIn.cancels)
    }

    @Test
    fun `걸어 둔 장소에 도착을 넣으면 근접 알림도 온다`() = runTest {
        val rig = Rig(this)
        val events = ArrayList<ProximityEvent>()
        backgroundScope.launch { rig.platform.proximity.events.collect { events += it } }
        runCurrent()

        assertTrue("에뮬레이터에 위치 서비스가 없어도 쓸 수 있다", rig.platform.proximity.isSupported())
        assertTrue(rig.platform.proximity.watch(SeedData.MART, 300).isSuccess)
        rig.console.arrive(SeedData.OFFICE)
        rig.console.arrive(SeedData.MART)
        rig.vehicle.proximity.emit(ProximityEvent("실제 장소", entered = true))
        runCurrent()

        assertEquals(listOf(ProximityEvent("마트", true), ProximityEvent("실제 장소", true)), events)
    }

    @Test
    fun `패널의 값은 실제 차량 신호를 비추고 시동 기어 좌석 버튼은 차를 바꾸지 않는다`() = runTest {
        val rig = Rig(this)
        rig.vehicle.signals.start()
        rig.vehicle.shift(GearState.DRIVE)
        rig.vehicle.odometer(12_850f)
        rig.vehicle.passengers(3)
        runCurrent()

        val shown = rig.console.state.value
        assertEquals(GearState.DRIVE, shown.gear)
        assertEquals(12_850f, shown.odometerKm)
        assertEquals(4, shown.seats.count)
        assertEquals(rig.vehicle.car.vin, shown.vin)
        assertFalse("운전석이 비어 있다", shown.ignitionOn)

        rig.console.ignition(true)
        rig.console.gear(GearState.PARK)
        rig.console.setSeat(1, false)
        runCurrent()

        assertEquals(GearState.DRIVE, rig.vehicle.car.gear)
        assertEquals(3, rig.vehicle.car.passengers)
        assertEquals(shown.copy(now = rig.console.state.value.now), rig.console.state.value)
    }

    @Test
    fun `차가 VIN 을 알려 주지 않으면 새 차의 날에만 정해 둔 새 차 VIN 을 쓴다`() = runTest {
        val unknown = Rig(this, FakeCar(vin = null))
        assertNull(unknown.platform.signals.vin())
        unknown.console.setDay(ScenarioDay.TWENTY_YEARS_LATER)
        assertEquals(SeedData.VIN_NEW_CAR, unknown.platform.signals.vin())

        val known = Rig(this)
        known.console.setDay(ScenarioDay.TWENTY_YEARS_LATER)
        assertEquals(known.vehicle.car.vin, known.platform.signals.vin())
    }
}
