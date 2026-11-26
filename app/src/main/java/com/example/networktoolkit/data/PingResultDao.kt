package com.example.networktoolkit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for ping test results.
 */
@Dao
interface PingResultDao {
    @Query("SELECT * FROM ping_results ORDER BY timestamp DESC")
    fun getAllResults(): Flow<List<PingResultEntity>>

    @Query("SELECT * FROM ping_results ORDER BY timestamp DESC")
    suspend fun getAllResultsList(): List<PingResultEntity>

    @Query("SELECT * FROM ping_results WHERE targetHost LIKE '%' || :query || '%' OR resolvedIp LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchResults(query: String): Flow<List<PingResultEntity>>

    @Query("SELECT * FROM ping_results WHERE targetHost LIKE '%' || :query || '%' OR resolvedIp LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchResultsList(query: String): List<PingResultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResult(result: PingResultEntity): Long

    @Query("DELETE FROM ping_results WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM ping_results")
    suspend fun clearAll()
}
