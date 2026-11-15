package com.naitae.connect.vehicle.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.naitae.connect.vehicle.SdkLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/*
 * Pleos 어댑터 공통 도구.
 *  - awaitSdk : 성공·실패 람다로 답하는 SDK 호출을 suspend 로 감싼다.
 *  - PleosVehicleSession : Vehicle 인스턴스 하나를 신호(읽기)와 실내 제어(쓰기)가 같이 쓴다.
 */

/** SDK 한 번 호출의 응답을 기다리는 시간. 차량 통신은 IPC 라 답이 오지 않을 수도 있다. */
internal const val SDK_TIMEOUT_MS = 4_000L

/**
 * 콜백형 SDK 호출을 Result 로 바꾼다. 예외·실패 콜백·시간 초과 모두 failure 로 돌려주고 던지지 않는다.
 *
 * 성공 람다는 (T) -> Unit, 실패 람다는 (Throwable?) -> Unit 값으로 넘긴다.
 * SDK 쪽 타입이 (T) 이든 (T?) 이든, (Exception) 이든 (Throwable) 이든 함수 타입의 반공변성으로 그대로 맞는다.
 * (T 는 널을 허용하는 타입으로 준다.)
 */
internal suspend fun <T> awaitSdk(
    label: String,
    timeoutMs: Long = SDK_TIMEOUT_MS,
    call: (ok: (T) -> Unit, fail: (Throwable?) -> Unit) -> Unit,
): Result<T> {
    val outcome = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Result<T>> { cont ->
            // SDK 가 콜백을 두 번 부르거나 다른 스레드에서 불러도 한 번만 이어 가게 한다.
            val done = AtomicBoolean(false)
            fun finish(result: Result<T>) {
                if (done.compareAndSet(false, true) && cont.isActive) cont.resume(result)
            }
            try {
                call(
                    { value -> finish(Result.success(value)) },
                    { error -> finish(Result.failure(error ?: IllegalStateException("$label 실패"))) },
                )
            } catch (t: Throwable) {
                finish(Result.failure(t))
            }
        }
    }
    return outcome ?: Result.failure(TimeoutException("$label 응답 없음(${timeoutMs}ms)"))
}

/**
 * 로그에 쓸 짧은 오류 설명.
 * Vehicle SDK 의 예외 클래스는 난독화되어 이름이 한 글자라서(메시지가 ERROR_… 코드 이름) 그럴 때는 메시지만 쓴다.
 */
internal fun Throwable.brief(): String {
    val name = javaClass.simpleName
    val detail = message ?: "원인 모름"
    return if (name.length <= 2) detail else "$name: $detail"
}

/** SDK 호출을 감싸 예외가 앱까지 올라오지 않게 한다. 실패하면 로그만 남긴다. */
internal inline fun safeSdk(label: String, block: () -> Unit): Boolean =
    try {
        block()
        true
    } catch (t: Throwable) {
        SdkLog.sys("$label 실패 · ${t.brief()}")
        false
    }

internal fun formatKm(km: Float?): String =
    if (km == null) "값 없음" else String.format(Locale.KOREA, "%,dkm", km.toLong())

internal fun formatArgb(argb: Int): String = String.format(Locale.US, "#%08X", argb)

/**
 * Vehicle SDK 연결 하나.
 *
 * initialize() 는 서비스 바인딩만 시작하고 결과를 알려 주지 않는다.
 * 그래서 기어 값을 읽어 보고, 답이 오면 '연결됨'으로 본다. 리스너 등록과 첫 값 읽기는 그 뒤에 한다.
 */
internal class PleosVehicleSession(
    val vehicle: Vehicle,
    private val scope: CoroutineScope,
) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 연결을 확인하며 읽은 첫 기어 값 */
    @Volatile
    var firstGear: VehicleGear? = null
        private set

    private var probeJob: Job? = null

    /** 연결 확인을 시작한다(이미 확인 중이거나 연결됐으면 아무 일도 하지 않는다). */
    @Synchronized
    fun connect() {
        if (_connected.value || probeJob?.isActive == true) return
        probeJob = scope.launch {
            var waitMs = 400L
            repeat(PROBE_ATTEMPTS) { attempt ->
                // 기어 API 는 SDK 에서 사용 중단 예고(deprecated)지만, 대체 수단인 android.car 라이브러리는 이 앱에 없다.
                val result = awaitSdk<VehicleGear?>("Gear.getCurrentGearSelectionState()") { ok, fail ->
                    @Suppress("DEPRECATION")
                    vehicle.getGear().getCurrentGearSelectionState(ok, fail)
                }
                if (result.isSuccess) {
                    firstGear = result.getOrNull()
                    _connected.value = true
                    SdkLog.sys("Vehicle 서비스 연결됨")
                    return@launch
                }
                if (attempt == 0 || attempt == PROBE_ATTEMPTS - 1) {
                    SdkLog.sys("Vehicle 서비스 응답 대기 · ${result.exceptionOrNull()?.brief()}")
                }
                delay(waitMs)
                waitMs = (waitMs * 2).coerceAtMost(3_000L)
            }
        }
    }

    suspend fun awaitConnected(timeoutMs: Long = CONNECT_WAIT_MS): Boolean {
        connect()
        return withTimeoutOrNull(timeoutMs) { connected.first { it } } ?: false
    }

    fun release() {
        probeJob?.cancel()
        _connected.value = false
        safeSdk("Vehicle.release()") {
            vehicle.release()
            SdkLog.api("Vehicle.release()")
        }
    }

    private companion object {
        const val PROBE_ATTEMPTS = 20
        const val CONNECT_WAIT_MS = 6_000L
    }
}
