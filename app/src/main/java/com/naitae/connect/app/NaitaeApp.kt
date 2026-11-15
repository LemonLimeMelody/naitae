package com.naitae.connect.app

import android.app.Application

/**
 * 앱 프로세스. AppContainer 를 한 번 만들어 들고 있고, 코디네이터가 차의 신호를 듣기 시작하게 한다.
 */
class NaitaeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.coordinator.start()
        // 실제 차에서는 화면이 앞에 없어도 시동 신호를 받도록 지켜보는 서비스를 띄운다. 시뮬레이터에는 필요 없다.
        if (container.isPleos) VehicleWatchService.start(this)
    }

    /** 기기에서는 프로세스가 그냥 끝나므로 불리지 않는다. 프로세스를 정리하며 끝내는 환경(에뮬레이션·테스트)에서 SDK 연결을 닫는다. */
    override fun onTerminate() {
        container.shutdown()
        super.onTerminate()
    }
}
