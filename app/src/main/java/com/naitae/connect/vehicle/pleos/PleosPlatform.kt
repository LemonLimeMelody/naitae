package com.naitae.connect.vehicle.pleos

import android.content.Context
import com.naitae.connect.vehicle.VehiclePlatform
import kotlinx.coroutines.CoroutineScope

/**
 * 실제 Pleos Connect SDK 연결 (구현 예정: pleos 담당).
 * Pleos 서비스가 없는 기기에서는 null 을 돌려주고, 앱은 시뮬레이터로 넘어간다.
 */
object PleosPlatform {
    fun createOrNull(context: Context, scope: CoroutineScope): VehiclePlatform? = null
}
