package com.example.networktoolkit.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkNoteDao {

    @Query("SELECT * FROM network_notes ORDER BY isPinned DESC, modifiedTimestamp DESC")
    fun getAllNotesFlow(): Flow<List<NetworkNoteEntity>>

    @Query("SELECT * FROM network_notes")
    suspend fun getAllNotesOnce(): List<NetworkNoteEntity>

    @Query("SELECT * FROM network_notes WHERE id = :id")
    suspend fun getNoteById(id: Long): NetworkNoteEntity?

    @Query("SELECT * FROM network_notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR ipOrHost LIKE '%' || :query || '%' ORDER BY isPinned DESC, modifiedTimestamp DESC")
    fun searchNotesFlow(query: String): Flow<List<NetworkNoteEntity>>

    @Query("SELECT * FROM network_notes WHERE category = :category ORDER BY isPinned DESC, modifiedTimestamp DESC")
    fun getNotesByCategoryFlow(category: String): Flow<List<NetworkNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NetworkNoteEntity): Long

    @Update
    suspend fun updateNote(note: NetworkNoteEntity)

    @Query("UPDATE network_notes SET isPinned = :isPinned, modifiedTimestamp = :modifiedTimestamp WHERE id = :id")
    suspend fun updatePinStatus(id: Long, isPinned: Boolean, modifiedTimestamp: Long)

    @Delete
    suspend fun deleteNote(note: NetworkNoteEntity)

    @Query("DELETE FROM network_notes WHERE id = :id")
    suspend fun deleteNoteById(id: Long)
}
