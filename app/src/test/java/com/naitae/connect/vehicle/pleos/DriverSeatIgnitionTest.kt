package com.naitae.connect.vehicle.pleos

import com.naitae.connect.vehicle.GearState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 운전석 착좌로 판단하는 탑승 신호(Pleos 어댑터의 ignitionOn) */
class DriverSeatIgnitionTest {

    /** 연결되어 첫 값을 읽은 상태: 기어 P, 운전석 착좌 값 seated */
    private fun connected(seated: Boolean, gear: GearState = GearState.PARK): DriverSeatIgnition =
        DriverSeatIgnition().apply {
            onGear(gear, 0)
            onDriverSeat(seated, 0)
            onAlive(0)
        }

    @Test
    fun `연결 전에는 운전석에 앉아 있어도 꺼져 있다`() {
        val logic = DriverSeatIgnition()
        logic.onGear(GearState.PARK, 0)
        logic.onDriverSeat(true, 0)

        assertFalse(logic.on)
        assertNull(logic.pendingMs(5_000))
    }

    @Test
    fun `첫 값을 읽은 순간의 착좌는 기다리지 않고 반영한다`() {
        assertTrue(connected(seated = true).on)
        assertFalse(connected(seated = false).on)
    }

    @Test
    fun `운전석에 앉으면 가라앉는 시간 뒤에 켜진다`() {
        val logic = connected(seated = false)

        logic.onDriverSeat(true, 1_000)

        assertEquals(1_500L, logic.pendingMs(1_000))
        assertFalse("아직 가라앉지 않음", logic.settle(2_400))
        assertFalse(logic.on)
        assertTrue(logic.settle(2_500))
        assertTrue(logic.on)
        assertNull(logic.pendingMs(2_500))
    }

    @Test
    fun `기어 P 에서 운전석이 비면 꺼진다`() {
        val logic = connected(seated = true)

        logic.onDriverSeat(false, 1_000)

        assertTrue(logic.settle(2_500))
        assertFalse(logic.on)
    }

    @Test
    fun `잠깐 흔들린 착좌 신호는 반영하지 않는다`() {
        val logic = connected(seated = true)

        logic.onDriverSeat(false, 1_000)
        logic.onDriverSeat(true, 1_600)

        assertNull("다시 앉았으므로 바뀔 것이 없다", logic.pendingMs(1_600))
        assertFalse(logic.settle(5_000))
        assertTrue(logic.on)
    }

    @Test
    fun `흔들린 뒤에는 마지막 판단부터 다시 기다린다`() {
        val logic = connected(seated = false)

        logic.onDriverSeat(true, 1_000)
        logic.onDriverSeat(false, 1_500)
        logic.onDriverSeat(true, 2_000)

        assertFalse("처음 앉은 때가 아니라 마지막에 앉은 때부터 센다", logic.settle(2_600))
        assertTrue(logic.settle(3_500))
        assertTrue(logic.on)
    }

    @Test
    fun `P 가 아닐 때의 비어 있음은 반영하지 않고 P 가 되면 그때 꺼진다`() {
        val logic = connected(seated = true)
        logic.onGear(GearState.DRIVE, 1_000)

        logic.onDriverSeat(false, 2_000)

        assertNull(logic.pendingMs(2_000))
        assertFalse(logic.settle(60_000))
        assertTrue(logic.on)

        logic.onGear(GearState.PARK, 61_000)
        assertEquals(1_500L, logic.pendingMs(61_000))
        assertTrue(logic.settle(62_500))
        assertFalse(logic.on)
    }

    @Test
    fun `착좌 값을 받은 적이 없으면 첫 값을 받은 때부터 켜진다`() {
        val logic = DriverSeatIgnition()
        logic.onGear(GearState.PARK, 0)

        assertTrue(logic.onAlive(0))
        assertFalse(logic.seatKnown)
        // 기어가 바뀌어도 그대로다(예전 동작).
        logic.onGear(GearState.DRIVE, 1_000)
        logic.onGear(GearState.PARK, 2_000)
        assertNull(logic.pendingMs(2_000))
        assertTrue(logic.on)
    }

    @Test
    fun `착좌 값이 뒤늦게 오기 시작하면 그때부터 착좌를 따른다`() {
        val logic = DriverSeatIgnition()
        logic.onGear(GearState.PARK, 0)
        logic.onAlive(0)

        logic.onDriverSeat(false, 10_000)

        assertTrue(logic.seatKnown)
        assertTrue(logic.settle(11_500))
        assertFalse(logic.on)
    }

    @Test
    fun `수신을 멈추면 꺼지고 다시 연결하면 처음부터 판단한다`() {
        val logic = connected(seated = true)

        logic.onStopped()

        assertFalse(logic.on)
        assertFalse(logic.seatKnown)
        assertTrue("착좌 값을 아직 못 받았으면 예전 동작", logic.onAlive(100))
    }
}
