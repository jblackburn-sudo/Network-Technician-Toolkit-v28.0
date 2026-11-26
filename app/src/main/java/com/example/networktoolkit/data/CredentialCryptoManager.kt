package com.example.networktoolkit.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides hardware-backed Android Keystore AES-256-GCM authenticated encryption for
 * sensitive credential notes persisted in Room.
 *
 * Ciphertext format: "ENC:v1:<base64(12-byte IV || ciphertext + 128-bit GCM tag)>"
 */
class CredentialCryptoManager @VisibleForTesting internal constructor(
    private val keyProvider: KeyProvider,
    private val cipherFactory: (String) -> Cipher
) {
    @VisibleForTesting
    internal constructor(keyProvider: KeyProvider) : this(keyProvider, { Cipher.getInstance(it) })

    constructor() : this(DefaultKeyProvider)

    interface KeyProvider {
        fun getOrCreateSecretKey(): SecretKey
    }

    sealed class EncryptResult {
        data class Success(val ciphertext: String) : EncryptResult()
        data class Failure(val reason: String) : EncryptResult()
    }

    sealed class DecryptResult {
        data class Success(val plaintext: String) : DecryptResult()
        data class NotEncrypted(val plaintext: String) : DecryptResult()
        data class Failure(val reason: String) : DecryptResult()
    }

    fun isEncryptedPayload(value: String): Boolean {
        return value.startsWith(PREFIX_V1)
    }

    fun encrypt(plaintext: String): EncryptResult {
        if (isEncryptedPayload(plaintext)) {
            return EncryptResult.Success(plaintext)
        }
        return try {
            val secretKey = keyProvider.getOrCreateSecretKey()
            val cipher = cipherFactory(TRANSFORMATION)
            // AndroidKeyStore keys configured with setRandomizedEncryptionRequired(true)
            // reject caller-supplied GCMParameterSpec/IVs during ENCRYPT_MODE and generate
            // the 12-byte random IV inside hardware/Keystore.
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            if (iv == null || iv.size != GCM_IV_LENGTH_BYTES) {
                return EncryptResult.Failure("Keystore encryption failed (InvalidIvLength)")
            }

            val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
            val encryptedBytes = cipher.doFinal(plainBytes)
            val tagLengthBytes = encryptedBytes.size - plainBytes.size
            if (tagLengthBytes != GCM_TAG_LENGTH_BYTES) {
                return EncryptResult.Failure("Keystore encryption failed (InvalidTagLength)")
            }

            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
            EncryptResult.Success("$PREFIX_V1$encoded")
        } catch (t: Throwable) {
            EncryptResult.Failure("Keystore encryption failed (${t::class.java.simpleName})")
        }
    }

    fun decrypt(storedValue: String): DecryptResult {
        if (!isEncryptedPayload(storedValue)) {
            return DecryptResult.NotEncrypted(storedValue)
        }
        return try {
            val payloadBase64 = storedValue.removePrefix(PREFIX_V1)
            val combined = Base64.decode(payloadBase64, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
                return DecryptResult.Failure("Corrupted encrypted payload length")
            }

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)

            val secretKey = keyProvider.getOrCreateSecretKey()
            val cipher = cipherFactory(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val decryptedBytes = cipher.doFinal(cipherBytes)
            DecryptResult.Success(String(decryptedBytes, Charsets.UTF_8))
        } catch (t: Throwable) {
            DecryptResult.Failure("Unable to decrypt credential note (${t::class.java.simpleName})")
        }
    }

    companion object {
        const val PREFIX_V1 = "ENC:v1:"
        private const val KEY_ALIAS = "network_toolkit_notes_cred_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8

        private val secureRandom = SecureRandom()

        private object DefaultKeyProvider : KeyProvider {
            @Volatile
            private var robolectricFallbackKey: SecretKey? = null

            override fun getOrCreateSecretKey(): SecretKey {
                return try {
                    getOrCreateAndroidKeyStoreKey()
                } catch (e: Throwable) {
                    if (isRobolectricEnvironment()) {
                        getOrCreateRobolectricFallbackKey()
                    } else {
                        throw e
                    }
                }
            }

            @Synchronized
            private fun getOrCreateAndroidKeyStoreKey(): SecretKey {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                keyStore.load(null)
                val existingKey = (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
                    ?: (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                if (existingKey != null) {
                    return existingKey
                }

                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(spec)
                return keyGenerator.generateKey()
            }

            private fun isRobolectricEnvironment(): Boolean {
                return Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
                    Build.HARDWARE.equals("robolectric", ignoreCase = true)
            }

            @Synchronized
            private fun getOrCreateRobolectricFallbackKey(): SecretKey {
                robolectricFallbackKey?.let { return it }
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256, secureRandom)
                val generated = keyGen.generateKey()
                robolectricFallbackKey = generated
                return generated
            }
        }
    }
}
