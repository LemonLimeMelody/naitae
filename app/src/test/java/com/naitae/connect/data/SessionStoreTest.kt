package com.naitae.connect.data

import com.naitae.connect.arrivedAt
import com.naitae.connect.domain.TripEffect
import com.naitae.connect.domain.TripSignal
import com.naitae.connect.domain.TripTracker
import com.naitae.connect.vehicle.ScenarioDay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/** 프로세스가 다시 떠도 이어 가는 진행 상태: 이동 상태의 저장·복원과 시연 시계 */
class SessionStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val saturday = SeedData.forDay(ScenarioDay.FISHING_SATURDAY)
    private val morning = SeedData.dateTimeOf(ScenarioDay.FISHING_SATURDAY)

    private fun tracker(): TripTracker = TripTracker({ saturday }, { SeedData.VIN_FIRST_CAR })

    @Test
    fun `이동 상태를 파일에 두었다가 새 추적기가 이어받으면 다시 탈 때 남길지 묻는다`() = runTest {
        val file = File(folder.root, "naitae-session.json")
        val first = tracker()
        assertTrue(first.onSignal(TripSignal.IgnitionOn(morning)).single() is TripEffect.AskRoute)
        first.onSignal(TripSignal.Arrived(arrivedAt(SeedData.PIER, 32f), morning.plusHours(1)))

        val store = SessionStore(file, backgroundScope, StandardTestDispatcher(testScheduler))
        assertNull("파일이 없으면 이어 갈 것이 없다", store.restored)
        store.update { it.copy(trip = first.snapshot(), postponedLetterId = "letter-1") }
        runCurrent()

        val saved = checkNotNull(SessionStore(file, backgroundScope).restored)
        assertEquals(first.snapshot(), saved.trip)
        assertEquals("letter-1", saved.postponedLetterId)

        val second = tracker()
        second.restore(saved.trip)
        val effects = second.onSignal(TripSignal.IgnitionOn(morning.plusHours(6)))
        assertEquals("낚시터", (effects.single() as TripEffect.AskRecord).draft.placeName)
        // 이미 물은 길 안내는 다시 묻지 않는다.
        second.clearDraft()
        assertTrue(second.onSignal(TripSignal.IgnitionOn(morning.plusHours(7))).isEmpty())
    }

    @Test
    fun `시연 시계는 저장한 시각에서 실제로 지난 시간만큼 흐른 시각으로 이어진다`() {
        val state = SessionState(day = ScenarioDay.BIRTHDAY.name, clock = "2027-11-23T07:38", clockSavedAtMs = 1_000_000L)

        assertEquals(ScenarioDay.BIRTHDAY, state.scenarioDay())
        assertEquals(LocalDateTime.of(2027, 11, 23, 7, 40), state.scenarioNow(1_120_000L))
        assertEquals("기기 시계가 뒤로 갔으면 저장한 시각", LocalDateTime.of(2027, 11, 23, 7, 38), state.scenarioNow(0L))
        assertNull(SessionState().scenarioNow(0L))
        assertNull(SessionState(day = "없는 날").scenarioDay())
    }

    @Test
    fun `가상 데이터의 첫 차를 지금 차의 VIN 으로 바꾸면 차와 기록이 모두 바뀐다`() {
        val seed = SeedData.forDay(ScenarioDay.ONE_YEAR_LATER)

        val mine = SeedData.withFirstCarVin(seed, "KMHEMULATOR000042")

        assertEquals("KMHEMULATOR000042", mine.currentCar?.vin)
        assertTrue(mine.records.isNotEmpty() && mine.records.all { it.vin == "KMHEMULATOR000042" })
        assertEquals("나머지는 그대로", seed.copy(cars = mine.cars, records = mine.records), mine)
        assertEquals(seed, SeedData.withFirstCarVin(seed, ""))
    }
}
