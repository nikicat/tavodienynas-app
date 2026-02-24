package lt.tavodienynas.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var prefs: SharedPreferences
    private var downloadManager: DownloadManager? = null

    // Debug panel
    private lateinit var debugPanel: android.widget.LinearLayout
    private lateinit var debugLogView: android.widget.TextView
    private lateinit var debugScrollView: android.widget.ScrollView
    private lateinit var debugUrlView: android.widget.TextView

    // ML Kit Translation
    private lateinit var translationManager: TranslationManager
    private lateinit var htmlTranslator: HtmlTranslator
    private var webViewTranslator: WebViewTranslator? = null

    private var currentLanguage: String = "original"
    private val pendingDownloads = mutableMapOf<Long, String>() // downloadId to mimeType
    private var isFirstPageLoad = true // Flag to skip interception on first load for cookie sync

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val downloadId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
            if (downloadId == -1L || !pendingDownloads.containsKey(downloadId)) return

            val mimeType = pendingDownloads.remove(downloadId)
            openDownloadedFile(downloadId, mimeType)
        }
    }

    companion object {
        private const val BASE_URL = "https://www.manodienynas.lt/"
        private const val LOGIN_URL_TEMPLATE = "https://www.manodienynas.lt/1/{lang}/public/public/login"
        private const val PREFS_NAME = "manodienynas_prefs"
        private const val PREF_LANGUAGE = "translation_language"
        private const val PREF_DEBUG_VISIBLE = "debug_panel_visible"
        private const val PREF_LAST_URL = "last_viewed_url"

        // Main site - only these can be navigated to
        private val ALLOWED_NAVIGATION_HOSTS = setOf(
            "manodienynas.lt",
            "www.manodienynas.lt"
        )

        // Allowed resource hosts (fonts, icons, CDNs)
        // These can load resources but NOT be navigated to
        private val ALLOWED_RESOURCE_HOSTS = setOf(
            // Google Fonts & Icons
            "fonts.googleapis.com",
            "fonts.gstatic.com",
            "www.gstatic.com",
            // Common CDNs
            "cdnjs.cloudflare.com",
            "cdn.jsdelivr.net",
            "unpkg.com",
            // Google APIs (for various services)
            "ajax.googleapis.com",
            "www.googleapis.com",
            // Cloudflare challenge/security verification
            "challenges.cloudflare.com"
        )

        // Website supported languages in URL path
        private val SITE_LANGUAGES = setOf("en", "ua", "lt", "ru")

        // Regex to match language in URL path: /1/{lang}/ or /{lang}/
        private val URL_LANG_PATTERN = Regex("^(https://[^/]+/)(?:(\\d+)/)?(${ SITE_LANGUAGES.joinToString("|") })(/.*)?$")

        /**
         * Map app language code to website URL language code
         */
        fun appLangToUrlLang(appLang: String): String {
            return when (appLang) {
                "original" -> "lt"
                "uk" -> "ua"  // Ukrainian: app uses "uk", site uses "ua"
                "pl" -> "en"  // Polish not supported by site, use English
                else -> appLang  // en, ru stay the same
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Load saved language preference
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentLanguage = prefs.getString(PREF_LANGUAGE, "original") ?: "original"

        // Initialize download manager and register receiver
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        } catch (e: Exception) {
            // Receiver registration failed - downloads will still work but won't auto-open
            DebugLogger.log("Failed to register download receiver: ${e.message}")
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        // Initialize debug panel
        debugPanel = findViewById(R.id.debugPanel)
        debugLogView = findViewById(R.id.debugLogView)
        debugScrollView = findViewById(R.id.debugScrollView)
        debugUrlView = findViewById(R.id.debugUrlView)
        setupDebugLogger()

        // Initialize ML Kit Translation
        translationManager = TranslationManager(this)
        htmlTranslator = HtmlTranslator(translationManager)

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()

        // Add JavaScript interface for POST body capture
        webView.addJavascriptInterface(
            htmlTranslator.PostBodyInterface(),
            HtmlTranslator.JS_INTERFACE_NAME
        )

        // Initialize WebView translator after WebView is set up
        webViewTranslator = WebViewTranslator(webView, translationManager, lifecycleScope)

        // DON'T enable translation yet on app start - let first page load normally
        // so WebView can sync cookies from disk. Translation will be applied in onPageFinished.
        DebugLogger.log("Startup: savedLang=$currentLanguage, waiting for first load")

        // Handle intent (deep links)
        handleIntent(intent)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupDebugLogger() {
        // Restore debug panel visibility
        val debugVisible = prefs.getBoolean(PREF_DEBUG_VISIBLE, false)
        debugPanel.visibility = if (debugVisible) View.VISIBLE else View.GONE

        DebugLogger.setCallback { message ->
            debugLogView.append("$message\n")
            // Auto-scroll to bottom if panel is visible
            if (debugPanel.visibility == View.VISIBLE) {
                debugScrollView.post {
                    debugScrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                }
            }
        }

        DebugLogger.setProgressCallback { show, progress, max ->
            if (show) {
                progressBar.visibility = View.VISIBLE
                progressBar.max = max
                progressBar.progress = progress
            } else {
                progressBar.visibility = View.GONE
            }
        }

        DebugLogger.log("Debug logger initialized")
        DebugLogger.log("Current language: $currentLanguage")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data
        if (uri != null && isAllowedNavigation(uri.toString())) {
            // Deep link - use provided URL with language adjustment
            val url = adjustUrlLanguage(uri.toString())
            webView.loadUrl(url)
        } else {
            // No deep link - restore last URL or use login page
            val lastUrl = prefs.getString(PREF_LAST_URL, null)
            val url = if (lastUrl != null && isAllowedNavigation(lastUrl)) {
                adjustUrlLanguage(lastUrl)
            } else {
                getDefaultLoginUrl()
            }
            DebugLogger.log("Startup URL: ${url.takeLast(50)}")
            webView.loadUrl(url)
        }
    }

    /**
     * Get login URL with correct language path
     */
    private fun getDefaultLoginUrl(): String {
        val urlLang = appLangToUrlLang(currentLanguage)
        return LOGIN_URL_TEMPLATE.replace("{lang}", urlLang)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Security settings
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
        }

        webView.webViewClient = SecureWebViewClient()
        webView.webChromeClient = SecureWebChromeClient()

        // Set up download listener for file downloads
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            // Only allow downloads from allowed domains
            if (isAllowedNavigation(url)) {
                downloadFile(url, userAgent, contentDisposition, mimeType)
            } else {
                Toast.makeText(this, getString(R.string.blocked_url_message, Uri.parse(url).host), Toast.LENGTH_SHORT).show()
            }
        }

        // Disable long press context menu to prevent "open in browser" option
        webView.setOnLongClickListener { true }
    }

    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        val dm = downloadManager
        if (dm == null) {
            Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url))

            // Get filename from content disposition or URL
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

            // Get cookies to include in download request
            val cookies = CookieManager.getInstance().getCookie(url)

            request.apply {
                setMimeType(mimeType)
                addRequestHeader("Cookie", cookies)
                addRequestHeader("User-Agent", userAgent)
                setDescription(getString(R.string.download_description))
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val downloadId = dm.enqueue(request)
            // Track download to open file when complete
            pendingDownloads[downloadId] = mimeType

            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
        swipeRefresh.setColorSchemeResources(
            R.color.primary,
            R.color.primary_dark
        )
    }

    /**
     * Checks if the URL can be navigated to (only manodienynas.lt domain)
     */
    private fun isAllowedNavigation(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false

            // Only allow HTTPS
            if (scheme != "https") {
                return false
            }

            // Check if host matches allowed navigation domains
            ALLOWED_NAVIGATION_HOSTS.contains(host)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if URL is allowed for resource loading (fonts, scripts, etc.)
     */
    private fun isAllowedResource(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false

            // Allow main site
            if (ALLOWED_NAVIGATION_HOSTS.contains(host)) {
                return true
            }

            // Allow known safe resource hosts
            ALLOWED_RESOURCE_HOSTS.any { host == it || host.endsWith(".$it") }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Replace language in URL path to match app's selected language.
     * URL pattern: https://www.manodienynas.lt/1/{lang}/page/...
     * Returns modified URL or original if no language found in path.
     * Skips action URLs (logout, login actions, etc.) to preserve server redirects.
     */
    private fun adjustUrlLanguage(url: String): String {
        val targetLang = appLangToUrlLang(currentLanguage)
        val match = URL_LANG_PATTERN.matchEntire(url) ?: return url

        val baseUrl = match.groupValues[1]        // https://www.manodienynas.lt/
        val numPrefix = match.groupValues[2]      // "1" or empty
        val urlLang = match.groupValues[3]        // en, ua, lt, ru
        val rest = match.groupValues[4]           // /page/... or empty

        // Skip action URLs - let server handle redirects
        if (rest.startsWith("/action/") || rest.startsWith("/ajax/")) {
            return url
        }

        // If URL already has correct language, return as-is
        if (urlLang == targetLang) return url

        DebugLogger.log("🌐 URL lang '$urlLang' -> '$targetLang'")

        return if (numPrefix.isNotEmpty()) {
            "$baseUrl$numPrefix/$targetLang$rest"
        } else {
            "$baseUrl$targetLang$rest"
        }
    }

    /**
     * Secure WebViewClient that blocks navigation to external sites
     */
    private inner class SecureWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return true

            if (!isAllowedNavigation(url)) {
                showBlockedUrlMessage(url)
                return true
            }

            // Let WebView handle all allowed navigations naturally
            // URL language adjustment is only done on explicit loadUrl calls (menu, startup)
            // This preserves session state during post-login redirects
            return false
        }

        @Deprecated("Deprecated in API 24, but needed for older devices")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url ?: return true

            if (!isAllowedNavigation(url)) {
                showBlockedUrlMessage(url)
                return true
            }

            // Let WebView handle all allowed navigations naturally
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            progressBar.visibility = View.VISIBLE

            // Extra security check - if somehow an external URL started loading, stop it
            if (url != null && !isAllowedNavigation(url)) {
                view?.stopLoading()
                view?.loadUrl(BASE_URL)
                showBlockedUrlMessage(url)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            progressBar.visibility = View.GONE
            swipeRefresh.isRefreshing = false

            // Update debug URL display
            debugUrlView.text = "URL: ${url ?: "-"}"

            // On first page load with saved language, now enable translation and reload
            // This ensures WebView has synced cookies from disk first
            if (isFirstPageLoad && currentLanguage != "original") {
                isFirstPageLoad = false
                DebugLogger.log("First load complete, applying saved language: $currentLanguage")
                applyTranslation(reloadPage = true)
                return
            }
            isFirstPageLoad = false

            // Inject POST body capture script as fallback for pages loaded from cache
            if (htmlTranslator.isEnabled) {
                view?.evaluateJavascript(htmlTranslator.getBodyCaptureScript(), null)
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE
            }
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

            // Block resources from non-allowed hosts
            if (!isAllowedResource(url)) {
                // Return empty response for blocked resources
                return WebResourceResponse("text/plain", "UTF-8", null)
            }

            // Try to intercept and translate HTML responses
            if (request != null && htmlTranslator.isEnabled) {
                val translatedResponse = htmlTranslator.interceptRequest(request)
                if (translatedResponse != null) {
                    return translatedResponse
                }
            }

            return super.shouldInterceptRequest(view, request)
        }
    }

    /**
     * Secure WebChromeClient for handling JavaScript dialogs
     */
    private inner class SecureWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            progressBar.progress = newProgress
        }

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setOnDismissListener { result?.confirm() }
                .create()
                .show()
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnDismissListener { result?.cancel() }
                .create()
                .show()
            return true
        }

        // Block file chooser to prevent potential exploits
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            return true
        }

        // Block geolocation requests
        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            callback?.invoke(origin, false, false)
        }
    }

    private fun showBlockedUrlMessage(url: String) {
        val host = try {
            Uri.parse(url).host ?: url
        } catch (e: Exception) {
            url
        }
        Toast.makeText(
            this,
            getString(R.string.blocked_url_message, host),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_debug -> {
                toggleDebugPanel()
                true
            }
            R.id.action_translate -> {
                showTranslateDialog()
                true
            }
            R.id.action_refresh -> {
                webView.reload()
                true
            }
            R.id.action_home -> {
                webView.loadUrl(BASE_URL)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleDebugPanel() {
        val isVisible = debugPanel.visibility == View.VISIBLE
        val newVisibility = if (isVisible) View.GONE else View.VISIBLE
        debugPanel.visibility = newVisibility
        prefs.edit().putBoolean(PREF_DEBUG_VISIBLE, !isVisible).apply()

        // Auto-scroll to end when opening panel
        if (newVisibility == View.VISIBLE) {
            debugScrollView.post {
                debugScrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun showTranslateDialog() {
        val languages = arrayOf(
            getString(R.string.lang_original),
            getString(R.string.lang_english),
            getString(R.string.lang_russian),
            getString(R.string.lang_polish),
            getString(R.string.lang_ukrainian)
        )
        val languageCodes = arrayOf("original", "en", "ru", "pl", "uk")

        val currentIndex = languageCodes.indexOf(currentLanguage).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.translate_title)
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val newLanguage = languageCodes[which]
                val languageChanged = newLanguage != currentLanguage

                currentLanguage = newLanguage
                // Save language preference
                prefs.edit().putString(PREF_LANGUAGE, currentLanguage).apply()

                if (currentLanguage == "original") {
                    // Disable HTML translation and load with Lithuanian URL
                    htmlTranslator.isEnabled = false
                    htmlTranslator.targetLanguage = null
                    val currentUrl = webView.url ?: BASE_URL
                    val adjustedUrl = adjustUrlLanguage(currentUrl)
                    if (adjustedUrl != currentUrl) {
                        webView.loadUrl(adjustedUrl)
                    } else {
                        webView.reload()
                    }
                } else {
                    applyTranslation(languageChanged)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Apply translation using HTTP response interception.
     * Downloads models if needed, then reloads page so content goes through translator.
     */
    private fun applyTranslation(reloadPage: Boolean = true) {
        if (currentLanguage == "original") return

        DebugLogger.languageChanged(currentLanguage)

        lifecycleScope.launch {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Preparing translation...", Toast.LENGTH_SHORT).show()
            }

            DebugLogger.log("Checking models for '$currentLanguage'...")

            val modelsReady = translationManager.ensureModelsReady(currentLanguage) { status ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, status, Toast.LENGTH_SHORT).show()
                }
                DebugLogger.log("Model status: $status")
            }

            if (!modelsReady) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to download language models", Toast.LENGTH_SHORT).show()
                }
                DebugLogger.log("ERROR: Models not ready!")
                return@launch
            }

            DebugLogger.log("Models ready ✓")

            // Configure HTML translator
            htmlTranslator.targetLanguage = currentLanguage
            htmlTranslator.isEnabled = true

            // Load page with correct language in URL path
            if (reloadPage) {
                runOnUiThread {
                    DebugLogger.refreshTriggered()
                    val currentUrl = webView.url ?: BASE_URL
                    val adjustedUrl = adjustUrlLanguage(currentUrl)
                    if (adjustedUrl != currentUrl) {
                        webView.loadUrl(adjustedUrl)
                    } else {
                        webView.reload()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putString("currentLanguage", currentLanguage)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
        currentLanguage = savedInstanceState.getString("currentLanguage", "original")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()

        // Save current URL for restore on next startup
        webView.url?.let { url ->
            if (isAllowedNavigation(url)) {
                prefs.edit().putString(PREF_LAST_URL, url).apply()
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        webViewTranslator?.cleanup()
        translationManager.close()
        webView.destroy()
        super.onDestroy()
    }

    private fun openDownloadedFile(downloadId: Long, mimeType: String?) {
        val dm = downloadManager
        if (dm == null) {
            Toast.makeText(this, getString(R.string.open_file_failed), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Get content URI from DownloadManager (works on all Android versions)
            val contentUri = dm.getUriForDownloadedFile(downloadId)

            if (contentUri != null) {
                // Get actual MIME type if not provided
                val actualMimeType = mimeType ?: dm.getMimeTypeForDownloadedFile(downloadId) ?: "*/*"

                // Open file with appropriate app in a new task
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, actualMimeType)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                startActivity(intent)
            } else {
                Toast.makeText(this, getString(R.string.open_file_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_app_to_open), Toast.LENGTH_SHORT).show()
        }
    }
}
