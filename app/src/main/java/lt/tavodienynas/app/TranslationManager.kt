package lt.tavodienynas.app

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages ML Kit translation models and provides translation functionality.
 * Supports Lithuanian as source language with English, Russian, Polish, Ukrainian as targets.
 * Uses SQLite cache for translated texts to improve performance.
 */
class TranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "TranslationManager"

        // Language code mapping: our codes -> ML Kit codes
        val LANGUAGE_MAP = mapOf(
            "lt" to TranslateLanguage.LITHUANIAN,
            "en" to TranslateLanguage.ENGLISH,
            "ru" to TranslateLanguage.RUSSIAN,
            "pl" to TranslateLanguage.POLISH,
            "uk" to TranslateLanguage.UKRAINIAN
        )

        // Source language is always Lithuanian
        const val SOURCE_LANGUAGE = "lt"

        private const val MODEL_CHECK_TIMEOUT_MS = 10_000L
        private const val MODEL_DOWNLOAD_TIMEOUT_MS = 120_000L
    }

    private val modelManager = RemoteModelManager.getInstance()
    private var currentTranslator: Translator? = null
    private var currentTargetLanguage: String? = null

    // Translation cache
    val cache = TranslationCache(context)

    /**
     * Check if a language model is downloaded
     */
    suspend fun isModelDownloaded(languageCode: String): Boolean {
        val mlKitCode = LANGUAGE_MAP[languageCode] ?: return false
        val model = TranslateRemoteModel.Builder(mlKitCode).build()

        return try {
            withTimeout(MODEL_CHECK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    modelManager.isModelDownloaded(model)
                        .addOnSuccessListener { isDownloaded ->
                            cont.resume(isDownloaded)
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to check model status: ${e.message}")
                            cont.resume(false)
                        }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Model check timed out for $languageCode (Google Play Services unavailable?)")
            false
        }
    }

    /**
     * Download a language model
     */
    suspend fun downloadModel(languageCode: String, onProgress: ((String) -> Unit)? = null): Boolean {
        val mlKitCode = LANGUAGE_MAP[languageCode] ?: return false
        val model = TranslateRemoteModel.Builder(mlKitCode).build()

        onProgress?.invoke("Downloading $languageCode model...")

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        return try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    modelManager.download(model, conditions)
                        .addOnSuccessListener {
                            Log.d(TAG, "Model downloaded: $languageCode")
                            onProgress?.invoke("Download complete")
                            cont.resume(true)
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to download model: ${e.message}")
                            onProgress?.invoke("Download failed: ${e.message}")
                            cont.resume(false)
                        }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Model download timed out for $languageCode")
            onProgress?.invoke("Download timed out — Google Play Services may be unavailable")
            false
        }
    }

    /**
     * Delete a language model to free space
     */
    suspend fun deleteModel(languageCode: String): Boolean {
        val mlKitCode = LANGUAGE_MAP[languageCode] ?: return false
        val model = TranslateRemoteModel.Builder(mlKitCode).build()

        return suspendCancellableCoroutine { cont ->
            modelManager.deleteDownloadedModel(model)
                .addOnSuccessListener {
                    Log.d(TAG, "Model deleted: $languageCode")
                    cont.resume(true)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to delete model: ${e.message}")
                    cont.resume(false)
                }
        }
    }

    /**
     * Get list of downloaded models
     */
    suspend fun getDownloadedModels(): List<String> {
        return suspendCancellableCoroutine { cont ->
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    val codes = models.mapNotNull { model ->
                        LANGUAGE_MAP.entries.find { it.value == model.language }?.key
                    }
                    cont.resume(codes)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get downloaded models: ${e.message}")
                    cont.resume(emptyList())
                }
        }
    }

    /**
     * Ensure required models are downloaded for translation
     */
    suspend fun ensureModelsReady(targetLanguage: String, onProgress: ((String) -> Unit)? = null): Boolean {
        // Always need Lithuanian (source) and target language
        val languagesToCheck = listOf(SOURCE_LANGUAGE, targetLanguage)

        for (lang in languagesToCheck) {
            if (!isModelDownloaded(lang)) {
                onProgress?.invoke("Downloading ${getLanguageName(lang)} model...")
                if (!downloadModel(lang, onProgress)) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Get or create a translator for the target language.
     * Returns null if the language is not supported.
     */
    private fun getTranslator(targetLanguage: String): Translator? {
        if (currentTargetLanguage == targetLanguage && currentTranslator != null) {
            return currentTranslator
        }

        // Close previous translator
        currentTranslator?.close()

        val sourceLang = LANGUAGE_MAP[SOURCE_LANGUAGE] ?: run {
            Log.e(TAG, "Source language '$SOURCE_LANGUAGE' not found in LANGUAGE_MAP")
            return null
        }
        val targetLang = LANGUAGE_MAP[targetLanguage] ?: run {
            Log.e(TAG, "Target language '$targetLanguage' not found in LANGUAGE_MAP")
            return null
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()

        currentTranslator = Translation.getClient(options)
        currentTargetLanguage = targetLanguage
        return currentTranslator
    }

    /**
     * Translate a single text string (checks cache first)
     */
    suspend fun translate(text: String, targetLanguage: String): String {
        if (text.isBlank()) return text

        // Check cache first
        cache.get(text, targetLanguage)?.let { cached ->
            return cached
        }

        // Not in cache, translate with ML Kit
        val translated = translateWithMlKit(text, targetLanguage)

        // Store in cache
        cache.put(text, targetLanguage, translated)

        return translated
    }

    /**
     * Translate using ML Kit (no cache)
     */
    private suspend fun translateWithMlKit(text: String, targetLanguage: String): String {
        val translator = getTranslator(targetLanguage) ?: run {
            Log.e(TAG, "Could not get translator for '$targetLanguage', returning original text")
            return text
        }

        return suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { translatedText ->
                    cont.resume(translatedText)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Translation failed: ${e.message}")
                    cont.resumeWithException(e)
                }
        }
    }

    /**
     * Translate multiple texts in parallel with concurrency limited to CPU count
     */
    suspend fun translateBatch(texts: List<String>, targetLanguage: String): List<String> {
        if (texts.isEmpty()) return emptyList()

        val maxConcurrency = Runtime.getRuntime().availableProcessors()
        val semaphore = Semaphore(maxConcurrency)

        return withContext(Dispatchers.IO) {
            texts.map { text ->
                async {
                    if (text.isBlank()) {
                        text
                    } else {
                        semaphore.withPermit {
                            try {
                                translate(text, targetLanguage)
                            } catch (e: Exception) {
                                Log.e(TAG, "Batch translation failed for text: ${e.message}")
                                text // Return original on failure
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Get human-readable language name
     */
    fun getLanguageName(code: String): String {
        return when (code) {
            "lt" -> "Lithuanian"
            "en" -> "English"
            "ru" -> "Russian"
            "pl" -> "Polish"
            "uk" -> "Ukrainian"
            else -> code
        }
    }

    /**
     * Clean up resources
     */
    fun close() {
        currentTranslator?.close()
        currentTranslator = null
        currentTargetLanguage = null
        cache.close()
    }
}
