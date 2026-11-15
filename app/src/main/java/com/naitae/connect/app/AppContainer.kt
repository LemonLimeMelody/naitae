package com.naitae.connect.app

import android.app.Application
import com.naitae.connect.data.JsonFileRepository
import com.naitae.connect.data.NaitaeRepository
import com.naitae.connect.data.SeedData
import com.naitae.connect.data.SessionStore
import com.naitae.connect.vehicle.PlatformKind
import com.naitae.connect.vehicle.RecordedVoicePlayer
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.VehiclePlatform
import com.naitae.connect.vehicle.demo.DemoPlatform
import com.naitae.connect.vehicle.pleos.PleosPlatform
import com.naitae.connect.vehicle.sim.SimPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * 앱의 부품을 한 번만 조립해 들고 있는다(프로세스당 하나, NaitaeApp 이 만든다).
 * Pleos 서비스가 있으면 실제 SDK 에 시연 층(DemoPlatform)을 씌워서, 없으면 시뮬레이터로 돈다.
 */
class AppContainer(app: Application) {

    /** 앱 전체 수명의 범위. 화면 상태를 바꾸는 일은 모두 메인 디스패처에서 한다. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val pleos: VehiclePlatform? = createPleos(app)

    /** Pleos 모드에서만 둔다: 프로세스가 다시 떠도 이어 갈 진행 상태(시연 날짜·시각, 이동 상태). */
    private val session: SessionStore? = pleos?.let { openSession(app) }

    val platform: VehiclePlatform = pleos?.let { withDemoLayer(it) } ?: SimPlatform.create(app, scope)

    val isPleos: Boolean get() = platform.kind == PlatformKind.PLEOS

    val repository: NaitaeRepository = JsonFileRepository(File(app.filesDir, DATA_FILE)) {
        // 두 모드 모두 발표 시나리오의 첫날로 시작한다(Pleos 모드는 코디네이터가 차의 VIN 에 맞춰 곧바로 다시 만든다).
        SeedData.forDay(ScenarioDay.WEEKDAY)
    }

    val voicePlayer: RecordedVoicePlayer = AssetVoicePlayer(app)

    val coordinator: NaitaeCoordinator = NaitaeCoordinator(platform, repository, voicePlayer, scope, session)

    /** 차량 연결을 쓰고 있는 곳(화면, 지켜보는 서비스). 하나라도 있으면 연결을 유지한다. */
    private val holders = HashSet<String>()

    /** 메인 스레드에서 부른다. 처음 붙잡는 곳이 생기면 차량 연결을 시작한다. */
    fun hold(who: String) {
        val first = holders.isEmpty()
        if (holders.add(who) && first) {
            guard("연결 시작") {
                platform.signals.start()
                platform.navi.start()
            }
        }
    }

    /** 붙잡은 곳이 모두 놓으면 차량 연결을 끊는다. */
    fun release(who: String) {
        if (holders.remove(who) && holders.isEmpty()) {
            guard("연결 종료") {
                platform.navi.stop()
                platform.signals.stop()
            }
        }
    }

    /** 앱을 끝낼 때: 차량 연결과 SDK 를 모두 닫고 돌던 일을 멈춘다. */
    fun shutdown() {
        guard("연결 종료") {
            if (holders.isNotEmpty()) {
                holders.clear()
                platform.navi.stop()
                platform.signals.stop()
            }
            PleosPlatform.release()
        }
        scope.cancel()
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            SdkLog.sys("차량 $what 실패: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun createPleos(app: Application): VehiclePlatform? =
        try {
            PleosPlatform.createOrNull(app, scope)
        } catch (e: Throwable) {
            // SDK 클래스를 못 찾는 것(LinkageError)까지 잡아 시뮬레이터로 넘어간다.
            SdkLog.sys("Pleos 연결을 만들지 못해 시뮬레이터로 실행: ${e.message ?: e.javaClass.simpleName}")
            null
        }

    private fun openSession(app: Application): SessionStore {
        val file = File(app.filesDir, SESSION_FILE)
        // 계정 원본이 없으면 이어 갈 것도 없다. 처음부터 시작한다.
        if (!File(app.filesDir, DATA_FILE).isFile) file.delete()
        return SessionStore(file, scope)
    }

    /**
     * Pleos Virtual Console 이 넣지 못하는 날짜·도착·말을 앱의 시연 패널에서 넣을 수 있게 한다.
     * 시연 시계는 지난 실행의 시각에서 이어 가고, 정할 때마다 적어 둔다.
     */
    private fun withDemoLayer(real: VehiclePlatform): VehiclePlatform {
        val saved = session?.restored
        val day = saved?.scenarioDay()
        val now = saved?.scenarioNow(System.currentTimeMillis())
        return DemoPlatform.wrap(
            real = real,
            scope = scope,
            resume = if (day != null && now != null) DemoPlatform.Resume(day, now) else null,
            onClockSet = { setDay, at ->
                session?.update { it.copy(day = setDay.name, clock = at.toString(), clockSavedAtMs = System.currentTimeMillis()) }
            },
        )
    }

    private companion object {
        const val DATA_FILE = "naitae.json"
        const val SESSION_FILE = "naitae-session.json"
    }
}
