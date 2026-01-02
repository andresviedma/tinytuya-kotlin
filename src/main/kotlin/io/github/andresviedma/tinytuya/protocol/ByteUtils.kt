package io.github.andresviedma.tinytuya.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


/**
 * Utility functions for byte manipulation in the Tuya protocol
 */
object ByteUtils {
    /**
     * Convert an Int to a 4-byte array (big-endian)
     */
    fun Int.toBytesBE(): ByteArray {
        return ByteBuffer.allocate(4).putInt(this).array()
    }

    /**
     * Convert a 4-byte array to an Int (big-endian)
     */
    fun ByteArray.toIntBE(offset: Int = 0): Int {
        require(size >= offset + 4) { "Array too small to read Int at offset $offset" }
        return ByteBuffer.wrap(this, offset, 4).int
    }

    /**
     * Calculate MD5 hash of a byte array
     */
    fun ByteArray.md5(): ByteArray {
        return MessageDigest.getInstance("MD5").digest(this)
    }

    /**
     * Calculate CRC32 checksum of a byte array
     */
    fun ByteArray.crc32(): Long {
        val crc = CRC32()
        crc.update(this)
        return crc.value
    }

    /**
     * Calculate CRC32 checksum and return as a 4-byte array
     */
    fun ByteArray.crc32Bytes(): ByteArray {
        val crcValue = crc32()
        return ByteBuffer.allocate(4).putInt(crcValue.toInt()).array()
    }

    /**
     * Convert a hex string to a byte array
     */
    fun String.hexToBytes(): ByteArray {
        val cleaned = this.replace(" ", "").replace(":", "")
        require(cleaned.length % 2 == 0) { "Hex string must have even length" }
        return cleaned.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    /**
     * Convert a byte array to a hex string
     */
    fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    /**
     * Concatenate multiple byte arrays
     */
    fun concatByteArrays(vararg arrays: ByteArray): ByteArray {
        val totalSize = arrays.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (array in arrays) {
            array.copyInto(result, offset)
            offset += array.size
        }
        return result
    }

    /**
     * XOR two byte arrays
     */
    fun ByteArray.xor(other: ByteArray): ByteArray {
        require(this.size == other.size) { "Arrays must be same size for XOR" }
        return ByteArray(size) { i -> (this[i].toInt() xor other[i].toInt()).toByte() }
    }

    /**
     * Pad a string to a multiple of blockSize with PKCS7 padding
     */
    fun String.padPKCS7(blockSize: Int): ByteArray {
        val bytes = this.toByteArray(Charsets.UTF_8)
        return bytes.padPKCS7(blockSize)
    }

    /**
     * Pad a byte array to a multiple of blockSize with PKCS7 padding
     */
    fun ByteArray.padPKCS7(blockSize: Int): ByteArray {
        val paddingSize = blockSize - (size % blockSize)
        val padded = ByteArray(size + paddingSize)
        this.copyInto(padded)
        for (i in size until padded.size) {
            padded[i] = paddingSize.toByte()
        }
        return padded
    }

    /**
     * Remove PKCS7 padding from a byte array
     */
    fun ByteArray.unpadPKCS7(): ByteArray {
        if (isEmpty()) return this
        val paddingSize = this[size - 1].toInt()
        if (paddingSize < 1 || paddingSize > 16) {
            // Invalid padding, return as-is
            return this
        }
        return copyOfRange(0, size - paddingSize)
    }

    fun ByteArray.macSha256(key: String): ByteArray {
        val algorithm = "HmacSHA256"
        val secretKeySpec = SecretKeySpec(key.toByteArray(), algorithm)
        val mac: Mac = Mac.getInstance(algorithm)
        mac.init(secretKeySpec)
        return mac.doFinal(this)
    }
}
