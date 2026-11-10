package com.taxitalk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.taxitalk.ui.TaxiTalkApp
import com.taxitalk.ui.TaxiTalkViewModel

class MainActivity : ComponentActivity() {
    private val vm: TaxiTalkViewModel by viewModels()
    private var keyHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { TaxiTalkApp(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 물리 버튼 (블루투스 키, 앱이 화면에 있을 때만 받는다).
     * 기사 버튼: 짧게 = 내가 말하기, 길게 = 손님 말 듣기. 뒷좌석 버튼: 손님 말 듣기.
     * 운행 전에는 기사 버튼만 음성으로 안내하고 뒷좌석 버튼은 무시한다.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val driverKey = event.keyCode in DRIVER_KEYS
        if (!driverKey && event.keyCode !in REAR_KEYS) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount > 0 || event.isLongPress)) keyHeld = true
        if (event.action == KeyEvent.ACTION_UP) {
            val long = keyHeld || event.eventTime - event.downTime >= LONG_PRESS_MS
            keyHeld = false
            when {
                !vm.ui.value.inRide -> if (driverKey) vm.promptChooseLanguage()
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                driverKey && !long -> vm.driverTalk()
                else -> vm.passengerTalk()
            }
        }
        return true
    }

    /** FR-1.4: QR → taxitalk://ride?lang=en */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "taxitalk") vm.handleDeepLink(data.getQueryParameter("lang"))
    }

    private companion object {
        const val LONG_PRESS_MS = 500L
        val DRIVER_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_F1,
        )
        val REAR_KEYS = setOf(KeyEvent.KEYCODE_BUTTON_2, KeyEvent.KEYCODE_F2)
    }
}
