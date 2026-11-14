package com.naitae.connect.vehicle.sim

import android.content.Context
import com.naitae.connect.vehicle.VehiclePlatform
import kotlinx.coroutines.CoroutineScope

/**
 * 시뮬레이터 (구현 예정: sim 담당). 가상 콘솔(SimulatorConsole)로 날짜·시동·기어·도착·좌석을 넣는다.
 * 돌려주는 VehiclePlatform 의 console 은 null 이 아니다.
 */
object SimPlatform {
    fun create(context: Context, scope: CoroutineScope): VehiclePlatform = TODO("sim")
}
