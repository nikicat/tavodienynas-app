package lt.tavodienynas.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple debug logger that displays events in the UI
 */
object DebugLogger {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var logCallback: ((String) -> Unit)? = null
    private var progressCallback: ((Boolean, Int, Int) -> Unit)? = null // (show, progress, max)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun setCallback(callback: (String) -> Unit) {
        logCallback = callback
    }

    fun setProgressCallback(callback: (Boolean, Int, Int) -> Unit) {
        progressCallback = callback
    }

    fun showProgress(currentBytes: Int, totalBytes: Int) {
        mainHandler.post {
            progressCallback?.invoke(true, currentBytes, totalBytes)
        }
    }

    fun hideProgress() {
        mainHandler.post {
            progressCallback?.invoke(false, 0, 0)
        }
    }

    fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        val formattedMessage = "[$timestamp] $message"
        mainHandler.post {
            logCallback?.invoke(formattedMessage)
        }
    }

    fun intercepted(url: String, statusCode: Int) {
        val shortUrl = url.takeLast(60).let { if (url.length > 60) "...$it" else it }
        log("→ INTERCEPT: $shortUrl (HTTP $statusCode)")
    }

    fun redirect(toUrl: String) {
        val shortUrl = toUrl.takeLast(50).let { if (toUrl.length > 50) "...$it" else it }
        log("  ↳ REDIRECT: $shortUrl")
    }

    fun translateStart(url: String, bytes: Int) {
        val shortUrl = url.takeLast(40).let { if (url.length > 40) "...$it" else it }
        log("⚡ TRANSLATE START: $shortUrl ($bytes bytes)")
    }

    fun translateDone(url: String, targetLang: String, durationMs: Long) {
        val shortUrl = url.takeLast(30).let { if (url.length > 30) "...$it" else it }
        log("✓ TRANSLATE DONE: $shortUrl → $targetLang (${durationMs}ms)")
    }

    fun passThrough(url: String, mimeType: String) {
        val shortUrl = url.takeLast(40).let { if (url.length > 40) "...$it" else it }
        log("  PASS: $shortUrl ($mimeType)")
    }

    fun languageChanged(language: String) {
        log("🌐 LANGUAGE: Changed to '$language'")
    }

    fun refreshTriggered() {
        log("🔄 REFRESH: Page reload triggered")
    }
}
