package lt.tavodienynas.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * SQLite-based LRU cache for translations.
 * Uses separate database file per target language for smaller index sizes.
 * Key is hash of source text only.
 */
class TranslationCache(private val context: Context) {

    companion object {
        private const val TAG = "TranslationCache"
        private const val DATABASE_VERSION = 1

        private const val TABLE_NAME = "translations"
        private const val COL_HASH = "hash"
        private const val COL_SOURCE_TEXT = "source_text"
        private const val COL_TRANSLATED_TEXT = "translated_text"
        private const val COL_LAST_ACCESSED = "last_accessed"
        private const val COL_CREATED_AT = "created_at"

        private fun getDatabaseName(targetLang: String) = "translation_cache_$targetLang.db"
    }

    // Cache of database helpers per language
    private val dbHelpers = mutableMapOf<String, LanguageCacheHelper>()
    private val helpersLock = Any()

    private val writeMutex = Mutex()

    // Statistics for current batch
    @Volatile var batchHits = 0
        private set
    @Volatile var batchMisses = 0
        private set
    @Volatile var batchNewEntries = 0
        private set

    private fun getHelper(targetLang: String): LanguageCacheHelper {
        synchronized(helpersLock) {
            return dbHelpers.getOrPut(targetLang) {
                LanguageCacheHelper(context, getDatabaseName(targetLang)).also {
                    it.writableDatabase.enableWriteAheadLogging()
                }
            }
        }
    }

    /**
     * Reset batch statistics before starting a new page translation
     */
    fun resetBatchStats() {
        batchHits = 0
        batchMisses = 0
        batchNewEntries = 0
    }

    /**
     * Get cached translation, updates last accessed time if found
     */
    suspend fun get(sourceText: String, targetLang: String): String? {
        val hash = computeHash(sourceText)
        val helper = getHelper(targetLang)

        return withContext(Dispatchers.IO) {
            val db = helper.readableDatabase
            val cursor = db.query(
                TABLE_NAME,
                arrayOf(COL_TRANSLATED_TEXT),
                "$COL_HASH = ?",
                arrayOf(hash),
                null, null, null
            )

            cursor.use {
                if (it.moveToFirst()) {
                    val translated = it.getString(0)
                    // Update last accessed time asynchronously
                    updateLastAccessed(helper, hash)
                    batchHits++
                    translated
                } else {
                    batchMisses++
                    null
                }
            }
        }
    }

    /**
     * Store translation in cache
     */
    suspend fun put(sourceText: String, targetLang: String, translatedText: String) {
        val hash = computeHash(sourceText)
        val helper = getHelper(targetLang)
        val now = System.currentTimeMillis()

        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(COL_HASH, hash)
                    put(COL_SOURCE_TEXT, sourceText)
                    put(COL_TRANSLATED_TEXT, translatedText)
                    put(COL_LAST_ACCESSED, now)
                    put(COL_CREATED_AT, now)
                }

