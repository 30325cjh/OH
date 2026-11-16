package com.oh.hanako

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.StatFs
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var engineStatus: TextView
    private var ageVerifiedThisLaunch = false
    @Volatile private var isModelInstalling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱 실행 때마다 생년월일을 다시 확인한다.
        showAgeVerification()
    }

    private fun calculateAge(year: Int, month: Int, day: Int): Int {
        val birth = Calendar.getInstance().apply {
            isLenient = false
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }
        return try {
            birth.time
            val today = Calendar.getInstance()
            var age = today.get(Calendar.YEAR) - year
            if (today.get(Calendar.MONTH) + 1 < month ||
                (today.get(Calendar.MONTH) + 1 == month && today.get(Calendar.DAY_OF_MONTH) < day)
            ) age--
            age
        } catch (_: Exception) {
            -1
        }
    }

    private fun showAgeVerification() {
        val today = Calendar.getInstance()
        val initial = (today.clone() as Calendar).apply { add(Calendar.YEAR, -20) }
        val picker = DatePickerDialog(this, { _, year, zeroBasedMonth, day ->
            val age = calculateAge(year, zeroBasedMonth + 1, day)
            if (age >= 19) {
                ageVerifiedThisLaunch = true
                showMainUi()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("이용할 수 없습니다")
                    .setMessage("이 앱은 실행일 기준 만 18세 초과(만 19세 이상)만 이용할 수 있습니다.")
                    .setCancelable(false)
                    .setPositiveButton("확인") { _, _ -> finish() }
                    .show()
            }
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH))
        picker.setTitle("생년월일 확인 · 만 19세 이상")
        picker.datePicker.maxDate = today.timeInMillis
        picker.setCancelable(false)
        picker.setOnCancelListener { finish() }
        picker.show()
    }

    private fun showMainUi() {
        if (!ageVerifiedThisLaunch) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFFFFF5FB.toInt())
        }
        root.addView(TextView(this).apply {
            text = "하나코 ♡"
            textSize = 32f
            setTextColor(0xFFE86AB2.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "하나코와 이야기를 나눠 보세요."
            textSize = 15f
            setPadding(0, 12, 0, 20)
        })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        root.addView(status)
        engineStatus = TextView(this).apply {
            text = "음성 엔진: 확인 중..."
            textSize = 13f
            setTextColor(0xFF76536A.toInt())
            setPadding(0, 0, 0, 14)
        }
        root.addView(engineStatus)
        root.addView(Button(this).apply {
            text = "화면 위에 표시 권한 허용"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                } else {
                    status.text = "오버레이 권한이 허용되어 있어요."
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Irodori 음성 모델 설치 (약 2GB)"
            setOnClickListener {
                if (!isModelInstalling) {
                    isModelInstalling = true
                    status.text = "Irodori 모델 설치 준비 중..."
                    Thread {
                        val result = IrodoriModelInstaller.install(this@MainActivity) { message ->
                            runOnUiThread { if (::status.isInitialized) status.text = message }
                        }
                        runOnUiThread {
                            isModelInstalling = false
                            if (::status.isInitialized) status.text = result
                        }
                    }.start()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "하나코 시작"
            setOnClickListener {
                if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this@MainActivity)) {
                    val service = Intent(this@MainActivity, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                    status.text = "하나코가 기다리고 있어요 ♡"
                } else {
                    status.text = "먼저 화면 위에 표시 권한을 허용해 주세요."
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                }
            }
        })
        setContentView(root)
        updateOverlayStatus()
        updateEngineStatus()
    }

    private fun updateEngineStatus() {
        Thread {
            val result = MnnRuntime.check(applicationContext)
            val memory = ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            val totalRamGb = memory.totalMem / (1024.0 * 1024.0 * 1024.0)
            val availableRamGb = memory.availMem / (1024.0 * 1024.0 * 1024.0)
            val modelDir = getExternalFilesDir("models")
            val storage = StatFs((modelDir ?: filesDir).absolutePath)
            val freeStorageGb = storage.availableBytes / (1024.0 * 1024.0 * 1024.0)
            runOnUiThread {
                if (::engineStatus.isInitialized) {
                    engineStatus.text = "음성 엔진: ${result.detail}\n" +
                        "기기 RAM: ${"%.1f".format(totalRamGb)}GB (현재 여유 ${"%.1f".format(availableRamGb)}GB)\n" +
                        "모델 저장 가능 공간: ${"%.1f".format(freeStorageGb)}GB"
                }
            }
        }.start()
    }

    private fun updateOverlayStatus() {
        if (!::status.isInitialized) return
        status.text = if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))
            "오버레이 권한: 허용됨" else "오버레이 권한: 아직 허용되지 않음"
    }

    override fun onResume() {
        super.onResume()
        if (ageVerifiedThisLaunch) updateOverlayStatus()
    }
}
