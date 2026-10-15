package com.example.nowplaying

import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var controller: NowPlayingController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_now_playing)

        // 화면을 항상 켜진 상태로 유지 (거치대에 놓고 보는 용도)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showOverLockScreen()
        keepFullscreen()

        val listenerComponent = ComponentName(this, MediaNotificationListenerService::class.java)
        controller = NowPlayingController(this, findViewById(R.id.rootContainer), listenerComponent)
    }

    /**
     * 잠금화면 위에 바로 표시되도록 한다.
     * (화면 잠금이 "없음/스와이프"면 잠금 해제 없이 바로 조작 가능,
     *  PIN/패턴/지문 등 보안 잠금이 걸려 있으면 안드로이드 정책상
     *  홈/뒤로가기 시 잠금해제 화면이 뜨는 것은 막을 수 없다.)
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    private fun keepFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onResume() {
        super.onResume()
        controller.start()
    }

    override fun onPause() {
        super.onPause()
        controller.stop()
    }
}

