package io.github.andresviedma.tinytuya.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TuyaCipherTest {

    @Test
    fun testEncryptDecrypt() {
        val localKey = "0123456789abcdef"
        val cipher = TuyaCipher(localKey)

        val plaintext = "Hello, Tuya!"
        val encrypted = cipher.encrypt(plaintext)
        val decrypted = cipher.decryptToString(encrypted)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testEncryptDecryptBytes() {
        val localKey = "0123456789abcdef"
        val cipher = TuyaCipher(localKey)

        val plaintext = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val encrypted = cipher.encrypt(plaintext)
        val decrypted = cipher.decrypt(encrypted)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun testJsonEncryptDecrypt() {
        val localKey = "0123456789abcdef"
        val cipher = TuyaCipher(localKey)

        val json = """{"dps":{"1":true,"2":100}}"""
        val encrypted = cipher.encrypt(json)
        val decrypted = cipher.decryptToString(encrypted)

        assertEquals(json, decrypted)
    }

    @Test
    fun testLongKeyHashedToMD5() {
        val longKey = "thiskeyshouldbehashedtomd5"
        val cipher = TuyaCipher(longKey)

        val plaintext = "Test"
        val encrypted = cipher.encrypt(plaintext)
        val decrypted = cipher.decryptToString(encrypted)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testCalculateSuffix() {
        val deviceId = "12345678901234567890"
        val localKey = "0123456789abcdef"

        val suffix = TuyaCipher.calculateSuffix(deviceId, localKey)

        // Suffix should be 16 bytes (MD5 hash)
        assertEquals(16, suffix.size)
    }

    @Test
    fun testEmptyStringEncryption() {
        val localKey = "0123456789abcdef"
        val cipher = TuyaCipher(localKey)

        val plaintext = ""
        val encrypted = cipher.encrypt(plaintext)
        val decrypted = cipher.decryptToString(encrypted)

        assertEquals(plaintext, decrypted)
    }
}
