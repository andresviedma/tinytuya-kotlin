package io.github.andresviedma.tinytuya.protocol

import io.github.andresviedma.tinytuya.crypto.TuyaCipher
import io.github.andresviedma.tinytuya.protocol.ByteUtils.hexToBytes
import io.github.andresviedma.tinytuya.protocol.ByteUtils.macSha256
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toHexString
import io.github.andresviedma.tinytuya.protocol.ByteUtils.xor
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Protocol compliance tests verifying the Kotlin port matches the Python reference implementation.
 * Test vectors are cross-verified against tinytuya (Python).
 */
class TuyaProtocolComplianceTest {

    // -----------------------------------------------------------------
    // Stage 1 — Command codes
    // -----------------------------------------------------------------

    @Test
    fun testSessKeyNegRespCode() {
        // Python: SESS_KEY_NEG_RESP = 4
        assertEquals(0x04, TuyaCommand.SESS_KEY_NEG_RESP.code)
    }

    @Test
    fun testSessKeyNegFinishCode() {
        // Python: SESS_KEY_NEG_FINISH = 5
        assertEquals(0x05, TuyaCommand.SESS_KEY_NEG_FINISH.code)
    }

    @Test
    fun testSessKeyNegStartCode() {
        assertEquals(0x03, TuyaCommand.SESS_KEY_NEG_START.code)
    }

    @Test
    fun testFromCodeRespAndFinishAreDistinct() {
        val resp = TuyaCommand.fromCode(0x04)
        val finish = TuyaCommand.fromCode(0x05)
        assertEquals(TuyaCommand.SESS_KEY_NEG_RESP, resp)
        assertEquals(TuyaCommand.SESS_KEY_NEG_FINISH, finish)
        assert(resp != finish)
    }

    // -----------------------------------------------------------------
    // Stage 2 — HMAC / crypto primitives
    // -----------------------------------------------------------------

    @Test
    fun testMacSha256WithBinaryKey() {
        // Python: hmac.new(binary_key, b"hello", sha256).digest()
        // binary_key is a raw 16-byte session key (not a UTF-8 string)
        val binaryKey = "74a78f53509872490e78048a8c6c45f2".hexToBytes()
        val result = "hello".toByteArray().macSha256(binaryKey)
        assertEquals("d559581465d265bd8ce7391e547fc91b3d7d9a9cd1b973900f7bbedcd5290836", result.toHexString())
    }

