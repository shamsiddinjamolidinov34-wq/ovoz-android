package uz.ovoz.ime

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class SetupActivity : AppCompatActivity() {

    private companion object {
        const val VOICE_SERVICE_KEY = "voice_recognition_service"
    }

    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var modelUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var btnPickZip: Button
    private lateinit var permStatus: TextView
    private lateinit var servicesResult: TextView
    private lateinit var currentResult: TextView
    private lateinit var testResult: TextView
    private lateinit var defaultResult: TextView

    private var busy = false
    private var lastAnnounced = -1

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permStatus.say(getString(if (granted) R.string.permission_granted else R.string.permission_denied))
        }

    private val zipPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) installFromUri(uri)
        }

    private val testLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            testResult.say(
                if (r.resultCode == RESULT_OK && text != null) getString(R.string.test_result, text)
                else getString(R.string.test_cancelled)
            )
        }

    private fun TextView.say(text: String) {
        this.text = text
        announceForAccessibility(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        modelStatus = findViewById(R.id.modelStatus)
        modelProgress = findViewById(R.id.modelProgress)
        modelUrl = findViewById(R.id.modelUrl)
        btnDownload = findViewById(R.id.btnDownload)
        btnPickZip = findViewById(R.id.btnPickZip)
        permStatus = findViewById(R.id.permStatus)
        servicesResult = findViewById(R.id.servicesResult)
        currentResult = findViewById(R.id.currentResult)
        testResult = findViewById(R.id.testResult)
        defaultResult = findViewById(R.id.defaultResult)

        modelUrl.setText(ModelManager.DEFAULT_MODEL_URL)

        btnDownload.setOnClickListener { startDownload() }
        btnPickZip.setOnClickListener {
            zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        findViewById<Button>(R.id.btnPermission).setOnClickListener {
            if (hasMic()) permStatus.say(getString(R.string.permission_granted))
            else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<Button>(R.id.btnListServices).setOnClickListener { listServices() }
        findViewById<Button>(R.id.btnCurrent).setOnClickListener { currentResult.say(readCurrent()) }
        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener { openSystemSettings() }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            testLauncher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .setClass(this, RecognizeActivity::class.java)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            )
        }
        findViewById<Button>(R.id.btnSetDefault).setOnClickListener { trySetDefault() }
    }

    override fun onResume() {
        super.onResume()
        if (!busy) refreshModel(announce = false)
        currentResult.text = readCurrent()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    // ---------- Model ----------

    private fun refreshModel(announce: Boolean) {
        val ready = ModelManager.isModelReady(this)
        val text = getString(if (ready) R.string.model_ready else R.string.model_missing)
        if (announce) modelStatus.say(text) else modelStatus.text = text
        modelProgress.progress = if (ready) 100 else 0
        setDownloadLabel(R.string.btn_download)
    }

    private fun setDownloadLabel(res: Int) {
        val label = getString(res)
        btnDownload.text = label
        btnDownload.contentDescription = label
    }

    private fun startDownload() {
        val url = modelUrl.text.toString().trim()
        if (!url.startsWith("https://")) {
            modelStatus.say(getString(R.string.err_url))
            return
        }
        runInstall { ModelManager.downloadAndInstall(this, url, ::reportProgress) }
    }

    private fun installFromUri(uri: Uri) {
        runInstall {
            val total = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException(getString(R.string.err_open_file))
            input.use { ModelManager.install(this, it, total, ::reportProgress) }
        }
    }

    private fun runInstall(block: () -> Unit) {
        if (busy) return
        busy = true
        lastAnnounced = -1
        btnDownload.isEnabled = false
        btnPickZip.isEnabled = false
        modelProgress.isIndeterminate = true
        modelStatus.say(getString(R.string.model_downloading_start))

        lifecycleScope.launch {
            val error: String? = withContext(Dispatchers.IO) {
                try {
                    block()
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            busy = false
            btnDownload.isEnabled = true
            btnPickZip.isEnabled = true
            modelProgress.isIndeterminate = false
            if (error == null) {
                refreshModel(announce = true)
            } else {
                modelProgress.progress = 0
                modelStatus.say(getString(R.string.model_error, error))
                setDownloadLabel(R.string.btn_retry)
            }
        }
    }

    private fun reportProgress(p: Int) {
        runOnUiThread {
            if (p < 0) {
                modelProgress.isIndeterminate = true
            } else {
                modelProgress.isIndeterminate = false
                modelProgress.progress = p
                if (p / 10 != lastAnnounced) {
                    lastAnnounced = p / 10
                    modelStatus.say(getString(R.string.model_downloading, p))
                }
            }
        }
    }

    // ---------- Xizmatlar ----------

    @Suppress("DEPRECATION")
    private fun queryRecognizers(): List<ResolveInfo> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentServices(intent, 0)
        }
    }

    private fun listServices() {
        val list = queryRecognizers()
        if (list.isEmpty()) {
            servicesResult.say(getString(R.string.services_none) + "\n" + getString(R.string.service_ours_no))
            return
        }
        val sb = StringBuilder(getString(R.string.services_header, list.size)).append('\n')
        var ours = false
        list.forEachIndexed { i, ri ->
            val pkg = ri.serviceInfo.packageName
            if (pkg == packageName) ours = true
            sb.append(i + 1).append(". ").append(ri.loadLabel(packageManager)).append(" (").append(pkg).append(")\n")
        }
        sb.append(getString(if (ours) R.string.service_ours_yes else R.string.service_ours_no))
        servicesResult.say(sb.toString())
    }

    private fun readCurrent(): String {
        val value = try {
            Settings.Secure.getString(contentResolver, VOICE_SERVICE_KEY)
        } catch (e: Exception) {
            null
        }
        if (value.isNullOrEmpty()) return getString(R.string.current_none)
        val ours = value.startsWith("$packageName/")
        return getString(R.string.current_value, value) + "\n" +
            getString(if (ours) R.string.current_ours else R.string.current_other)
    }

    private fun openSystemSettings() {
        // Baʼzi telefonlar (masalan, Samsung) uchinchi tomon nutq xizmatini tanlashga ruxsat bermaydi.
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                currentResult.say(getString(R.string.settings_error))
            }
        }
    }

    private fun trySetDefault() {
        val value = ComponentName(this, OvozSpeechService::class.java).flattenToString()
        val msg = try {
            val ok = Settings.Secure.putString(contentResolver, VOICE_SERVICE_KEY, value)
            val now = Settings.Secure.getString(contentResolver, VOICE_SERVICE_KEY)
            if (ok && now == value) getString(R.string.set_default_ok) else getString(R.string.set_default_failed)
        } catch (e: SecurityException) {
            getString(R.string.set_default_denied)
        } catch (e: Exception) {
            getString(R.string.set_default_failed)
        }
        defaultResult.say(msg)
        currentResult.text = readCurrent()
    }
}
