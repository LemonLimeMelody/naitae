package com.naitae.connect.app

import android.app.Application
import com.naitae.connect.data.JsonFileRepository
import com.naitae.connect.data.NaitaeRepository
import com.naitae.connect.data.SeedData
import com.naitae.connect.vehicle.PlatformKind
import com.naitae.connect.vehicle.RecordedVoicePlayer
import com.naitae.connect.vehicle.ScenarioDay
import com.naitae.connect.vehicle.SdkLog
import com.naitae.connect.vehicle.VehiclePlatform
import com.naitae.connect.vehicle.pleos.PleosPlatform
import com.naitae.connect.vehicle.sim.SimPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.LocalDate

/**
 * 앱의 부품을 한 번만 조립해 들고 있는다(프로세스당 하나, NaitaeApp 이 만든다).
 * Pleos 서비스가 있으면 실제 SDK 로, 없으면 시뮬레이터로 돈다.
 */
class AppContainer(app: Application) {

    /** 앱 전체 수명의 범위. 화면 상태를 바꾸는 일은 모두 메인 디스패처에서 한다. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val platform: VehiclePlatform = createPlatform(app)

    val isPleos: Boolean get() = platform.kind == PlatformKind.PLEOS

    val repository: NaitaeRepository = JsonFileRepository(File(app.filesDir, DATA_FILE)) {
        // 시뮬레이터는 발표 시나리오의 첫날로, 실제 차는 오늘 날짜의 가상 페르소나 데이터로 시작한다.
        if (isPleos) SeedData.base(LocalDate.now()) else SeedData.forDay(ScenarioDay.WEEKDAY)
    }

    val voicePlayer: RecordedVoicePlayer = AssetVoicePlayer(app)

    val coordinator: NaitaeCoordinator = NaitaeCoordinator(platform, repository, voicePlayer, scope)

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

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            SdkLog.sys("차량 $what 실패: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun createPlatform(app: Application): VehiclePlatform {
        val pleos = try {
            PleosPlatform.createOrNull(app, scope)
        } catch (e: Throwable) {
            // SDK 클래스를 못 찾는 것(LinkageError)까지 잡아 시뮬레이터로 넘어간다.
            SdkLog.sys("Pleos 연결을 만들지 못해 시뮬레이터로 실행: ${e.message ?: e.javaClass.simpleName}")
            null
        }
        return pleos ?: SimPlatform.create(app, scope)
    }

    private companion object {
        const val DATA_FILE = "naitae.json"
    }
}
