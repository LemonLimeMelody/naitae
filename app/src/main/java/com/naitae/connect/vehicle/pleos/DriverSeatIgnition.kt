package com.naitae.connect.vehicle.pleos

import com.naitae.connect.vehicle.GearState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 운전석 착좌로 판단하는 탑승 신호(= VehicleSignals.ignitionOn). SDK 를 모르는 순수 로직이라 단위 테스트한다.
 *
 * 규칙
 *  - 차량 값(기어·좌석 등)을 처음 받기 전에는 꺼짐.
 *  - 운전석 착좌 값을 한 번이라도 받았으면: 앉으면 켜짐, 기어 P 에서 비면 꺼짐.
 *    P 가 아닐 때의 '비어 있음'은 반영하지 않는다(주행 중 센서가 잠깐 떨어지는 경우). P 가 되었을 때도 비어 있으면 그때 꺼진다.
 *  - 착좌 값을 받은 적이 없으면(센서 없음·읽기 실패): 첫 값을 받은 때부터 켜짐(예전 동작).
 *  - 켜짐·꺼짐이 바뀌려면 같은 판단이 settleMs 동안 이어져야 한다(센서 흔들림에 시동 흐름이 다시 돌지 않게).
 *    첫 값을 받는 순간의 판단은 흔들림이 아니므로 바로 반영한다.
 *
 * 시각(nowMs)은 부르는 쪽이 넘긴다. 단조 시계면 무엇이든 된다.
 */
internal class DriverSeatIgnition(private val settleMs: Long = SETTLE_MS) {

    /** 지금 알릴 값 */
    @get:Synchronized
    var on: Boolean = false
        private set

    /** 운전석 착좌 값을 받은 적이 있는지(없으면 예전 동작으로 돈다) */
    @get:Synchronized
    var seatKnown: Boolean = false
        private set

    private var alive = false
    private var occupied = false
    private var gear = GearState.UNKNOWN

    /** 흔들림이 가라앉으면 알릴 값과, 그 판단이 시작된 시각 */
    private var wanted = false
    private var wantedSinceMs = 0L

    @Synchronized
    fun onGear(gear: GearState, nowMs: Long) {
        this.gear = gear
        evaluate(nowMs)
    }

    @Synchronized
    fun onDriverSeat(occupied: Boolean, nowMs: Long) {
        seatKnown = true
        this.occupied = occupied
        evaluate(nowMs)
    }

    /** 차량 서비스에 연결되어 첫 값을 읽었다. 이때의 판단을 바로 반영하고 돌려준다. */
    @Synchronized
    fun onAlive(nowMs: Long): Boolean {
        alive = true
        wanted = target()
        wantedSinceMs = nowMs
        on = wanted
        return on
    }

    /** 신호 수신을 멈췄다. 다음 연결에서는 착좌 값을 처음부터 다시 받는다. */
    @Synchronized
    fun onStopped() {
        alive = false
        seatKnown = false
        occupied = false
        wanted = false
        on = false
    }

    /** 바뀔 예정이면 남은 시간(ms, 0 이면 지금 [settle] 하면 바뀐다). 바뀔 것이 없으면 null */
    @Synchronized
    fun pendingMs(nowMs: Long): Long? =
        if (wanted == on) null else (wantedSinceMs + settleMs - nowMs).coerceAtLeast(0L)

    /** 판단이 충분히 이어졌으면 반영한다. [on] 이 바뀌었으면 true */
    @Synchronized
    fun settle(nowMs: Long): Boolean {
        if (pendingMs(nowMs) != 0L) return false
        on = wanted
        return true
    }

    private fun evaluate(nowMs: Long) {
        val next = target()
        if (next != wanted) {
            wanted = next
            wantedSinceMs = nowMs
        }
    }

    private fun target(): Boolean = when {
        !alive -> false
        !seatKnown -> true
        occupied -> true
        gear == GearState.PARK -> false
        // P 가 아닐 때의 '비어 있음'으로는 바꾸지 않는다.
        else -> on
    }

    companion object {
        const val SETTLE_MS = 1_500L
    }
}

/**
 * [DriverSeatIgnition] 의 판단이 가라앉기를 기다렸다가 알린다.
 * 차량 리스너는 바인더 스레드에서 오므로 입력은 어느 스레드에서 불러도 되고, 알림(onChanged)은 scope 에서 나간다.
 */
internal class BoardingWatch(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val logic: DriverSeatIgnition = DriverSeatIgnition(),
    private val onChanged: (on: Boolean) -> Unit,
) {
    private val lock = Any()
    private var settling: Job? = null

    val seatKnown: Boolean get() = logic.seatKnown

    fun gear(gear: GearState) {
        logic.onGear(gear, nowMs())
        schedule()
    }

    fun driverSeat(occupied: Boolean) {
        logic.onDriverSeat(occupied, nowMs())
        schedule()
    }

    /** 첫 값을 읽었다. 이때의 탑승 여부를 돌려준다(onChanged 는 부르지 않는다). */
    fun alive(): Boolean = logic.onAlive(nowMs()).also { schedule() }

    fun stopped() {
        synchronized(lock) {
            settling?.cancel()
            settling = null
        }
        logic.onStopped()
    }

    private fun schedule() {
        synchronized(lock) {
            settling?.cancel()
            settling = if (logic.pendingMs(nowMs()) == null) null else scope.launch { settle() }
        }
    }

    private suspend fun settle() {
        while (true) {
            val waitMs = logic.pendingMs(nowMs()) ?: return
            if (waitMs > 0L) {
                delay(waitMs)
            } else if (logic.settle(nowMs())) {
                onChanged(logic.on)
                return
            }
        }
    }
}
