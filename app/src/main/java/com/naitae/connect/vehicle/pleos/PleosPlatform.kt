package com.naitae.connect.vehicle.pleos

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.naitae.connect.vehicle.AppClock
import com.naitae.connect.vehicle.PlatformKind
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.VehiclePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 실제 Pleos Connect SDK 연결.
 * Pleos 서비스가 없는 기기에서는 null 을 돌려주고, 앱은 시뮬레이터로 넘어간다.
 */
object PleosPlatform {

    /**
     * 실행 중에 사용자 허용이 필요한 권한(보호 수준 dangerous).
     * 액티비티에서 requestPermissions 로 요청한다. 나머지 Pleos 권한은 매니페스트 선언만으로 된다.
     */
    val runtimePermissions: List<String> = listOf("pleos.car.permission.FUSED_LOCATION")

    // Vehicle SDK 가 서비스에 연결할 때 쓰는 값(SDK 의 Constants 는 internal 이라 가져다 쓸 수 없어 그대로 적는다).
    private const val VEHICLE_ACTION = "ai.pleos.playground.vehicle.VEHICLE_START"
    private const val VEHICLE_SERVICE_PACKAGE = "ai.pleos.playground.service.vehicle"

    private var active: Active? = null

    private class Active(
        val session: PleosVehicleSession,
        val signals: PleosSignals,
        val navi: PleosNavi,
        val voiceOut: PleosVoiceOutput,
        val voiceIn: PleosVoiceInput,
        val proximity: PleosProximity,
    )

    @Synchronized
    fun createOrNull(context: Context, scope: CoroutineScope): VehiclePlatform? =
        try {
            create(context.applicationContext ?: context, scope)
        } catch (t: Throwable) {
            // SDK 클래스 로딩·생성·초기화에서 무엇이 나와도 앱은 시뮬레이터로 계속 실행된다.
            SdkLog.sys("Pleos SDK 연결 실패 → 시뮬레이터로 실행 · ${t.brief()}")
            null
        }

    /** 앱을 끝낼 때 SDK 연결을 모두 닫는다(부르지 않아도 동작에는 문제없다). */
    @Synchronized
    fun release() {
        val current = active ?: return
        active = null
        current.signals.stop()
        current.navi.stop()
        current.voiceOut.release()
        current.voiceIn.release()
        current.proximity.release()
        current.session.release()
    }

    private fun create(appContext: Context, scope: CoroutineScope): VehiclePlatform? {
        if (!hasVehicleService(appContext)) return null
        release()

        val vehicle = Vehicle(appContext)
        vehicle.initialize()
        SdkLog.api("Vehicle.initialize()")

        val session = PleosVehicleSession(vehicle, scope)
        session.connect()

        // 나머지 SDK(내비·음성·위치)는 처음 쓸 때 각 어댑터 안에서 만들고, 실패해도 그 기능만 실패로 돌려준다.
        val signals = PleosSignals(session, scope)
        val cabin = PleosCabin(session, scope)
        val navi = PleosNavi(appContext)
        val voiceOut = PleosVoiceOutput(appContext)
        val voiceIn = PleosVoiceInput(appContext)
        val proximity = PleosProximity(appContext)
        active = Active(session, signals, navi, voiceOut, voiceIn, proximity)

        // 첫 브리핑이 늦지 않게 음성 엔진을 미리 준비한다.
        scope.launch {
            voiceOut.warmUp()
            voiceIn.warmUp()
        }

        SdkLog.sys("Pleos Connect 플랫폼으로 실행")
        return VehiclePlatform(
            kind = PlatformKind.PLEOS,
            signals = signals,
            cabin = cabin,
            navi = navi,
            voiceOut = voiceOut,
            voiceIn = voiceIn,
            proximity = proximity,
            clock = AppClock { LocalDateTime.now() },
            console = null,
        )
    }

    /**
     * Pleos Vehicle 서비스가 이 기기에 있는지 본다. 서비스에 연결하지 않고 패키지 조회만 하므로 가볍고,
     * 일반 휴대폰·에뮬레이터에서는 SDK 객체를 하나도 만들지 않고 끝난다.
     * (조회에 필요한 <queries> 는 Vehicle SDK 의 매니페스트가 병합해 준다.)
     */
    private fun hasVehicleService(context: Context): Boolean {
        val packageManager = context.packageManager
        val automotive = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val intent = Intent(VEHICLE_ACTION).setPackage(VEHICLE_SERVICE_PACKAGE)
        @Suppress("DEPRECATION")
        val found = packageManager.resolveService(intent, 0) != null
        if (!found) {
            SdkLog.sys(
                "Pleos Vehicle 서비스 없음(${if (automotive) "차량용 OS 이지만 Pleos 아님" else "차량용 OS 아님"}) → 시뮬레이터로 실행",
            )
        }
        return found
    }
}
