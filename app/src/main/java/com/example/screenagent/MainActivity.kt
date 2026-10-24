package com.example.screenagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var goal: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        val prefs = getSharedPreferences("agent", MODE_PRIVATE)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val title = TextView(this).apply { text = "Screen Agent"; textSize = 28f }
        val key = EditText(this).apply {
            hint = "Anthropic API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        goal = EditText(this).apply { hint = "What should I do on this phone?" }
        val voice = CheckBox(this).apply { text = "AI talks out loud"; isChecked = true }

        val enable = Button(this).apply {
            text = "1. Turn on Accessibility access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val mic = Button(this).apply {
            text = "🎤 Speak goal"
            setOnClickListener {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                try { startActivityForResult(i, 7) }
                catch (e: Exception) { Toast.makeText(context, "Speech input not available", Toast.LENGTH_SHORT).show() }
            }
        }
        val go = Button(this).apply {
            text = "2. Start"
            setOnClickListener {
                val svc = AgentService.instance
                if (svc == null) {
                    Toast.makeText(context, "Turn on Accessibility access first", Toast.LENGTH_LONG).show()
                } else if (key.text.isBlank() || goal.text.isBlank()) {
                    Toast.makeText(context, "Enter your API key and a goal", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString("key", key.text.toString().trim()).apply()
                    svc.voiceOn = voice.isChecked
                    svc.startGoal(goal.text.toString(), key.text.toString().trim())
                    moveTaskToBack(true)
                }
            }
        }
        listOf(title, key, goal, voice, mic, enable, go).forEach { col.addView(it) }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 7 && res == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.let { goal.setText(it) }
        }
    }
}
