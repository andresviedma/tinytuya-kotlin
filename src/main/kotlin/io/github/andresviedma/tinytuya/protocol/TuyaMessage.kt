package io.github.andresviedma.tinytuya.protocol

import io.github.andresviedma.tinytuya.crypto.TuyaCipher
import io.github.andresviedma.tinytuya.protocol.ByteUtils.concatByteArrays
import io.github.andresviedma.tinytuya.protocol.ByteUtils.crc32Bytes
import io.github.andresviedma.tinytuya.protocol.ByteUtils.macSha256
import io.github.andresviedma.tinytuya.protocol.ByteUtils.md5
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toBytesBE
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toHexString
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toIntBE
import java.util.Base64
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Represents a Tuya protocol message.
 *
 * Message format:
 * - Prefix (4 bytes): 0x000055aa
 * - Sequence number (4 bytes): incrementing counter
 * - Command (4 bytes): command code
 * - Payload length (4 bytes): length of the payload
 * - Return code (4 bytes): status/error code
 * - Payload (variable): encrypted or plaintext data
 * - CRC (4 bytes): CRC32 checksum
 * - Suffix (4 bytes): 0x0000aa55
 */
data class TuyaMessage(
    val command: TuyaCommand,
    val payload: ByteArray,
    val sequenceNumber: Int = 0,
    val returnCode: Int? = null,
) {
    /**
     * Encode the message to a byte array for transmission
     */
    fun encode(
        cipher: TuyaCipher? = null,
        version: TuyaProtocolVersion = TuyaProtocolVersion.V3_3,
        deviceId: String? = null
    ): ByteArray {
        // Prepare payload
        val finalPayload = preparePayload(payload, cipher, version)

        // Build the message structure
        val prefix = PREFIX
        val seqNum = sequenceNumber.toBytesBE()
        val cmd = command.code.toBytesBE()
        val crcLength = if (version == TuyaProtocolVersion.V3_4) 32 else 4
        val payloadLength = (finalPayload.size + crcLength + 4).toBytesBE() // +8 for return code and CRC
        val retCode = returnCode?.toBytesBE() ?: ByteArray(0)

        // Combine header (without payload, final CRC and suffix)
        val header = concatByteArrays(
            prefix,
            seqNum,
            cmd,
            payloadLength
        )

        // Calculate CRC over header + return code + payload
        val checksumData = concatByteArrays(header, finalPayload) // retCode, finalPayload)
        val checksum = if (version == TuyaProtocolVersion.V3_4)
            checksumData.macSha256(cipher!!.keyBytes)
        else
            checksumData.crc32Bytes()

        // Combine everything
        return concatByteArrays(
            header,
            retCode,
            finalPayload,
            checksum,
            SUFFIX
        )
    }

    private fun preparePayload(
        payload: ByteArray,
        cipher: TuyaCipher?,
        version: TuyaProtocolVersion,
    ): ByteArray {
        return when {
            cipher == null -> payload

            version == TuyaProtocolVersion.V3_1 ->
                if (command == TuyaCommand.CONTROL) {
                    // Encrypt payload with AES-ECB + base64
                    val encrypted = Base64.getEncoder().encode(cipher.encrypt(payload))
                    // Compute MD5 of "data=<b64>||lpv=3.1||<localKey>"
                    val preMd5 = concatByteArrays(
                        "data=".toByteArray(),
                        encrypted,
                        "||lpv=3.1||".toByteArray(),
                        cipher.keyBytes
                    )
                    val hexDigest = preMd5.md5().joinToString("") { "%02x".format(it) }
                    // Prepend "3.1" + md5[8..23] (16 chars) + encrypted payload
                    concatByteArrays(
                        "3.1".toByteArray(),
                        hexDigest.substring(8, 24).toByteArray(Charsets.ISO_8859_1),
                        encrypted
                    )
                } else {
                    payload
                }

            command in NO_PROTOCOL_HEADER_CMDS -> cipher.encrypt(payload)

            version in setOf(TuyaProtocolVersion.V3_2, TuyaProtocolVersion.V3_3) ->
                concatByteArrays(versionHeader(version), cipher.encrypt(payload))

            version == TuyaProtocolVersion.V3_4 ->
                cipher.encrypt(concatByteArrays(versionHeader(version), payload))

            version == TuyaProtocolVersion.V3_5 ->
                error("Library not compatible with message version 3.5")

            else -> error("Unimplemented version ${version.version}")
        }
    }

    private fun versionHeader(version: TuyaProtocolVersion): ByteArray =
        concatByteArrays(
            version.version.toByteArray(Charsets.UTF_8),
            MutableList(12) { 0.toByte() }.toByteArray()
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TuyaMessage

        if (command != other.command) return false
        if (!payload.contentEquals(other.payload)) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (returnCode != other.returnCode) return false

        return true
    }

    override fun hashCode(): Int {
        var result = command.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + sequenceNumber
        result = 31 * result + (returnCode ?: 0)
        return result
    }

    companion object {
        // Message prefix and suffix
        private val PREFIX = byteArrayOf(0x00, 0x00, 0x55, 0xaa.toByte())
        private val SUFFIX = byteArrayOf(0x00, 0x00, 0xaa.toByte(), 0x55)

        // Header size: prefix(4) + seqNum(4) + cmd(4) + length(4) = 16 bytes
        private const val HEADER_SIZE = 16

        // Minimum message size: header(16) + returnCode(4) + crc(4) + suffix(4) = 28 bytes
        private const val MIN_MESSAGE_SIZE = 28

        // Types of commands with no protocol header
        private val NO_PROTOCOL_HEADER_CMDS = setOf(
            TuyaCommand.DP_QUERY,
            TuyaCommand.DP_QUERY_NEW,
            TuyaCommand.UPDATE_DPS,
            TuyaCommand.HEART_BEAT,
            TuyaCommand.SESS_KEY_NEG_START,
            TuyaCommand.SESS_KEY_NEG_RESP,
            TuyaCommand.SESS_KEY_NEG_FINISH,
            TuyaCommand.LAN_EXT_STREAM,
        )

        /**
         * Decode a Tuya message from a byte array
         */
        fun decode(
            data: ByteArray,
            cipher: TuyaCipher? = null,
            version: TuyaProtocolVersion = TuyaProtocolVersion.V3_3
        ): TuyaMessage {
            require(data.size >= MIN_MESSAGE_SIZE) {
                "Message too short: ${data.size} bytes (minimum $MIN_MESSAGE_SIZE)"
            }

            // Verify prefix
            val prefix = data.copyOfRange(0, 4)
            require(prefix.contentEquals(PREFIX)) {
                "Invalid message prefix: ${prefix.toHexString()}"
            }

            // Verify suffix
            val suffix = data.copyOfRange(data.size - 4, data.size)
            require(suffix.contentEquals(SUFFIX)) {
                "Invalid message suffix: ${suffix.toHexString()}"
            }

            // Parse header
            val sequenceNumber = data.toIntBE(4)
            val commandCode = data.toIntBE(8)
            val payloadLength = data.toIntBE(12)
            val returnCode = data.toIntBE(16)

            // Extract command
            val command = TuyaCommand.fromCode(commandCode)
                ?: throw IllegalArgumentException("Unknown command code: 0x${commandCode.toString(16)}")

            // Extract payload (excluding return code, integrity field, and suffix)
            val payloadStart = 20  // After header and return code
            // v3.4 uses 32-byte HMAC + 4-byte suffix; others use 4-byte CRC + 4-byte suffix
            val integritySize = if (version == TuyaProtocolVersion.V3_4) 32 else 4
            val payloadEnd = data.size - integritySize - 4  // Before integrity field and suffix
            val encryptedPayload = data.copyOfRange(payloadStart, payloadEnd)

            // Verify integrity: HMAC-SHA256 for v3.4, CRC32 for all others
            val integrityDataEnd = if (version == TuyaProtocolVersion.V3_4) data.size - 36 else data.size - 8
            val integrityData = data.copyOfRange(0, integrityDataEnd)
            if (version == TuyaProtocolVersion.V3_4) {
                require(cipher != null) { "Cipher required for v3.4 integrity check" }
                val receivedHmac = data.copyOfRange(data.size - 36, data.size - 4)
                val calculatedHmac = integrityData.macSha256(cipher.keyBytes)
                require(receivedHmac.contentEquals(calculatedHmac)) {
                    "HMAC mismatch for v3.4 message"
                }
            } else {
                val receivedCrc = data.toIntBE(data.size - 8)
                val calculatedCrc = integrityData.crc32Bytes().toIntBE()
                require(receivedCrc == calculatedCrc) {
                    "CRC mismatch: received 0x${receivedCrc.toString(16)}, calculated 0x${calculatedCrc.toString(16)}"
                }
            }

            // Decrypt payload
            val payload = decryptPayload(encryptedPayload, cipher, version)
            logger.debug { "Received payload: ${String(payload, Charsets.UTF_8)}" }

            return TuyaMessage(
                command = command,
                payload = payload,
                sequenceNumber = sequenceNumber,
                returnCode = returnCode
            )
        }

        private fun decryptPayload(
            encryptedPayload: ByteArray,
            cipher: TuyaCipher?,
            version: TuyaProtocolVersion
        ): ByteArray {
            if (cipher == null || encryptedPayload.isEmpty()) {
                return encryptedPayload
            }

            return when (version) {
                TuyaProtocolVersion.V3_1 -> {
                    // v3.1 responses: if starts with "3.1", strip it + 16-byte MD5 slice, then decrypt
                    if (encryptedPayload.size >= 19 &&
                        encryptedPayload.copyOfRange(0, 3).contentEquals("3.1".toByteArray())) {
                        cipher.decrypt(encryptedPayload.copyOfRange(19, encryptedPayload.size))
                    } else {
                        encryptedPayload // plaintext (status responses)
                    }
                }
                TuyaProtocolVersion.V3_2, TuyaProtocolVersion.V3_3 -> {
                    // v3.2/3.3 responses: optional version header (15 bytes: 3 + 12 zeros) then encrypted data
                    val headerLen = 15
                    val stripped = if (encryptedPayload.size >= headerLen + 1 &&
                        encryptedPayload[0] == '3'.code.toByte()) {
                        encryptedPayload.copyOfRange(headerLen, encryptedPayload.size)
                    } else {
                        encryptedPayload
                    }
                    val decrypted = cipher.decrypt(stripped)
                    // Strip source header: 24-byte "3.xCCCCCCCCSSSSSSSSUUUUUUUU" prefix if present
                    stripSourceHeader(decrypted, version)
                }
                TuyaProtocolVersion.V3_4 -> {
                    // v3.4: entire payload (including version header) is AES-ECB encrypted together
                    val decrypted = cipher.decrypt(encryptedPayload)
                    // After decryption, strip version header (15 bytes: "3.4" + 12 zeros)
                    val stripped = if (decrypted.size >= 15 &&
                        decrypted.copyOfRange(0, 3).contentEquals("3.4".toByteArray())) {
                        decrypted.copyOfRange(15, decrypted.size)
                    } else {
                        decrypted
                    }
                    stripSourceHeader(stripped, version)
                }
                else -> encryptedPayload
            }
        }

        /**
         * Strip the 24-byte source header "3.xCCCCCCCCSSSSSSSSUUUUUUUU" from decrypted v3.3+ responses.
         * The header is present on STATUS/update responses but not on all messages.
         */
        private fun stripSourceHeader(data: ByteArray, version: TuyaProtocolVersion): ByteArray {
            val sourceHeaderLen = 24
            if (data.size > sourceHeaderLen &&
                data[0] == '3'.code.toByte() &&
                data[1] == '.'.code.toByte() &&
                data[2] == version.version[2].code.toByte()) {
                return data.copyOfRange(sourceHeaderLen, data.size)
            }
            return data
        }

        /**
         * Create a message with JSON payload
         */
        fun createWithJsonPayload(
            command: TuyaCommand,
            json: String,
            sequenceNumber: Int = 0
        ): TuyaMessage {
            return TuyaMessage(
                command = command,
                payload = json.toByteArray(Charsets.UTF_8),
                sequenceNumber = sequenceNumber
            )
        }

        /**
         * Create an empty message (for commands that don't need payload)
         */
        fun createEmpty(
            command: TuyaCommand,
            sequenceNumber: Int = 0
        ): TuyaMessage {
            return TuyaMessage(
                command = command,
                payload = ByteArray(0),
                sequenceNumber = sequenceNumber
            )
        }
    }
}
