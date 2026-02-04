package lt.tavodienynas.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Intercepts HTTP responses containing HTML and translates text content
 * before passing to WebView. Handles redirects properly to maintain session.
 */
class HtmlTranslator(
    private val translationManager: TranslationManager
) {
    companion object {
        private const val TAG = "HtmlTranslator"
        private const val BATCH_SIZE = 10
        private const val MIN_TEXT_LENGTH = 2
        private const val MAX_REDIRECTS = 5
        private const val CLEANUP_DELAY_MS = 1000L
        private const val CACHE_MAX_AGE_DAYS = 7
        const val JS_INTERFACE_NAME = "PostBodyCapture"

        // Elements to skip during translation
        private val SKIP_TAGS = setOf(
            "script", "style", "noscript", "code", "pre", "textarea",
            "input", "select", "option", "button"
        )

        // Classes that indicate icons (should not be translated)
        private val ICON_CLASS_PATTERNS = listOf(
            "icon", "material-icons", "material-symbols", "fa-", "fas", "far", "fab"
        )

        // Lithuanian day abbreviations -> target language mappings
        // Pr=Monday, An=Tuesday, Tr=Wednesday, Kt=Thursday, Pn=Friday, Št=Saturday, Sk=Sunday
        private val DAY_TRANSLATIONS = mapOf(
            "en" to mapOf(
                "Pr" to "Mon", "An" to "Tue", "Tr" to "Wed", "Kt" to "Thu",
                "Pn" to "Fri", "Št" to "Sat", "Sk" to "Sun"
            ),
            "ru" to mapOf(
                "Pr" to "Пн", "An" to "Вт", "Tr" to "Ср", "Kt" to "Чт",
                "Pn" to "Пт", "Št" to "Сб", "Sk" to "Вс"
            ),
            "pl" to mapOf(
                "Pr" to "Pon", "An" to "Wt", "Tr" to "Śr", "Kt" to "Czw",
                "Pn" to "Pt", "Št" to "Sob", "Sk" to "Ndz"
            ),
            "uk" to mapOf(
                "Pr" to "Пн", "An" to "Вт", "Tr" to "Ср", "Kt" to "Чт",
                "Pn" to "Пт", "Št" to "Сб", "Sk" to "Нд"
            )
        )

        // Classes that indicate day-of-week elements (use hardcoded translation)
        private val DAY_CONTAINER_CLASSES = setOf("swiper-day")

        // IDs where text content should be skipped but title attributes translated
        private val SKIP_TEXT_IDS = setOf("languages-block")

        // Attributes to translate
        private val TRANSLATABLE_ATTRS = setOf("title", "alt", "placeholder")
    }

    var targetLanguage: String? = null
    var isEnabled: Boolean = false

    // Store captured POST bodies
    private val postBodies = ConcurrentHashMap<String, PostBodyInfo>()

    // Idle detection for cache cleanup
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = Runnable { performCacheCleanup() }
    private val cleanupScope = CoroutineScope(Dispatchers.IO)

    // OkHttp client - NO automatic redirect following (we handle manually)
    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private data class PostBodyInfo(
        val body: String,
        val contentType: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * JavaScript interface for capturing POST bodies
     */
    inner class PostBodyInterface {
        @android.webkit.JavascriptInterface
        fun sendPostData(url: String, body: String, contentType: String?) {
            Log.d(TAG, "Captured POST body for $url (${body.length} chars)")
            DebugLogger.log("📦 POST BODY URL: $url")
            postBodies[url] = PostBodyInfo(body, contentType)
        }
    }

    /**
     * Get the JavaScript to inject for capturing POST bodies
     */
    fun getBodyCaptureScript(): String {
        return """
            (function() {
                if (window._postBodyCaptureInstalled) return;
                window._postBodyCaptureInstalled = true;

                var originalXhrOpen = XMLHttpRequest.prototype.open;
                var originalXhrSend = XMLHttpRequest.prototype.send;
                var originalFetch = window.fetch;

                XMLHttpRequest.prototype.open = function(method, url) {
                    this._method = method;
                    this._url = url;
                    return originalXhrOpen.apply(this, arguments);
                };

                XMLHttpRequest.prototype.send = function(body) {
                    if (this._method && this._method.toUpperCase() === 'POST' && body && window.$JS_INTERFACE_NAME) {
                        window.$JS_INTERFACE_NAME.sendPostData(
                            this._url,
                            typeof body === 'string' ? body : body.toString(),
                            null
                        );
                    }
                    return originalXhrSend.apply(this, arguments);
                };

                window.fetch = function(input, init) {
                    if (init && init.method && init.method.toUpperCase() === 'POST' && init.body && window.$JS_INTERFACE_NAME) {
                        var url = typeof input === 'string' ? input : input.url;
                        window.$JS_INTERFACE_NAME.sendPostData(
                            url,
                            typeof init.body === 'string' ? init.body : init.body.toString(),
                            null
                        );
                    }
                    return originalFetch.apply(this, arguments);
                };

                console.log('POST body capture installed');
            })();
        """.trimIndent().replace("\$JS_INTERFACE_NAME", JS_INTERFACE_NAME)
    }

    /**
     * Intercept a request and return translated response if applicable
     */
    fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        if (!isEnabled || targetLanguage == null) return null

        val url = request.url.toString()
        val method = request.method.uppercase()

        // Skip known non-HTML resources by URL pattern
        if (isNonHtmlResource(url)) return null

        // For POST requests, check if we have the body
        val postBody: PostBodyInfo? = if (method == "POST") {
            DebugLogger.log("📬 POST request URL: $url")

            // Try exact match first
            var body = postBodies[url]

            // If not found, try to find by URL ending (in case of relative vs absolute URL)
            if (body == null) {
                Thread.sleep(100)
                body = postBodies[url]
            }

            // Still not found? Try partial match
            if (body == null) {
                val matchingKey = postBodies.keys.find { key ->
                    url.endsWith(key) || key.endsWith(url.substringAfter("://").substringAfter("/")) ||
                    url.contains(key) || key.contains(url.substringAfterLast("/"))
                }
                if (matchingKey != null) {
                    DebugLogger.log("🔗 Matched by partial: $matchingKey")
                    body = postBodies[matchingKey]
                    postBodies.remove(matchingKey)
                }
            }

            if (body == null) {
                Log.d(TAG, "No POST body for $url, letting WebView handle")
                DebugLogger.log("⚠️ No body found. Have keys: ${postBodies.keys.map { it.takeLast(30) }}")
                return null
            }
            DebugLogger.log("✓ POST body found, intercepting")
            body
        } else {
            null
        }

        return try {
            val response = fetchWithRedirects(request, postBody)

            if (response == null) {
                Log.d(TAG, "Fetch failed for $url")
                return null
            }

            // Check if it's HTML content by Content-Type
            if (!isHtmlContent(response.mimeType)) {
                Log.d(TAG, "Non-HTML content (${response.mimeType}) for $url")
                DebugLogger.passThrough(url, response.mimeType)
                return WebResourceResponse(
                    response.mimeType,
                    response.encoding,
                    ByteArrayInputStream(response.content)
                )
            }

            // Verify content actually looks like HTML (not empty or non-HTML with wrong content-type)
            val charset = try { Charset.forName(response.encoding) } catch (e: Exception) { Charsets.UTF_8 }
            if (!looksLikeHtml(response.content, charset)) {
                Log.d(TAG, "Content doesn't look like HTML for $url (${response.content.size} bytes)")
                DebugLogger.log("⏭️ SKIP: not HTML content ($url)")
                return WebResourceResponse(
                    response.mimeType,
                    response.encoding,
                    ByteArrayInputStream(response.content)
                )
            }

            Log.d(TAG, "Translating HTML for $url (${response.content.size} bytes)")
            DebugLogger.translateStart(url, response.content.size)
            val startTime = System.currentTimeMillis()
            val result = translateHtmlResponse(response, url)
            val duration = System.currentTimeMillis() - startTime
            DebugLogger.translateDone(url, targetLanguage ?: "?", duration)
            result

        } catch (e: Exception) {
            Log.e(TAG, "Failed to intercept $url: ${e.message}", e)
            null
        } finally {
            postBody?.let { postBodies.remove(url) }
            if (postBodies.size > 50) cleanupOldBodies()
        }
    }

    /**
     * Fetch URL following redirects manually to preserve cookies
     */
    private fun fetchWithRedirects(request: WebResourceRequest, postBody: PostBodyInfo?): FetchResponse? {
        var currentUrl = request.url.toString()
        var redirectCount = 0
        val cookieManager = CookieManager.getInstance()

        // Use POST only for first request, redirects are always GET
        var isPost = postBody != null

        while (redirectCount < MAX_REDIRECTS) {
            cookieManager.flush()

            // Build headers
            val headersBuilder = mutableMapOf<String, String>()

            // Copy original headers only for first request
            if (redirectCount == 0) {
                request.requestHeaders?.forEach { (key, value) ->
                    if (!key.equals("Content-Length", ignoreCase = true) &&
                        !key.equals("Host", ignoreCase = true)) {
                        headersBuilder[key] = value
                    }
                }
            }

            // Get cookies for current URL
            val cookies = cookieManager.getCookie(currentUrl)
            if (cookies != null) {
                headersBuilder["Cookie"] = cookies
            }

            // Build request
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .headers(headersBuilder.toHeaders())

            if (isPost && postBody != null) {
                val mediaType = (postBody.contentType ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()
                requestBuilder.post(postBody.body.toRequestBody(mediaType))
            } else {
                requestBuilder.get()
            }

            val okRequest = requestBuilder.build()
            val response = okHttpClient.newCall(okRequest).execute()

            // Store cookies from response
            response.headers("Set-Cookie").forEach { cookie ->
                cookieManager.setCookie(currentUrl, cookie)
            }
            cookieManager.flush()

            val responseCode = response.code
            Log.d(TAG, "Response $responseCode for $currentUrl")
            DebugLogger.intercepted(currentUrl, responseCode)

            // Handle redirects
            if (responseCode in 300..399) {
                val location = response.header("Location")
                response.close()

                if (location == null) {
                    Log.e(TAG, "Redirect without Location header")
                    return null
                }

                // Resolve relative URLs
                currentUrl = if (location.startsWith("http")) {
                    location
                } else if (location.startsWith("/")) {
                    val uri = java.net.URI(currentUrl)
                    "${uri.scheme}://${uri.host}$location"
                } else {
                    val uri = java.net.URI(currentUrl)
                    val basePath = uri.path.substringBeforeLast("/")
                    "${uri.scheme}://${uri.host}$basePath/$location"
                }

                Log.d(TAG, "Following redirect to $currentUrl")
                DebugLogger.redirect(currentUrl)
                redirectCount++
                isPost = false // Redirects are always GET
                continue
            }

            // Non-redirect response
            if (responseCode !in 200..299) {
                response.close()
                return null
            }

            val contentType = response.header("Content-Type") ?: "text/html"
            val mimeType = contentType.split(";").firstOrNull()?.trim() ?: "text/html"
            val encoding = extractCharset(contentType) ?: "UTF-8"
            val content = response.body?.bytes() ?: return null

            return FetchResponse(mimeType, encoding, content)
        }

        Log.e(TAG, "Too many redirects for ${request.url}")
        return null
    }

    private fun isNonHtmlResource(url: String): Boolean {
        val lower = url.lowercase()

        // Resource patterns with optional cache-busters
        val resourcePatterns = listOf(
            "\\.js", "\\.css",
            "\\.png", "\\.jpg", "\\.jpeg", "\\.gif", "\\.svg", "\\.ico", "\\.webp", "\\.bmp",
            "\\.woff", "\\.woff2", "\\.ttf", "\\.eot", "\\.otf",
            "\\.mp3", "\\.mp4", "\\.webm", "\\.ogg", "\\.wav",
            "\\.pdf", "\\.zip", "\\.tar", "\\.gz"
        )

        for (pattern in resourcePatterns) {
            if (Regex("$pattern(\\?|\\d*\$|#)").containsMatchIn(lower)) {
                return true
            }
        }

        // Known resource paths
        if (lower.contains("/uploaded_files/") ||
            lower.contains("/assets/images/") ||
            lower.contains("/static/") ||
            lower.contains("/fonts/")) {
            return true
        }

        return lower.contains("/api/") || lower.contains(".json")
    }

    private fun isHtmlContent(mimeType: String?): Boolean {
        if (mimeType == null) return false
        val lower = mimeType.lowercase()
        return lower.contains("text/html") || lower.contains("application/xhtml")
    }

    // Regex to match HTML: optional whitespace, then <tag or <!doctype/comment
    private val HTML_START_PATTERN = Regex("^\\s*<[a-zA-Z!]")

    /**
     * Check if content actually looks like HTML (starts with optional whitespace then an HTML tag).
     * This prevents translating empty responses or non-HTML content with text/html mime type.
     */
    private fun looksLikeHtml(content: ByteArray, charset: Charset): Boolean {
        if (content.isEmpty()) return false
        val preview = String(content, 0, minOf(content.size, 500), charset)
        return HTML_START_PATTERN.containsMatchIn(preview)
    }

    private fun extractCharset(contentType: String?): String? {
        if (contentType == null) return null
        for (part in contentType.split(";")) {
            val trimmed = part.trim()
            if (trimmed.startsWith("charset=", ignoreCase = true)) {
                return trimmed.substring(8).trim('"', '\'', ' ')
            }
        }
        return null
    }

    private fun translateHtmlResponse(response: FetchResponse, url: String): WebResourceResponse {
        val charset = Charset.forName(response.encoding)
        val html = String(response.content, charset)

        Log.d(TAG, "Starting HTML translation (${html.length} chars)")
        var translatedHtml = runBlocking {
            translateHtml(html)
        }

        // Inject POST capture script at the very beginning so it runs before any other scripts
        translatedHtml = injectPostCaptureScript(translatedHtml)

        Log.d(TAG, "Translation complete (${translatedHtml.length} chars), returning WebResourceResponse")

        return WebResourceResponse(
            response.mimeType,
            response.encoding,
            ByteArrayInputStream(translatedHtml.toByteArray(charset))
        )
    }

    /**
     * Inject POST body capture script into HTML so it runs before any other scripts
     */
    private fun injectPostCaptureScript(html: String): String {
        val script = "<script>${getBodyCaptureScript()}</script>"

        // Try to inject right after <head> tag
        val headIndex = html.indexOf("<head", ignoreCase = true)
        if (headIndex != -1) {
            val headCloseIndex = html.indexOf(">", headIndex)
            if (headCloseIndex != -1) {
                DebugLogger.log("💉 POST script injected into <head>")
                return html.substring(0, headCloseIndex + 1) + script + html.substring(headCloseIndex + 1)
            }
        }

        // Try to inject right after <html> tag
        val htmlIndex = html.indexOf("<html", ignoreCase = true)
        if (htmlIndex != -1) {
            val htmlCloseIndex = html.indexOf(">", htmlIndex)
            if (htmlCloseIndex != -1) {
                DebugLogger.log("💉 POST script injected into <html>")
                return html.substring(0, htmlCloseIndex + 1) + script + html.substring(htmlCloseIndex + 1)
            }
        }

        // Fallback: prepend to content (for HTML fragments)
        DebugLogger.log("💉 POST script prepended to fragment")
        return script + html
    }

    private suspend fun translateHtml(html: String): String {
        val lang = targetLanguage ?: return html

        // Reset cache stats for this page
        translationManager.cache.resetBatchStats()

        return try {
            val doc = Jsoup.parse(html)
            val collectedNodes = mutableListOf<CollectedTextNode>()
            val collectedAttrs = mutableListOf<CollectedAttribute>()
            collectTextNodes(doc.body(), collectedNodes, collectedAttrs, isDayContext = false, skipTextContext = false)

            if (collectedNodes.isEmpty() && collectedAttrs.isEmpty()) return html

            // Separate day nodes from regular nodes
            val dayNodes = collectedNodes.filter { it.isDayNode }
            val regularNodes = collectedNodes.filter { !it.isDayNode }

            // Translate day nodes using hardcoded mapping
            val dayMap = DAY_TRANSLATIONS[lang]
            for (collected in dayNodes) {
                val originalText = collected.node.text().trim()
                val translated = dayMap?.get(originalText) ?: originalText
                val fullText = collected.node.text()
                val leading = fullText.takeWhile { it.isWhitespace() }
                val trailing = fullText.takeLastWhile { it.isWhitespace() }
                collected.node.text(leading + translated + trailing)
            }

            // Collect all texts for ML Kit translation (regular nodes + attributes)
            val regularTexts = regularNodes
                .map { it.node.text().trim() }
                .filter { it.length >= MIN_TEXT_LENGTH && !it.all { c -> c.isDigit() || c.isWhitespace() } }

            val attrTexts = collectedAttrs.map { it.originalValue.trim() }

            val allTextsToTranslate = regularTexts + attrTexts

            if (allTextsToTranslate.isNotEmpty()) {
                // Show progress bar
                val totalBatches = (allTextsToTranslate.size + BATCH_SIZE - 1) / BATCH_SIZE
                var completedBatches = 0
                DebugLogger.showProgress(0, totalBatches)

                val translatedTexts = mutableListOf<String>()
                allTextsToTranslate.chunked(BATCH_SIZE).forEach { batch ->
                    translatedTexts.addAll(translationManager.translateBatch(batch, lang))
                    completedBatches++
                    DebugLogger.showProgress(completedBatches, totalBatches)
                }

                // Apply translations to regular text nodes
                var idx = 0
                for (collected in regularNodes) {
                    val originalText = collected.node.text().trim()
                    if (originalText.length >= MIN_TEXT_LENGTH &&
                        !originalText.all { c -> c.isDigit() || c.isWhitespace() } &&
                        idx < translatedTexts.size) {
                        val fullText = collected.node.text()
                        val leading = fullText.takeWhile { it.isWhitespace() }
                        val trailing = fullText.takeLastWhile { it.isWhitespace() }
                        collected.node.text(leading + translatedTexts[idx] + trailing)
                        idx++
                    }
                }

                // Apply translations to attributes
                for (collected in collectedAttrs) {
                    if (idx < translatedTexts.size) {
                        collected.element.attr(collected.attrName, translatedTexts[idx])
                        idx++
                    }
                }

                DebugLogger.hideProgress()
            }

            // Log cache statistics
            DebugLogger.log("📊 ${translationManager.cache.getBatchStatsLog()}")

            // Schedule cache cleanup after idle period
            scheduleCleanup()

            doc.outerHtml()
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed: ${e.message}")
            DebugLogger.hideProgress()
            html
        }
    }

    /** Wrapper for text node with context info */
    private data class CollectedTextNode(
        val node: TextNode,
        val isDayNode: Boolean
    )

    /** Wrapper for element attribute to translate */
    private data class CollectedAttribute(
        val element: Element,
        val attrName: String,
        val originalValue: String
    )

    private fun collectTextNodes(
        element: Element?,
        textNodes: MutableList<CollectedTextNode>,
        attributes: MutableList<CollectedAttribute>,
        isDayContext: Boolean,
        skipTextContext: Boolean
    ) {
        if (element == null) return
        if (SKIP_TAGS.contains(element.tagName().lowercase())) return
        if (element.attr("translate") == "no") return
        if (element.hasClass("notranslate")) return
        if (isIconElement(element)) return

        // Check if this element is a day container
        val isDay = isDayContext || DAY_CONTAINER_CLASSES.any { element.hasClass(it) }

        // Check if we should skip text content (but still translate attributes)
        val skipText = skipTextContext || SKIP_TEXT_IDS.contains(element.id())

        // Collect translatable attributes
        for (attr in TRANSLATABLE_ATTRS) {
            val value = element.attr(attr)
            if (value.isNotBlank() && value.length >= MIN_TEXT_LENGTH) {
                attributes.add(CollectedAttribute(element, attr, value))
            }
        }

        for (child in element.childNodes()) {
            when (child) {
                is TextNode -> if (!skipText && child.text().trim().isNotEmpty()) {
                    textNodes.add(CollectedTextNode(child, isDay))
                }
                is Element -> collectTextNodes(child, textNodes, attributes, isDay, skipText)
            }
        }
    }

    private fun isIconElement(element: Element): Boolean {
        if (element.tagName().lowercase() == "i") return true
        if (element.hasAttr("data-icon")) return true
        return element.classNames().any { cls ->
            ICON_CLASS_PATTERNS.any { cls.lowercase().contains(it) }
        }
    }

    private fun cleanupOldBodies() {
        val now = System.currentTimeMillis()
        postBodies.entries.filter { now - it.value.timestamp > 30000 }
            .forEach { postBodies.remove(it.key) }
    }

    /**
     * Schedule cache cleanup after idle period
     */
    private fun scheduleCleanup() {
        mainHandler.removeCallbacks(cleanupRunnable)
        mainHandler.postDelayed(cleanupRunnable, CLEANUP_DELAY_MS)
    }

    /**
     * Perform cache cleanup in background
     */
    private fun performCacheCleanup() {
        cleanupScope.launch {
            try {
                DebugLogger.log("🧹 Starting cache cleanup...")
                val stats = translationManager.cache.cleanup(CACHE_MAX_AGE_DAYS)
                DebugLogger.log("🧹 Cleanup done: ${stats.totalEntries} entries, ${stats.formatSize()}, removed ${stats.removedEntries}, took ${stats.durationMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Cache cleanup failed: ${e.message}")
            }
        }
    }

    private data class FetchResponse(
        val mimeType: String,
        val encoding: String,
        val content: ByteArray
    )
}
