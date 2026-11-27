package uz.ovoz.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** RECOGNIZE_SPEECH intentiga javob beruvchi oyna — zaxira yoʻl. */
class RecognizeActivity : AppCompatActivity(), RecognitionSession.Callback {

    private lateinit var statusText: TextView
    private lateinit var btnStop: Button
    private var session: RecognitionSession? = null
    private var started = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) begin() else showError(getString(R.string.err_permission))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognize)
        statusText = findViewById(R.id.statusText)
        btnStop = findViewById(R.id.btnStop)

        btnStop.setOnClickListener {
            say(getString(R.string.status_processing))
            session?.stop()
        }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { cancelAndFinish() }

        if (!ModelManager.isModelReady(this)) {
            showError(getString(R.string.err_model_missing))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            begin()
        } else {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun begin() {
        if (started) return
        started = true
        say(getString(R.string.status_loading_model))
        session = RecognitionSession(applicationContext, this).also { it.start() }
    }

    private fun say(text: String) {
        statusText.text = text
        statusText.announceForAccessibility(text)
    }

    private fun showError(text: String) {
        btnStop.isEnabled = false
        say(text)
    }

    private fun cancelAndFinish() {
        session?.cancel()
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        session?.cancel()
        super.onDestroy()
    }

    // --- RecognitionSession.Callback (fon oqimida) ---

    override fun onReady() {
        runOnUiThread { say(getString(R.string.status_listening)) }
    }

    override fun onVolume(level: Float) = Unit

    override fun onResult(text: String) {
        runOnUiThread {
            say(getString(R.string.status_text, text))
            val data = Intent()
            data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
            data.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f))
            setResult(RESULT_OK, data)
            finish()
        }
    }

    override fun onError(code: Int) {
        runOnUiThread {
            val msg = when (code) {
                SpeechRecognizer.ERROR_NO_MATCH -> getString(R.string.err_no_match)
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.err_timeout)
                SpeechRecognizer.ERROR_AUDIO -> getString(R.string.err_audio)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.err_permission)
                SpeechRecognizer.ERROR_CLIENT -> getString(R.string.err_model_missing)
                else -> getString(R.string.err_generic)
            }
            showError(msg)
        }
    }
}
