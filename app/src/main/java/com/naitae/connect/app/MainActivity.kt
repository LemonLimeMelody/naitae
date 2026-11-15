package com.naitae.connect.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naitae.connect.ui.theme.NaitaeTheme

/**
 * 하나뿐인 화면(가로). 코디네이터의 상태를 그리고, 사용자의 조작을 코디네이터에 넘긴다.
 * 화면이 보이는 동안 차량 연결을 붙잡아 둔다(실제 차에서는 지켜보는 서비스가 계속 붙잡고 있다).
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as NaitaeApp).container

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 위치 권한을 받았으면 장소 메모의 근접 알림을 다시 건다.
            container.coordinator.retryWatches()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        hideSystemBars()
        // 시뮬레이터는 발표용으로 휴대폰·태블릿에서 돌리므로 화면이 꺼지지 않게 한다.
        if (!container.isPleos) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val coordinator = container.coordinator
        val console = container.platform.console
        setContent {
            NaitaeTheme {
                val state by coordinator.ui.collectAsStateWithLifecycle()
                NaitaeAppScreen(state = state, actions = coordinator, console = console)
            }
        }

        // 화면을 먼저 그린 뒤에 묻는다. 허용하지 않아도 앱은 그대로 돈다(해당 기능만 빠진다).
        if (savedInstanceState == null) requestMissingPermissions()
    }

    override fun onStart() {
        super.onStart()
        container.hold(HOLDER)
        // 앱이 뒤에 있을 때 막혔던 서비스 시작을 화면이 앞에 온 지금 다시 시도한다.
        if (container.isPleos) VehicleWatchService.start(this)
    }

    override fun onStop() {
        container.release(HOLDER)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** 차량 화면처럼 전체 화면으로 쓴다. 가장자리를 쓸면 시스템 바가 잠깐 나온다. */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** 매니페스트에 선언한 권한 가운데 실행 중 허용이 필요한 것(위치·마이크 등)만 골라 한 번에 묻는다. */
    private fun requestMissingPermissions() {
        val missing = declaredDangerousPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }

    private fun declaredDangerousPermissions(): List<String> {
        val declared: Array<out String>? = try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return declared.orEmpty().filter { name -> needsRuntimeGrant(name) }
    }

    private fun needsRuntimeGrant(name: String): Boolean {
        // 알림 권한은 지켜보는 서비스의 알림에만 쓰므로 시뮬레이터에서는 묻지 않는다.
        if (name == Manifest.permission.POST_NOTIFICATIONS && !container.isPleos) return false
        return try {
            packageManager.getPermissionInfo(name, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
        } catch (e: PackageManager.NameNotFoundException) {
            // 이 기기에 정의되지 않은 권한(예: Pleos 전용 권한을 일반 기기에서)은 물을 수 없다.
            false
        }
    }

    private companion object {
        const val HOLDER = "activity"
    }
}
