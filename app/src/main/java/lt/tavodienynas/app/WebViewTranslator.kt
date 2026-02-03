package lt.tavodienynas.app

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles translation of WebView content using ML Kit.
 * Extracts text nodes from DOM, translates them, and replaces them back.
 */
class WebViewTranslator(
    private val webView: WebView,
    private val translationManager: TranslationManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "WebViewTranslator"
        private const val JS_INTERFACE_NAME = "AndroidTranslator"
        private const val BATCH_SIZE = 50 // Translate in batches for better performance
        private const val MIN_TEXT_LENGTH = 2 // Skip very short texts
    }

    private var isTranslating = false
    private var currentLanguage: String? = null
    private var translationJob: Job? = null

    // Store original texts for reverting
    private val originalTexts = mutableMapOf<String, String>()

    init {
        // Add JavaScript interface
        webView.addJavascriptInterface(TranslationInterface(), JS_INTERFACE_NAME)
    }

    /**
     * JavaScript interface for communication between WebView and Kotlin
     */
    inner class TranslationInterface {
        @JavascriptInterface
        fun onTextsExtracted(jsonData: String) {
            scope.launch {
                processExtractedTexts(jsonData)
            }
        }

        @JavascriptInterface
        fun onTranslationComplete() {
            Log.d(TAG, "Translation injection complete")
            // Set up observer for future dynamic content
            scope.launch {
                withContext(Dispatchers.Main) {
                    setupDynamicContentObserver()
                }
            }
        }

        @JavascriptInterface
        fun onNewContentDetected() {
            Log.d(TAG, "New content detected, translating...")
            if (!isTranslating && currentLanguage != null) {
                scope.launch {
                    withContext(Dispatchers.Main) {
                        injectExtractionScript(onlyNew = true)
                    }
                }
            }
        }
    }

    /**
     * Start translating the page to the target language
     */
    fun translatePage(targetLanguage: String, onProgress: ((String) -> Unit)? = null) {
        if (isTranslating) {
            Log.d(TAG, "Translation already in progress")
            return
        }

        translationJob?.cancel()
        translationJob = scope.launch {
            try {
                isTranslating = true
                currentLanguage = targetLanguage

                // Ensure models are ready
                onProgress?.invoke("Preparing translation...")
                val modelsReady = translationManager.ensureModelsReady(targetLanguage) { status ->
                    onProgress?.invoke(status)
                }

                if (!modelsReady) {
                    onProgress?.invoke("Failed to download language models")
                    return@launch
                }

                onProgress?.invoke("Extracting text...")

                // Inject JavaScript to extract text nodes
                withContext(Dispatchers.Main) {
                    injectExtractionScript()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Translation failed: ${e.message}")
                onProgress?.invoke("Translation failed: ${e.message}")
            } finally {
                isTranslating = false
            }
        }
    }

    /**
     * Revert to original (reload page)
     */
    fun revertTranslation() {
        translationJob?.cancel()
        isTranslating = false
        currentLanguage = null
        originalTexts.clear()
        webView.reload()
    }

    /**
     * Process extracted texts from JavaScript
     */
    private suspend fun processExtractedTexts(jsonData: String) {
        try {
            val json = JSONArray(jsonData)
            val textsToTranslate = mutableListOf<Pair<String, String>>() // id -> text

            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                val id = item.getString("id")
                val text = item.getString("text")

                if (text.length >= MIN_TEXT_LENGTH && !text.all { it.isDigit() || it.isWhitespace() }) {
                    textsToTranslate.add(id to text)
                    originalTexts[id] = text
                }
            }

            Log.d(TAG, "Extracted ${textsToTranslate.size} texts to translate")

            if (textsToTranslate.isEmpty()) {
                return
            }

            val targetLang = currentLanguage ?: return

            // Translate in batches
            val translations = mutableMapOf<String, String>()

            textsToTranslate.chunked(BATCH_SIZE).forEach { batch ->
                val texts = batch.map { it.second }
                val translated = translationManager.translateBatch(texts, targetLang)

                batch.forEachIndexed { index, (id, _) ->
                    translations[id] = translated[index]
                }
            }

            // Inject translations back into page
            withContext(Dispatchers.Main) {
                injectTranslations(translations)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process extracted texts: ${e.message}")
        }
    }

    /**
     * Inject JavaScript to extract all text nodes
     */
    private fun injectExtractionScript(onlyNew: Boolean = false) {
        val script = """
            (function() {
                var texts = [];
                var nodeId = window._translationNodeId || 0;

                // Skip these elements
                var skipTags = ['SCRIPT', 'STYLE', 'NOSCRIPT', 'IFRAME', 'OBJECT', 'EMBED', 'SVG', 'MATH'];

                // Icon-related classes to skip
                var iconClasses = [
                    'notranslate',
                    'material-icons',
                    'material-icons-outlined',
                    'material-icons-round',
                    'material-icons-sharp',
                    'material-icons-two-tone',
                    'material-symbols-outlined',
                    'material-symbols-rounded',
                    'material-symbols-sharp',
                    'fa', 'fas', 'far', 'fal', 'fab', 'fad'
                ];

                function shouldSkip(element) {
                    if (!element) return true;
                    if (skipTags.indexOf(element.tagName) >= 0) return true;
                    if (element.getAttribute('translate') === 'no') return true;
                    if (element.getAttribute('data-icon')) return true;

                    // Check tag name for icons
                    if (element.tagName === 'I') return true;

                    // Check classList
                    if (element.classList) {
                        for (var i = 0; i < iconClasses.length; i++) {
                            if (element.classList.contains(iconClasses[i])) return true;
                        }
                        // Check for any class containing 'icon'
                        for (var i = 0; i < element.classList.length; i++) {
                            var cls = element.classList[i].toLowerCase();
                            if (cls.indexOf('icon') >= 0) return true;
                        }
                    }

                    // Check className attribute for SVG elements
                    var classAttr = element.getAttribute ? element.getAttribute('class') : null;
                    if (classAttr && classAttr.toLowerCase().indexOf('icon') >= 0) return true;

                    return false;
                }

                function extractTexts(node, onlyNew) {
                    if (!node) return;

                    if (node.nodeType === Node.TEXT_NODE) {
                        // Skip if already processed (unless we want all)
                        if (onlyNew && node._translationId) return;

                        var text = node.textContent.trim();
                        if (text.length > 0) {
                            var id = 'txt_' + (nodeId++);
                            node._translationId = id;
                            texts.push({id: id, text: text});
                        }
                        return;
                    }

                    if (node.nodeType === Node.ELEMENT_NODE) {
                        if (shouldSkip(node)) return;

                        for (var i = 0; i < node.childNodes.length; i++) {
                            extractTexts(node.childNodes[i], onlyNew);
                        }
                    }
                }

                extractTexts(document.body, ${onlyNew});

                // Save nodeId for incremental extractions
                window._translationNodeId = nodeId;

                // Send to Android
                if (window.$JS_INTERFACE_NAME && texts.length > 0) {
                    window.$JS_INTERFACE_NAME.onTextsExtracted(JSON.stringify(texts));
                }
            })();
        """.trimIndent().replace("\$JS_INTERFACE_NAME", JS_INTERFACE_NAME)

        webView.evaluateJavascript(script, null)
    }

    /**
     * Inject translated texts back into the page
     */
    private fun injectTranslations(translations: Map<String, String>) {
        // Convert to JSON
        val jsonObj = JSONObject()
        translations.forEach { (id, text) ->
            jsonObj.put(id, text)
        }
        val jsonStr = jsonObj.toString().replace("'", "\\'").replace("\n", "\\n")

        val script = """
            (function() {
                var translations = JSON.parse('$jsonStr');

                function replaceTexts(node) {
                    if (!node) return;

                    if (node.nodeType === Node.TEXT_NODE) {
                        if (node._translationId && translations[node._translationId]) {
                            node.textContent = translations[node._translationId];
                        }
                        return;
                    }

                    if (node.nodeType === Node.ELEMENT_NODE) {
                        for (var i = 0; i < node.childNodes.length; i++) {
                            replaceTexts(node.childNodes[i]);
                        }
                    }
                }

                replaceTexts(document.body);

                if (window.$JS_INTERFACE_NAME) {
                    window.$JS_INTERFACE_NAME.onTranslationComplete();
                }
            })();
        """.trimIndent().replace("\$JS_INTERFACE_NAME", JS_INTERFACE_NAME)

        webView.evaluateJavascript(script, null)
    }

    /**
     * Set up observer for dynamic content (call after page load)
     */
    private fun setupDynamicContentObserver() {
        if (currentLanguage == null) return

        val script = """
            (function() {
                if (window._translationObserver) {
                    window._translationObserver.disconnect();
                }

                var debounceTimer = null;
                var DOM_IDLE_TIMEOUT = 400;

                window._translationObserver = new MutationObserver(function(mutations) {
                    // Debounce to avoid too frequent updates
                    if (debounceTimer) clearTimeout(debounceTimer);
                    debounceTimer = setTimeout(function() {
                        // Check if there are new untranslated text nodes
                        var hasNewTexts = false;
                        for (var i = 0; i < mutations.length; i++) {
                            var mutation = mutations[i];
                            if (mutation.addedNodes.length > 0) {
                                for (var j = 0; j < mutation.addedNodes.length; j++) {
                                    var node = mutation.addedNodes[j];
                                    // Check for text node without translation ID
                                    if (node.nodeType === Node.TEXT_NODE && !node._translationId && node.textContent.trim()) {
                                        hasNewTexts = true;
                                        break;
                                    }
                                    // Check for element with untranslated text content
                                    if (node.nodeType === Node.ELEMENT_NODE) {
                                        var walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT, null, false);
                                        var textNode;
                                        while (textNode = walker.nextNode()) {
                                            if (!textNode._translationId && textNode.textContent.trim()) {
                                                hasNewTexts = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (hasNewTexts) break;
                                }
                            }
                            if (hasNewTexts) break;
                        }

                        if (hasNewTexts && window.$JS_INTERFACE_NAME) {
                            window.$JS_INTERFACE_NAME.onNewContentDetected();
                        }
                    }, DOM_IDLE_TIMEOUT);
                });

                window._translationObserver.observe(document.body, {
                    childList: true,
                    subtree: true
                });
            })();
        """.trimIndent().replace("\$JS_INTERFACE_NAME", JS_INTERFACE_NAME)

        webView.evaluateJavascript(script, null)
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        translationJob?.cancel()
        originalTexts.clear()
    }
}
