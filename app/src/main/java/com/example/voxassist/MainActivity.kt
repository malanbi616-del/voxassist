package com.example.voxassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * VoxAssist v0.1 — push-to-talk voice assistant.
 *
 * Flow: mic -> on-device speech recognition -> send transcript to Gemini,
 * asking it to return a small JSON "action" -> execute that action via a
 * normal, visible Android Intent (dial, text, open app, search, navigate,
 * set alarm) -> speak the assistant's reply back.
 *
 * No accessibility service, no overlay, no hidden automation — every action
 * this app takes opens a normal system screen the user can see and confirm,
 * the same way tapping a notification action would.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var transcriptView: TextView
    private lateinit var apiKeyField: EditText
    private val prefs by lazy { getSharedPreferences("voxassist", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transcriptView = findViewById(R.id.transcriptView)
        apiKeyField = findViewById(R.id.apiKeyField)
        apiKeyField.setText(prefs.getString("gemini_key", ""))

        findViewById<Button>(R.id.saveKeyButton).setOnClickListener {
            prefs.edit().putString("gemini_key", apiKeyField.text.toString().trim()).apply()
            Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
        }

        tts = TextToSpeech(this, this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.firstOrNull() ?: return
                transcriptView.text = "You said: $heard"
                Thread { handleCommand(heard) }.start()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                transcriptView.text = "Didn't catch that — try again"
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        findViewById<Button>(R.id.micButton).setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            speechRecognizer.startListening(intent)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.getDefault()
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance")
    }

    private fun handleCommand(heard: String) {
        val apiKey = prefs.getString("gemini_key", "") ?: ""
        if (apiKey.isBlank()) {
            runOnUiThread { speak("Please set your Gemini API key in the app first.") }
            return
        }
        try {
            val action = askGemini(heard, apiKey)
            runOnUiThread { executeAction(action) }
        } catch (e: Exception) {
            runOnUiThread { speak("Something went wrong: ${e.message}") }
        }
    }

    // Sends the transcript to Gemini and asks for a small structured JSON action.
    // NOTE: model name / endpoint may need updating — check ai.google.dev for the
    // current model list under your API key before relying on this.
    private fun askGemini(userText: String, apiKey: String): JSONObject {
        val model = "gemini-2.0-flash"
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val prompt = """
            You are a phone voice assistant. The user said: "$userText"
            Reply with ONLY a JSON object, no other text, matching this shape:
            {"action": "dial"|"text"|"open_app"|"search"|"navigate"|"alarm"|"reply",
             "target": "string param for the action (phone number, app package/name, search query, address, or alarm time HH:MM)",
             "message": "if action is text, the message body, else empty",
             "speak": "a short spoken reply to the user"}
            Pick the closest action. If nothing fits, use "reply".
        """.trimIndent()

        val body = JSONObject().apply {
            put("contents", listOf(JSONObject().apply {
                put("parts", listOf(JSONObject().put("text", prompt)))
            }))
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val responseText = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(responseText)
        val raw = json.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return JSONObject(cleaned)
    }

    private fun executeAction(action: JSONObject) {
        val type = action.optString("action", "reply")
        val target = action.optString("target", "")
        val speakText = action.optString("speak", "Okay.")

        when (type) {
            "dial" -> startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$target")))
            "text" -> {
                val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$target"))
                smsIntent.putExtra("sms_body", action.optString("message", ""))
                startActivity(smsIntent)
            }
            "open_app" -> {
                val launch = packageManager.getLaunchIntentForPackage(target)
                if (launch != null) startActivity(launch)
                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$target")))
            }
            "search" -> startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", target))
            "navigate" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$target")))
            "alarm" -> {
                val parts = target.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val alarmIntent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, h)
                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
                }
                startActivity(alarmIntent)
            }
            else -> {}
        }
        transcriptView.text = "VoxAssist: $speakText"
        speak(speakText)
    }

    override fun onDestroy() {
        speechRecognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
