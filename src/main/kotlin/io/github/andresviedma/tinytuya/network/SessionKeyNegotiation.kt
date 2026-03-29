package io.github.andresviedma.tinytuya.network

import io.github.andresviedma.tinytuya.crypto.TuyaCipher
import io.github.andresviedma.tinytuya.protocol.ByteUtils.macSha256
import io.github.andresviedma.tinytuya.protocol.ByteUtils.xor
import io.github.andresviedma.tinytuya.protocol.TuyaProtocolVersion

/**
 * Pure session key negotiation math for Tuya protocol v3.4 and v3.5.
 *
 * The 3-way handshake (socket I/O omitted):
 *   Step 1 — client sends SESS_KEY_NEG_START payload = clientNonce (16 bytes)
 *   Step 2 — device sends SESS_KEY_NEG_RESP  payload = AES-ECB(realKey, deviceNonce(16) + HMAC(realKey,clientNonce)(32))
 *   Step 3 — client sends SESS_KEY_NEG_FINISH payload = HMAC(realKey, deviceNonce) (32 bytes)
 *   Finalize — session key derived from the two nonces
 */
object SessionKeyNegotiation {

    /**
     * Parse and validate the device's step-2 response payload.
     *
     * For v3.4 the payload is AES-ECB encrypted; for v3.5 it is sent in plaintext (GCM
     * integrity is on the outer frame). Returns the extracted device nonce on success,
     * or throws [IllegalArgumentException] if the embedded HMAC check fails.
     */
    fun parseStep2(
        step2Payload: ByteArray,
        realCipher: TuyaCipher,
        clientNonce: ByteArray,
        version: TuyaProtocolVersion,
    ): ByteArray {
        val plaintext = when (version) {
            TuyaProtocolVersion.V3_4 -> realCipher.decrypt(step2Payload)
            else                     -> step2Payload   // v3.5 outer GCM covers integrity
        }
        require(plaintext.size >= 48) {
            "Step-2 payload too short: ${plaintext.size} bytes (need >= 48)"
        }
        val deviceNonce = plaintext.copyOfRange(0, 16)
        val embeddedHmac = plaintext.copyOfRange(16, 48)
        val expectedHmac = clientNonce.macSha256(realCipher.keyBytes)
        require(embeddedHmac.contentEquals(expectedHmac)) {
            "Step-2 HMAC check failed — possible MITM or wrong local key"
        }
        return deviceNonce
    }

    /**
     * Build the SESS_KEY_NEG_FINISH payload: HMAC(realKey, deviceNonce).
     */
    fun buildStep3(deviceNonce: ByteArray, realCipher: TuyaCipher): ByteArray =
        deviceNonce.macSha256(realCipher.keyBytes)

    /**
     * Derive the session key from the two nonces and the real local key.
     *
     * v3.4: AES-ECB(realKey, clientNonce XOR deviceNonce) — full 16 bytes
     * v3.5: AES-GCM(realKey, iv=clientNonce[:12], clientNonce XOR deviceNonce)[12:28]
     */
    fun deriveSessionKey(
        clientNonce: ByteArray,
        deviceNonce: ByteArray,
        realCipher: TuyaCipher,
        version: TuyaProtocolVersion,
    ): ByteArray {
        val xored = clientNonce.xor(deviceNonce)
        return when (version) {
            TuyaProtocolVersion.V3_4 -> realCipher.encryptRaw(xored)
            else -> {
                val iv = clientNonce.copyOfRange(0, 12)
                realCipher.encryptGcm(xored, iv).copyOfRange(12, 28)
            }
        }
    }
}
