package uz.ovoz.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Tizim nutq tanish xizmati. Faqat ochiq (public) API: onStartListening,
 * onStopListening, onCancel, onDestroy.
 */
class OvozSpeechService : RecognitionService() {

    private val lock = Any()
    private var session: RecognitionSession? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        val newSession: RecognitionSession
        synchronized(lock) {
            if (session != null) {
                listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                return
            }
            newSession = RecognitionSession(applicationContext, object : RecognitionSession.Callback {
                override fun onReady() {
                    runCatching {
                        listener.readyForSpeech(Bundle())
                        listener.beginningOfSpeech()
                    }
                }

                override fun onVolume(level: Float) {
                    runCatching { listener.rmsChanged(level) }
                }

                override fun onResult(text: String) {
                    clearSession()
                    runCatching {
                        listener.endOfSpeech()
                        val b = Bundle()
                        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                        b.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
                        listener.results(b)
                    }
                }

                override fun onError(code: Int) {
                    clearSession()
                    runCatching { listener.error(code) }
                }
            })
            session = newSession
        }
        newSession.start()
    }

    override fun onStopListening(listener: RecognitionService.Callback) {
        val s = synchronized(lock) { session }
        if (s == null) listener.error(SpeechRecognizer.ERROR_CLIENT) else s.stop()
    }

    override fun onCancel(listener: RecognitionService.Callback) {
        synchronized(lock) {
            session?.cancel()
            session = null
        }
    }

    override fun onDestroy() {
        synchronized(lock) {
            session?.cancel()
            session = null
        }
        super.onDestroy()
    }

    private fun clearSession() {
        synchronized(lock) { session = null }
    }
}
