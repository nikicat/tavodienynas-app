package lt.manodienynas.app

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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var prefs: SharedPreferences
    private lateinit var downloadManager: DownloadManager

    private var currentLanguage: String = "original"
    private val pendingDownloads = mutableMapOf<Long, String>() // downloadId to mimeType

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
        private const val PREFS_NAME = "manodienynas_prefs"
        private const val PREF_LANGUAGE = "translation_language"

        // Main site - only these can be navigated to
        private val ALLOWED_NAVIGATION_HOSTS = setOf(
            "manodienynas.lt",
            "www.manodienynas.lt"
        )

        // Allowed resource hosts (fonts, icons, CDNs, translation)
        // These can load resources but NOT be navigated to
        private val ALLOWED_RESOURCE_HOSTS = setOf(
            // Google Fonts & Icons
            "fonts.googleapis.com",
            "fonts.gstatic.com",
            // Google Translate
            "translate.google.com",
            "translate.googleapis.com",
            "translate-pa.googleapis.com",
            "www.gstatic.com",
            // Common CDNs
            "cdnjs.cloudflare.com",
            "cdn.jsdelivr.net",
            "unpkg.com",
            // Google APIs (for various services)
            "ajax.googleapis.com",
            "www.googleapis.com"
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Load saved language preference
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentLanguage = prefs.getString(PREF_LANGUAGE, "original") ?: "original"

        // Initialize download manager and register receiver
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data
        if (uri != null && isAllowedNavigation(uri.toString())) {
            webView.loadUrl(uri.toString())
        } else {
            webView.loadUrl(BASE_URL)
        }
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

            val downloadId = downloadManager.enqueue(request)
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
     * Secure WebViewClient that blocks navigation to external sites
     */
    private inner class SecureWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return true

            return if (isAllowedNavigation(url)) {
                // Allow navigation within manodienynas.lt
                false
            } else {
                // Block all external URLs
                showBlockedUrlMessage(url)
                true
            }
        }

        @Deprecated("Deprecated in API 24, but needed for older devices")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url ?: return true

            return if (isAllowedNavigation(url)) {
                false
            } else {
                showBlockedUrlMessage(url)
                true
            }
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

            // Apply translation if language is selected
            if (currentLanguage != "original") {
                applyTranslation()
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

    private fun showTranslateDialog() {
        val languages = arrayOf(
            getString(R.string.lang_original),
            getString(R.string.lang_english),
            getString(R.string.lang_russian),
            getString(R.string.lang_polish),
            getString(R.string.lang_german),
            getString(R.string.lang_french)
        )
        val languageCodes = arrayOf("original", "en", "ru", "pl", "de", "fr")

        val currentIndex = languageCodes.indexOf(currentLanguage).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.translate_title)
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                currentLanguage = languageCodes[which]
                // Save language preference
                prefs.edit().putString(PREF_LANGUAGE, currentLanguage).apply()

                if (currentLanguage == "original") {
                    // Reload page without translation
                    webView.reload()
                } else {
                    applyTranslation()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Applies Google Translate to the current page using JavaScript injection
     */
    private fun applyTranslation() {
        if (currentLanguage == "original") return

        val targetLang = currentLanguage  // Capture for use in script

        // Inject Google Translate element
        val script = """
            (function() {
                var targetLanguage = '${targetLang}';

                // Protect icon elements from translation
                var iconSelectors = [
                    '.material-icons',
                    '.material-icons-outlined',
                    '.material-icons-round',
                    '.material-icons-sharp',
                    '.material-icons-two-tone',
                    '.material-symbols-outlined',
                    '.material-symbols-rounded',
                    '.material-symbols-sharp',
                    '[class*="icon"]',
                    '[class*="Icon"]',
                    '.fa', '.fas', '.far', '.fal', '.fab', '.fad',
                    '[data-icon]',
                    'i[class]'
                ];

                iconSelectors.forEach(function(selector) {
                    try {
                        document.querySelectorAll(selector).forEach(function(el) {
                            el.classList.add('notranslate');
                            el.setAttribute('translate', 'no');
                        });
                    } catch(e) {}
                });

                // Function to hide GT banner (runs repeatedly to catch dynamically added elements)
                function hideGTBanner() {
                    // Hide the iframe banner
                    var frames = document.querySelectorAll('.goog-te-banner-frame, iframe.goog-te-banner-frame');
                    frames.forEach(function(f) {
                        f.style.display = 'none';
                        f.style.height = '0';
                        f.style.visibility = 'hidden';
                    });

                    // Fix body position that GT modifies
                    document.body.style.top = '0px';
                    document.body.style.position = 'relative';

                    // Hide other GT elements
                    var gtElements = document.querySelectorAll('.skiptranslate, #goog-gt-tt, .goog-te-balloon-frame');
                    gtElements.forEach(function(el) {
                        if (!el.querySelector('.goog-te-combo')) {
                            el.style.display = 'none';
                        }
                    });
                }

                // Set up MutationObserver to hide banner when it appears
                if (!window.gtObserver) {
                    window.gtObserver = new MutationObserver(function(mutations) {
                        hideGTBanner();
                    });
                    window.gtObserver.observe(document.body, { childList: true, subtree: true });
                }

                // Check if GT is already loaded
                if (window.google && window.google.translate) {
                    // GT already loaded, just change language
                    var select = document.querySelector('.goog-te-combo');
                    if (select) {
                        select.value = targetLanguage;
                        select.dispatchEvent(new Event('change'));
                        setTimeout(hideGTBanner, 100);
                        setTimeout(hideGTBanner, 500);
                        setTimeout(hideGTBanner, 1000);
                        return;
                    }
                }

                // Remove existing elements for fresh start
                var existing = document.getElementById('google_translate_element');
                if (existing) existing.remove();

                var oldScript = document.getElementById('gt_script1');
                if (oldScript) oldScript.remove();

                // Create translate container (hidden)
                var div = document.createElement('div');
                div.id = 'google_translate_element';
                div.style.display = 'none';
                document.body.appendChild(div);

                // Initialize Google Translate
                window.googleTranslateElementInit = function() {
                    new google.translate.TranslateElement({
                        pageLanguage: 'lt',
                        includedLanguages: 'en,ru,pl,de,fr',
                        autoDisplay: false
                    }, 'google_translate_element');

                    // Select target language after GT initializes
                    var attempts = 0;
                    var selectLanguage = function() {
                        var select = document.querySelector('.goog-te-combo');
                        if (select && select.options.length > 1) {
                            select.value = targetLanguage;
                            select.dispatchEvent(new Event('change'));
                            hideGTBanner();
                            setTimeout(hideGTBanner, 500);
                            setTimeout(hideGTBanner, 1000);
                            setTimeout(hideGTBanner, 2000);
                        } else if (attempts < 20) {
                            attempts++;
                            setTimeout(selectLanguage, 200);
                        }
                    };
                    setTimeout(selectLanguage, 500);
                };

                // Load Google Translate script
                var s1 = document.createElement('script');
                s1.id = 'gt_script1';
                s1.src = 'https://translate.google.com/translate_a/element.js?cb=googleTranslateElementInit';
                document.body.appendChild(s1);

                // Also run hide function periodically for first few seconds
                setTimeout(hideGTBanner, 1000);
                setTimeout(hideGTBanner, 2000);
                setTimeout(hideGTBanner, 3000);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
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
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        webView.destroy()
        super.onDestroy()
    }

    private fun openDownloadedFile(downloadId: Long, mimeType: String?) {
        try {
            // Get content URI from DownloadManager (works on all Android versions)
            val contentUri = downloadManager.getUriForDownloadedFile(downloadId)

            if (contentUri != null) {
                // Get actual MIME type if not provided
                val actualMimeType = mimeType ?: downloadManager.getMimeTypeForDownloadedFile(downloadId) ?: "*/*"

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
