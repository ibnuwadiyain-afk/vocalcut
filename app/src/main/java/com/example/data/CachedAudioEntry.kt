package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_cache")
data class CachedAudioEntry(
    @PrimaryKey val cacheKey: String,
    val videoUri: String,
    val videoTitle: String,
    val vocalFilePath: String,
    val accompanimentFilePath: String,
    val rawWavFilePath: String? = null,
    val durationMs: Long = 0L,
    val fileSize: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis()
)
