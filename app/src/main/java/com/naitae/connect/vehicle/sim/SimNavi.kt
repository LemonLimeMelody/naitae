package com.naitae.connect.vehicle.sim

import com.naitae.connect.core.model.Place
import com.naitae.connect.data.SeedData
import com.naitae.connect.vehicle.NaviEvent
import com.naitae.connect.vehicle.NaviGateway
import com.naitae.connect.vehicle.ProximityEvent
import com.naitae.connect.vehicle.ProximityGateway
import com.naitae.connect.vehicle.SdkLog
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 내비 시뮬레이터. 도착 이벤트는 가상 콘솔의 arrive() 가 만든다. */
internal class SimNavi(private val vehicle: SimVehicle) : NaviGateway {

    override val events: SharedFlow<NaviEvent> = vehicle.naviEvents.asSharedFlow()

    override suspend fun requestRoute(place: Place): Result<Unit> {
        SdkLog.api("NaviHelper.requestRoute(${place.name})")
        vehicle.startGuidance(place)
        return Result.success(Unit)
    }

    override suspend fun bookmarks(): List<Place> {
        val places = SeedData.places
        SdkLog.api("NaviHelper.getBookmarkInfo() → ${places.joinToString("·") { it.name }}")
        return places
    }

    override fun start() {
        SdkLog.api("NaviHelper.initialize() · addListener(NaviHelperEventListener)")
    }

    override fun stop() {
        SdkLog.api("NaviHelper.release()")
    }
}

/** 장소 근접 알림 시뮬레이터. 걸어 둔 장소로 arrive() 하면 도착 직전에 진입 이벤트가 온다. */
internal class SimProximity(private val vehicle: SimVehicle) : ProximityGateway {

    override val events: SharedFlow<ProximityEvent> = vehicle.proximityEvents.asSharedFlow()

    override suspend fun isSupported(): Boolean {
        SdkLog.api("FusedLocation.isSupported() → true")
        return true
    }

    override suspend fun watch(place: Place, radiusM: Int): Result<Unit> {
        SdkLog.api("FusedLocation.addProximityAlert(${place.name}, ${radiusM}m)")
        vehicle.watched[place.name] = radiusM
        return Result.success(Unit)
    }

    override suspend fun unwatch(placeName: String) {
        if (vehicle.watched.remove(placeName) != null) {
            SdkLog.api("FusedLocation.removeProximityAlert($placeName)")
        }
    }
}
