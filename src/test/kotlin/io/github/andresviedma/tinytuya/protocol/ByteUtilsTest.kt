package io.github.andresviedma.tinytuya.protocol

import io.github.andresviedma.tinytuya.protocol.ByteUtils.crc32
import io.github.andresviedma.tinytuya.protocol.ByteUtils.hexToBytes
import io.github.andresviedma.tinytuya.protocol.ByteUtils.md5
import io.github.andresviedma.tinytuya.protocol.ByteUtils.padPKCS7
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toBytesBE
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toHexString
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toIntBE
import io.github.andresviedma.tinytuya.protocol.ByteUtils.unpadPKCS7
import io.github.andresviedma.tinytuya.protocol.ByteUtils.xor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ByteUtilsTest {

    @Test
    fun testIntToBytesBE() {
        val result = 0x12345678.toBytesBE()
        assertContentEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), result)
    }

    @Test
    fun testBytesToIntBE() {
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val result = bytes.toIntBE()
        assertEquals(0x12345678, result)
    }

    @Test
    fun testHexToBytes() {
        val hex = "48656c6c6f"
        val result = hex.hexToBytes()
        assertContentEquals("Hello".toByteArray(), result)
    }

    @Test
    fun testBytesToHex() {
        val bytes = "Hello".toByteArray()
        val result = bytes.toHexString()
        assertEquals("48656c6c6f", result)
    }

    @Test
    fun testMd5() {
        val input = "test".toByteArray()
        val result = input.md5()
        // MD5 of "test" is "098f6bcd4621d373cade4e832627b4f6"
        assertEquals("098f6bcd4621d373cade4e832627b4f6", result.toHexString())
    }

    @Test
    fun testCrc32() {
        val input = "test".toByteArray()
        val result = input.crc32()
        // CRC32 of "test" is 0xD87F7E0C
        assertEquals(0xD87F7E0CL, result)
    }

    @Test
    fun testXor() {
        val array1 = byteArrayOf(0x01, 0x02, 0x03)
        val array2 = byteArrayOf(0x04, 0x05, 0x06)
        val result = array1.xor(array2)
        assertContentEquals(byteArrayOf(0x05, 0x07, 0x05), result)
    }

    @Test
    fun testPadPKCS7() {
        val input = "Hello".toByteArray()
        val padded = input.padPKCS7(16)
        assertEquals(16, padded.size)
        // Last 11 bytes should be 0x0B (11 in decimal)
        for (i in 5 until 16) {
            assertEquals(0x0B.toByte(), padded[i])
        }
    }

    @Test
    fun testUnpadPKCS7() {
        val padded = byteArrayOf(
            0x48, 0x65, 0x6c, 0x6c, 0x6f,  // "Hello"
            0x0B, 0x0B, 0x0B, 0x0B, 0x0B, 0x0B, 0x0B, 0x0B, 0x0B, 0x0B, 0x0B  // 11 bytes of padding
        )
        val unpadded = padded.unpadPKCS7()
        assertContentEquals("Hello".toByteArray(), unpadded)
    }

    @Test
    fun testRoundTripPadding() {
        val original = "Test data for padding".toByteArray()
        val padded = original.padPKCS7(16)
        val unpadded = padded.unpadPKCS7()
        assertContentEquals(original, unpadded)
    }
}
