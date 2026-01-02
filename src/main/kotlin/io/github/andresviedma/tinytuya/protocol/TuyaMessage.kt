package io.github.andresviedma.tinytuya.protocol

import io.github.andresviedma.tinytuya.crypto.TuyaCipher
import io.github.andresviedma.tinytuya.protocol.ByteUtils.concatByteArrays
import io.github.andresviedma.tinytuya.protocol.ByteUtils.crc32Bytes
import io.github.andresviedma.tinytuya.protocol.ByteUtils.macSha256
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toBytesBE
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toHexString
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toIntBE
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
            checksumData.macSha256(cipher!!.localKey)
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
                    error("CONTROL command encoding not implemented in message version 3.1")
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

            // Extract payload (excluding return code, CRC, and suffix)
            val payloadStart = 20  // After header and return code
            val payloadEnd = data.size - 8  // Before CRC and suffix
            val encryptedPayload = data.copyOfRange(payloadStart, payloadEnd)

            // Verify CRC
            val receivedCrc = data.toIntBE(data.size - 8)
            val calculatedCrcData = data.copyOfRange(0, data.size - 8)
            val calculatedCrc = calculatedCrcData.crc32Bytes().toIntBE()
            require(receivedCrc == calculatedCrc) {
                "CRC mismatch: received 0x${receivedCrc.toString(16)}, calculated 0x${calculatedCrc.toString(16)}"
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
                    // Version 3.1: Simple decryption
                    cipher.decrypt(encryptedPayload)
                }
                TuyaProtocolVersion.V3_3, TuyaProtocolVersion.V3_4, TuyaProtocolVersion.V3_5 -> {
                    // Version 3.3+: Remove version header and suffix
                    // Minimum size: 3 (header) + 16 (min encrypted block) + 16 (MD5 suffix) = 35 bytes
                    if (encryptedPayload.size < 35) {
                        return encryptedPayload
                    }

                    // Check for version header
                    val versionHeader = String(encryptedPayload.copyOfRange(0, 3), Charsets.UTF_8)
                    if (versionHeader == "3.3") {
                        // Remove version header (3 bytes) and suffix (16 bytes - MD5 hash)
                        val encryptedData = encryptedPayload.copyOfRange(3, encryptedPayload.size - 16)
                        cipher.decrypt(encryptedData)
                    } else {
                        // No version header, decrypt as-is
                        cipher.decrypt(encryptedPayload)
                    }
                }
                else -> {
                    // Default: simple decryption
                    cipher.decrypt(encryptedPayload)
                }
            }
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