    @Test
    fun testTuyaCipherFromRawKeyBytes() {
        // TuyaCipher constructed from a raw ByteArray must use those bytes as-is (no MD5)
        val rawKey = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
        val cipher = TuyaCipher(rawKey)
        val plaintext = """{"dps":{"1":true}}""".toByteArray()

        val encrypted = cipher.encrypt(plaintext)
        // Python: AESCipher(raw_key).encrypt(plaintext, use_base64=False)
        assertEquals("a9eac2bbeb1979f8ccbab77d61a66e1b2d8be6ae089fe84d66c0a26cb6af3595", encrypted.toHexString())

        val decrypted = cipher.decrypt(encrypted)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun testAesGcmEncryptProducesKnownVector() {
        // Python: AES-GCM with iv=b"0123456789ab", key=0102...0f10, plaintext={"dps":{"1":true}}
        // encryptGcm returns iv(12) + ciphertext + tag(16)
        val key = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
        val iv  = "303132333435363738396162".hexToBytes()  // b"0123456789ab"
        val plaintext = """{"dps":{"1":true}}""".toByteArray()

        val result = TuyaCipher(key).encryptGcm(plaintext, iv)
        // iv(12) + ciphertext(18) + tag(16) = 46 bytes
        assertEquals(
            "3031323334353637383961626da7c50a5add53c61f7c0404d6796860f6fd1b12a0a2902215ba9a6784adfcac3aa5",
            result.toHexString()
        )
    }

    @Test
    fun testAesGcmDecryptRoundTrip() {
        val key = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
        val iv  = "303132333435363738396162".hexToBytes()
        val plaintext = """{"dps":{"1":true}}""".toByteArray()
        val cipher = TuyaCipher(key)

        val encrypted = cipher.encryptGcm(plaintext, iv)
        // encrypted = iv(12) + ciphertext+tag — strip iv to pass ciphertext+tag to decryptGcm
        val ciphertextAndTag = encrypted.copyOfRange(12, encrypted.size)
        val decrypted = cipher.decryptGcm(ciphertextAndTag, iv)

        assertContentEquals(plaintext, decrypted)
    }

    // -----------------------------------------------------------------
    // Stage 3 — Message encode compliance
    // -----------------------------------------------------------------

    private val localKey = "JvEuI)cyLCdpGFf:"
    private val deviceId = "bf4e86355fde4faab6l043"
    private val cipher   = TuyaCipher(localKey)

    @Test
    fun testV31ControlEncodeStructure() {
        // Python reference: XenonDevice._encode_message with version=3.1, cmd=CONTROL
        // Wire format: "3.1" (3 bytes) + md5[8:24] (16 bytes) + base64(AES-ECB(json))
        val json = """{"gwId":"$deviceId","devId":"$deviceId","dps":{"1":true}}"""
        val message = TuyaMessage.createWithJsonPayload(TuyaCommand.CONTROL, json, sequenceNumber = 1)

        val encoded = message.encode(cipher = cipher, version = TuyaProtocolVersion.V3_1)
        // Full wire hex cross-verified against Python pack_message output
        assertEquals(
            "000055aa00000001000000070000009b" +
            "332e31383435343365346362663962656363656d4b6a6f374d6a505957416f563371386c6b3743315a743859636f" +
            "4c31466c466f64453569724b2f6c7a422f31565473304f354f394d64614c2b6f6665376c75396f2b615674536530" +
            "6c664a627053344930685545744936646334674e2b6b546947617342594563586c79476a53733554546a6e375775" +
            "4434327259424b5262" +
            "458172c1" +
            "0000aa55",
            encoded.toHexString()
        )
    }

    @Test
    fun testV31ControlEncodePayloadParts() {
        // Break down the payload to verify each part independently
        val json = """{"gwId":"$deviceId","devId":"$deviceId","dps":{"1":true}}"""
        val message = TuyaMessage.createWithJsonPayload(TuyaCommand.CONTROL, json, sequenceNumber = 1)
        val encoded = message.encode(cipher = cipher, version = TuyaProtocolVersion.V3_1)

        // payload starts at byte 16 (after 4-byte prefix + 4-byte seqno + 4-byte cmd + 4-byte length)
        val wirePayload = encoded.copyOfRange(16, encoded.size - 8) // strip CRC(4)+suffix(4)

        // First 3 bytes must be "3.1"
        assertEquals("3.1", wirePayload.copyOfRange(0, 3).toString(Charsets.UTF_8), "version prefix")

        // Bytes [3:19] are the MD5 hex digest slice [8:24] — all ASCII
        val md5Slice = wirePayload.copyOfRange(3, 19).toString(Charsets.ISO_8859_1)
        assertEquals("84543e4cbf9becce", md5Slice, "md5 slice")

        // Bytes [19:] are base64 — must decode to valid AES-ECB ciphertext (multiple of 16)
        val b64Payload = wirePayload.copyOfRange(19, wirePayload.size)
        val decoded = Base64.getDecoder().decode(b64Payload)
        assertEquals(0, decoded.size % 16, "base64-decoded ciphertext must be multiple of 16 bytes")

        // Decrypting must recover the original JSON
        val plaintext = cipher.decrypt(decoded).toString(Charsets.UTF_8)
        assertEquals(json, plaintext, "decrypted payload")
    }

    @Test
    fun testV34EncodeIntegrityIsHmacSha256() {
        // v3.4 messages must use a 32-byte HMAC-SHA256 integrity field, not a 4-byte CRC32
        val json = """{"gwId":"$deviceId","devId":"$deviceId","dps":{"1":true}}"""
        val message = TuyaMessage.createWithJsonPayload(TuyaCommand.STATUS, json, sequenceNumber = 1)
        val encoded = message.encode(cipher = cipher, version = TuyaProtocolVersion.V3_4)

        // Total structure: prefix(4)+seqno(4)+cmd(4)+length(4)+payload(N)+HMAC(32)+suffix(4)
        // So encoded[-36:-4] is the HMAC field
        val hmacField = encoded.copyOfRange(encoded.size - 36, encoded.size - 4)
        assertEquals(32, hmacField.size, "HMAC field must be 32 bytes")

        // Must equal HMAC-SHA256(localKey, everything-before-the-hmac)
        val hmacInput = encoded.copyOfRange(0, encoded.size - 36)
        val expected  = hmacInput.macSha256(cipher.keyBytes)
        assertContentEquals(expected, hmacField, "HMAC value")

        // Cross-check the full encoded hex against the Python reference
        assertEquals(
            "000055aa000000010000000800000094" +
            "c253bd6a4db8481844b219147c365ab1402f72a7fc83e8597a6c1a47f4912c2f8719267af2c176661beb729dd69252d6c" +
            "4ec3ed05a3cbe7b18826e455d87a7509b7c61ca0bd45945a1d1398ab2bf9730" +
            "bf6aab0e7cf4b2b23a63357f9e764b122d857000496d7930c72b4d0a8ed62b31" +
            "18ab9d23be2a9715e8a161a063bbf1ab02e0a753bc818edb046290a5e17ae73b" +
            "0000aa55",
            encoded.toHexString()
        )
    }

    // -----------------------------------------------------------------
    // Stage 4 — Message decode compliance
    // -----------------------------------------------------------------

    @Test
    fun testV33DecodeStripsSourceHeader() {
        // Real v3.3 STATUS response where the decrypted plaintext starts with a 24-byte
        // source header "3.3CCCCCCCCSSSSSSSS12345". The decoder must strip it and return
        // only the JSON body.
        // Vector generated by Python pack_message with retcode=0.
        val hex = "000055aa0000000a000000080000004b" +
                  "00000000" +
                  "332e33000000000000000000000000" +
                  "ace2742b5e45b90d247568d02b34496d2c4217743cc797114c086b066b3d5d58" +
                  "2a6ec36f620bf9c952e7e4799114166a" +
                  "d0d0a7cc" +
                  "0000aa55"

        val msg = TuyaMessage.decode(
            data    = hex.hexToBytes(),
            cipher  = cipher,
            version = TuyaProtocolVersion.V3_3,
        )

        assertEquals(TuyaCommand.STATUS, msg.command)
        assertEquals(10, msg.sequenceNumber)
        assertEquals(0, msg.returnCode)
        assertEquals("""{"dps":{"1":true}}""", msg.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun testV33DecodeNoSourceHeader() {
        // v3.3 STATUS response where the plaintext has no source header — must decode normally.
        val hex = "000055aa0000000b000000080000003b" +
                  "00000000" +
                  "332e3300000000000000000000000079f100f88506065faa8d0e73c6b501e2" +
                  "2d857000496d7930c72b4d0a8ed62b31" +
                  "f8504584" +
                  "0000aa55"

        val msg = TuyaMessage.decode(
            data    = hex.hexToBytes(),
            cipher  = cipher,
            version = TuyaProtocolVersion.V3_3,
        )

        assertEquals(TuyaCommand.STATUS, msg.command)
        assertEquals(11, msg.sequenceNumber)
        assertEquals("""{"dps":{"1":true}}""", msg.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun testV34DecodeHmacCheckAndPayload() {
        // Real v3.4 STATUS response: retcode(4) + AES-ECB("3.4"+12x00+json) + HMAC(32) + suffix(4).
        // Decoder must verify HMAC-SHA256, strip the encrypted version header, and return JSON.
        val hex = "000055aa00000007000000080000005800000000" +
                  "c253bd6a4db8481844b219147c365ab1c7dbfbf381cba171cf8f7b645b0aa85f" +
                  "e94fc8cdea7a11a92bdce7626311942a" +
                  "99c5df078e885dba9153e6ee22e1c8f1386ba5b131fd73d6ee12a6bf5f77ce4d" +
                  "0000aa55"

        val msg = TuyaMessage.decode(
            data    = hex.hexToBytes(),
            cipher  = cipher,
            version = TuyaProtocolVersion.V3_4,
        )

        assertEquals(TuyaCommand.STATUS, msg.command)
        assertEquals(7, msg.sequenceNumber)
        assertEquals(0, msg.returnCode)
        assertEquals("""{"dps":{"1":true}}""", msg.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun testV34DecodeRejectsCorruptHmac() {
        // Flip one byte in the HMAC field — decode must throw.
        val hex = "000055aa00000007000000080000005800000000" +
                  "c253bd6a4db8481844b219147c365ab1c7dbfbf381cba171cf8f7b645b0aa85f" +
                  "e94fc8cdea7a11a92bdce7626311942a" +
                  "99c5df078e885dba9153e6ee22e1c8f1386ba5b131fd73d6ee12a6bf5f77ce4d" +
                  "0000aa55"
        val data = hex.hexToBytes().also { it[it.size - 37] = (it[it.size - 37].toInt() xor 0xff).toByte() }

        var threw = false
        try {
            TuyaMessage.decode(data = data, cipher = cipher, version = TuyaProtocolVersion.V3_4)
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("HMAC"), "exception should mention HMAC")
        }
        assertTrue(threw, "expected IllegalArgumentException for corrupt HMAC")
    }

    // -----------------------------------------------------------------
    // Stage 5 — Session key derivation
    // -----------------------------------------------------------------

    // Fixed nonces used across all session key tests (mirrors Python test fixture)
    private val localNonce  = "30313233343536373839616263646566".hexToBytes()  // b"0123456789abcdef"
    private val remoteNonce = "61626364656630313233343536373839".hexToBytes()  // b"abcdef0123456789"

    @Test
    fun testSessionKeyNoncesXor() {
        // Both v3.4 and v3.5 start from XOR of client and device nonces
        val xored = localNonce.xor(remoteNonce)
        assertEquals("51535157515306060a0a555755535d5f", xored.toHexString())
    }

    @Test
    fun testSessionKeyDerivationV34() {
        // Python: local_key = AESCipher(realKey).encrypt(localNonce XOR remoteNonce, use_base64=False, pad=False)
        // Full 16-byte AES-ECB block — no GCM, no slicing, no padding
        val xored      = localNonce.xor(remoteNonce)
        val sessionKey = cipher.encryptRaw(xored)
        assertEquals("74a78f53509872490e78048a8c6c45f2", sessionKey.toHexString())
        assertEquals(16, sessionKey.size)
    }

    @Test
    fun testSessionKeyDerivationV35() {
        // Python: gcm_out = AESCipher(realKey).encrypt(xored, use_base64=False, pad=False, iv=localNonce[:12])
        //         session_key = gcm_out[12:28]
        // iv = first 12 bytes of the client nonce; result = iv(12)+ciphertext(16)+tag(16) = 44 bytes
        val xored  = localNonce.xor(remoteNonce)
        val iv     = localNonce.copyOfRange(0, 12)

        // encryptGcm returns iv(12) + ciphertext + tag(16)
        val gcmOut    = cipher.encryptGcm(xored, iv)
        assertEquals(44, gcmOut.size, "GCM output must be iv(12)+ct(16)+tag(16)=44 bytes")

        val sessionKey = gcmOut.copyOfRange(12, 28)
        assertEquals("864a1aee47bc070f79ecc471e1c1ec1c", sessionKey.toHexString())
        assertEquals(16, sessionKey.size)
    }

    @Test
    fun testHandshakeHmacLocalNonce() {
        // Step 2 verification: client checks HMAC(realKey, localNonce) == payload[16:48]
        val hmac = localNonce.macSha256(cipher.keyBytes)
        assertEquals("00c9e4c5af08815ac00cad4f100aafb1e8534d6092778a4047ff86df64058239", hmac.toHexString())
    }

    @Test
    fun testHandshakeHmacRemoteNonce() {
        // Step 3: client sends HMAC(realKey, remoteNonce) back to device as the FINISH payload
        val hmac = remoteNonce.macSha256(cipher.keyBytes)
        assertEquals("2f9c07bb840c020193e8cd18deadf37e3fc102f48c389ba1dd1f33ce52b3a8e9", hmac.toHexString())
    }

    @Test
    fun testHandshakeStep2PayloadDecrypt() {
        // Device step 2 response (v3.4): AES-ECB(realKey, remoteNonce(16) + HMAC(realKey,localNonce)(32))
        // Client must decrypt, extract remoteNonce from [0:16], verify HMAC at [16:48].
        val step2Encrypted = "5b4a2faa852004bf2bf5047202f11111025e47725b6213cce72f220c81e5a4d3" +
                             "8216608c26bc3fad4c2f065b7979c44411e6ac7806d5759a3b5da8a05da8b85d"

        val decrypted = cipher.decrypt(step2Encrypted.hexToBytes())
        assertEquals(48, decrypted.size)

        // First 16 bytes = remoteNonce
        val extractedRemote = decrypted.copyOfRange(0, 16)
        assertContentEquals(remoteNonce, extractedRemote, "extracted remoteNonce")

        // Bytes [16:48] = HMAC(realKey, localNonce) — client uses this to authenticate the device
        val extractedHmac = decrypted.copyOfRange(16, 48)
        val expectedHmac  = localNonce.macSha256(cipher.keyBytes)
        assertContentEquals(expectedHmac, extractedHmac, "HMAC(localKey, localNonce)")
    }
}
