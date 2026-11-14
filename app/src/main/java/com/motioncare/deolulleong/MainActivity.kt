package com.motioncare.deolulleong

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * 덜울렁 메인 화면. distractionOptimized=true (주행 중에도 보이는 화면).
 * 주행 중에는 단계 색과 한 단어만, 정차하면 리포트를 보여 준다.
 * 로그: adb logcat -s DEOL
 */
class MainActivity : ComponentActivity() {
    private lateinit var engine: CareEngine
    private var testReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = CareEngine(this, getSharedPreferences("deol", MODE_PRIVATE))
        engine.start()
        val granted = checkSelfPermission(PERM_SPEED) == PackageManager.PERMISSION_GRANTED
        engine.onSpeedPermission(granted)
        if (!granted) requestSpeed()
        setContent { DeolApp(engine, ::requestSpeed) }
        registerTestHooks()
    }

    /**
     * debug 빌드 전용 시험 훅. 콘솔로는 차량 신호를 끊을 수 없어 '신호 확인 중' 동작을 이것으로 시험한다.
     * adb shell am broadcast -a com.motioncare.deolulleong.TEST_SIGNAL_LOSS --el ms 5000
     * adb shell am broadcast -a com.motioncare.deolulleong.TEST_FINISH   (finish -> onDestroy 복원 시험.
     *   Android 12부터 뒤로 키는 첫 화면을 끝내지 않고 뒤로 보내기만 해서 onDestroy가 불리지 않는다)
     */
    private fun registerTestHooks() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_TEST_SIGNAL_LOSS -> engine.simulateSignalLoss(i.getLongExtra("ms", 5000L))
                    ACTION_TEST_FINISH -> finish()
                }
            }
        }
        val f = IntentFilter(ACTION_TEST_SIGNAL_LOSS).apply { addAction(ACTION_TEST_FINISH) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, RECEIVER_EXPORTED) else registerReceiver(r, f)
        testReceiver = r
    }

    private fun requestSpeed() {
        if (checkSelfPermission(PERM_SPEED) != PackageManager.PERMISSION_GRANTED) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(PERM_SPEED), REQ_SPEED)
        }
    }

    @Deprecated("Activity result API는 시험 범위 밖이라 기존 방식을 쓴다")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SPEED) engine.onSpeedPermission(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    override fun onDestroy() {
        testReceiver?.let { runCatching { unregisterReceiver(it) } }
        engine.stop() // 무드등, 공조를 원래 값으로 되돌린다
        super.onDestroy()
    }

    companion object {
        private const val PERM_SPEED = "android.car.permission.CAR_SPEED"
        private const val REQ_SPEED = 1
        private const val ACTION_TEST_SIGNAL_LOSS = "com.motioncare.deolulleong.TEST_SIGNAL_LOSS"
        private const val ACTION_TEST_FINISH = "com.motioncare.deolulleong.TEST_FINISH"
    }
}
