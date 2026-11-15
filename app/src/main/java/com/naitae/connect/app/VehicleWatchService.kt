package com.naitae.connect.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.naitae.connect.R
import com.naitae.connect.vehicle.SdkLog

/**
 * 차의 신호를 지켜보는 포그라운드 서비스.
 *
 * 하는 일은 두 가지뿐이다: 알림을 띄워 프로세스를 살려 두고, AppContainer 의 차량 연결을 붙잡아 둔다.
 * 그래서 화면이 앞에 없어도 시동(P) 신호를 받아 브리핑을 시작할 수 있다. 신호 처리는 모두 코디네이터가 한다.
 *
 * API 34 는 포그라운드 서비스 유형이 필수다. 마이크·위치는 Pleos 서비스 쪽에서 열기 때문에
 * 이 서비스는 specialUse(차량 신호 구독 유지)로 선언했다. 부팅 때 스스로 뜨지 않는다(docs/PLEOS_NOTES.md 6.4절).
 */
class VehicleWatchService : Service() {

    private var holding = false

    override fun onCreate() {
        super.onCreate()
        container().hold(HOLDER)
        holding = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 로 시작되면 곧바로 알림을 올려야 한다.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (holding) {
            holding = false
            container().release(HOLDER)
        }
        super.onDestroy()
    }

    private fun container(): AppContainer = (application as NaitaeApp).container

    private fun enterForeground(): Boolean =
        try {
            createChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // 권한·유형이 맞지 않거나 백그라운드에서 시작이 막힌 경우: 서비스 없이 화면이 떠 있는 동안만 동작한다.
            SdkLog.sys("지켜보는 서비스를 포그라운드로 올리지 못함: ${e.message ?: e.javaClass.simpleName}")
            false
        }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.watch_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.watch_channel_description)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_naitae)
            .setContentTitle(getString(R.string.watch_notification_title))
            .setContentText(getString(R.string.watch_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val HOLDER = "watch-service"
        private const val CHANNEL_ID = "vehicle_watch"
        private const val NOTIFICATION_ID = 1001

        /**
         * 서비스를 시작한다. 앱이 백그라운드에 있으면 시스템이 막을 수 있으므로(API 31+),
         * 실패해도 앱은 그대로 돌고 화면이 다시 앞에 올 때 한 번 더 시도한다.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, VehicleWatchService::class.java))
            } catch (e: Exception) {
                SdkLog.sys("지켜보는 서비스를 시작하지 못함: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
