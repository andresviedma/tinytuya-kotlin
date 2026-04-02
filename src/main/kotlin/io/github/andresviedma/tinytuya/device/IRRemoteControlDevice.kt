package io.github.andresviedma.tinytuya.device

import io.github.andresviedma.tinytuya.network.DeviceConnectionConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.math.round
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Tuya WiFi smart universal IR remote control device.
 *
 * Supports two device generations:
 *  - controlType = 1: older devices using DPS 201 (send) and 202 (learned report)
 *  - controlType = 2: newer devices using DPS 1–13
 *
 * If [controlType] is not provided (defaults to 0), [detectControlType] is called
 * automatically after [connect].
 *
 * DPS and NSDP constants are available on [RemoteControlDps].
 */
class IRRemoteControlDevice(
    val device: TuyaDevice,
    var controlType: Int = 0,
) : AutoCloseable {

    constructor(config: DeviceConnectionConfig, controlType: Int = 0) : this(TuyaDevice(config), controlType)

    // ── Connection ─────────────────────────────────────────────────────────────

    suspend fun connect(): IRRemoteControlDevice {
        device.connect()
        if (controlType == 0) detectControlType()
        return this
    }

    override fun close() {
        device.close()
    }

    // ── Control type detection ──────────────────────────────────────────────────

    suspend fun detectControlType() {
        controlType = detectRemoteControlType(device)
    }

    // ── High-level commands ─────────────────────────────────────────────────────

    /**
     * Send a raw command.
     *
     * When [mode] is `"send"`, [data] must contain either:
     *  - `"base64_code"` — a learned raw code in Base64
     *  - `"head"` + `"key"` — a head/key pair
     *
     * All other mode strings (e.g. `"study"`, `"study_exit"`) are forwarded as-is.
     */
    suspend fun sendCommand(mode: String, data: Map<String, String> = emptyMap()) {
        if (mode == "send") {
            when (controlType) {
                1 -> {
                    val command = buildJsonObject {
                        put(RemoteControlDps.NSDP_CONTROL, "send_ir")
                        put(RemoteControlDps.NSDP_TYPE, 0)
                        if (data.containsKey("base64_code")) {
                            put(RemoteControlDps.NSDP_HEAD, "")
                            put(RemoteControlDps.NSDP_KEY1, "1" + data["base64_code"])
                        } else if (data.containsKey("head") && data.containsKey("key")) {
                            put(RemoteControlDps.NSDP_HEAD, data["head"]!!)
                            put(RemoteControlDps.NSDP_KEY1, "0" + data["key"])
                        }
                    }
                    device.setDp(RemoteControlDps.DP_SEND_IR, command.toString())
                }
                2 -> {
                    val sendMode = if (data.containsKey("base64_code")) "study_key" else "send_ir"
                    val dps = buildMap<String, JsonPrimitive> {
                        put(RemoteControlDps.DP_MODE, JsonPrimitive(sendMode))
                        put(RemoteControlDps.DP_CODE_TYPE, JsonPrimitive(0))
                        if (data.containsKey("base64_code")) {
                            put(RemoteControlDps.DP_KEY_STUDY, JsonPrimitive(data["base64_code"]!!))
                        } else if (data.containsKey("head") && data.containsKey("key")) {
                            put(RemoteControlDps.DP_HEAD, JsonPrimitive(data["head"]!!))
                            put(RemoteControlDps.DP_KEY_CODE, JsonPrimitive(data["key"]!!))
                        }
                    }
                    device.setDps(dps)
                }
            }
        } else {
            when (controlType) {
                1 -> {
                    val command = buildJsonObject { put(RemoteControlDps.NSDP_CONTROL, mode) }
                    device.setDp(RemoteControlDps.DP_SEND_IR, command.toString())
                }
                2 -> device.setDp(RemoteControlDps.DP_MODE, mode)
            }
        }
    }

    suspend fun studyStart() = sendCommand("study")
    suspend fun studyEnd()   = sendCommand("study_exit")

    /**
     * Enters study mode and waits up to [timeout] for the user to press a button on a
     * real remote.  Returns the learned code as a Base64 string, or null on timeout.
     */
    suspend fun receiveButton(timeout: Duration = 30.seconds): String? {
        logger.debug { "Receiving IR button (timeout=$timeout)" }
        return waitForLearnedCode(
            device     = device,
            startStudy = ::studyStart,
            endStudy   = ::studyEnd,
            timeout    = timeout,
        )
    }

    /** Send a previously learned button (raw Base64 code). */
    suspend fun sendButton(base64Code: String) {
        logger.debug { "Sending learned IR button: $base64Code" }
        sendCommand("send", mapOf("base64_code" to base64Code))
    }

    /** Send a head/key pair. */
    suspend fun sendKey(head: String, key: String) {
        logger.debug { "Sending IR key: head=$head key=$key" }
        sendCommand("send", mapOf("head" to head, "key" to key))
    }

    // ── Companion: IR-specific pulse/format utilities ─────────────────────────

    companion object {

        /** Timing symbol characters used inside key1 strings (IR-specific). */
        const val KEY1_SYMBOL_LIST = "@#\$%^&*()QWRLTXKVNM{}[]JUP<>|=HS~"

        // ── Pulse ↔ Base64 ───────────────────────────────────────────────────

        /** Decode a Base64-encoded IR code into a list of pulse/gap durations (µs). */
        fun base64ToPulses(base64Code: String): List<Int> {
            val code = if (base64Code.length % 4 == 1 && base64Code.startsWith("1"))
                base64Code.substring(1) else base64Code
            val raw = Base64.getDecoder().decode(code)
            return (raw.indices step 2).map { i ->
                (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
            }
        }

        /** Encode pulse/gap durations (µs) to a Base64 string (little-endian 16-bit words). */
        fun pulsesToBase64(pulses: List<Int>): String {
            val buf = ByteArray(pulses.size * 2)
            pulses.forEachIndexed { i, v ->
                buf[i * 2]     = (v and 0xFF).toByte()
                buf[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return Base64.getEncoder().encodeToString(buf)
        }

        // ── Pulse ↔ HEX ─────────────────────────────────────────────────────

        /** Decode a HEX-encoded IR code (Tuya Cloud API format) into pulses. */
        fun hexToPulses(codeHex: String): List<Int> {
            val raw = codeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return (raw.indices step 2).map { i ->
                (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
            }
        }

        /** Encode pulses into a HEX string (little-endian 16-bit words, lower-case). */
        fun pulsesToHex(pulses: List<Int>): String =
            pulses.joinToString("") { v ->
                val swapped = ((v and 0xFF) shl 8) or ((v shr 8) and 0xFF)
                "%04x".format(swapped)
            }

        // ── Pronto ───────────────────────────────────────────────────────────

        /**
         * Convert a Pronto code string to pulse/gap durations (µs).
         * Only raw (learned, type 0x0000) 38 kHz codes are supported.
         */
        fun prontoPulses(pronto: String): List<Int> {
            val words = pronto.trim().split(Regex("\\s+")).map { it.toInt(16) }
            if (words[0] != 0) return emptyList()
            if (words[1] < 90 || words[1] > 139) return emptyList()
            val timebase = words[1] * 0.241246
            val pair1Len = words[2]
            val pair2Len = words[3]
            val data = words.drop(4)
            val result = mutableListOf<Int>()
            for (i in 0 until pair1Len * 2 step 2)
                result += listOf(round(data[i] * timebase).toInt(), round(data[i + 1] * timebase).toInt())
            val data2 = data.drop(pair1Len * 2)
            for (i in 0 until pair2Len * 2 step 2)
                result += listOf(round(data2[i] * timebase).toInt(), round(data2[i + 1] * timebase).toInt())
            return result
        }

        /** Encode pulses into a Pronto code string (38 kHz only). */
        fun pulsesToPronto(pulses: List<Int>): String {
            val freq = 38000.0
            val scale = 1_000_000.0 / freq
            val header = "%04X %04X %04X %04X".format(0, round(scale / 0.241246).toInt(), 0, pulses.size / 2)
            val body = pulses.joinToString(" ") { "%04X".format(round(it / scale).toInt()) }
            return "$header $body"
        }

        /** Convert a Pronto code string directly to a head/key pair. */
        fun prontoToHeadKey(pronto: String): Pair<String, String>? {
            val words = pronto.trim().split(Regex("\\s+")).map { it.toInt(16) }
            if (words[0] != 0) return null
            val timebase = words[1] * 0.241246
            val freq = round(4_145_152.0 / words[1] / 100) / 10
            val pair1Len = words[2]
            val pair2Len = words[3]
            val data = words.drop(4)
            val pulses = mutableListOf<Int>()
            for (i in 0 until pair1Len * 2 step 2)
                pulses += listOf(round(data[i] * timebase).toInt(), round(data[i + 1] * timebase).toInt())
            val data2 = data.drop(pair1Len * 2)
            for (i in 0 until pair2Len * 2 step 2)
                pulses += listOf(round(data2[i] * timebase).toInt(), round(data2[i + 1] * timebase).toInt())
            return pulsesToHeadKey(pulses, freq = freq.toInt())
        }

        // ── NEC / Samsung ────────────────────────────────────────────────────

        /** Encode a 32-bit NEC code (or 8/16-bit address + 8-bit data) to pulses. */
        fun necToPulses(address: Long, data: Int? = null): List<Int> {
            val uint32 = if (data == null) {
                address
            } else {
                val addr = if (address < 256) {
                    val m = mirrorBits(address.toInt())
                    ((m shl 8) or (m xor 0xFF)).toLong()
                } else {
                    val hi = mirrorBits(((address shr 8) and 0xFF).toInt()).toLong()
                    val lo = mirrorBits((address and 0xFF).toInt()).toLong()
                    (hi shl 8) or lo
                }
                val d = mirrorBits(data)
                (addr shl 16) or ((d shl 8) or (d xor 0xFF)).toLong()
            }
            return widthEncodedToPulses(uint32)
        }

        /**
         * Decode pulses to a list of NEC code results.
         * Each result contains `type`, `uint32`, `address`, `data`, and `hex`.
         */
        fun pulsesToNec(pulses: List<Int>): List<Map<String, Any?>> {
            return pulsesToWidthEncoded(pulses, startMark = 9000, spaceThreshold = 1125).map { code ->
                val addr    = mirrorBits(((code shr 24) and 0xFF).toInt())
                val addrNot = mirrorBits(((code shr 16) and 0xFF).toInt())
                val d       = mirrorBits(((code shr 8) and 0xFF).toInt())
                val dNot    = mirrorBits((code and 0xFF).toInt())
                val finalAddr = if (addr != (addrNot xor 0xFF)) (addr shl 8) or addrNot else addr
                mapOf(
                    "type"    to "nec",
                    "uint32"  to code,
                    "hex"     to "%08X".format(code),
                    "address" to if (d == (dNot xor 0xFF)) finalAddr else null,
                    "data"    to if (d == (dNot xor 0xFF)) d else null,
                )
            }
        }

        /** Encode a Samsung code to pulses (same as NEC but start mark = 4.5 ms). */
        fun samsungToPulses(address: Int, data: Int? = null): List<Int> {
            val uint32 = if (data == null) {
                address.toLong()
            } else {
                val a = mirrorBits(address).toLong()
                val d = mirrorBits(data).toLong()
                (a shl 24) or (a shl 16) or (d shl 8) or (d xor 0xFF)
            }
            return widthEncodedToPulses(uint32, startMark = 4500)
        }

        /** Decode pulses to Samsung code results. */
        fun pulsesToSamsung(pulses: List<Int>): List<Map<String, Any?>> {
            return pulsesToWidthEncoded(pulses, startMark = 4500, spaceThreshold = 1125).map { code ->
                val addr    = ((code shr 24) and 0xFF).toInt()
                val addrNot = ((code shr 16) and 0xFF).toInt()
                val d       = ((code shr 8) and 0xFF).toInt()
                val dNot    = (code and 0xFF).toInt()
                mapOf(
                    "type"    to "samsung",
                    "uint32"  to code,
                    "hex"     to "%08X".format(code),
                    "address" to if (addr == addrNot) mirrorBits(addr) else null,
                    "data"    to if (d == (dNot xor 0xFF)) mirrorBits(d) else null,
                )
            }
        }

        // ── Width-encoded helpers ────────────────────────────────────────────

        /**
         * Encode a 32-bit uint to a pulse train.
         * Default parameters match NEC; pass `startMark = 4500` for Samsung.
         */
        fun widthEncodedToPulses(
            uint32: Long,
            startMark: Int = 9000,
            startSpace: Int = 4500,
            pulseOne: Int = 563,
            pulseZero: Int = 563,
            spaceOne: Int = 1688,
            spaceZero: Int = 563,
            trailingPulse: Int = 563,
            trailingSpace: Int = 30000,
        ): List<Int> {
            val pulses = mutableListOf(startMark, startSpace)
            for (i in 31 downTo 0) {
                if (uint32 and (1L shl i) != 0L) {
                    pulses += pulseOne; pulses += spaceOne
                } else {
                    pulses += pulseZero; pulses += spaceZero
                }
            }
            pulses += trailingPulse
            pulses += trailingSpace
            return pulses
        }

        /**
         * Decode a pulse train to a list of 32-bit unsigned integers.
         * At least one of [pulseThreshold] or [spaceThreshold] must be supplied.
         */
        fun pulsesToWidthEncoded(
            pulses: List<Int>,
            startMark: Int? = null,
            startSpace: Int? = null,
            pulseThreshold: Int? = null,
            spaceThreshold: Int? = null,
        ): List<Long> {
            if (pulses.size < 68) return emptyList()
            if (pulseThreshold == null && spaceThreshold == null) return emptyList()

            var remaining = pulses.toMutableList()
            if (startMark != null) {
                while (remaining.size >= 68 && (remaining[0] < startMark * 0.75 || remaining[0] > startMark * 1.25))
                    remaining.removeAt(0)
            }

            val results = mutableListOf<Long>()
            while (remaining.size >= 68) {
                if (startMark != null && (remaining[0] < startMark * 0.75 || remaining[0] > startMark * 1.25)) break
                if (startSpace != null && (remaining[1] < startSpace * 0.75 || remaining[1] > startSpace * 1.25)) break

                remaining = remaining.drop(2).toMutableList()
                var uint32 = 0L
                for (i in 31 downTo 0) {
                    val pulseMatch = pulseThreshold?.let { if (remaining[0] >= it) 1L else 0L }
                    val spaceMatch = spaceThreshold?.let { if (remaining[1] >= it) 1L else 0L }
                    val bit = when {
                        pulseMatch != null && spaceMatch != null -> {
                            if (pulseMatch != spaceMatch) break
                            spaceMatch
                        }
                        pulseMatch == null -> spaceMatch!!
                        else -> pulseMatch
                    }
                    uint32 = uint32 or (bit shl i)
                    remaining = remaining.drop(2).toMutableList()
                }
                remaining = remaining.drop(2).toMutableList()
                if (uint32 !in results) results += uint32
            }
            return results
        }

        // ── Head / Key ───────────────────────────────────────────────────────

        /**
         * Build the `head` field for a head/key IR command.
         *
         * @param freq Carrier frequency in kHz (default 38)
         * @param timings Additional timing values (µs if [convertTime] is true)
         * @param convertTime When true, timings are in µs and are converted to device units
         */
        fun buildHead(
            freq: Double = 38.0,
            bitTime: Int = 0,
            zeroTime: Int = 0,
            oneTime: Int = 0,
            bitTimeType: Int = 1,
            timings: List<Int> = emptyList(),
            convertTime: Boolean = true,
        ): String {
            val timingsMut = timings.toMutableList()
            val freqScaled = round(freq * 100).toInt()

            var bt = bitTime
            var zt = zeroTime
            var ot = oneTime
            if (bt == 0 && timingsMut.isNotEmpty()) { bt = timingsMut.removeAt(0) }
            if (zt == 0 && timingsMut.isNotEmpty()) { zt = timingsMut.removeAt(0) }
            if (ot == 0 && timingsMut.isNotEmpty()) { ot = timingsMut.removeAt(0) }

            if (convertTime) {
                val timeBase = 100_000.0 / freqScaled
                bt = round(bt / timeBase).toInt()
                zt = round(zt / timeBase).toInt()
                ot = round(ot / timeBase).toInt()
                for (i in timingsMut.indices) timingsMut[i] = round(timingsMut[i] / timeBase).toInt()
            }

            var head = "%02X%04X0000000000".format(bitTimeType, freqScaled)
            head += "%02X%04X%04X%04X".format(timingsMut.size + 3, bt, zt, ot)
            for (t in timingsMut) head += "%04X".format(t)
            return head
        }

        /**
         * Convert a head/key pair to a list of pulse/gap durations (µs).
         * If [head] is null, [key] is treated as a raw Base64 code.
         */
        fun headKeyToPulses(head: String?, key: String): List<Int> {
            if (key.length < 4) throw IllegalArgumentException("key must be at least 4 characters")
            if (head == null) return base64ToPulses(key)
            if (head.length < 18) throw IllegalArgumentException("head must be at least 18 characters")

            val headBytes = head.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val headType   = headBytes[0].toInt() and 0xFF
            val timescale  = ((headBytes[1].toInt() and 0xFF) shl 8) or (headBytes[2].toInt() and 0xFF)
            val numTimings = ((headBytes[7].toInt() and 0xFF) shl 8) or (headBytes[8].toInt() and 0xFF)
            val timeBase   = 100_000.0 / timescale
            val symbols    = KEY1_SYMBOL_LIST.substring(0, numTimings)

            @Suppress("UNUSED_VARIABLE")
            val repeat = try { key.substring(0, 2).toInt(16) } catch (e: Exception) {
                throw IllegalArgumentException("First 2 digits of key must be a hex byte")
            }
            var keyStr = key.substring(2)

            if (head.length != (numTimings * 2 + 9) * 2)
                throw IllegalArgumentException("head must be ${(numTimings * 2 + 9) * 2} characters")

            val timingValues = (0 until numTimings).map { i ->
                val off = 9 + i * 2
                val t = ((headBytes[off].toInt() and 0xFF) shl 8) or (headBytes[off + 1].toInt() and 0xFF)
                symbols[i] to round(timeBase * t).toInt()
            }.toMap()

            val bitTimings = when (headType) {
                1 -> Pair("@@", "@#")
                2 -> Pair("@#", "@\$")
                else -> throw IllegalArgumentException("Unhandled head type: $headType")
            }

            val expanded = StringBuilder()
            while (keyStr.isNotEmpty()) {
                var cnt = 0
                for (c in keyStr) {
                    if (c !in symbols) break
                    expanded.append(c)
                    cnt++
                }
                keyStr = keyStr.drop(cnt)
                if (keyStr.isEmpty()) break

                val b0 = keyStr.substring(0, 2).toInt(16)
                val b1 = keyStr.substring(2, 4).toInt(16)
                keyStr = keyStr.drop(4)

                var bits: Int
                var dataStr: String
                var dataCnt: Int

                if (b0 != 0) {
                    cnt = 0; bits = 0; dataStr = ""
                    for (c in keyStr) {
                        val c2 = c.uppercaseChar().code
                        if (c2 < 0x30 || c2 > 0x46 || (c2 > 0x39 && c2 < 0x41)) break
                        dataStr += c; cnt++; bits += 4
                    }
                    if (dataStr.length % 2 == 1) dataStr += "0"
                    dataCnt = cnt
                } else {
                    bits = b1
                    val byteCount = (bits + 7) / 8
                    dataCnt = byteCount * 2
                    dataStr = keyStr.substring(0, dataCnt)
                }

                keyStr = keyStr.drop(dataCnt)
                var remaining = bits
                var dataBytes = dataStr.chunked(2).map { it.toInt(16) }
                while (remaining > 0) {
                    var d = dataBytes[0]; dataBytes = dataBytes.drop(1)
                    for (i in 0 until 8) {
                        if (remaining == 0) break
                        expanded.append(if ((d and 0x80) != 0) bitTimings.second else bitTimings.first)
                        d = (d shl 1) and 0xFF
                        remaining--
                    }
                }
            }

            return expanded.map { timingValues[it]!! }
        }

        /**
         * Convert pulses to a head/key pair using symbol-pattern analysis.
         * Returns a (head, key) pair or null if conversion fails.
         */
        fun pulsesToHeadKey(pulses: List<Int>, fudge: Double = 0.1, freq: Int = 38): Pair<String, String>? {
            if (pulses.size < 2) return null

            val pulsesMut = if (pulses.size % 2 == 1) pulses + listOf(pulses[0]) else pulses

            val psCount = mutableMapOf<Int, Int>()
            for (t in pulsesMut) psCount[t] = (psCount[t] ?: 0) + 1
            val psMap = mergeSimilarPulseTimes(psCount, fudge)

            val pKeyMap = mutableMapOf<Int, MutableMap<String, Any>>()
            val sKeyMap = mutableMapOf<Int, MutableMap<String, Any>>()
            val symbolList = mutableMapOf<Char, MutableList<Any>>()
            val symbolPattern = StringBuilder()
            var isPulse = false

            for (rawT in pulsesMut) {
                isPulse = !isPulse
                val t = psMap[rawT] ?: rawT
                if (isPulse) {
                    if (!pKeyMap.containsKey(t)) {
                        val letter = ('A' + pKeyMap.size)
                        pKeyMap[t] = mutableMapOf("count" to 1, "char" to letter.toString())
                        if (letter !in symbolList) symbolList[letter] = mutableListOf(t, false)
                    } else {
                        pKeyMap[t]!!["count"] = (pKeyMap[t]!!["count"] as Int) + 1
                    }
                    symbolPattern.append((pKeyMap[t]!!["char"] as String)[0])
                } else {
                    if (!sKeyMap.containsKey(t)) {
                        val letter = ('a' + sKeyMap.size)
                        sKeyMap[t] = mutableMapOf("count" to 1, "char" to letter.toString())
                        if (letter !in symbolList) symbolList[letter] = mutableListOf(t, false)
                    } else {
                        sKeyMap[t]!!["count"] = (sKeyMap[t]!!["count"] as Int) + 1
                    }
                    symbolPattern.append((sKeyMap[t]!!["char"] as String)[0])
                }
            }

            val pmax = pKeyMap.maxByOrNull { it.value["count"] as Int }
            val smax = sKeyMap.maxByOrNull { it.value["count"] as Int }
            if (pmax == null || smax == null) return null

            val pulseLetter = (pmax.value["char"] as String)[0]
            val spaceLetter = (smax.value["char"] as String)[0]

            val encodingResults      = Array<String?>(2) { null }
            val encodingSymbolLists  = Array<Map<Char, Int>>(2) { emptyMap() }
            val bitTimeTypes         = IntArray(2)

            for (encodingType in 0..1) {
                for (k in symbolList.keys) symbolList[k]!![1] = false

                val currentLetter = if (encodingType == 0) pulseLetter else spaceLetter
                val patCounts = mutableMapOf<String, Int>()
                for (i in encodingType until symbolPattern.length step 2) {
                    val k = symbolPattern.substring(i, minOf(i + 2, symbolPattern.length))
                    if (k.length == 2 && k[0] == currentLetter)
                        patCounts[k] = (patCounts[k] ?: 0) + 1
                }

                val patMax     = patCounts.maxByOrNull { it.value }?.toPair() ?: continue
                val patNextMax = patCounts.filter { it.key != patMax.first }.maxByOrNull { it.value }?.toPair()

                val zeroSymbol: String
                val oneSymbol: String

                if (patNextMax == null) {
                    symbolList[patMax.first[0]]!![1] = '@'
                    symbolList[patMax.first[1]]!![1] = '#'
                    zeroSymbol = patMax.first
                    oneSymbol = "DEADBEEF"
                } else {
                    val a  = patMax.first[0];     val b  = patNextMax.first[0]
                    val a2 = patMax.first[1];     val b2 = patNextMax.first[1]
                    val timeA  = symbolList[a]!![0] as Int
                    val timeB  = symbolList[b]!![0] as Int
                    val timeA2 = symbolList[a2]!![0] as Int
                    val timeB2 = symbolList[b2]!![0] as Int

                    if (timeA == timeB) {
                        symbolList[a]!![1] = '@'; symbolList[b]!![1] = '@'
                        if (timeA2 < timeB2) {
                            symbolList[a2]!![1] = '#'; symbolList[b2]!![1] = '$'
                            zeroSymbol = patMax.first; oneSymbol = patNextMax.first
                        } else {
                            symbolList[a2]!![1] = '$'; symbolList[b2]!![1] = '#'
                            zeroSymbol = patNextMax.first; oneSymbol = patMax.first
                        }
                    } else {
                        if (timeA < timeB) {
                            symbolList[a]!![1] = '#'; symbolList[b]!![1] = '$'
                            zeroSymbol = patMax.first; oneSymbol = patNextMax.first
                        } else {
                            symbolList[a]!![1] = '$'; symbolList[b]!![1] = '#'
                            zeroSymbol = patNextMax.first; oneSymbol = patMax.first
                        }
                        val sa = symbolList[a2]!![0] as Int
                        val sb = symbolList[b2]!![0] as Int
                        if (sa == sb) {
                            symbolList[a2]!![1] = '@'; symbolList[b2]!![1] = '@'
                        } else {
                            symbolList[a2]!![1] = '@'
                        }
                    }
                }

                var bitStartSymbol: Char? = null; var bitZeroSymbol: Char? = null
                for ((k, v) in symbolList) {
                    if (v[1] == '@') bitStartSymbol = k
                    else if (v[1] == '#') bitZeroSymbol = k
                }
                val symbolsAvailable = KEY1_SYMBOL_LIST.drop(2).toMutableList()

                if (bitStartSymbol != null && bitZeroSymbol != null &&
                    (symbolList[bitStartSymbol]!![0] as Int) == (symbolList[bitZeroSymbol]!![0] as Int)) {
                    bitTimeTypes[encodingType] = 1
                    symbolList[bitZeroSymbol]!![1] = '@'
                    for ((_, v) in symbolList) if (v[1] == '$') v[1] = '#'
                } else {
                    bitTimeTypes[encodingType] = 2
                }

                val timeSymbols = mutableMapOf<Int, Char>()
                for ((k, v) in symbolList) if (v[1] != false) timeSymbols[v[0] as Int] = v[1] as Char
                var needAbort = false
                for ((k, v) in symbolList) {
                    if (v[1] == false) {
                        val t = v[0] as Int
                        if (t in timeSymbols) { v[1] = timeSymbols[t]!!; continue }
                        if (symbolsAvailable.isEmpty()) { needAbort = true; break }
                        val s = symbolsAvailable.removeAt(0)
                        v[1] = s; timeSymbols[t] = s
                    }
                }
                if (needAbort) continue

                val rawPattern = symbolPattern.map { symbolList[it]!![1] as Char }.joinToString("")

                val bitPattern = StringBuilder()
                val fullPattern = symbolPattern.toString()
                var bits = 0; var bitData = 0; val byts = mutableListOf<Int>()
                var removed = ""

                if (encodingType == 1 && fullPattern.isNotEmpty())
                    bitPattern.append(symbolList[fullPattern[0]]!![1] as Char)

                for (i in (if (encodingType == 1) 1 else 0) until fullPattern.length + 2 step 2) {
                    val k = if (i < fullPattern.length) fullPattern.substring(i, minOf(i + 2, fullPattern.length)) else ""
                    val kSym = k.map { symbolList[it]!![1] as Char }.joinToString("")
                    when {
                        k == zeroSymbol -> {
                            removed += kSym; bits++
                            if (bits == 8) { byts += bitData; bits = 0; bitData = 0 }
                        }
                        k == oneSymbol -> {
                            removed += kSym; bits++
                            bitData = bitData or (1 shl (8 - bits))
                            if (bits == 8) { byts += bitData; bits = 0; bitData = 0 }
                        }
                        else -> {
                            if (bits > 0 || byts.isNotEmpty()) {
                                val newBitfield = buildKeyBitfield(bits, bitData, byts)
                                bitPattern.append(if (newBitfield.length < removed.length) newBitfield else removed)
                            }
                            bits = 0; bitData = 0; byts.clear(); removed = ""
                            bitPattern.append(kSym)
                        }
                    }
                }

                val finalPattern = if (bitPattern.length > rawPattern.length) rawPattern else bitPattern.toString()
                encodingResults[encodingType] = finalPattern
                encodingSymbolLists[encodingType] = symbolList
                    .entries
                    .filter { it.value[1] != false }
                    .associate { e -> (e.value[1] as Char) to (e.value[0] as Int) }
            }

            val (bestPattern, bestSymbols, bestBitTimeType) = when {
                encodingResults[0] == null -> Triple(encodingResults[1], encodingSymbolLists[1], bitTimeTypes[1])
                encodingResults[1] == null -> Triple(encodingResults[0], encodingSymbolLists[0], bitTimeTypes[0])
                encodingResults[0]!!.length <= encodingResults[1]!!.length ->
                    Triple(encodingResults[0], encodingSymbolLists[0], bitTimeTypes[0])
                else -> Triple(encodingResults[1], encodingSymbolLists[1], bitTimeTypes[1])
            }
            if (bestPattern == null) return null

            val timeSymbols = mutableListOf<Int>()
            for (c in KEY1_SYMBOL_LIST) {
                if (c in bestSymbols) timeSymbols += bestSymbols[c]!!
                else if (timeSymbols.size < 3) timeSymbols += 100
                else break
            }

            val header = buildHead(freq = freq.toDouble(), bitTimeType = bestBitTimeType, timings = timeSymbols)
            return Pair(header, "01$bestPattern")
        }

        // ── Pretty-print ─────────────────────────────────────────────────────

        /** Return a human-readable description of pulses decoded from [base64Code]. */
        fun printPulses(base64Code: String): String {
            val pulses = base64ToPulses(base64Code)
            return "Pulses and gaps (µs): " + pulses.mapIndexed { i, t ->
                "${if (i % 2 == 0) "p" else "g"}$t"
            }.joinToString(" ")
        }

        // ── Private helpers ───────────────────────────────────────────────────

        private fun mirrorBits(data: Int, bits: Int = 8): Int {
            var out = 0; var shift = bits - 1
            for (i in 0 until bits) { if (data and (1 shl i) != 0) out = out or (1 shl shift); shift-- }
            return out
        }

        private fun mergeSimilarPulseTimes(count: MutableMap<Int, Int>, fudge: Double): Map<Int, Int> {
            val pMap = mutableMapOf<Int, Int>()
            var mod = true
            while (mod) {
                mod = false
                var merge: Pair<Int, Int>? = null
                outer@ for (a in count.keys) {
                    val pfudge = a * fudge
                    for (b in count.keys) {
                        if (a == b) continue
                        if (b >= a - pfudge && b <= a + pfudge) { merge = Pair(a, b); break@outer }
                    }
                }
                if (merge != null) {
                    mod = true
                    val (a, b) = merge
                    val newCount = (count[a] ?: 0) + (count[b] ?: 0)
                    val newP = (a + b) / 2
                    count.remove(a); count.remove(b); count[newP] = newCount
                    pMap[a] = newP; pMap[b] = newP
                    for (k in pMap.keys) { if (pMap[k] == a || pMap[k] == b) pMap[k] = newP }
                }
            }
            return pMap
        }

        private fun buildKeyBitfield(bits: Int, bitData: Int, byts: List<Int>): String {
            val numBits = bits + byts.size * 8
            var result = "%02X%02X".format(0, numBits)
            for (b in byts) result += "%02X".format(b)
            if (bits > 0) result += "%02X".format(bitData)
            return result
        }
    }
}
