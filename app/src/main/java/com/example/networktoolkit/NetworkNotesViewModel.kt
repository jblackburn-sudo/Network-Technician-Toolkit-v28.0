package com.example.networktoolkit

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.networktoolkit.data.CredentialCryptoManager
import com.example.networktoolkit.data.NetworkNoteDao
import com.example.networktoolkit.data.NetworkNoteEntity
import com.example.networktoolkit.data.NetworkToolkitDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NetworkNotesUiState(
    val notes: List<NetworkNoteEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedCategory: String = "All",
    val availableCategories: List<String> = listOf("All", "Router / Gateway", "Switch", "VLAN", "Credentials", "Rack / Patch", "General"),
    val decryptionErrorNoteIds: Set<Long> = emptySet(),
    val statusMessage: String? = null
)

class NetworkNotesViewModel @VisibleForTesting internal constructor(
    application: Application,
    private val noteDao: NetworkNoteDao,
    private val cryptoManager: CredentialCryptoManager
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        noteDao = NetworkToolkitDatabase.getInstance(application).networkNoteDao(),
        cryptoManager = CredentialCryptoManager()
    )

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategory = MutableStateFlow("All")
    private val _statusMessage = MutableStateFlow<String?>(null)

    private val decryptedNotesFlow = noteDao.getAllNotesFlow()
        .map { rawNotes ->
            val failedIds = linkedSetOf<Long>()
            val projected = rawNotes.map { note ->
                if (note.isEncrypted || cryptoManager.isEncryptedPayload(note.content)) {
                    when (val result = cryptoManager.decrypt(note.content)) {
                        is CredentialCryptoManager.DecryptResult.Success ->
                            note.copy(content = result.plaintext, isEncrypted = true)
                        is CredentialCryptoManager.DecryptResult.NotEncrypted ->
                            note
                        is CredentialCryptoManager.DecryptResult.Failure -> {
                            failedIds.add(note.id)
                            note.copy(content = DECRYPTION_ERROR_PLACEHOLDER, isEncrypted = true)
                        }
                    }
                } else {
                    note
                }
            }
            projected to failedIds.toSet()
        }
        .flowOn(Dispatchers.IO)

    val uiState: StateFlow<NetworkNotesUiState> = combine(
        decryptedNotesFlow,
        _searchQuery,
        _selectedCategory,
        _statusMessage
    ) { (allNotes, failedIds), query, category, statusMsg ->
        val filtered = allNotes.filter { note ->
            val matchesCategory = category == "All" || note.category.equals(category, ignoreCase = true)
            val isFailedDecrypt = note.id in failedIds
            val matchesQuery = query.isBlank() ||
                note.title.contains(query, ignoreCase = true) ||
                (!isFailedDecrypt && note.content.contains(query, ignoreCase = true)) ||
                (note.ipOrHost?.contains(query, ignoreCase = true) == true)
            matchesCategory && matchesQuery
        }
        NetworkNotesUiState(
            notes = filtered,
            searchQuery = query,
            selectedCategory = category,
            decryptionErrorNoteIds = failedIds,
            statusMessage = statusMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NetworkNotesUiState()
    )

    init {
        viewModelScope.launch {
            migrateLegacyPlaintextCredentials()
        }
    }

    suspend fun migrateLegacyPlaintextCredentials() = withContext(Dispatchers.IO) {
        try {
            val allNotes = noteDao.getAllNotesOnce()
            for (note in allNotes) {
                if (shouldEncryptCategory(note.category)) {
                    if (cryptoManager.isEncryptedPayload(note.content)) {
                        if (!note.isEncrypted) {
                            noteDao.updateNote(note.copy(isEncrypted = true))
                        }
                    } else if (!note.isEncrypted) {
                        when (val enc = cryptoManager.encrypt(note.content)) {
                            is CredentialCryptoManager.EncryptResult.Success -> {
                                noteDao.updateNote(
                                    note.copy(
                                        content = enc.ciphertext,
                                        isEncrypted = true
                                    )
                                )
                            }
                            is CredentialCryptoManager.EncryptResult.Failure -> {
                                // Fail closed without modifying or deleting existing note content
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Preserve existing notes safely if storage read is temporarily unavailable
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onCategorySelected(category: String) {
        _selectedCategory.value = category
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun saveNote(
        id: Long = 0,
        title: String,
        content: String,
        category: String,
        ipOrHost: String?,
        isPinned: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedTitle = title.trim()
            val trimmedContent = content.trim()
            if (trimmedTitle.isBlank() || trimmedContent.isBlank()) return@launch

            if (trimmedContent == DECRYPTION_ERROR_PLACEHOLDER && id != 0L) {
                _statusMessage.value = "Encrypted note could not be unlocked; original stored ciphertext was preserved."
                return@launch
            }

            val existingCreated = if (id != 0L) {
                noteDao.getNoteById(id)?.createdTimestamp ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            }

            val encryptRequired = shouldEncryptCategory(category)
            val persistedContent: String
            val persistedEncrypted: Boolean

            if (encryptRequired) {
                when (val enc = cryptoManager.encrypt(trimmedContent)) {
                    is CredentialCryptoManager.EncryptResult.Success -> {
                        persistedContent = enc.ciphertext
                        persistedEncrypted = true
                    }
                    is CredentialCryptoManager.EncryptResult.Failure -> {
                        _statusMessage.value = "Encryption failed (${enc.reason}). Credential note was not saved in plaintext."
                        return@launch
                    }
                }
            } else {
                persistedContent = trimmedContent
                persistedEncrypted = false
            }

            val note = NetworkNoteEntity(
                id = id,
                title = trimmedTitle,
                content = persistedContent,
                category = category,
                ipOrHost = ipOrHost?.trim()?.takeIf { it.isNotBlank() },
                isPinned = isPinned,
                createdTimestamp = existingCreated,
                modifiedTimestamp = System.currentTimeMillis(),
                isEncrypted = persistedEncrypted
            )
            if (id == 0L) {
                noteDao.insertNote(note)
            } else {
                noteDao.updateNote(note)
            }
            _statusMessage.value = null
        }
    }

    fun togglePin(note: NetworkNoteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            noteDao.updatePinStatus(
                id = note.id,
                isPinned = !note.isPinned,
                modifiedTimestamp = System.currentTimeMillis()
            )
        }
    }

    fun deleteNote(note: NetworkNoteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            noteDao.deleteNote(note)
        }
    }

    companion object {
        const val CATEGORY_CREDENTIALS = "Credentials"
        const val DECRYPTION_ERROR_PLACEHOLDER =
            "[Encrypted content unavailable — device Keystore key could not decrypt this note]"

        fun shouldEncryptCategory(category: String): Boolean {
            return category.trim().equals(CATEGORY_CREDENTIALS, ignoreCase = true)
        }
    }
}
