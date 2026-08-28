package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class AudioCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioCacheManager"
        private const val CACHE_DIR_NAME = "vocal_audio_cache"
    }

    private val db = AppDatabase.getInstance(context)
    private val dao = db.audioCacheDao()

    private val cacheDirectory: File
        get() = File(context.filesDir, CACHE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    /**
     * Generates a deterministic SHA-256 fingerprint for a video Uri
     * based on its URI, size, and display name.
     */
    fun computeCacheKey(uri: Uri): String {
        var fileSize = 0L
        var displayName = uri.lastPathSegment ?: "unknown"

        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst()) {
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                        if (nameIndex != -1) displayName = cursor.getString(nameIndex) ?: displayName
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading content metadata for key", e)
            }
        } else if (uri.scheme == "file" || uri.path != null) {
            try {
                val f = File(uri.path ?: "")
                if (f.exists()) {
                    fileSize = f.length()
                    displayName = f.name
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading file metadata for key", e)
            }
        }

        val rawSignature = "${uri.toString()}_${displayName}_$fileSize"
        return sha256(rawSignature)
    }

    /**
     * Checks if a valid cached separation exists for the given video Uri.
     */
    suspend fun getCachedEntry(uri: Uri): CachedAudioEntry? = withContext(Dispatchers.IO) {
        try {
            val key = computeCacheKey(uri)
            val entry = dao.getEntryByKey(key) ?: return@withContext null

            val vocalFile = File(entry.vocalFilePath)
            val accompanimentFile = File(entry.accompanimentFilePath)

            if (vocalFile.exists() && vocalFile.length() > 44 &&
                accompanimentFile.exists() && accompanimentFile.length() > 44
            ) {
                // Update access timestamp
                dao.updateAccessTime(key, System.currentTimeMillis())
                Log.d(TAG, "Cache HIT for video: ${entry.videoTitle} (Key: $key)")
                return@withContext entry
            } else {
                Log.w(TAG, "Cache file missing on disk, cleaning up key: $key")
                dao.deleteEntry(key)
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking cache: ${e.message}", e)
            null
        }
    }

    /**
     * Persists separated audio files to the local persistent cache and records metadata in Room.
     */
    suspend fun saveToCache(
        uri: Uri,
        videoTitle: String,
        vocalSourceFile: File,
        accompanimentSourceFile: File,
        rawWavSourceFile: File? = null,
        durationMs: Long = 0L
    ): CachedAudioEntry? = withContext(Dispatchers.IO) {
        try {
            val key = computeCacheKey(uri)
            val dir = cacheDirectory

            val targetVocal = File(dir, "${key}_vocal.wav")
            val targetAccompaniment = File(dir, "${key}_accompaniment.wav")
            val targetRaw = if (rawWavSourceFile != null && rawWavSourceFile.exists()) {
                File(dir, "${key}_raw.wav")
            } else null

            // Copy to persistent storage
            vocalSourceFile.copyTo(targetVocal, overwrite = true)
            accompanimentSourceFile.copyTo(targetAccompaniment, overwrite = true)
            targetRaw?.let { rawWavSourceFile?.copyTo(it, overwrite = true) }

            val totalSize = targetVocal.length() + targetAccompaniment.length() + (targetRaw?.length() ?: 0L)

            val entry = CachedAudioEntry(
                cacheKey = key,
                videoUri = uri.toString(),
                videoTitle = videoTitle,
                vocalFilePath = targetVocal.absolutePath,
                accompanimentFilePath = targetAccompaniment.absolutePath,
                rawWavFilePath = targetRaw?.absolutePath,
                durationMs = durationMs,
                fileSize = totalSize,
                createdAt = System.currentTimeMillis(),
                lastAccessedAt = System.currentTimeMillis()
            )

            dao.insertEntry(entry)
            Log.d(TAG, "Saved audio separation to local cache: ${entry.videoTitle} ($totalSize bytes)")
            entry
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audio to cache: ${e.message}", e)
            null
        }
    }

    /**
     * Clears all cached audio files and resets the database.
     */
    suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.clearAll()
            cacheDirectory.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Cleared all audio cache files")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache: ${e.message}", e)
            false
        }
    }

    /**
     * Calculates total bytes used by cached audio.
     */
    suspend fun getTotalCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        try {
            var total = 0L
            cacheDirectory.listFiles()?.forEach { file ->
                if (file.isFile) total += file.length()
            }
            total
        } catch (e: Exception) {
            0L
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
