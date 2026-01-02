package io.github.andresviedma.tinytuya.crypto

import io.github.andresviedma.tinytuya.protocol.ByteUtils.md5
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Handles AES encryption and decryption for Tuya protocol communication.
 * Uses BouncyCastle as the cryptographic provider.
 */
class TuyaCipher(val localKey: String, forceMd5: Boolean = false) {
    private val keyBytes: ByteArray

    init {
        // Ensure BouncyCastle provider is registered
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        // Convert local key to 16-byte key
        keyBytes = if (localKey.length == 16 && !forceMd5) {
            localKey.toByteArray(Charsets.UTF_8)
        } else {
            // If key is not 16 bytes, use MD5 hash
            localKey.toByteArray(Charsets.UTF_8).md5()
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
