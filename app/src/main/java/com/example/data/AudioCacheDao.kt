package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioCacheDao {
    @Query("SELECT * FROM audio_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun getEntryByKey(key: String): CachedAudioEntry?

    @Query("SELECT * FROM audio_cache ORDER BY lastAccessedAt DESC")
    fun getAllEntries(): Flow<List<CachedAudioEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: CachedAudioEntry)

    @Query("UPDATE audio_cache SET lastAccessedAt = :time WHERE cacheKey = :key")
    suspend fun updateAccessTime(key: String, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM audio_cache WHERE cacheKey = :key")
    suspend fun deleteEntry(key: String)

    @Query("DELETE FROM audio_cache")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM audio_cache")
    suspend fun getCount(): Int
}
