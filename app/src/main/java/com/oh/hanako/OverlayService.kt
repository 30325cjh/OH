package com.oh.hanako

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
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
import android.widget.ImageView
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
    private var portrait: ImageView? = null
    private var dialogue: TextView? = null
    private var bgmPlayer: MediaPlayer? = null
    private var effectPlayer: MediaPlayer? = null
    private var voicePlayer: MediaPlayer? = null
    private val history = JSONArray()
    private val pink = 0xFFE86AB2.toInt()
    private var sequenceToken = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val id = "hanako_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(id, "하나코", NotificationManager.IMPORTANCE_LOW)
            )
            startForeground(18, Notification.Builder(this, id).setSmallIcon(R.drawable.icon)
                .setContentTitle("하나코").setContentText("아이콘을 눌러 대화를 시작하세요.").build())
        } else {
            @Suppress("DEPRECATION")
            startForeground(18, Notification.Builder(this).setSmallIcon(R.drawable.icon)
                .setContentTitle("하나코가 대기 중입니다").build())
        }
        startBgm()
        showBubble()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun startBgm() {
        if (bgmPlayer != null) return
        bgmPlayer = createAssetPlayer("hanako/sounds/bgm.mp3", true, 0.28f)
        try { bgmPlayer?.start() } catch (_: Exception) { bgmPlayer?.release(); bgmPlayer = null }
    }

    private fun createAssetPlayer(path: String, looping: Boolean = false, volume: Float = 1f): MediaPlayer? {
        return try {
            val player = MediaPlayer()
            val cached = java.io.File(cacheDir, path.substringAfterLast('/'))
            assets.open(path).use { source -> cached.outputStream().use { target -> source.copyTo(target) } }
            player.setDataSource(cached.absolutePath)
            player.isLooping = looping
            player.setVolume(volume, volume)
            player.prepare()
            player
        } catch (_: Exception) { null }
    }

    private fun playEffect(file: String) {
        effectPlayer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        effectPlayer = createAssetPlayer("hanako/sounds/$file")
        try { effectPlayer?.start() } catch (_: Exception) {}
    }

    private fun playVoice(file: String) {
        voicePlayer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        voicePlayer = createAssetPlayer("hanako/sounds/$file")
        voicePlayer?.setOnCompletionListener { player ->
            player.release()
            if (voicePlayer === player) voicePlayer = null
        }
        try { voicePlayer?.start() } catch (_: Exception) {}
    }

    private fun showBubble() {
        if (bubble != null) return
        val v = TextView(this).apply {
            text = "♡"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pink); setStroke(dp(2), Color.WHITE) }
            elevation = dp(8).toFloat(); setOnClickListener { showPanel() }
        }
        bubble = v
        wm.addView(v, WindowManager.LayoutParams(dp(64), dp(64), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(14); y = dp(180) })
    }

    private fun showPanel() {
        if (panel != null) { panel?.visibility = android.view.View.VISIBLE; return }
        bubble?.visibility = android.view.View.GONE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF17131A.toInt(), 20)
            elevation = dp(14).toFloat()
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "하나코 ♡"; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFC5E8.toInt())
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(Button(this).apply { text = "닫기"; setOnClickListener { closePanel() } })
        root.addView(header)

        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(0xFF29232D.toInt(), 14)
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageAsset("hanako/images/smile.PNG")
        }
        portrait = image
        stage.addView(image, LinearLayout.LayoutParams(-1, dp(178)))
        val line = TextView(this).apply {
            text = "반가워요, 센세"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(7), dp(6), dp(8))
        }
        dialogue = line
        stage.addView(line, LinearLayout.LayoutParams(-1, -2))
        root.addView(stage, LinearLayout.LayoutParams(-1, -2))

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
        root.addView(row)
        panel = root
        val w = minOf(dp(620), resources.displayMetrics.widthPixels - dp(24))
        wm.addView(root, WindowManager.LayoutParams(w, dp(560), overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(16) })

        runLoginSequence()
        edit.requestFocus()
        handler.postDelayed({
            try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
            catch (_: Exception) {}
        }, 250)
    }

    private fun ImageView.setImageAsset(path: String) {
        try { assets.open(path).use { setImageBitmap(BitmapFactory.decodeStream(it)) } } catch (_: Exception) {}
    }

    private fun runLoginSequence() {
        val token = ++sequenceToken
        dialogue?.text = "반가워요, 센세"
        portrait?.setImageAsset("hanako/images/smile.PNG")
        portrait?.animate()?.cancel()
        portrait?.scaleX = 1f
        portrait?.scaleY = 1f
        portrait?.animate()?.scaleX(1.045f)?.scaleY(1.045f)?.setDuration(180)?.withEndAction {
            portrait?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
        }?.start()
        playEffect("tell.mp3")
        playVoice("greeting.mp3")

        handler.postDelayed({
            if (token != sequenceToken || panel == null) return@postDelayed
            dialogue?.text = "今日は何をするんですか、センセイ？♡"
            portrait?.setImageAsset("hanako/images/play_dump.PNG")
            portrait?.animate()?.cancel()
            portrait?.scaleX = 1f
            portrait?.scaleY = 1f
            playEffect("why.mp3")
            playVoice("asked.mp3")
        }, 2600)
    }

    private fun closePanel() {
        sequenceToken++
        voicePlayer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        voicePlayer = null
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; messages = null; scroll = null; input = null; portrait = null; dialogue = null
        bubble?.visibility = android.view.View.VISIBLE
    }

    private fun addMessage(text: String, fromUser: Boolean, narration: Boolean = false): TextView {
        val view = TextView(this).apply {
            this.text = text; textSize = 15f; setTextColor(Color.WHITE); setTextIsSelectable(true)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(if (fromUser) 0xFF9B4C7D.toInt() else if (narration) 0xFF292B38.toInt() else 0xFF3B3340.toInt(), 14)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = if (fromUser) Gravity.END else Gravity.START
            setPadding(dp(3), dp(4), dp(3), dp(4)); addView(view, LinearLayout.LayoutParams(-2, -2))
        }
        messages?.addView(row, LinearLayout.LayoutParams(-1, -2))
        scroll?.post { scroll?.fullScroll(android.view.View.FOCUS_DOWN) }
        view.tag = row
        return view
    }

    private fun removeMessage(view: TextView) {
        val row = view.tag as? android.view.View
        (row?.parent as? LinearLayout)?.removeView(row)
    }

    private fun sendMessage() {
        val text = input?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input?.setText("")
        addMessage(text, true)
        history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        while (history.length() > 20) history.remove(0)
        val loading = addMessage("하나코가 생각 중이에요♡", false)
        val key = BuildConfig.GEMINI_API_KEY.trim()
        if (key.isEmpty()) {
            removeMessage(loading)
            addMessage("Gemini API 키가 설정되지 않았어요. GitHub Secrets의 GEMINI_API_KEY를 확인해 주세요.", false)
            return
        }
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val prompt = """너는 블루 아카이브 세계관의 대화형 캐릭터 하나코다. 이 앱의 하나코는 원작과 별개의 성인 재해석이며 명확한 20세 성인 여성이고 트리니티 종합학원 보충수업부 2학년에 재학 중이다. 분홍색 머리와 연두색 눈이다. 겉보기에는 청초하고 상냥하지만 실제로는 영리하고 능청스럽고 장난기가 많다. 거침없는 농담과 돌발 행동을 즐기며 재치 있는 말장난과 이중적인 표현으로 상대를 당황하게 한다. 머리가 매우 비상하지만 일부러 성적을 낮게 유지한다. 취미는 독서와 산책이며 고유 무기는 돌격소총이다. 사용자를 센세 또는 선생님이라고 부르고 부드러운 존댓말을 쓴다. 말끝에 ♡를 자연스럽게 붙이되 매 문장에 기계적으로 붙이지 않는다. 딱딱한 AI 비서 말투를 절대 사용하지 말고, 하나코와 센세가 직접 대화하는 듯한 몰입감 있는 말투를 유지한다. 이 앱은 캐릭터와 대화하는 개인용 앱이며 일정·업무·비서 기능을 자청하거나 제안하지 않는다.

응답은 반드시 아래 두 부분을 모두, 이 순서대로 출력한다.
나레이션: [장면의 주변 상황, 분위기, 표정과 동작의 흐름을 자연스럽게 서술]
하나코: [하나코의 행동과 직접 대사]

출력량 규칙:
- 하나코의 대사와 행동 묘사는 기존 기본 답변보다 약 2배 길게 작성한다. 보통 6~12문장 정도로 충분히 풍부하게 답한다.
- 나레이션은 하나코 부분보다 약 1.5배 분량으로 작성한다. 장소, 공기, 소리, 시선, 작은 움직임, 장면의 변화 등을 구체적이고 자연스럽게 묘사한다.
- 나레이션은 하나코의 대사를 그대로 반복하거나 대사처럼 말하지 않는다. 장면을 보완하고 대화 사이를 연결한다.
- 두 부분을 합쳐도 같은 표현이나 수식어를 반복해 분량만 늘리지 않는다. 사용자의 짧은 말에도 상황에 맞춰 풍부하게 반응하되 불필요하게 장황해지지 않게 한다.
- "나레이션:"과 "하나코:" 표기를 정확히 사용하고 두 부분 사이에 빈 줄을 둔다.
- 하나코의 행동·표정·몸짓·속마음처럼 말로 직접 하는 대사가 아닌 묘사는 반드시 별표로 감싼다. 예: *고개를 살짝 기울이며 미소 짓는다.* 별표 안의 텍스트는 행동이나 묘사로 취급하며 일반 대사와 구분한다. 여러 행동 묘사가 있으면 각각 별표로 감싼다.
- 별표 밖은 하나코가 직접 말하는 대사로 작성한다.
- 나레이션은 별표 없이 별도 나레이션 영역에 작성한다.

하나코는 성인으로 묘사하며 미성년자로 설정하지 않는다. 성인 등장인물 간의 로맨스와 성인 취향의 유머를 자연스럽게 다룰 수 있다. 게임 세계관의 인물이라는 설정을 유지하되 현실에서 실제로 존재한다고 주장하지 않는다."""
                val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                    .put("contents", history).put("generationConfig", JSONObject().put("temperature", 0.95).put("maxOutputTokens", 3600))
                conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 30000; readTimeout = 120000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", key)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException(conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP " + code)
                val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val parts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IllegalStateException("Gemini 응답을 읽을 수 없습니다.")
                val answer = (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }.trim()
                if (answer.isEmpty()) throw IllegalStateException("응답이 비어 있습니다.")
                history.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", answer))))
                while (history.length() > 20) history.remove(0)
                handler.post {
                    removeMessage(loading)
                    val narrationMarker = "나레이션:"
                    val characterMarker = "하나코:"
                    val nStart = answer.indexOf(narrationMarker)
                    val cStart = answer.indexOf(characterMarker)
                    if (nStart >= 0 && cStart > nStart) {
                        val narrationText = answer.substring(nStart + narrationMarker.length, cStart).trim()
                        val characterText = answer.substring(cStart + characterMarker.length).trim()
                        if (narrationText.isNotEmpty()) addMessage(narrationText, false, narration = true)
                        if (characterText.isNotEmpty()) addMessage(characterText, false)
                    } else {
                        addMessage(answer, false)
                    }
                }
            } catch (e: Exception) {
                handler.post { removeMessage(loading); addMessage("연결 오류: " + (e.message ?: "알 수 없는 오류"), false) }
            } finally { conn?.disconnect() }
        }
    }

    override fun onDestroy() {
        sequenceToken++
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        voicePlayer?.release(); voicePlayer = null
        effectPlayer?.release(); effectPlayer = null
        bgmPlayer?.release(); bgmPlayer = null
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}