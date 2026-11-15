package com.oh.hanako
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

class MainActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasVerifiedAdultAge()) showMainUi() else showAgeVerification()
    }
    private fun hasVerifiedAdultAge(): Boolean {
        val p = getSharedPreferences("hanako_age_gate", MODE_PRIVATE)
        val y = p.getInt("birth_year", -1); val m = p.getInt("birth_month", -1); val d = p.getInt("birth_day", -1)
        return y > 0 && m in 1..12 && d in 1..31 && calculateAge(y, m, d) >= 18
    }
    private fun calculateAge(year: Int, month: Int, day: Int): Int {
        val now = Calendar.getInstance()
        var age = now.get(Calendar.YEAR) - year
        val m = now.get(Calendar.MONTH) + 1; val d = now.get(Calendar.DAY_OF_MONTH)
        if (m < month || (m == month && d < day)) age--
        return age
    }
    private fun showAgeVerification() {
        val now = Calendar.getInstance()
        val picker = DatePickerDialog(this, { _, y, m, d ->
            val month = m + 1
            if (calculateAge(y, month, d) >= 18) {
                getSharedPreferences("hanako_age_gate", MODE_PRIVATE).edit()
                    .putInt("birth_year", y).putInt("birth_month", month).putInt("birth_day", d).apply()
                showMainUi()
            } else {
                AlertDialog.Builder(this).setTitle("이용할 수 없습니다")
                    .setMessage("이 앱은 만 18세 이상만 이용할 수 있습니다.")
                    .setCancelable(false).setPositiveButton("확인") { _, _ -> finish() }.show()
            }
        }, 2000, 0, 1)
        picker.setTitle("생년월일 확인 · 만 18세 이상")
        picker.datePicker.maxDate = now.timeInMillis
        picker.setCancelable(false); picker.setOnCancelListener { finish() }; picker.show()
    }
    private fun showMainUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 36, 28, 28); setBackgroundColor(0xFFFFF5FB.toInt())
        }
        root.addView(TextView(this).apply { text = "하나코 ♡"; textSize = 32f; setTextColor(0xFFE86AB2.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply { text = "트리니티의 하나코가 화면 위에서 기다리고 있어요."; textSize = 15f; setPadding(0, 12, 0, 20) })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 16) }; root.addView(status)
        root.addView(Button(this).apply {
            text = "화면 위에 표시 권한 허용"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this@MainActivity))
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                else status.text = "오버레이 권한이 허용되어 있어요."
            }
        })
        root.addView(Button(this).apply {
            text = "하나코 시작"
            setOnClickListener {
                if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this@MainActivity)) {
                    val service = Intent(this@MainActivity, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                    status.text = "하나코가 화면 위에서 대기 중이에요!"
                } else {
                    status.text = "먼저 화면 위에 표시 권한을 허용해 주세요."
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                }
            }
        })
        setContentView(root)
    }
    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) "오버레이 권한: 허용됨" else "오버레이 권한: 아직 허용되지 않음"
    }
}
