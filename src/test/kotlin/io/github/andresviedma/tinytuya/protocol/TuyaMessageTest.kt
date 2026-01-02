package io.github.andresviedma.tinytuya.protocol

import io.github.andresviedma.tinytuya.crypto.TuyaCipher
import io.github.andresviedma.tinytuya.network.TuyaScanner
import io.github.andresviedma.tinytuya.protocol.ByteUtils.crc32
import io.github.andresviedma.tinytuya.protocol.ByteUtils.crc32Bytes
import io.github.andresviedma.tinytuya.protocol.ByteUtils.hexToBytes
import io.github.andresviedma.tinytuya.protocol.ByteUtils.md5
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals

class TuyaMessageTest {

    private val deviceId = "bf4e86355fde4faab6l043"
    private val localKey = "JvEuI)cyLCdpGFf:"
    private val cipher = TuyaCipher(localKey)

    @Test
    fun testEncode3_3() {
        val message = TuyaMessage.createWithJsonPayload(
            command = TuyaCommand.STATUS,
            json = """{"gwId":"$deviceId","devId":"$deviceId","dps":"{\"test\":\"data\"}"}""",
            sequenceNumber = 1,
        )

        val result = message.encode(
            version = TuyaProtocolVersion.V3_3,
            cipher = cipher,
            deviceId = deviceId,
        ).toHexString()

        // Header
        result.assert("header prefix", 0, 7, "000055aa")
        result.assert("sequence number", 8, 15, "00000001")
        result.assert("command", 16, 23, "00000008")
        // payload length (24-31) will be validated at the end

        // Payload
        result.assert("version header", 32, 61, "332e33000000000000000000000000")
        result.assert("payload", 62, result.length - 17, "98a8e8ecc8cf616028577abc964ec2d59b7c61ca0bd45945a1d1398ab2bf97307fd554ecd0ee4ef4c75a2fea1f7bb96ef68f9a56d49ed257c96e94b82348541244761418064623a5f6da70164c45656c9f1173dfa75c1ff66cc9c1b7e7569937")

        // Payload length (part of the header)
        result.assert("payload length", 24, 31, "00000077")

        // checksum (header + payload) and suffix
        result.assert("crc", result.length - 16, result.length - 9, "81d1e693")
        result.assert("message suffix", result.length - 8, result.length - 1, "0000aa55")
    }

    @Test
    fun testDecode3_3() {
        val message = "000055aa00000001000000070000000c00000000a505a9140000aa55"
        val result = TuyaMessage.decode(
            data = message.hexToBytes(),
            cipher = TuyaCipher(localKey),
            version = TuyaProtocolVersion.V3_3,
        )

        assertEquals(TuyaCommand.CONTROL, result.command)
        assertEquals(1, result.sequenceNumber)
        assertEquals(0, result.returnCode)
        assertEquals(0, result.payload.size)
    }

