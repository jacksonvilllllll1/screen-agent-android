package com.example.screenagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The AI "hands, eyes and voice". Runs as an Accessibility Service so it can
 * take screenshots, tap, swipe and type in any app.
 */
class AgentService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile var instance: AgentService? = null
        const val MODEL = "claude-sonnet-4-5"
        const val MAX_STEPS = 40
        const val KEEP_SCREENSHOTS = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val http = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val speechDone = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var overlay: Button? = null

    /** Set from the app: false = AI stays silent. */
    var voiceOn = true

    override fun onServiceConnected() {
        instance = this
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { speechDone[id]?.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { speechDone[id]?.complete(Unit) }
            })
            ttsReady = true
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stop() }
    override fun onDestroy() {
        instance = null
        scope.cancel()
        if (ttsReady) tts.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ control ---
    fun startGoal(goal: String, apiKey: String) {
        job?.cancel()
        job = scope.launch {
            try {
                runAgent(goal, apiKey)
            } catch (e: CancellationException) {
                speak("Stopped.")
            } catch (e: Exception) {
                speak("Sorry, something went wrong. ${e.message}")
            } finally {
                hideStop()
            }
        }
    }

    fun stop() {
        job?.cancel()
        if (ttsReady) tts.stop()
    }

    // -------------------------------------------------------------- voice ---
    private fun speak(text: String) {
        if (!voiceOn || !ttsReady || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    private suspend fun speakAndWait(text: String) {
        if (!voiceOn || !ttsReady || text.isBlank()) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        speechDone[id] = done
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        withTimeoutOrNull(20_000) { done.await() }
        speechDone.remove(id)
    }

    private suspend fun listen(): String = suspendCancellableCoroutine { cont ->
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) {
                val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                rec.destroy()
                if (cont.isActive) cont.resume(t)
            }
            override fun onError(error: Int) { rec.destroy(); if (cont.isActive) cont.resume("") }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        )
        cont.invokeOnCancellation { rec.destroy() }
    }

    // ------------------------------------------------------- stop button ---
    private fun showStop() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply {
            text = "■ STOP"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.RED)
            setOnClickListener { stop() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 250 }
        wm.addView(b, lp)
        overlay = b
    }

    private fun hideStop() {
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        overlay = null
    }

    // --------------------------------------------------------- agent loop ---
    private suspend fun runAgent(goal: String, key: String) {
        val dm = resources.displayMetrics
        val scale = minOf(1f, 1024f / maxOf(dm.widthPixels, dm.heightPixels))
        val sw = (dm.widthPixels * scale).toInt()
        val sh = (dm.heightPixels * scale).toInt()

        val system = """You control an Android phone through the `phone` tool. Screenshots are ${sw}x${sh} px; give all coordinates in that space.
Before each action say ONE short friendly sentence about what you're doing; it is spoken aloud.
Use open_app to launch apps by name. To scroll down, swipe up (and vice versa).
Use ask_user (spoken question, spoken answer) BEFORE anything irreversible or costly: sending messages, purchases, deleting, posting, or changing important settings, and whenever you're unsure what the user wants.
Never type passwords or payment details; ask the user to do that themselves. A red STOP button floats on screen: never tap it.
When the goal is complete, reply with a brief spoken summary and make no tool call."""

        val tools = JSONArray().put(phoneTool())
        showStop()
        speak("Okay. $goal")
        performGlobalAction(GLOBAL_ACTION_HOME)
        delay(1000)

        val messages = JSONArray().put(
            JSONObject().put("role", "user").put(
                "content", JSONArray().put(textBlock(goal)).put(imageBlock(shot(scale)))
            )
        )

        for (step in 1..MAX_STEPS) {
            val resp = callApi(key, system, tools, messages)
            val content = resp.getJSONArray("content")
            messages.put(JSONObject().put("role", "assistant").put("content", content))

            val results = JSONArray()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.getString("type")) {
                    "text" -> speak(b.getString("text"))
                    "tool_use" -> {
                        val note = execute(b.getJSONObject("input"), scale)
                        delay(900)
                        results.put(
                            JSONObject().put("type", "tool_result").put("tool_use_id", b.getString("id"))
                                .put("content", JSONArray().put(textBlock(note)).put(imageBlock(shot(scale))))
                        )
                    }
                }
            }
            if (results.length() == 0) return
            messages.put(JSONObject().put("role", "user").put("content", results))
            pruneImages(messages)
        }
        speak("I hit the step limit, so I'm stopping.")
    }

    private suspend fun execute(inp: JSONObject, scale: Float): String {
        fun px(k: String) = (inp.optDouble(k, 0.0) / scale).toFloat()
        return try {
            when (inp.getString("action")) {
                "screenshot" -> "ok"
                "wait" -> { delay((inp.optDouble("seconds", 1.0) * 1000).toLong()); "ok" }
                "tap" -> { tap(px("x"), px("y"), 50); "ok" }
                "double_tap" -> { tap(px("x"), px("y"), 50); delay(80); tap(px("x"), px("y"), 50); "ok" }
                "long_press" -> { tap(px("x"), px("y"), 800); "ok" }
                "swipe" -> { swipe(px("x"), px("y"), px("x2"), px("y2")); "ok" }
                "type" -> typeText(inp.getString("text"))
                "enter" -> {
                    val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (n?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true) "ok"
                    else "error: no focused field"
                }
                "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); "ok" }
                "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); "ok" }
                "recents" -> { performGlobalAction(GLOBAL_ACTION_RECENTS); "ok" }
                "notifications" -> { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); "ok" }
                "open_app" -> openApp(inp.getString("text"))
                "ask_user" -> {
                    speakAndWait(inp.getString("text"))
                    listen().ifBlank { "(no answer heard)" }
                }
                else -> "error: unknown action"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    // ------------------------------------------------------------ actions ---
    private suspend fun gesture(path: Path, ms: Long): Boolean = suspendCancellableCoroutine { c ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, null)
        if (!ok && c.isActive) c.resume(false)
    }

    private suspend fun tap(x: Float, y: Float, ms: Long) =
        gesture(Path().apply { moveTo(x, y) }, ms)

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 400)

    private fun typeText(t: String): String {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "error: no focused text field; tap a field first"
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + t)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "ok"
        else "error: could not type into this field"
    }

    private fun openApp(name: String): String {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            ?: return "error: no app named $name"
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "error: cannot launch $name"
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "ok"
    }

    // --------------------------------------------------------- screenshot ---
    private suspend fun shot(scale: Float): String = suspendCancellableCoroutine { c ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                try {
                    val hb = r.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)!!
                        .copy(Bitmap.Config.ARGB_8888, false)
                    hb.close()
                    val small = Bitmap.createScaledBitmap(
                        bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                    )
                    val out = ByteArrayOutputStream()
                    small.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    c.resume(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                } catch (e: Exception) {
                    c.resumeWith(Result.failure(e))
                }
            }
            override fun onFailure(code: Int) {
                c.resumeWith(Result.failure(Exception("screenshot failed (code $code)")))
            }
        })
    }

    // ---------------------------------------------------------------- API ---
    private suspend fun callApi(key: String, system: String, tools: JSONArray, messages: JSONArray): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("model", MODEL).put("max_tokens", 1024)
                .put("system", system).put("tools", tools).put("messages", messages)
            val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw Exception("API ${r.code}: ${s.take(200)}")
                JSONObject(s)
            }
        }

    private fun phoneTool() = JSONObject(
        """{"name":"phone","description":"Control the phone: see the screen, tap, swipe, type, navigate, open apps, or ask the user a spoken question.",
        "input_schema":{"type":"object","properties":{
        "action":{"type":"string","enum":["screenshot","tap","double_tap","long_press","swipe","type","enter","back","home","recents","notifications","open_app","wait","ask_user"]},
        "x":{"type":"integer"},"y":{"type":"integer"},"x2":{"type":"integer"},"y2":{"type":"integer"},
        "text":{"type":"string","description":"text to type, app name for open_app, or question for ask_user"},
        "seconds":{"type":"number"}},"required":["action"]}}"""
    )

    private fun textBlock(t: String) = JSONObject().put("type", "text").put("text", t)

    private fun imageBlock(b64: String) = JSONObject().put("type", "image").put(
        "source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)
    )

    /** Keep only the newest few screenshots so requests stay small and cheap. */
    private fun pruneImages(messages: JSONArray) {
        var seen = 0
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.getJSONObject(i)
            if (m.getString("role") != "user") continue
            val c = m.optJSONArray("content") ?: continue
            for (j in c.length() - 1 downTo 0) {
                val blk = c.getJSONObject(j)
                when (blk.optString("type")) {
                    "image" -> if (++seen > KEEP_SCREENSHOTS) c.put(j, textBlock("[old screenshot removed]"))
                    "tool_result" -> {
                        val inner = blk.optJSONArray("content") ?: continue
                        for (k in inner.length() - 1 downTo 0) {
                            if (inner.getJSONObject(k).optString("type") == "image" && ++seen > KEEP_SCREENSHOTS)
                                inner.put(k, textBlock("[old screenshot removed]"))
                        }
                    }
                }
            }
        }
    }
}
