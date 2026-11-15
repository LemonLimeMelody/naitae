package com.naitae.connect.vehicle.sim

import android.content.Context
import com.naitae.connect.vehicle.AppClock
import com.naitae.connect.vehicle.PlatformKind
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.VehiclePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 시뮬레이터. 가상 콘솔(SimulatorConsole)로 날짜·시동·기어·도착·좌석을 넣는다.
 * 돌려주는 VehiclePlatform 의 console 은 null 이 아니다.
 */
object SimPlatform {
    fun create(context: Context, scope: CoroutineScope): VehiclePlatform {
        val cabin = SimCabin()
        val vehicle = SimVehicle(scope, cabin)
        vehicle.startClock()
        SdkLog.sys("시뮬레이터 시작 · 가상 콘솔이 차량 신호를 넣습니다")
        return VehiclePlatform(
            kind = PlatformKind.SIMULATOR,
            signals = vehicle.signals,
            cabin = cabin,
            navi = SimNavi(vehicle),
            voiceOut = SimVoiceOutput(context),
            voiceIn = SimVoiceInput(vehicle),
            proximity = SimProximity(vehicle),
            clock = AppClock { vehicle.clockNow() },
            console = vehicle,
        )
    }
}

/** 시뮬레이터 설정. 가상 콘솔 화면이 켜고 끌 수 있다. */
object SimSettings {
    private val _speakAloud = MutableStateFlow(false)

    /** 화면에서 지켜볼 때 쓴다. */
    val speakAloudState: StateFlow<Boolean> = _speakAloud.asStateFlow()

    /**
     * 기기의 합성 음성으로 실제 소리를 낼지. 기본은 끔:
     * 소리 없이 자막만 보이고, 읽는 데 걸릴 시간만큼 기다린다.
     */
    var speakAloud: Boolean
        get() = _speakAloud.value
        set(value) {
            _speakAloud.value = value
        }
}