    @Test
    fun testDecode3_3_scan() {
        val message = "000055aa00000000000000130000009c00000000e8ade47bd7ff48369d1c8b0a78e48e3a3a70ea54f8867e9ec7fc56b6751e75406e42babb86a7cda254b6f8aea1fe11f7f956d629c5e5f4c7486e62b69065c68ec2fb8459b1155fc75d4bf6699f92cba4c0ba520148045e7605fa0498dfea5aab549982ac0f1f64dedb607dac87b8ca4318aa61807b51b2352a27b165add3569b6eda8eea40e93b1e3fc14a2570e1827934e932340000aa55"

        val result = TuyaMessage.decode(
            data = message.hexToBytes(),
            cipher = TuyaCipher(TuyaScanner.UDP_KEY, forceMd5 = true),
            version = TuyaProtocolVersion.V3_3,
        )

        assertEquals(TuyaCommand.DISCOVER, result.command)
        assertEquals(0, result.returnCode)
        assertEquals(0, result.sequenceNumber)
        assertEquals(
            """{"ip":"10.214.2.176","gwId":"bf1bd7f0bda4cbc644ichw","active":2,"ablilty":0,"encrypt":true,"productKey":"keym4vvjhx4sd9kk","version":"3.3"}""",
            result.payload.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun testEncode3_4() {
        val message = TuyaMessage.createWithJsonPayload(
            command = TuyaCommand.STATUS,
            json = """{"gwId":"$deviceId","devId":"$deviceId","dps":"{\"test\":\"data\"}"}""",
            sequenceNumber = 1,
        )

        val result = message.encode(
            version = TuyaProtocolVersion.V3_4,
            cipher = cipher,
            deviceId = deviceId,
        ).toHexString()

        // Header
        result.assert("header prefix", 0, 7, "000055aa")
        result.assert("sequence number", 8, 15, "00000001")
        result.assert("command", 16, 23, "00000008")
        // payload length (24-31) will be validated at the end

        // Payload
        result.assert("payload with version header", 32, result.length - 73, "c253bd6a4db8481844b219147c365ab1402f72a7fc83e8597a6c1a47f4912c2f8719267af2c176661beb729dd69252d6c4ec3ed05a3cbe7b18826e455d87a7509b7c61ca0bd45945a1d1398ab2bf9730c543d1bd63e8cfd88edfaec091ccbc325a48e44c64f23952560e4697540c3cd1")

        // Payload length (part of the header)
        result.assert("payload length", 24, 31, "00000094")

        // checksum (header + payload) and suffix
        result.assert("sha256", result.length - 72, result.length - 9, "c33113cbc906b66daa5316e5242e9c603ea0da2281c98bf5dc794e02908ad804")
        result.assert("message suffix", result.length - 8, result.length - 1, "0000aa55")
    }

    @Test
    fun testEncode3_2() {
        val message = TuyaMessage.createWithJsonPayload(
            command = TuyaCommand.STATUS,
            json = """{"gwId":"$deviceId","devId":"$deviceId","dps":"{\"test\":\"data\"}"}""",
            sequenceNumber = 1,
        )

        val result = message.encode(
            version = TuyaProtocolVersion.V3_2,
            cipher = cipher,
            deviceId = deviceId,
        ).toHexString()

        // *** Result is the same as 3.3 but changing only the version header and the CRC

        // Header
        result.assert("header prefix", 0, 7, "000055aa")
        result.assert("sequence number", 8, 15, "00000001")
        result.assert("command", 16, 23, "00000008")
        // payload length (24-31) will be validated at the end

        // Payload
        result.assert("version header", 32, 61, "332e32000000000000000000000000")
        result.assert("payload", 62, result.length - 17, "98a8e8ecc8cf616028577abc964ec2d59b7c61ca0bd45945a1d1398ab2bf97307fd554ecd0ee4ef4c75a2fea1f7bb96ef68f9a56d49ed257c96e94b82348541244761418064623a5f6da70164c45656c9f1173dfa75c1ff66cc9c1b7e7569937")

        // Payload length (part of the header)
        result.assert("payload length", 24, 31, "00000077")

        // checksum (header + payload) and suffix
        result.assert("crc", result.length - 16, result.length - 9, "44ad97ed")
        result.assert("message suffix", result.length - 8, result.length - 1, "0000aa55")
    }

    @Test
    fun testEncode3_1() {
        val message = TuyaMessage.createWithJsonPayload(
            command = TuyaCommand.STATUS,
            json = """{"gwId":"$deviceId","devId":"$deviceId","dps":"{\"test\":\"data\"}"}""",
            sequenceNumber = 1,
        )

        val result = message.encode(
            version = TuyaProtocolVersion.V3_1,
            cipher = cipher,
            deviceId = deviceId,
        ).toHexString()

        // *** Result is the same as 3.3 but changing only the version header and the CRC

        // Header
        result.assert("header prefix", 0, 7, "000055aa")
        result.assert("sequence number", 8, 15, "00000001")
        result.assert("command", 16, 23, "00000008")
        // payload length (24-31) will be validated at the end

        // Payload
        result.assert("payload", 32, result.length - 17, "7b2267774964223a226266346538363335356664653466616162366c303433222c226465764964223a226266346538363335356664653466616162366c303433222c22647073223a227b5c22746573745c223a5c22646174615c227d227d")

        // Payload length (part of the header)
        result.assert("payload length", 24, 31, "00000066")

        // checksum (header + payload) and suffix
        result.assert("crc", result.length - 16, result.length - 9, "7629b7a4")
        result.assert("message suffix", result.length - 8, result.length - 1, "0000aa55")
    }

    private fun String.assert(field: String, from: Int, to: Int, expected: String) {
        assertEquals(expected, slice(from..to), field)
    }
}
