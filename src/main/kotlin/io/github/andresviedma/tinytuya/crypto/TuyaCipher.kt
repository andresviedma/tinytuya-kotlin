package io.github.andresviedma.tinytuya.crypto

import io.github.andresviedma.tinytuya.protocol.ByteUtils.md5
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles AES encryption and decryption for Tuya protocol communication.
 * Uses BouncyCastle as the cryptographic provider.
 */
class TuyaCipher private constructor(val localKey: String, val keyBytes: ByteArray) {

    constructor(localKey: String, forceMd5: Boolean = false) : this(
        localKey = localKey,

        // Convert local key to 16-byte key
        keyBytes = if (localKey.length == 16 && !forceMd5) {
            localKey.toByteArray(Charsets.UTF_8)
        } else {
            // If key is not 16 bytes, use MD5 hash
            localKey.toByteArray(Charsets.UTF_8).md5()
        }
    )

    constructor(keyBytes: ByteArray) : this(localKey = "", keyBytes = keyBytes)

    init {
        // Ensure BouncyCastle provider is registered
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Encrypt data using AES-128-ECB with PKCS7 padding
     */
    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS7Padding", BouncyCastleProvider.PROVIDER_NAME)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return cipher.doFinal(data)
    }

    /**
     * Encrypt string data
     */
    fun encrypt(data: String): ByteArray {
        return encrypt(data.toByteArray(Charsets.UTF_8))
    }

    /**
     * Decrypt data using AES-128-ECB with PKCS7 padding
     */
    fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS7Padding", BouncyCastleProvider.PROVIDER_NAME)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
        return cipher.doFinal(data)
    }

    /**
     * Decrypt data and return as UTF-8 string
     */
    fun decryptToString(data: ByteArray): String {
        val decrypted = decrypt(data)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * Encrypt exactly one 16-byte block using AES-128-ECB with no padding.
     * Used for session key derivation (v3.4/v3.5) where the input is already block-aligned.
     */
    fun encryptRaw(data: ByteArray): ByteArray {
        require(data.size % 16 == 0) { "encryptRaw requires block-aligned input (got ${data.size} bytes)" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding", BouncyCastleProvider.PROVIDER_NAME)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return cipher.doFinal(data)
    }

    /**
     * Encrypt data using AES-128-GCM. Returns iv (12 bytes) + ciphertext + tag (16 bytes).
     * @param iv 12-byte nonce/IV
     * @param aad Optional additional authenticated data
     */
    fun encryptGcm(data: ByteArray, iv: ByteArray, aad: ByteArray? = null): ByteArray {
        require(iv.size == 12) { "GCM IV must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        val cipherAndTag = cipher.doFinal(data)
        return iv + cipherAndTag
    }

    /**
     * Decrypt data using AES-128-GCM.
     * @param iv 12-byte nonce/IV
     * @param ciphertextAndTag ciphertext concatenated with 16-byte GCM tag
     * @param aad Optional additional authenticated data
     */
    fun decryptGcm(ciphertextAndTag: ByteArray, iv: ByteArray, aad: ByteArray? = null): ByteArray {
        require(iv.size == 12) { "GCM IV must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextAndTag)
    }

    companion object {
        /**
         * Calculate the version 3.3+ suffix for device ID
         * Used in message encryption for protocol 3.3 and above
         */
        fun calculateSuffix(deviceId: String, localKey: String): ByteArray {
            val data = "data=$deviceId||lpv=3.3||${localKey}"
            return data.toByteArray(Charsets.UTF_8).md5()
        }
    }
}