                val result = helper.writableDatabase.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )

                if (result != -1L) {
                    batchNewEntries++
                }
            }
        }
    }

    private suspend fun updateLastAccessed(helper: LanguageCacheHelper, hash: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(COL_LAST_ACCESSED, System.currentTimeMillis())
                }
                helper.writableDatabase.update(TABLE_NAME, values, "$COL_HASH = ?", arrayOf(hash))
            }
        }
    }

    /**
     * Remove entries not accessed within maxAgeDays from all language caches.
     * Returns combined cleanup statistics.
     */
    suspend fun cleanup(maxAgeDays: Int): CleanupStats {
        val startTime = System.currentTimeMillis()
        val cutoffTime = startTime - (maxAgeDays * 24 * 60 * 60 * 1000L)

        return writeMutex.withLock {
            withContext(Dispatchers.IO) {
                var totalEntries = 0
                var totalSize = 0L
                var totalRemoved = 0

                // Get all language cache files
                val cacheFiles = context.databaseList()
                    .filter { it.startsWith("translation_cache_") && it.endsWith(".db") }

                for (dbName in cacheFiles) {
                    val lang = dbName.removePrefix("translation_cache_").removeSuffix(".db")
                    val helper = getHelper(lang)
                    val db = helper.writableDatabase

                    // Delete old entries
                    val deletedCount = db.delete(
                        TABLE_NAME,
                        "$COL_LAST_ACCESSED < ?",
                        arrayOf(cutoffTime.toString())
                    )
                    totalRemoved += deletedCount

                    // Vacuum if we deleted something
                    if (deletedCount > 0) {
                        db.execSQL("VACUUM")
                    }

                    totalEntries += getEntryCount(db)
                    totalSize += context.getDatabasePath(dbName).length()
                }

                val duration = System.currentTimeMillis() - startTime

                CleanupStats(
                    totalEntries = totalEntries,
                    sizeBytes = totalSize,
                    removedEntries = totalRemoved,
                    durationMs = duration
                )
            }
        }
    }

    private fun getEntryCount(db: SQLiteDatabase): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Get current cache statistics across all language databases
     */
    suspend fun getStats(): CacheStats {
        return withContext(Dispatchers.IO) {
            var totalEntries = 0
            var totalSize = 0L

            val cacheFiles = context.databaseList()
                .filter { it.startsWith("translation_cache_") && it.endsWith(".db") }

            for (dbName in cacheFiles) {
                val lang = dbName.removePrefix("translation_cache_").removeSuffix(".db")
                val helper = getHelper(lang)
                totalEntries += getEntryCount(helper.readableDatabase)
                totalSize += context.getDatabasePath(dbName).length()
            }

            CacheStats(
                totalEntries = totalEntries,
                sizeBytes = totalSize
            )
        }
    }

    private fun computeHash(sourceText: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(sourceText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Get batch statistics as formatted string for logging
     */
    fun getBatchStatsLog(): String {
        val total = batchHits + batchMisses
        val hitPercent = if (total > 0) (batchHits * 100.0 / total) else 0.0
        return "Cache: %.1f%% hits (%d/%d), %d new entries".format(
            hitPercent, batchHits, total, batchNewEntries
        )
    }

    /**
     * Close all database connections
     */
    fun close() {
        synchronized(helpersLock) {
            dbHelpers.values.forEach { it.close() }
            dbHelpers.clear()
        }
    }

    /**
     * SQLiteOpenHelper for a single language cache
     */
    private class LanguageCacheHelper(
        context: Context,
        dbName: String
    ) : SQLiteOpenHelper(context, dbName, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE_NAME (
                    $COL_HASH TEXT PRIMARY KEY,
                    $COL_SOURCE_TEXT TEXT NOT NULL,
                    $COL_TRANSLATED_TEXT TEXT NOT NULL,
                    $COL_LAST_ACCESSED INTEGER NOT NULL,
                    $COL_CREATED_AT INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX idx_last_accessed ON $TABLE_NAME($COL_LAST_ACCESSED)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    data class CacheStats(
        val totalEntries: Int,
        val sizeBytes: Long
    ) {
        fun formatSize(): String {
            return when {
                sizeBytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(sizeBytes / (1024.0 * 1024 * 1024))
                sizeBytes >= 1024 * 1024 -> "%.2f MB".format(sizeBytes / (1024.0 * 1024))
                sizeBytes >= 1024 -> "%.2f KB".format(sizeBytes / 1024.0)
                else -> "$sizeBytes B"
            }
        }
    }

    data class CleanupStats(
        val totalEntries: Int,
        val sizeBytes: Long,
        val removedEntries: Int,
        val durationMs: Long
    ) {
        fun formatSize(): String {
            return when {
                sizeBytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(sizeBytes / (1024.0 * 1024 * 1024))
                sizeBytes >= 1024 * 1024 -> "%.2f MB".format(sizeBytes / (1024.0 * 1024))
                sizeBytes >= 1024 -> "%.2f KB".format(sizeBytes / 1024.0)
                else -> "$sizeBytes B"
            }
        }
    }
}
