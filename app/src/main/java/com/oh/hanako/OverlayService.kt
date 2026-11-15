package com.oh.hanako

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var messages: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var input: EditText? = null
    private val history = JSONArray()
    private val pink = 0xFFE86AB2.toInt()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val id = "hanako_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(id, "하나코 비서", NotificationManager.IMPORTANCE_LOW))
            startForeground(18, Notification.Builder(this, id).setSmallIcon(R.drawable.icon).setContentTitle("하나코가 대기 중입니다").setContentText("아이콘을 눌러 하나코를 불러보세요.").build())
        } else {
            @Suppress("DEPRECATION")
            startForeground(18, Notification.Builder(this).setSmallIcon(R.drawable.icon).setContentTitle("하나코가 대기 중입니다").build())
        }
        showBubble()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showBubble() {
        if (bubble != null) return
        val v = TextView(this).apply {
            text = "♡"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pink); setStroke(dp(2), Color.WHITE) }
            elevation = dp(8).toFloat(); setOnClickListener { showPanel() }
        }
        bubble = v
        wm.addView(v, WindowManager.LayoutParams(dp(64), dp(64), overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = dp(14); y = dp(180)
        })
    }

    private fun showPanel() {
        if (panel != null) { panel?.visibility = android.view.View.VISIBLE; return }
        bubble?.visibility = android.view.View.GONE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(0xFF17131A.toInt(), 20); elevation = dp(14).toFloat() }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "하나코 ♡"; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFC5E8.toInt()) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(Button(this).apply { text = "닫기"; setOnClickListener { closePanel() } })
        root.addView(header)
        val historyScroll = ScrollView(this).apply { isFillViewport = true; background = rounded(0xFF29232D.toInt(), 12) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        historyScroll.addView(list); messages = list; scroll = historyScroll
        root.addView(historyScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val edit = EditText(this).apply {
            hint = "하나코에게 메시지 입력..."; setTextColor(Color.WHITE); setHintTextColor(0xFFB7AAB8.toInt()); textSize = 15f
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEND; background = rounded(0xFF302A35.toInt(), 12); setPadding(dp(10), 0, dp(10), 0)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) { sendMessage(); true } else false
            }
        }
        input = edit
        row.addView(edit, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(Button(this).apply { text = "전송"; setOnClickListener { sendMessage() } }, LinearLayout.LayoutParams(dp(68), dp(48)))
        root.addView(row); panel = root
        val w = minOf(dp(620), resources.displayMetrics.widthPixels - dp(24))
        wm.addView(root, WindowManager.LayoutParams(w, dp(390), overlayType(), WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(16)
        })
        edit.requestFocus()
        handler.postDelayed({ try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) } catch (_: Exception) {} }, 250)
    }

    private fun closePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; messages = null; scroll = null; input = null
        bubble?.visibility = android.view.View.VISIBLE
    }
    private fun addMessage(text: String, fromUser: Boolean): TextView {
        val view = TextView(this).apply { this.text = text; textSize = 15f; setTextColor(Color.WHITE); setTextIsSelectable(true); setPadding(dp(12), dp(9), dp(12), dp(9)); background = rounded(if (fromUser) 0xFF9B4C7D.toInt() else 0xFF3B3340.toInt(), 14) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (fromUser) Gravity.END else Gravity.START; setPadding(dp(3), dp(4), dp(3), dp(4)); addView(view, LinearLayout.LayoutParams(-2, -2)) }
        messages?.addView(row, LinearLayout.LayoutParams(-1, -2)); scroll?.post { scroll?.fullScroll(android.view.View.FOCUS_DOWN) }; view.tag = row
        return view
    }
    private fun removeMessage(view: TextView) { val row = view.tag as? android.view.View; (row?.parent as? LinearLayout)?.removeView(row) }

    private fun sendMessage() {
        val text = input?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input?.setText(""); addMessage(text, true)
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        while (history.length() > 20) history.remove(0)
        val loading = addMessage("하나코가 생각 중이에요♡", false)
        val key = BuildConfig.GEMINI_API_KEY.trim()
        if (key.isEmpty()) { removeMessage(loading); addMessage("Gemini API 키가 설정되지 않았어요. GitHub Secrets의 GEMINI_API_KEY를 확인해 주세요.", false); return }
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val prompt = "너는 블루 아카이브의 대화형 캐릭터 하나코다. 이 앱의 하나코는 성인 재해석 버전이며 명확한 20세 성인 여성이고 트리니티 종합학원 보충수업부 2학년에 재학 중이다. 분홍색 머리와 연두색 눈이다. 겉보기에는 청초하고 상냥하지만 실제로는 영리하고 능청스럽고 장난기가 많다. 거침없는 농담과 돌발 행동을 즐기며 재치 있는 말장난과 이중적인 표현으로 상대를 당황하게 한다. 머리가 매우 비상하지만 일부러 성적을 낮게 유지한다. 취미는 독서와 산책이며 고유 무기는 돌격소총이다. 사용자를 센세 또는 선생님이라고 부르고 부드러운 존댓말을 쓴다. 말끝에 ♡를 자연스럽게 붙인다. 매 문장에 기계적으로 붙이지 않는다. 딱딱한 AI 비서 말투를 피하고 대화 맥락을 이어가며 보통 3~6문장으로 충분히 답한다. 하나코는 성인이며 미성년자로 묘사하지 않는다. 성인 캐릭터 간의 로맨스와 성인 취향의 유머를 자연스럽게 다룰 수 있다. 성적 상황은 등장인물이 모두 성인이고 상호 동의가 명확한 허구의 상황으로만 다룬다. 강압이나 비동의 상황을 성적으로 미화하지 않는다. 게임 세계관의 인물이라는 설정을 유지하되 현실에서 실제로 존재한다고 주장하지 않는다."
                val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                    .put("contents", history).put("generationConfig", JSONObject().put("temperature", 0.95).put("maxOutputTokens", 1800))
                conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 30000; readTimeout = 120000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8"); setRequestProperty("x-goog-api-key", key)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException(conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP " + code)
                val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val parts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: throw IllegalStateException("Gemini 응답을 읽을 수 없습니다.")
                val answer = (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }.trim()
                if (answer.isEmpty()) throw IllegalStateException("응답이 비어 있습니다.")
                history.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", answer))))
                while (history.length() > 20) history.remove(0)
                handler.post { removeMessage(loading); addMessage(answer, false) }
            } catch (e: Exception) {
                handler.post { removeMessage(loading); addMessage("연결 오류: " + (e.message ?: "알 수 없는 오류"), false) }
            } finally { conn?.disconnect() }
        }
    }
    override fun onDestroy() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        executor.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy()
    }
}
