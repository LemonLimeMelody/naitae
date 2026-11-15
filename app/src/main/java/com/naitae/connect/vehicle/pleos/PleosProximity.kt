package com.naitae.connect.vehicle.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.content.pm.PackageManager
import com.naitae.connect.core.model.Place
import com.naitae.connect.vehicle.ProximityEvent
import com.naitae.connect.vehicle.ProximityGateway
import com.naitae.connect.vehicle.SdkLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 장소 근접 알림(FusedLocation SDK addProximityAlert).
 *
 * 위치 권한 pleos.car.permission.FUSED_LOCATION 은 실행 중에 사용자가 허용해야 하는 권한이다.
 * 허용 전에는 watch() 가 실패(Result.failure)를 돌려준다.
 */
internal class PleosProximity(private val context: Context) : ProximityGateway {

    private val _events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ProximityEvent> = _events.asSharedFlow()

    private val mutex = Mutex()
    private val lock = Any()
    private var client: FusedLocation? = null
    private var initialized = false

    /** 장소 이름 → SDK 가 돌려준 알림 ID */
    private val alertIds = HashMap<String, String>()

    override suspend fun isSupported(): Boolean {
        val fused = clientOrNull() ?: return false
        val supported = try {
            fused.isSupported()
        } catch (t: Throwable) {
            SdkLog.sys("FusedLocation.isSupported() 실패 · ${t.brief()}")
            false
        }
        SdkLog.api("FusedLocation.isSupported() → ${if (supported) "지원" else "미지원"}")
        return supported
    }

    override suspend fun watch(place: Place, radiusM: Int): Result<Unit> = mutex.withLock {
        val fused = clientOrNull()
            ?: return@withLock Result.failure(IllegalStateException("위치 서비스를 쓸 수 없음"))
        val supported = try {
            fused.isSupported()
        } catch (t: Throwable) {
            false
        }
        if (!supported) return@withLock Result.failure(UnsupportedOperationException("이 기기는 FusedLocation 을 지원하지 않음"))
        if (context.checkSelfPermission(PERMISSION_FUSED_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            SdkLog.sys("위치 권한($PERMISSION_FUSED_LOCATION)이 아직 허용되지 않음")
            return@withLock Result.failure(SecurityException("$PERMISSION_FUSED_LOCATION 권한이 필요함"))
        }
        if (!ensureInitialized(fused)) return@withLock Result.failure(IllegalStateException("FusedLocation 초기화 실패"))

        // 같은 이름으로 이미 걸어 둔 알림이 있으면 새 위치·반경으로 바꾼다.
        removeAlert(fused, place.name)

        val request = ProximityAlertRequest(
            latitude = place.lat,
            longitude = place.lon,
            radiusMeters = radiusM.coerceAtLeast(1).toFloat(),
            transitionTypes = setOf(ProximityTransition.ENTER, ProximityTransition.EXIT),
        )
        // 등록 응답이 시간 안에 오지 않았는데 나중에 등록되면, 주인 없는 알림이 남지 않게 바로 지운다.
        val abandoned = AtomicBoolean(false)
        val registered = awaitSdk<String?>("FusedLocation.addProximityAlert()", REGISTER_TIMEOUT_MS) { ok, fail ->
            fused.addProximityAlert(
                request,
                object : ProximityAlertListener {
                    override fun onRegistered(alertId: String) {
                        if (abandoned.get()) {
                            safeSdk("FusedLocation.removeProximityAlert()") { fused.removeProximityAlert(alertId) }
                        } else {
                            ok(alertId)
                        }
                    }

                    override fun onTransition(event: ProximityAlertEvent) {
                        val stillWatching = synchronized(alertIds) { alertIds[place.name] == event.alertId }
                        if (!stillWatching) return
                        val entered = event.transition == ProximityTransition.ENTER
                        SdkLog.event("FusedLocation.onTransition(${place.name}) → ${if (entered) "가까워짐" else "멀어짐"}")
                        _events.tryEmit(ProximityEvent(placeName = place.name, entered = entered))
                    }

                    override fun onError(exception: Exception) {
                        SdkLog.sys("FusedLocation 근접 알림 오류(${place.name}) · ${exception.brief()}")
                        fail(exception)
                    }
                },
            )
        }
        val alertId = registered.getOrNull()
        SdkLog.api(
            "FusedLocation.addProximityAlert(${place.name}, 반경 ${radiusM}m) → " +
                (if (alertId != null) "등록됨" else "실패 · ${registered.exceptionOrNull()?.brief() ?: "알림 ID 없음"}"),
        )
        if (alertId == null) {
            abandoned.set(true)
            return@withLock Result.failure(registered.exceptionOrNull() ?: IllegalStateException("근접 알림 등록 실패"))
        }
        synchronized(alertIds) { alertIds[place.name] = alertId }
        Result.success(Unit)
    }

    override suspend fun unwatch(placeName: String) {
        mutex.withLock {
            val fused = synchronized(lock) { client } ?: return@withLock
            removeAlert(fused, placeName)
        }
    }

    fun release() {
        val fused = synchronized(lock) {
            val current = client
            client = null
            initialized = false
            current
        } ?: return
        val names = synchronized(alertIds) { alertIds.keys.toList() }
        names.forEach { removeAlert(fused, it) }
        safeSdk("FusedLocation.release()") {
            fused.release()
            SdkLog.api("FusedLocation.release()")
        }
    }

    private fun clientOrNull(): FusedLocation? = synchronized(lock) {
        client ?: try {
            FusedLocation(context).also { client = it }
        } catch (t: Throwable) {
            SdkLog.sys("FusedLocation 생성 실패 · ${t.brief()}")
            null
        }
    }

    private fun ensureInitialized(fused: FusedLocation): Boolean = synchronized(lock) {
        if (initialized) return@synchronized true
        initialized = safeSdk("FusedLocation.initialize()") {
            fused.initialize()
            SdkLog.api("FusedLocation.initialize()")
        }
        initialized
    }

    private fun removeAlert(fused: FusedLocation, placeName: String) {
        val alertId = synchronized(alertIds) { alertIds.remove(placeName) } ?: return
        safeSdk("FusedLocation.removeProximityAlert()") {
            fused.removeProximityAlert(alertId)
            SdkLog.api("FusedLocation.removeProximityAlert($placeName)")
        }
    }

    private companion object {
        const val PERMISSION_FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        const val REGISTER_TIMEOUT_MS = 5_000L
    }
}
