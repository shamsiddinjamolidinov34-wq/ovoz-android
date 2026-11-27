package uz.ovoz.ime

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bitta tinglash sessiyasi: 16 kHz mono PCM16 yozadi, Vosk bilan oflayn tanadi.
 * Jimlikni oʻzimiz sezamiz: gap boshlangach 1200 ms jimlik boʻlsa tugaydi.
 * Eng uzun yozuv — 60 soniya. Callback metodlari fon oqimida chaqiriladi.
 */
class RecognitionSession(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onReady()
        fun onVolume(level: Float)
        fun onResult(text: String)
        fun onError(code: Int)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val SILENCE_MS = 1_200L
        const val MAX_RECORDING_MS = 60_000L
        const val START_TIMEOUT_MS = 8_000L
        private const val MIN_SPEECH_RMS = 500.0
        private const val MAX_SPEECH_RMS = 2_500.0
    }

    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch { run() }
    }

    /** Yozuvni toʻxtatib, matnga aylantirib natija beradi. */
    fun stop() {
        stopRequested = true
    }

    /** Yozuvni bekor qiladi; hech qanday callback chaqirilmaydi. */
    fun cancel() {
        cancelled = true
        stopRequested = true
    }

    private fun fail(code: Int) {
        if (!cancelled) callback.onError(code)
    }

    @SuppressLint("MissingPermission")
    private fun run() {
        var recorder: AudioRecord? = null
        var recognizer: Recognizer? = null
        try {
            val model = ModelManager.loadModel(context)
            if (model == null) {
                fail(SpeechRecognizer.ERROR_CLIENT)
                return
            }
            if (cancelled) return

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                fail(SpeechRecognizer.ERROR_AUDIO)
                return
            }
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
            recorder = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                fail(SpeechRecognizer.ERROR_AUDIO)
                return
            }
            val rcg = Recognizer(model, SAMPLE_RATE.toFloat())
            recognizer = rcg

            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail(SpeechRecognizer.ERROR_AUDIO)
                return
            }
            if (!cancelled) callback.onReady()

            val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
            val text = StringBuilder()
            val begin = SystemClock.elapsedRealtime()
            var lastVoice = begin
            var speechStarted = false
            var noise = 0.0
            var calib = 0

            while (!cancelled) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) {
                    fail(SpeechRecognizer.ERROR_AUDIO)
                    return
                }
                if (n == 0) continue

                if (rcg.acceptWaveForm(chunk, n)) appendText(text, rcg.result)

                val level = rms(chunk, n)
                callback.onVolume((level / 300.0).toFloat().coerceIn(0f, 10f))
                val now = SystemClock.elapsedRealtime()

                if (calib < 3) {
                    noise = max(noise, level) // dastlabki 300 ms — fon shovqini
                    calib++
                } else {
                    val threshold = min(max(MIN_SPEECH_RMS, noise * 2.5), MAX_SPEECH_RMS)
                    if (level > threshold) {
                        speechStarted = true
                        lastVoice = now
                    }
                }

                if (stopRequested) break
                if (speechStarted && now - lastVoice >= SILENCE_MS) break
                if (now - begin >= MAX_RECORDING_MS) break
                if (!speechStarted && now - begin >= START_TIMEOUT_MS) {
                    fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    return
                }
            }
            if (cancelled) return

            try { rec.stop() } catch (_: Exception) { }
            appendText(text, rcg.finalResult)

            val result = text.toString().trim()
            if (result.isEmpty()) fail(SpeechRecognizer.ERROR_NO_MATCH) else callback.onResult(result)
        } catch (e: SecurityException) {
            fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        } catch (e: Throwable) {
            fail(SpeechRecognizer.ERROR_CLIENT)
        } finally {
            try { recorder?.release() } catch (_: Exception) { }
            try { recognizer?.close() } catch (_: Exception) { }
        }
    }

    private fun appendText(sb: StringBuilder, json: String) {
        val t = try { JSONObject(json).optString("text") } catch (_: Exception) { "" }
        if (t.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t.trim())
        }
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n)
    }
}
