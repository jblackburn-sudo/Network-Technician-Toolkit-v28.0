package com.example.networktoolkit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for traceroute test session records.
 */
@Dao
interface TracerouteResultDao {

    @Query("SELECT * FROM traceroute_results ORDER BY timestamp DESC")
    fun getAllResults(): Flow<List<TracerouteResultEntity>>

    @Query("SELECT * FROM traceroute_results ORDER BY timestamp DESC")
    suspend fun getAllResultsList(): List<TracerouteResultEntity>

    @Query("SELECT * FROM traceroute_results WHERE targetHost LIKE '%' || :query || '%' OR targetIp LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchResults(query: String): Flow<List<TracerouteResultEntity>>

    @Query("SELECT * FROM traceroute_results WHERE targetHost LIKE '%' || :query || '%' OR targetIp LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchResultsList(query: String): List<TracerouteResultEntity>

    @Query("SELECT * FROM traceroute_results WHERE id = :id")
    suspend fun getResultById(id: Long): TracerouteResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResult(result: TracerouteResultEntity): Long

    @Query("DELETE FROM traceroute_results WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM traceroute_results")
    suspend fun clearAll()
}
