package com.example.networktoolkit

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.util.Base64
import org.robolectric.Shadows
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.networktoolkit.data.CredentialCryptoManager
import com.example.networktoolkit.data.NetworkNoteEntity
import com.example.networktoolkit.data.NetworkToolkitDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.AlgorithmParameters
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkNotesEncryptionHotfixTest {

    @get:Rule
    val composeRule = createComposeRule()

    // =========================================================================
    // TEST A — ENCRYPTION INITIALIZATION (STRICT ANDROID KEYSTORE SEMANTICS)
    // =========================================================================

    @Test
    fun `TEST A - encryption initialization omits caller-provided IV under strict Keystore semantics`() {
        val secretKey = generateAes256Key()
        var initWithoutParamsCount = 0
        val strictKeyStoreManager = CredentialCryptoManager(
            keyProvider = object : CredentialCryptoManager.KeyProvider {
                override fun getOrCreateSecretKey(): SecretKey = secretKey
            },
            cipherFactory = { transformation ->
                StrictAndroidKeyStoreMockCipher.create(
                    delegate = Cipher.getInstance(transformation),
                    onEncryptInitWithoutParams = { initWithoutParamsCount++ }
                )
            }
        )

        val result = strictKeyStoreManager.encrypt("enable_secret=Pass#2026")
        assertTrue(
            "Encryption must succeed when AndroidKeyStore forbids caller-supplied IVs: $result",
            result is CredentialCryptoManager.EncryptResult.Success
        )
        assertEquals(1, initWithoutParamsCount)

        // Verify default CredentialCryptoManager() also initializes cleanly in test environment
        val defaultManager = CredentialCryptoManager()
        val defaultEnc = defaultManager.encrypt("admin / CoreRouter99!")
        assertTrue(defaultEnc is CredentialCryptoManager.EncryptResult.Success)
        val defaultDec = defaultManager.decrypt((defaultEnc as CredentialCryptoManager.EncryptResult.Success).ciphertext)
        assertEquals(CredentialCryptoManager.DecryptResult.Success("admin / CoreRouter99!"), defaultDec)
    }

    // =========================================================================
    // TEST B — CREDENTIAL ENCRYPTION AT REST IN ROOM
    // =========================================================================

    @Test
    fun `TEST B - credential note encrypts before Room persistence and never stores plaintext`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, NetworkToolkitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val vmStore = ViewModelStore()
        try {
            val strictKey = generateAes256Key()
            val crypto = strictKeyStoreCryptoManager(strictKey)
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkNotesViewModel(app, db.networkNoteDao(), crypto) as T
                    }
                }
            )[NetworkNotesViewModel::class.java]

            val secretText = "ssh_user=netops\nssh_pass=Tr0ub4dor&3"
            vm.saveNote(
                id = 0L,
                title = "Core Distribution Switch",
                content = secretText,
                category = "Credentials",
                ipOrHost = "10.60.104.1",
                isPinned = true
            )

            // Wait for coroutine persistence
            val notes = waitUntilNotes(vm) { list -> list.any { it.title == "Core Distribution Switch" } }
            assertNull("Successful save must clear error banner", vm.uiState.value.statusMessage)
            assertEquals(1, notes.size)

            val storedRaw = db.networkNoteDao().getAllNotesOnce().single()
            assertTrue(storedRaw.isEncrypted)
            assertTrue(storedRaw.content.startsWith(CredentialCryptoManager.PREFIX_V1))
            assertFalse(storedRaw.content.contains("netops"))
            assertFalse(storedRaw.content.contains("Tr0ub4dor"))
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    // =========================================================================
    // TEST C — CREDENTIAL DECRYPTION ON RETRIEVAL
    // =========================================================================

    @Test
    fun `TEST C - stored credential note decrypts accurately on retrieval via Room and ViewModel`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, NetworkToolkitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val vmStore = ViewModelStore()
        try {
            val strictKey = generateAes256Key()
            val crypto = strictKeyStoreCryptoManager(strictKey)
            val writerVm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkNotesViewModel(app, db.networkNoteDao(), crypto) as T
                    }
                }
            )["writer", NetworkNotesViewModel::class.java]

            writerVm.saveNote(
                id = 0L,
                title = "BGP Peer Auth",
                content = "md5=Key_8812_Prod!",
                category = "Credentials",
                ipOrHost = "198.51.100.2"
            )
            waitUntilNotes(writerVm) { it.isNotEmpty() }

            // Re-open with a fresh ViewModel using the same Keystore key
            val readerVm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkNotesViewModel(app, db.networkNoteDao(), crypto) as T
                    }
                }
            )["reader", NetworkNotesViewModel::class.java]
            val loadedNotes = waitUntilNotes(readerVm) { it.isNotEmpty() }
            assertEquals(1, loadedNotes.size)
            assertEquals("md5=Key_8812_Prod!", loadedNotes.single().content)
            assertTrue(readerVm.uiState.value.decryptionErrorNoteIds.isEmpty())
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    // =========================================================================
    // TEST D — ROUND-TRIP UTF-8 & SPECIAL CHARACTER INTEGRITY
    // =========================================================================

    @Test
    fun `TEST D - round-trip preserves multi-line UTF-8 symbols and long secrets accurately`() {
        val crypto = strictKeyStoreCryptoManager(generateAes256Key())
        val samples = listOf(
            "a",
            "user: netadmin | pass: !@#\$%^&*()_+-=[]{}|;':\",./<>?`~",
            "line1: π≈3.14 — Core Ω\nline2: パスワード🔑\nline3: backslash \\ end",
            "K".repeat(4096)
        )

        samples.forEach { plaintext ->
            val enc = crypto.encrypt(plaintext)
            assertTrue(enc is CredentialCryptoManager.EncryptResult.Success)
            val ciphertext = (enc as CredentialCryptoManager.EncryptResult.Success).ciphertext
            val dec = crypto.decrypt(ciphertext)
            assertEquals(CredentialCryptoManager.DecryptResult.Success(plaintext), dec)
        }
    }

    // =========================================================================
    // TEST E — IV / NONCE HANDLING (KEYSTORE GENERATED, 12 BYTES, NEVER REUSED)
    // =========================================================================

    @Test
    fun `TEST E - 12-byte GCM IVs are Keystore-generated, encoded in ENC v1 payload, and never reused`() {
        val crypto = strictKeyStoreCryptoManager(generateAes256Key())
        val seenIvs = mutableSetOf<String>()
        val seenCiphertexts = mutableSetOf<String>()

        repeat(25) {
            val enc = crypto.encrypt("identical-credential-payload") as CredentialCryptoManager.EncryptResult.Success
            assertTrue(seenCiphertexts.add(enc.ciphertext))

            val raw = Base64.decode(enc.ciphertext.removePrefix(CredentialCryptoManager.PREFIX_V1), Base64.NO_WRAP)
            // 12-byte IV + plaintext(28 bytes) + 16-byte GCM tag = 56 bytes
            assertEquals(12 + "identical-credential-payload".toByteArray(Charsets.UTF_8).size + 16, raw.size)

            val iv = raw.copyOfRange(0, 12)
            assertFalse("IV must never be all zeros", iv.all { it == 0.toByte() })
            assertTrue("Keystore IV must never be reused", seenIvs.add(iv.joinToString(",") { "%02x".format(it) }))

            val dec = crypto.decrypt(enc.ciphertext)
            assertEquals(CredentialCryptoManager.DecryptResult.Success("identical-credential-payload"), dec)
        }
        assertEquals(25, seenIvs.size)
    }

    // =========================================================================
    // TEST F — GCM AUTHENTICATION INTEGRITY (TAMPER & WRONG KEY REJECTION)
    // =========================================================================

    @Test
    fun `TEST F - tampering IV ciphertext or GCM tag fails authentication cleanly`() {
        val key1 = generateAes256Key()
        val key2 = generateAes256Key()
        val manager1 = strictKeyStoreCryptoManager(key1)
        val manager2 = strictKeyStoreCryptoManager(key2)

        val enc = manager1.encrypt("radius_secret=SuperSecret987!") as CredentialCryptoManager.EncryptResult.Success
        val decoded = Base64.decode(enc.ciphertext.removePrefix(CredentialCryptoManager.PREFIX_V1), Base64.NO_WRAP)

        // 1. Flip bit in IV (byte 0)
        val tamperedIv = decoded.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        val resIv = manager1.decrypt(CredentialCryptoManager.PREFIX_V1 + Base64.encodeToString(tamperedIv, Base64.NO_WRAP))
        assertTrue(resIv is CredentialCryptoManager.DecryptResult.Failure)

        // 2. Flip bit in ciphertext body (byte 14)
        val tamperedBody = decoded.copyOf().apply { this[14] = (this[14].toInt() xor 0x80).toByte() }
        val resBody = manager1.decrypt(CredentialCryptoManager.PREFIX_V1 + Base64.encodeToString(tamperedBody, Base64.NO_WRAP))
        assertTrue(resBody is CredentialCryptoManager.DecryptResult.Failure)

        // 3. Flip bit in GCM auth tag (last byte)
        val tamperedTag = decoded.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0xFF).toByte() }
        val resTag = manager1.decrypt(CredentialCryptoManager.PREFIX_V1 + Base64.encodeToString(tamperedTag, Base64.NO_WRAP))
        assertTrue(resTag is CredentialCryptoManager.DecryptResult.Failure)

        // 4. Truncated payload (< 12-byte IV + 16-byte tag)
        val truncated = decoded.copyOfRange(0, 20)
        val resTrunc = manager1.decrypt(CredentialCryptoManager.PREFIX_V1 + Base64.encodeToString(truncated, Base64.NO_WRAP))
        assertTrue(resTrunc is CredentialCryptoManager.DecryptResult.Failure)

        // 5. Wrong SecretKey
        val wrongKeyRes = manager2.decrypt(enc.ciphertext)
        assertTrue(wrongKeyRes is CredentialCryptoManager.DecryptResult.Failure)
    }

    // =========================================================================
    // TEST G — FAIL-CLOSED BEHAVIOR ON ENCRYPTION FAILURE
    // =========================================================================

    @Test
    fun `TEST G - encryption failure fails closed without saving plaintext or leaking secret in status`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, NetworkToolkitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val vmStore = ViewModelStore()
        try {
            val brokenCrypto = CredentialCryptoManager(
                keyProvider = object : CredentialCryptoManager.KeyProvider {
                    override fun getOrCreateSecretKey(): SecretKey {
                        throw InvalidAlgorithmParameterException("Simulated Keystore failure")
                    }
                }
            )
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkNotesViewModel(app, db.networkNoteDao(), brokenCrypto) as T
                    }
                }
            )[NetworkNotesViewModel::class.java]

            val secretToProtect = "DoNotEverStoreInPlaintext!99"
            vm.saveNote(
                id = 0L,
                title = "Broken Keystore Test",
                content = secretToProtect,
                category = "Credentials",
                ipOrHost = "10.0.0.5"
            )

            // Wait for statusMessage update via non-blocking Flow observation
            val status = waitUntilUiState(
                vm = vm,
                description = "encryption failure statusMessage"
            ) { it.statusMessage != null }.statusMessage

            assertNotNull("Failure status must be surfaced to user", status)
            assertTrue(status!!.contains("Credential note was not saved in plaintext"))
            assertFalse("Status error must never expose secret", status!!.contains(secretToProtect))
            assertTrue("Room table must remain empty on fail-closed", db.networkNoteDao().getAllNotesOnce().isEmpty())

            // Non-credential notes still save normally when unrelated to Keystore
            vm.saveNote(
                id = 0L,
                title = "Rack Wiring",
                content = "Patch 1 -> Switch A",
                category = "Rack / Patch",
                ipOrHost = null
            )
            val notes = waitUntilNotes(vm) { it.any { n -> n.title == "Rack Wiring" } }
            assertEquals(1, notes.size)
            assertFalse(notes.single().isEncrypted)
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    // =========================================================================
    // TEST H — EXISTING NOTE PRESERVATION ACROSS MIGRATIONS & EDITS
    // =========================================================================

    @Test
    fun `TEST H - Room v3 to v4 migration and legacy plaintext credential migration preserve all notes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as Application
        val dbFile = context.getDatabasePath("hotfix_preserve_notes_v3_v4.db")
        dbFile.delete()
        dbFile.parentFile?.mkdirs()

        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ping_results` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `targetHost` TEXT NOT NULL, `resolvedIp` TEXT NOT NULL,
                `packetsTransmitted` INTEGER NOT NULL, `packetsReceived` INTEGER NOT NULL,
                `packetLossPercentage` REAL NOT NULL, `minRttMs` REAL NOT NULL,
                `avgRttMs` REAL NOT NULL, `maxRttMs` REAL NOT NULL, `mdevRttMs` REAL NOT NULL,
                `protocolMode` TEXT NOT NULL, `durationMs` INTEGER NOT NULL,
                `logOutput` TEXT NOT NULL, `timestamp` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `network_notes` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL, `content` TEXT NOT NULL, `category` TEXT NOT NULL,
                `ipOrHost` TEXT, `isPinned` INTEGER NOT NULL,
                `createdTimestamp` INTEGER NOT NULL, `modifiedTimestamp` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `traceroute_results` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `targetHost` TEXT NOT NULL, `targetIp` TEXT NOT NULL,
                `totalHops` INTEGER NOT NULL, `isDestinationReached` INTEGER NOT NULL,
                `avgRttMs` REAL, `durationMs` INTEGER NOT NULL,
                `hopsJson` TEXT NOT NULL, `timestamp` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO `network_notes` (`id`, `title`, `content`, `category`, `ipOrHost`, `isPinned`, `createdTimestamp`, `modifiedTimestamp`)
            VALUES
              (1, 'Legacy Admin', 'admin / LegacySecret123', 'Credentials', '10.0.0.1', 1, 1000, 2000),
              (2, 'VLAN 20 Voice', 'VoIP subnet 10.20.0.0/24', 'VLAN', '10.20.0.1', 0, 1100, 2100)
            """.trimIndent()
        )
        sqlite.version = 3
        sqlite.close()

        val roomDb = Room.databaseBuilder(context, NetworkToolkitDatabase::class.java, dbFile.name)
            .addMigrations(
                NetworkToolkitDatabase.MIGRATION_1_2,
                NetworkToolkitDatabase.MIGRATION_2_3,
                NetworkToolkitDatabase.MIGRATION_3_4
            )
            .allowMainThreadQueries()
            .build()

        val vmStore = ViewModelStore()
        try {
            val crypto = strictKeyStoreCryptoManager(generateAes256Key())
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkNotesViewModel(app, roomDb.networkNoteDao(), crypto) as T
                    }
                }
            )[NetworkNotesViewModel::class.java]
            vm.migrateLegacyPlaintextCredentials()

            val rawCred = roomDb.networkNoteDao().getNoteById(1L)!!
            assertTrue(rawCred.isEncrypted)
            assertTrue(rawCred.content.startsWith(CredentialCryptoManager.PREFIX_V1))
            assertFalse(rawCred.content.contains("LegacySecret123"))

            val rawVlan = roomDb.networkNoteDao().getNoteById(2L)!!
            assertFalse(rawVlan.isEncrypted)
            assertEquals("VoIP subnet 10.20.0.0/24", rawVlan.content)

            val uiNotes = waitUntilNotes(vm) { it.size == 2 }
            assertEquals("admin / LegacySecret123", uiNotes.first { it.id == 1L }.content)
            assertEquals("VoIP subnet 10.20.0.0/24", uiNotes.first { it.id == 2L }.content)
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            roomDb.close()
            dbFile.delete()
        }
    }

    // =========================================================================
    // TEST I — CREDENTIAL REDACTION ON EXPORT
    // =========================================================================

    @Test
    fun `TEST I - text report export defaults to redacted credentials while keeping non-credential notes`() {
        val notes = listOf(
            NetworkNoteEntity(
                id = 1L,
                title = "Firewall Root",
                content = "root / TopSecretFirewallPw",
                category = "Credentials",
                ipOrHost = "10.0.0.254",
                isEncrypted = true
            ),
            NetworkNoteEntity(
                id = 2L,
                title = "IDF Closet 2",
                content = "24-port PoE switch uplink on Gi1/0/24",
                category = "Switch",
                ipOrHost = "10.0.2.10",
                isEncrypted = false
            )
        )

        val defaultRedactedReport = buildNetworkNotesTextReport(notes)
        assertFalse(defaultRedactedReport.contains("TopSecretFirewallPw"))
        assertTrue(defaultRedactedReport.contains("[CREDENTIAL CONTENT REDACTED]"))
        assertTrue(defaultRedactedReport.contains("24-port PoE switch uplink on Gi1/0/24"))

        val explicitUnredactedReport = buildNetworkNotesTextReport(notes, redactCredentials = false)
        assertTrue(explicitUnredactedReport.contains("root / TopSecretFirewallPw"))
    }

    // =========================================================================
    // TEST J — PASSWORD MASKING IN UI
    // =========================================================================

    @Test
    fun `TEST J - UI masks credential card content and existing credential editor field by default`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, NetworkToolkitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val vmStore = ViewModelStore()
        try {
            val crypto = strictKeyStoreCryptoManager(generateAes256Key())
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkNotesViewModel(app, db.networkNoteDao(), crypto) as T
                    }
                }
            )[NetworkNotesViewModel::class.java]
            val secretPass = "MaskedUiPassword#42"
            vm.saveNote(
                id = 0L,
                title = "WAN CPE Login",
                content = secretPass,
                category = "Credentials",
                ipOrHost = "192.168.100.1"
            )
            val loaded = waitUntilNotes(vm) { it.isNotEmpty() }
            val noteId = loaded.single().id

            composeRule.setContent {
                NetworkNotesScreen(
                    viewModel = vm,
                    onBackClick = {}
                )
            }
            composeRule.waitForIdle()

            // Card content must be masked by default
            composeRule.onNodeWithText("••••••••••••  (Hidden — tap eye icon to reveal)").assertIsDisplayed()
            composeRule.onNodeWithText(secretPass).assertDoesNotExist()

            // Tapping reveal eye icon shows credential content; tapping again hides it
            composeRule.onNodeWithTag("toggle_reveal_note_$noteId").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(secretPass).assertIsDisplayed()

            composeRule.onNodeWithTag("toggle_reveal_note_$noteId").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("••••••••••••  (Hidden — tap eye icon to reveal)").assertIsDisplayed()

            // Opening editor on an existing credential note applies PasswordVisualTransformation by default
            composeRule.onNodeWithTag("edit_note_button_$noteId").performClick()
            composeRule.waitForIdle()
            val passwordFieldMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
            assertTrue(
                "Editing an existing credential note must mask content input by default",
                composeRule.onAllNodes(passwordFieldMatcher).fetchSemanticsNodes().isNotEmpty()
            )
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    // =========================================================================
    // TEST K — KEYSTORE ERROR HANDLING & LOCKED CIPHERTEXT PRESERVATION
    // =========================================================================

    @Test
    fun `TEST K - Keystore exceptions sanitize error strings and preserve stored ciphertext`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, NetworkToolkitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val vmStore = ViewModelStore()
        try {
            val validCrypto = strictKeyStoreCryptoManager(generateAes256Key())
            val writerVm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkNotesViewModel(app, db.networkNoteDao(), validCrypto) as T
                    }
                }
            )["writer", NetworkNotesViewModel::class.java]
            val sensitivePayload = "enable_pw=UltraConfidential88"
            writerVm.saveNote(
                id = 0L,
                title = "Edge Router Secret",
                content = sensitivePayload,
                category = "Credentials",
                ipOrHost = "10.99.0.1"
            )
            waitUntilNotes(writerVm) { it.isNotEmpty() }
            val originalEncryptedRow = db.networkNoteDao().getAllNotesOnce().single()

            // Simulate permanently invalidated / unrecoverable Keystore key
            val unrecoverableCrypto = CredentialCryptoManager(
                keyProvider = object : CredentialCryptoManager.KeyProvider {
                    override fun getOrCreateSecretKey(): SecretKey {
                        throw UnrecoverableKeyException("Key permanently invalidated: sensitiveKeyBlob=0xDEADBEEF")
                    }
                }
            )

            // Verify encrypt failure string contains only exception class name, not plaintext or exception detail message
            val encFail = unrecoverableCrypto.encrypt(sensitivePayload) as CredentialCryptoManager.EncryptResult.Failure
            assertTrue(encFail.reason.contains("UnrecoverableKeyException"))
            assertFalse(encFail.reason.contains(sensitivePayload))
            assertFalse(encFail.reason.contains("DEADBEEF"))

            // Verify decrypt failure string is sanitized and ViewModel preserves encrypted DB row
            val decFail = unrecoverableCrypto.decrypt(originalEncryptedRow.content) as CredentialCryptoManager.DecryptResult.Failure
            assertTrue(decFail.reason.contains("UnrecoverableKeyException"))
            assertFalse(decFail.reason.contains(originalEncryptedRow.content))
            assertFalse(decFail.reason.contains("DEADBEEF"))

            val lockedVm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkNotesViewModel(app, db.networkNoteDao(), unrecoverableCrypto) as T
                    }
                }
            )["locked", NetworkNotesViewModel::class.java]
            val lockedNotes = waitUntilNotes(lockedVm) { it.isNotEmpty() }
            assertEquals(NetworkNotesViewModel.DECRYPTION_ERROR_PLACEHOLDER, lockedNotes.single().content)
            assertTrue(lockedVm.uiState.value.decryptionErrorNoteIds.contains(originalEncryptedRow.id))

            // Attempting to save placeholder must preserve original stored ciphertext in Room
            lockedVm.saveNote(
                id = originalEncryptedRow.id,
                title = originalEncryptedRow.title,
                content = NetworkNotesViewModel.DECRYPTION_ERROR_PLACEHOLDER,
                category = "Credentials",
                ipOrHost = originalEncryptedRow.ipOrHost
            )
            delay(100)
            val rowAfterAttempt = db.networkNoteDao().getNoteById(originalEncryptedRow.id)!!
            assertEquals(originalEncryptedRow.content, rowAfterAttempt.content)
            assertTrue(rowAfterAttempt.isEncrypted)

            // Redacted & unredacted report on locked note must not leak raw ciphertext
            val report = buildNetworkNotesTextReport(
                notes = lockedNotes,
                redactCredentials = false,
                decryptionErrorNoteIds = lockedVm.uiState.value.decryptionErrorNoteIds
            )
            assertTrue(report.contains("[ENCRYPTED CONTENT UNAVAILABLE]"))
            assertFalse(report.contains(originalEncryptedRow.content))
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    // =========================================================================
    // HELPERS & STRICT ANDROID KEYSTORE MOCK CIPHER
    // =========================================================================

    private fun generateAes256Key(): SecretKey {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256)
        return gen.generateKey()
    }

    private fun strictKeyStoreCryptoManager(secretKey: SecretKey): CredentialCryptoManager {
        return CredentialCryptoManager(
            keyProvider = object : CredentialCryptoManager.KeyProvider {
                override fun getOrCreateSecretKey(): SecretKey = secretKey
            },
            cipherFactory = { transformation ->
                StrictAndroidKeyStoreMockCipher.create(Cipher.getInstance(transformation))
            }
        )
    }

    private suspend fun waitUntilNotes(
        vm: NetworkNotesViewModel,
        timeoutMs: Long = 10_000L,
        predicate: (List<NetworkNoteEntity>) -> Boolean
    ): List<NetworkNoteEntity> {
        return waitUntilUiState(
            vm = vm,
            timeoutMs = timeoutMs,
            description = "NetworkNotesViewModel.uiState.notes condition"
        ) { state ->
            predicate(state.notes)
        }.notes
    }

    private suspend fun waitUntilUiState(
        vm: NetworkNotesViewModel,
        timeoutMs: Long = 10_000L,
        description: String = "NetworkNotesViewModel.uiState condition",
        predicate: (NetworkNotesUiState) -> Boolean
    ): NetworkNotesUiState {
        var latestState = vm.uiState.value
        val matched = CompletableDeferred<NetworkNotesUiState>()
        val collectorJob = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.uiState.collect { state ->
                latestState = state
                if (predicate(state) && !matched.isCompleted) {
                    matched.complete(state)
                }
            }
        }
        try {
            val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
            while (!matched.isCompleted) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (matched.isCompleted) break
                val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) {
                    fail(
                        "Timed out after ${timeoutMs}ms waiting for $description; " +
                            "last observed notes=${latestState.notes.map { it.title }}, " +
                            "statusMessage=${latestState.statusMessage}"
                    )
                }
                delay(minOf(10L, remainingMs))
            }
            return matched.await()
        } finally {
            collectorJob.cancel()
        }
    }

    /**
     * Simulates real AndroidKeyStore AES/GCM/NoPadding with setRandomizedEncryptionRequired(true):
     * throws InvalidAlgorithmParameterException("Caller-provided IV not permitted") whenever
     * Cipher.ENCRYPT_MODE is initialized with any caller-supplied AlgorithmParameterSpec or
     * AlgorithmParameters, while allowing parameterless ENCRYPT_MODE (internal random 12-byte IV)
     * and explicit GCMParameterSpec in DECRYPT_MODE.
     */
    private class StrictAndroidKeyStoreMockCipher private constructor(
        spi: CipherSpi,
        provider: Provider,
        transformation: String
    ) : Cipher(spi, provider, transformation) {

        companion object {
            private val mockProvider = object : Provider("StrictAndroidKeyStoreMock", 1.0, "Test") {}

            fun create(
                delegate: Cipher,
                onEncryptInitWithoutParams: () -> Unit = {}
            ): Cipher {
                val spi = object : CipherSpi() {
                    override fun engineSetMode(mode: String?) {}
                    override fun engineSetPadding(padding: String?) {}
                    override fun engineGetBlockSize(): Int = delegate.blockSize
                    override fun engineGetOutputSize(inputLen: Int): Int = delegate.getOutputSize(inputLen)
                    override fun engineGetIV(): ByteArray? = delegate.iv
                    override fun engineGetParameters(): AlgorithmParameters? = delegate.parameters

                    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
                        if (opmode == ENCRYPT_MODE) {
                            onEncryptInitWithoutParams()
                        }
                        delegate.init(opmode, key, random)
                    }

                    override fun engineInit(
                        opmode: Int,
                        key: Key?,
                        params: AlgorithmParameterSpec?,
                        random: SecureRandom?
                    ) {
                        if (opmode == ENCRYPT_MODE && params != null) {
                            throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
                        }
                        delegate.init(opmode, key, params, random)
                    }

                    override fun engineInit(
                        opmode: Int,
                        key: Key?,
                        params: AlgorithmParameters?,
                        random: SecureRandom?
                    ) {
                        if (opmode == ENCRYPT_MODE && params != null) {
                            throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
                        }
                        delegate.init(opmode, key, params, random)
                    }

                    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray {
                        return delegate.update(input, inputOffset, inputLen) ?: ByteArray(0)
                    }

                    override fun engineUpdate(
                        input: ByteArray?,
                        inputOffset: Int,
                        inputLen: Int,
                        output: ByteArray?,
                        outputOffset: Int
                    ): Int {
                        return delegate.update(input, inputOffset, inputLen, output, outputOffset)
                    }

                    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray {
                        return delegate.doFinal(input, inputOffset, inputLen)
                    }

                    override fun engineDoFinal(
                        input: ByteArray?,
                        inputOffset: Int,
                        inputLen: Int,
                        output: ByteArray?,
                        outputOffset: Int
                    ): Int {
                        return delegate.doFinal(input, inputOffset, inputLen, output, outputOffset)
                    }
                }
                return StrictAndroidKeyStoreMockCipher(spi, mockProvider, "AES/GCM/NoPadding")
            }
        }
    }
}
