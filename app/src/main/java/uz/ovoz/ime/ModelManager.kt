package uz.ovoz.ime

import android.content.Context
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Vosk modelini yuklash, ochish, tekshirish va xotiraga olish. */
object ModelManager {

    const val DEFAULT_MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-uz-0.22.zip"

    @Volatile
    private var model: Model? = null

    fun modelDir(context: Context): File = File(context.filesDir, "model")

    /** Papka mavjud va ichida model fayllari bormi. */
    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.isDirectory &&
            File(dir, "am/final.mdl").isFile &&
            File(dir, "conf").isDirectory
    }

    @Synchronized
    fun loadModel(context: Context): Model? {
        model?.let { return it }
        if (!isModelReady(context)) return null
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(modelDir(context).absolutePath).also { model = it }
        } catch (e: Throwable) {
            null
        }
    }

    @Synchronized
    fun release() {
        try {
            model?.close()
        } catch (_: Throwable) {
        }
        model = null
    }

    /** URL'dan yuklab, to'g'ridan-to'g'ri ochadi (vaqtinchalik zip fayl yo'q). */
    @Throws(IOException::class)
    fun downloadAndInstall(context: Context, url: String, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("server javobi ${conn.responseCode}")
            }
            install(context, conn.inputStream, conn.contentLengthLong, onProgress)
        } finally {
            conn.disconnect()
        }
    }

    /** Zip oqimidan modelni filesDir/model ichiga ochadi. */
    @Throws(IOException::class)
    fun install(context: Context, input: InputStream, totalBytes: Long, onProgress: (Int) -> Unit) {
        val tmp = File(context.filesDir, "model_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            var lastPct = -2
            val counting = CountingInputStream(input) { read ->
                val pct = if (totalBytes > 0) ((read * 100) / totalBytes).toInt().coerceIn(0, 99) else -1
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }
            val rootPath = tmp.canonicalPath + File.separator
            ZipInputStream(counting).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(tmp, entry.name)
                    // Zip-slip himoyasi
                    if (!out.canonicalPath.startsWith(rootPath)) {
                        throw IOException("zip ichida notoʻgʻri yoʻl bor")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val root = findModelRoot(tmp) ?: throw IOException("zip ichida model fayllari topilmadi")
            release()
            val target = modelDir(context)
            target.deleteRecursively()
            if (!root.renameTo(target)) throw IOException("modelni joyiga koʻchirib boʻlmadi")
            onProgress(100)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun findModelRoot(dir: File): File? {
        if (File(dir, "am/final.mdl").isFile) return dir
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            findModelRoot(sub)?.let { return it }
        }
        return null
    }

    private class CountingInputStream(
        input: InputStream,
        private val onBytes: (Long) -> Unit
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) {
                count++
                onBytes(count)
            }
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) {
                count += r
                onBytes(count)
            }
            return r
        }
    }
}
