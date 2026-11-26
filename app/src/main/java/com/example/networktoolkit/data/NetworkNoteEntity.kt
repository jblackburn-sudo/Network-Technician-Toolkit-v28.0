package com.example.networktoolkit.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a field technician's network note.
 * Stores configuration details, device IP/hosts, site observations, and credentials.
 */
@Entity(tableName = "network_notes")
data class NetworkNoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val category: String = "General",
    val ipOrHost: String? = null,
    val isPinned: Boolean = false,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val modifiedTimestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val isEncrypted: Boolean = false
)
