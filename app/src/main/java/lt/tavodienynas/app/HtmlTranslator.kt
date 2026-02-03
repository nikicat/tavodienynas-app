package lt.tavodienynas.app

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Intercepts HTTP responses containing HTML and translates text content
 * before passing to WebView. This ensures content is already translated
 * when it reaches the DOM.
 */
class HtmlTranslator(
    private val translationManager: TranslationManager
) {
    companion object {
        private const val TAG = "HtmlTranslator"
        private const val BATCH_SIZE = 50
        private const val MIN_TEXT_LENGTH = 2

        // Elements to skip during translation
        private val SKIP_TAGS = setOf(
            "script", "style", "noscript", "code", "pre", "textarea"
        )

        // Classes that indicate icons (should not be translated)
        private val ICON_CLASS_PATTERNS = listOf(
            "icon", "material-icons", "material-symbols", "fa-", "fas", "far", "fab"
        )
    }

    var targetLanguage: String? = null
    var isEnabled: Boolean = false

    /**
     * Intercept a request and return translated response if applicable
     */
    fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        if (!isEnabled || targetLanguage == null) return null

        val url = request.url.toString()

        // Only intercept requests that might return HTML
        // Skip known non-HTML resources
        if (isNonHtmlResource(url)) return null

        return try {
            val response = fetchUrl(request)
            if (response != null && isHtmlContent(response.mimeType)) {
                translateHtmlResponse(response)
            } else {
                // Not HTML or failed to fetch - let WebView handle it normally
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to intercept request: ${e.message}")
            null
        }
    }

    /**
     * Check if URL is likely a non-HTML resource
     */
    private fun isNonHtmlResource(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".js") ||
                lower.endsWith(".css") ||
                lower.endsWith(".png") ||
                lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") ||
                lower.endsWith(".gif") ||
                lower.endsWith(".svg") ||
                lower.endsWith(".woff") ||
                lower.endsWith(".woff2") ||
                lower.endsWith(".ttf") ||
                lower.endsWith(".eot") ||
                lower.contains("/api/") ||
                lower.contains(".json")
    }

    /**
     * Check if content type indicates HTML
     */
    private fun isHtmlContent(mimeType: String?): Boolean {
        if (mimeType == null) return false
        return mimeType.contains("text/html") ||
                mimeType.contains("application/xhtml")
    }

    /**
     * Fetch URL and return response data
     */
    private fun fetchUrl(request: WebResourceRequest): FetchResponse? {
        val url = URL(request.url.toString())
        val connection = url.openConnection() as HttpURLConnection

        try {
            // Copy headers from original request
            request.requestHeaders?.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            connection.requestMethod = request.method
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return null
            }

            val mimeType = connection.contentType?.split(";")?.firstOrNull()?.trim() ?: "text/html"
            val encoding = extractCharset(connection.contentType) ?: "UTF-8"

            val inputStream = connection.inputStream
            val content = inputStream.readBytes()

            return FetchResponse(
                mimeType = mimeType,
                encoding = encoding,
                content = content,
                headers = connection.headerFields
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Extract charset from Content-Type header
     */
    private fun extractCharset(contentType: String?): String? {
        if (contentType == null) return null
        val parts = contentType.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("charset=", ignoreCase = true)) {
                return trimmed.substring(8).trim('"', '\'', ' ')
            }
        }
        return null
    }

    /**
     * Translate HTML content and return modified response
     */
    private fun translateHtmlResponse(response: FetchResponse): WebResourceResponse {
        val charset = Charset.forName(response.encoding)
        val html = String(response.content, charset)

        val translatedHtml = runBlocking {
            translateHtml(html)
        }

        val translatedBytes = translatedHtml.toByteArray(charset)

        return WebResourceResponse(
            response.mimeType,
            response.encoding,
            ByteArrayInputStream(translatedBytes)
        )
    }

    /**
     * Translate text content in HTML document
     */
    private suspend fun translateHtml(html: String): String {
        val lang = targetLanguage ?: return html

        return try {
            val doc = Jsoup.parse(html)

            // Collect all text nodes that need translation
            val textNodes = mutableListOf<TextNode>()
            collectTextNodes(doc.body(), textNodes)

            if (textNodes.isEmpty()) {
                return html
            }

            // Extract texts
            val texts = textNodes.map { it.text().trim() }
                .filter { it.length >= MIN_TEXT_LENGTH && !it.all { c -> c.isDigit() || c.isWhitespace() } }

            if (texts.isEmpty()) {
                return html
            }

            // Translate in batches
            val translatedTexts = mutableListOf<String>()
            texts.chunked(BATCH_SIZE).forEach { batch ->
                val translated = translationManager.translateBatch(batch, lang)
                translatedTexts.addAll(translated)
            }

            // Replace text nodes with translations
            var translationIndex = 0
            for (node in textNodes) {
                val originalText = node.text().trim()
                if (originalText.length >= MIN_TEXT_LENGTH &&
                    !originalText.all { c -> c.isDigit() || c.isWhitespace() } &&
                    translationIndex < translatedTexts.size) {

                    // Preserve leading/trailing whitespace
                    val fullText = node.text()
                    val leadingSpace = fullText.takeWhile { it.isWhitespace() }
                    val trailingSpace = fullText.takeLastWhile { it.isWhitespace() }

                    node.text(leadingSpace + translatedTexts[translationIndex] + trailingSpace)
                    translationIndex++
                }
            }

            doc.outerHtml()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to translate HTML: ${e.message}")
            html
        }
    }

    /**
     * Recursively collect text nodes, skipping icons and script elements
     */
    private fun collectTextNodes(element: Element?, nodes: MutableList<TextNode>) {
        if (element == null) return

        // Skip certain tags
        if (SKIP_TAGS.contains(element.tagName().lowercase())) return

        // Skip elements marked as no-translate
        if (element.attr("translate") == "no") return
        if (element.hasClass("notranslate")) return

        // Skip icon elements
        if (isIconElement(element)) return

        for (child in element.childNodes()) {
            when (child) {
                is TextNode -> {
                    if (child.text().trim().isNotEmpty()) {
                        nodes.add(child)
                    }
                }
                is Element -> {
                    collectTextNodes(child, nodes)
                }
            }
        }
    }

    /**
     * Check if element is an icon
     */
    private fun isIconElement(element: Element): Boolean {
        // Check tag name
        if (element.tagName().lowercase() == "i") return true

        // Check for data-icon attribute
        if (element.hasAttr("data-icon")) return true

        // Check class names
        val classes = element.classNames()
        for (cls in classes) {
            val lower = cls.lowercase()
            if (ICON_CLASS_PATTERNS.any { lower.contains(it) }) {
                return true
            }
        }

        return false
    }

    /**
     * Response data holder
     */
    private data class FetchResponse(
        val mimeType: String,
        val encoding: String,
        val content: ByteArray,
        val headers: Map<String, List<String>>
    )
}
