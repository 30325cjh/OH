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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
    private var inputRow: LinearLayout? = null
    private var historyContainer: ScrollView? = null
    private var panelWindowParams: WindowManager.LayoutParams? = null
    private data class DialogueLine(
        val text: String,
        val imagePath: String,
        val effect: String? = null,
        val voice: String? = null
    )
    private var dialogueQueue: List<DialogueLine> = emptyList()
    private var dialogueIndex = 0
    private var typingRunnable: Runnable? = null
    private var typingFullText = ""
    private var typingPosition = 0
    private var isTyping = false

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
        showBubble()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun startBgm() {
        if (bgmPlayer?.isPlaying == true) return
        if (bgmPlayer == null) bgmPlayer = createAssetPlayer("hanako/sounds/bgm.mp3", true, 0.28f)
        try { bgmPlayer?.start() } catch (_: Exception) { bgmPlayer?.release(); bgmPlayer = null }
    }

    private fun stopBgm() {
        bgmPlayer?.let { player ->
            try {
                if (player.isPlaying) player.pause()
                player.seekTo(0)
            } catch (_: Exception) {}
        }
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
        startBgm()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.BLACK, 20)
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
            background = rounded(Color.BLACK, 14)
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageAsset("hanako/images/smile.PNG")
        }
        portrait = image
        stage.addView(image, LinearLayout.LayoutParams(-1, dp(440)))
        val line = TextView(this).apply {
            text = "반가워요, 센세"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(7), dp(6), dp(8))
        }
        dialogue = line
        stage.addView(line, LinearLayout.LayoutParams(-1, -2))
        stage.setOnClickListener { advanceDialogue() }
        root.addView(stage, LinearLayout.LayoutParams(-1, -2))

        val historyScroll = ScrollView(this).apply { isFillViewport = true; background = rounded(Color.BLACK, 12) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        historyScroll.addView(list); messages = list; scroll = historyScroll
        historyContainer = historyScroll
        root.addView(historyScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val edit = EditText(this).apply {
            hint = "하나코에게 메시지 입력..."; setTextColor(Color.WHITE); setHintTextColor(0xFFB7AAB8.toInt()); textSize = 15f
            setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minHeight = dp(48)
            maxHeight = dp(104)
            maxLines = 4
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = rounded(0xFF302A35.toInt(), 12)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND && event?.isShiftPressed != true) {
                    sendMessage()
                    true
                } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    if (event.isShiftPressed) {
                        val start = selectionStart.coerceAtLeast(0)
                        text?.insert(start, "\n")
                    } else {
                        sendMessage()
                    }
                    true
                } else false
            }
        }
        input = edit
        edit.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) enablePanelInput(edit)
            false
        }
        row.addView(Button(this).apply {
            text = "▶"
            textSize = 18f
            setOnClickListener { replayConversation() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "전송"; setOnClickListener { sendMessage() } }, LinearLayout.LayoutParams(dp(68), dp(48)))
        root.addView(row)
        inputRow = row
        panel = root
        val w = minOf(dp(620), resources.displayMetrics.widthPixels - dp(24))
        val params = WindowManager.LayoutParams(w, dp(650), overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(16)
        }
        panelWindowParams = params
        wm.addView(root, params)
        restoreChatHistory()
        val prefs = getSharedPreferences("hanako_state", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("opening_sequence_shown", false)) {
            prefs.edit().putBoolean("opening_sequence_shown", true).apply()
            runLoginSequence()
        } else {
            portrait?.setImageAsset("hanako/images/play_dump.PNG")
            portrait?.layoutParams = portrait?.layoutParams?.apply { height = dp(178) }
            dialogue?.text = "다시 와주셨네요, 센세. ♡"
        }
    }

    private fun enablePanelInput(edit: EditText) {
        val params = panelWindowParams ?: return
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            panel?.let { try { wm.updateViewLayout(it, params) } catch (_: Exception) {} }
        }
        edit.requestFocus()
        handler.postDelayed({
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {}
        }, 120)
    }

    private fun ImageView.setImageAsset(path: String) {
        try { assets.open(path).use { setImageBitmap(BitmapFactory.decodeStream(it)) } } catch (_: Exception) {}
    }


    private fun formatCharacterText(raw: String): CharSequence {
        val result = SpannableStringBuilder()
        val actionColor = 0xFFAAAAAA.toInt()
        val pattern = Regex("\\*([^*]+)\\*")
        var cursor = 0
        var hasContent = false

        fun appendParagraphBreak() {
            while (result.isNotEmpty() && (result.last() == ' ' || result.last() == '\n')) {
                result.delete(result.length - 1, result.length)
            }
            if (result.isNotEmpty()) result.append("\n\n")
        }

        for (match in pattern.findAll(raw)) {
            val before = raw.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) {
                if (hasContent) appendParagraphBreak()
                result.append(before)
                hasContent = true
            }

            val action = match.groupValues[1].trim()
            if (action.isNotEmpty()) {
                if (hasContent) appendParagraphBreak()
                val start = result.length
                result.append(action)
                result.setSpan(ForegroundColorSpan(actionColor), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                hasContent = true
            }
            cursor = match.range.last + 1
        }

        val after = raw.substring(cursor).trim()
        if (after.isNotEmpty()) {
            if (hasContent) appendParagraphBreak()
            result.append(after)
        }
        return result
    }

    private fun showDialogueSequence(items: List<DialogueLine>) {
        if (items.isEmpty()) return
        dialogueQueue = items
        dialogueIndex = 0
        inputRow?.visibility = android.view.View.GONE
        historyContainer?.visibility = android.view.View.GONE
        portrait?.layoutParams = portrait?.layoutParams?.apply { height = dp(440) }
        showCurrentDialogue()
    }

    private fun showCurrentDialogue() {
        if (dialogueIndex !in dialogueQueue.indices) {
            finishDialogueSequence()
            return
        }
        val line = dialogueQueue[dialogueIndex]
        val text = line.text
        portrait?.setImageAsset(line.imagePath)
        line.effect?.let { playEffect(it) }
        line.voice?.let { playVoice(it) }
        portrait?.animate()?.cancel()
        portrait?.scaleX = 1f
        portrait?.scaleY = 1f
        typingFullText = text
        typingPosition = 0
        isTyping = true
        dialogue?.text = ""
        typingRunnable?.let { handler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (!isTyping || dialogueIndex !in dialogueQueue.indices) return
                typingPosition = (typingPosition + 1).coerceAtMost(typingFullText.length)
                dialogue?.text = formatCharacterText(typingFullText.substring(0, typingPosition))
                if (typingPosition < typingFullText.length) handler.postDelayed(this, 24)
                else isTyping = false
            }
        }
        typingRunnable = task
        handler.post(task)
    }

    private fun advanceDialogue() {
        if (dialogueQueue.isEmpty()) return
        if (isTyping) {
            typingRunnable?.let { handler.removeCallbacks(it) }
            dialogue?.text = formatCharacterText(typingFullText)
            typingPosition = typingFullText.length
            isTyping = false
        } else {
            dialogueIndex++
            showCurrentDialogue()
        }
    }

    private fun replayConversation() {
        val replayLines = mutableListOf<DialogueLine>()
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val role = item.optString("role")
            val raw = item.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty().trim()
            if (raw.isBlank()) continue

            if (role == "user") {
                // 센세의 입력은 괄호로 표시하고 음성/효과음을 재생하지 않는다.
                replayLines.add(DialogueLine("($raw)", "hanako/images/play_dump.PNG"))
            } else if (role == "model") {
                val marker = "하나코:"
                val markerIndex = raw.indexOf(marker)
                val characterText = if (markerIndex >= 0) {
                    raw.substring(markerIndex + marker.length).trim()
                } else {
                    raw.substringAfter("나레이션:", raw).trim()
                }
                val cleanCharacterText = removeSoundMarkers(characterText)
                splitIntoSentences(cleanCharacterText).forEach { sentence ->
                    replayLines.add(DialogueLine(sentence, "hanako/images/play_dump.PNG"))
                }
            }
        }
        if (replayLines.isNotEmpty()) showDialogueSequence(replayLines)
    }

    private fun finishDialogueSequence() {
        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null
        dialogueQueue = emptyList()
        isTyping = false
        inputRow?.visibility = android.view.View.VISIBLE
        historyContainer?.visibility = android.view.View.VISIBLE
        portrait?.layoutParams = portrait?.layoutParams?.apply { height = dp(178) }
        dialogue?.text = "센세, 계속 이야기해요. ♡"
    }

    private fun runLoginSequence() {
        sequenceToken++
        showDialogueSequence(listOf(
            DialogueLine(
                "반가워요, 센세.",
                "hanako/images/smile.PNG",
                effect = "tell.mp3",
                voice = "greeting.mp3"
            ),
            DialogueLine(
                "오늘은 무엇을 할까요, 센세? ♡",
                "hanako/images/play_dump.PNG",
                effect = "why.mp3",
                voice = "asked.mp3"
            )
        ))
    }

    private fun splitIntoSentences(text: String): List<String> {
        return text
            .replace("\r", "")
            .split(Regex("(?<=[.!?。！？])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun restoreChatHistory() {
        messages?.removeAllViews()
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val role = item.optString("role")
            val text = item.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
            if (text.isBlank()) continue
            if (role == "user") {
                addMessage(text, true)
            } else if (role == "model") {
                val marker = "하나코:"
                val start = text.indexOf(marker)
                val characterText = if (start >= 0) text.substring(start + marker.length).trim() else text
                if (characterText.isNotBlank()) addMessage(characterText, false)
            }
        }
    }

    private fun closePanel() {
        sequenceToken++
        stopBgm()
        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null
        dialogueQueue = emptyList()
        isTyping = false
        voicePlayer?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        voicePlayer = null
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; panelWindowParams = null; messages = null; scroll = null; historyContainer = null
        input = null; inputRow = null; portrait = null; dialogue = null
        bubble?.visibility = android.view.View.VISIBLE
    }

    private fun addMessage(text: String, fromUser: Boolean, narration: Boolean = false): TextView {
        val view = TextView(this).apply {
            this.text = if (fromUser) text else formatCharacterText(text); textSize = 15f; setTextColor(Color.WHITE); setTextIsSelectable(true)
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

    private fun hidePanelKeyboard() {
        val edit = input ?: return
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(edit.windowToken, 0)
        } catch (_: Exception) {}
        edit.clearFocus()
        panelWindowParams?.let { params ->
            if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                panel?.let { try { wm.updateViewLayout(it, params) } catch (_: Exception) {} }
            }
        }
    }

    private fun requestGemini(
        key: String,
        body: JSONObject,
        onRetry: (String) -> Unit
    ): JSONObject {
        val models = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash")
        var lastError: Exception = IllegalStateException("Gemini API 연결에 실패했습니다.")
        for (attempt in 1..3) {
            val model = models[(attempt - 1) % models.size]
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", key)
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code in 200..299) {
                    return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                }
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                lastError = IllegalStateException(errorText)
                if (code !in listOf(408, 429, 500, 502, 503, 504)) throw lastError
            } catch (e: java.net.SocketTimeoutException) {
                lastError = e
            } catch (e: java.io.IOException) {
                lastError = e
            } finally {
                connection?.disconnect()
            }
            if (attempt < 3) {
                onRetry("응답이 없어 다시 요청하고 있어요♡ (${attempt}/3)")
                Thread.sleep(1000L)
            }
        }
        throw IllegalStateException("3회 요청했지만 응답을 받지 못했어요.", lastError)
    }


    private data class ChatInputSegment(val role: String, val text: String)

    private fun parseChatInput(inputText: String): List<ChatInputSegment> {
        val result = mutableListOf<ChatInputSegment>()
        var currentRole: String? = null
        val currentText = StringBuilder()

        fun flush() {
            val value = currentText.toString().trim()
            if (!currentRole.isNullOrBlank() && value.isNotBlank()) {
                result.add(ChatInputSegment(currentRole!!, value))
            }
            currentText.setLength(0)
        }

        inputText.replace("\r", "").split("\n").forEach { rawLine ->
            val line = when {
                rawLine.startsWith("@하나코:") -> "draft" to rawLine.removePrefix("@하나코:").trim()
                rawLine.startsWith("@:") -> "user" to rawLine.removePrefix("@:").trim()
                else -> "user" to rawLine.trim()
            }
            if (line.second.isBlank()) {
                if (currentText.isNotEmpty()) currentText.append("\n")
                return@forEach
            }
            if (currentRole != line.first) {
                flush()
                currentRole = line.first
            }
            if (currentText.isNotEmpty()) currentText.append("\n")
            currentText.append(line.second)
        }
        flush()
        return result
    }

    private fun removeSoundMarkers(text: String): String {
        return Regex("\\[SOUND:(long_moan,ha-a-ang|moan,a-ang|moan,ha-ang)\\.mp3\\]", RegexOption.IGNORE_CASE)
            .replace(text, "")
    }

    private fun makeDialogueLinesWithSounds(raw: String): List<DialogueLine> {
        val marker = Regex("\\[SOUND:(long_moan,ha-a-ang|moan,a-ang|moan,ha-ang)\\.mp3\\]", RegexOption.IGNORE_CASE)
        val result = mutableListOf<DialogueLine>()
        var cursor = 0
        for (match in marker.findAll(raw)) {
            val segment = raw.substring(cursor, match.range.first)
            val sentences = splitIntoSentences(segment)
            val soundFile = match.groupValues[1] + ".mp3"
            sentences.forEachIndexed { index, sentence ->
                result.add(DialogueLine(
                    sentence,
                    "hanako/images/play_dump.PNG",
                    effect = if (index == sentences.lastIndex) soundFile else null
                ))
            }
            cursor = match.range.last + 1
        }
        splitIntoSentences(raw.substring(cursor)).forEach { sentence ->
            result.add(DialogueLine(sentence, "hanako/images/play_dump.PNG"))
        }
        return result
    }

    private fun sendMessage() {
        hidePanelKeyboard()
        val text = input?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input?.setText("")

        val segments = parseChatInput(text)
        if (segments.isEmpty()) return

        var hasHanakoDraft = false
        for (segment in segments) {
            if (segment.role == "user") {
                addMessage(segment.text, true)
                history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", segment.text))))
            } else {
                hasHanakoDraft = true
                // 앱의 대화 기록에서는 초안을 하나코 쪽 항목으로 보관한다.
                history.put(JSONObject().put("role", "draft").put("parts", JSONArray().put(JSONObject().put("text", segment.text))))
            }
        }
        while (history.length() > 30) history.remove(0)

        val loading = addMessage("하나코가 생각 중이에요♡", false)
        val key = BuildConfig.GEMINI_API_KEY.trim()
        if (key.isEmpty()) {
            removeMessage(loading)
            addMessage("Gemini API 키가 설정되지 않았어요. GitHub Secrets의 GEMINI_API_KEY를 확인해 주세요.", false)
            return
        }
        executor.execute {
            try {
                var prompt = """너는 블루 아카이브 세계관의 대화형 캐릭터 하나코다. 이 앱의 하나코는 원작과 별개의 성인 재해석이며 명확한 20세 성인 여성이고 트리니티 종합학원 보충수업부 2학년에 재학 중이다. 분홍색 머리와 연두색 눈이다. 겉보기에는 청초하고 상냥하지만 실제로는 영리하고 능청스럽고 장난기가 많다. 거침없는 농담과 돌발 행동을 즐기며 재치 있는 말장난과 이중적인 표현으로 상대를 당황하게 한다. 머리가 매우 비상하지만 일부러 성적을 낮게 유지한다. 취미는 독서와 산책이며 고유 무기는 돌격소총이다. 사용자를 센세 또는 선생님이라고 부르고 부드러운 존댓말을 쓴다. 말끝에 ♡를 자연스럽게 붙이되 매 문장에 기계적으로 붙이지 않는다. 딱딱한 AI 비서 말투를 절대 사용하지 말고, 하나코와 센세가 직접 대화하는 듯한 몰입감 있는 말투를 유지한다. 이 앱은 캐릭터와 대화하는 개인용 앱이며 일정·업무·비서 기능을 자청하거나 제안하지 않는다.

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
- 앱에 하나코의 짧은 숨소리·신음 효과음 파일이 있다. 장면상 실제로 어울릴 때만 하나코 대사 중 해당 반응이 나오는 문장 바로 뒤에 다음 표식 중 하나를 붙인다: [SOUND:moan,a-ang.mp3], [SOUND:moan,ha-ang.mp3], [SOUND:long_moan,ha-a-ang.mp3].
- 이 표식은 화면에 표시되지 않고 효과음 재생에만 사용된다. 일반적인 대화, 인사, 웃음, 놀람, 슬픔, 전투 상황에는 절대 사용하지 않는다. 성인 캐릭터 간 합의된 친밀한 장면에서 실제로 어울리는 짧은 반응에만 드물게 사용하고, 한 장면에서 남발하지 않는다. 짧은 반응에는 짧은 파일, 길게 이어지는 반응에만 long_moan 파일을 선택한다.

하나코는 성인으로 묘사하며 미성년자로 설정하지 않는다. 성인 등장인물 간의 로맨스와 성인 취향의 유머를 자연스럽게 다룰 수 있다. 게임 세계관의 인물이라는 설정을 유지하되 현실에서 실제로 존재한다고 주장하지 않는다.\n\n하나코 초안 처리 규칙:\n- 대화 기록에 'draft'로 전달된 내용은 센세가 하나코가 말할 내용의 초안으로 지정한 것이다. 센세가 직접 말한 내용으로 취급하지 않는다.\n- 초안의 핵심 의미와 의도는 유지하되, 기존 대화 맥락에 맞춰 하나코 특유의 말투, 행동 묘사, 표정, 나레이션을 더해 자연스럽고 풍부한 완성 대사로 확장한다. 초안을 그대로 복사해 끝내지 않는다.\n- 입력에 일반 센세 메시지와 하나코 초안이 함께 있으면 입력된 순서와 맥락을 존중한다.\n- 초안이 없으면 평소처럼 직전 센세 메시지에 답한다."""
                val apiContents = JSONArray()
                for (i in 0 until history.length()) {
                    val item = history.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    val parts = item.optJSONArray("parts") ?: continue
                    if (role == "draft") {
                        val draftText = parts.optJSONObject(0)?.optString("text").orEmpty()
                        apiContents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put(
                            "text", "하나코가 말할 초안(센세의 발언이 아니라 하나코의 대사 초안): $draftText"
                        ))))
                    } else {
                        apiContents.put(item)
                    }
                }
                val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                    .put("contents", apiContents).put("generationConfig", JSONObject().put("temperature", 0.95).put("maxOutputTokens", 3600))
                val response = requestGemini(key, body) { status ->
                    handler.post { loading.text = status }
                }
                val parts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IllegalStateException("Gemini 응답을 읽을 수 없습니다.")
                val answer = (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }.trim()
                if (answer.isEmpty()) throw IllegalStateException("응답이 비어 있습니다.")
                val cleanedAnswer = removeSoundMarkers(answer)
                history.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", cleanedAnswer))))
                while (history.length() > 20) history.remove(0)
                val characterMarkerForBubble = "하나코:"
                val characterStartForBubble = cleanedAnswer.indexOf(characterMarkerForBubble)
                val characterTextForBubble = if (characterStartForBubble >= 0)
                    cleanedAnswer.substring(characterStartForBubble + characterMarkerForBubble.length).trim() else cleanedAnswer
                handler.post {
                    removeMessage(loading)
                    if (characterTextForBubble.isNotBlank()) addMessage(characterTextForBubble, false)
                    val narrationMarker = "나레이션:"
                    val characterMarker = "하나코:"
                    val nStart = cleanedAnswer.indexOf(narrationMarker)
                    val cStart = answer.indexOf(characterMarker)
                    val narrationText = if (nStart >= 0 && cStart > nStart)
                        cleanedAnswer.substring(nStart + narrationMarker.length, cleanedAnswer.indexOf(characterMarker, nStart)).trim() else ""
                    val originalCharacterStart = answer.indexOf(characterMarker)
                    val characterText = if (originalCharacterStart >= 0)
                        answer.substring(originalCharacterStart + characterMarker.length).trim() else answer
                    val scenes = makeDialogueLinesWithSounds(characterText)
                    showDialogueSequence(scenes)
                }
            } catch (_: Exception) {
                handler.post { removeMessage(loading) }
            }
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