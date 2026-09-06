package io.github.andresviedma.tinytuya.device

import io.github.andresviedma.tinytuya.network.DeviceConnectionConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Tuya WiFi smart universal RF remote control device (sub-GHz, CMT2300A chip).
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
class RFRemoteControlDevice(
    val device: TuyaDevice,
    var controlType: Int = 0,
) : AutoCloseable {

    constructor(config: DeviceConnectionConfig, controlType: Int = 0) : this(TuyaDevice(config), controlType)

    // ── Connection ─────────────────────────────────────────────────────────────

    suspend fun connect(): RFRemoteControlDevice {
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

    // ── RF study session ────────────────────────────────────────────────────────

    /**
     * Enter RF study mode.
     *
     * @param freq Carrier frequency string (e.g. `"433"`, `"315"`). Pass `"0"` (default)
     *             to let the device auto-detect the frequency.
     * @param short When true, uses the `rf_shortstudy` command instead of `rf_study`.
     */
    suspend fun rfStudyStart(freq: String = "0", short: Boolean = false) {
        val mode = if (short) "rf_shortstudy" else "rf_study"
        sendCommand(mode, mapOf("freq" to freq))
    }

    /**
     * Exit RF study mode.
     *
     * @param freq Must match the frequency used in [rfStudyStart].
     * @param short Must match the [short] flag used in [rfStudyStart].
     */
    suspend fun rfStudyEnd(freq: String = "0", short: Boolean = false) {
        val mode = if (short) "rfshortstudy_exit" else "rfstudy_exit"
        sendCommand(mode, mapOf("freq" to freq))
    }

    /**
     * Enters RF study mode and waits up to [timeout] for the user to press a button on a
     * real remote.  Returns the learned code as a Base64 string, or null on timeout.
     *
     * @param freq Carrier frequency string (pass `"0"` to auto-detect).
     */
    suspend fun rfReceiveButton(freq: String = "0", timeout: Duration = 30.seconds): String? {
        logger.debug { "Receiving RF button (freq=$freq, timeout=$timeout)" }
        return waitForLearnedCode(
            device     = device,
            startStudy = { rfStudyStart(freq) },
            endStudy   = { rfStudyEnd(freq) },
            timeout    = timeout,
        )
    }

    /**
     * Enters RF study mode and waits up to [timeout] for the user to press a button on a
     * real remote.  Returns the learned code as a Base64 string, or null on timeout.
     *
     * @param freq Carrier frequency string (pass `"0"` to auto-detect).
     */
    suspend fun rfReceiveButtons(freq: String = "0", timeout: Duration = 30.seconds): List<String> {
        logger.debug { "Receiving RF buttons (freq=$freq, timeout=$timeout)" }
        rfStudyEnd(freq)
        rfStudyStart(freq)
        val result = mutableSetOf<String>()
        try {
            device.pollStatus(interval = 50.milliseconds, duration = timeout).collect { status ->
                val code = when {
                    status.has(RemoteControlDps.DP_LEARNED_ID)     -> status.getString(RemoteControlDps.DP_LEARNED_ID)
                    status.has(RemoteControlDps.DP_LEARNED_REPORT) -> status.getString(RemoteControlDps.DP_LEARNED_REPORT)
                    else -> null
                }
                if (code != null && !result.contains(code)) {
                    result += code
                    println("**** DETECTED: $code")
                }
            }
        } finally {
            rfStudyEnd(freq)
        }
        return result.toList()
    }

    /**
     * Replay a previously learned RF button.
     *
     * @param base64Code The Base64 string returned by [rfReceiveButton].
     * @param times Number of times to transmit the code (default 6).
     * @param delay Delay between transmissions in milliseconds (default 0).
     * @param intervals Inter-frame interval in milliseconds (default 0).
     */
    suspend fun rfSendButton(base64Code: String, times: Int = 6, delay: Int = 0, intervals: Int = 0) {
        logger.debug { "Sending learned RF button: $base64Code" }
        val bdata = rfDecodeButton(base64Code)
        val key1 = mutableMapOf(
            "code"      to base64Code,
            "times"     to times,
            "delay"     to delay,
            "intervals" to intervals,
        )
        val data = mutableMapOf<String, Any>("key1" to key1)
        if (bdata != null && bdata.containsKey("ver")) {
            // feq=0: device uses the frequency embedded in the code itself
            data["ver"] = bdata["ver"]!!
        }
        sendCommand("rfstudy_send", data)
    }

    /**
     * Send one or more pre-defined RF keys using CMOSTEK CMT2300A register banks.
     *
     * The bank arrays are copied directly from CMOSTEK's RFPDK software
     * (select chip "CMT2300A").
     *
     * [keys] may be:
     *  - A `Map` with `"code"`, `"delay"`, `"intervals"`, `"times"` entries.
     *  - A hex string (shorthand; delay/intervals/times default to 0/0/5).
     *  - A `List` of the above.
     *
     * @param cmtBank      12-element list (CMT register bank "c")
     * @param systemBank   12-element list (System register bank "s")
     * @param frequencyBank 8-element list (Frequency register bank "f")
     * @param datarateBank 24-element list (Data Rate register bank "d")
     * @param basebandBank 29-element list (Baseband register bank "b")
     * @param txBank       11-element list (TX register bank "t")
     * @param mode         Transmission mode (default 8)
     * @param freq         Frequency override; 0 = use frequency bank (default 0)
     * @param rate         Data rate override; 0 = use datarate bank (default 0)
     */
    suspend fun rfSendKey(
        keys: Any,
        cmtBank: List<Int>,
        systemBank: List<Int>,
        frequencyBank: List<Int>,
        datarateBank: List<Int>,
        basebandBank: List<Int>,
        txBank: List<Int>,
        mode: Int = 8,
        freq: Int = 0,
        rate: Int = 0,
    ) {
        require(cmtBank.size == 12)      { "CMT Bank list size must be 12" }
        require(systemBank.size == 12)   { "System Bank list size must be 12" }
        require(frequencyBank.size == 8) { "Frequency Bank list size must be 8" }
        require(datarateBank.size == 24) { "Data Rate Bank list size must be 24" }
        require(basebandBank.size == 29) { "Baseband Bank list size must be 29" }
        require(txBank.size == 11)       { "TX Bank list size must be 11" }

        val data = mutableMapOf<String, Any>()
        val defaultKey = mapOf("delay" to 0, "intervals" to 0, "times" to 5)

        fun addKey(index: Int, k: Any) {
            val keyName = "key$index"
            val entry: MutableMap<String, Any> = when (k) {
                is Map<*, *> -> k.entries.associate { (a, b) -> a.toString() to (b ?: 0) }.toMutableMap()
                is String    -> mutableMapOf("code" to k, "delay" to 0, "intervals" to 0, "times" to 5)
                else         -> throw IllegalArgumentException("rfSendKey: unknown key type ${k::class}")
            }
            for ((dk, dv) in defaultKey) {
                if (entry[dk] !is Int) entry[dk] = dv
            }
            data[keyName] = entry
        }

        when (keys) {
            is Map<*, *> -> addKey(1, keys)
            is String    -> addKey(1, keys)
            is List<*>   -> keys.forEachIndexed { i, k -> addKey(i + 1, k!!) }
            else         -> throw IllegalArgumentException("rfSendKey: unknown keys type ${keys::class}")
        }

        data["rf_type"] = "sub_2g"
        data["mode"]    = mode
        data["feq"]     = freq
        data["rate"]    = rate
        data["cfg"] = mapOf(
            "c" to cmtBank,
            "s" to systemBank,
            "f" to frequencyBank,
            "d" to datarateBank,
            "b" to basebandBank,
            "t" to txBank,
        )

        logger.info { "Sending RF keys: $data" }
        sendCommand("send_cmd", data)
    }

    // ── Internal command dispatcher ─────────────────────────────────────────────

    /**
     * Low-level command dispatcher.  Handles RF-specific modes; all other modes are
     * forwarded as IR-style commands (setting [RemoteControlDps.DP_MODE] or
     * [RemoteControlDps.DP_SEND_IR] directly).
     *
     * RF-specific modes: `rf_study`, `rfstudy_exit`, `rfstudy_send`,
     * `rf_shortstudy`, `rfshortstudy_exit`, `send_cmd`.
     */
    private suspend fun sendCommand(mode: String, data: Map<String, Any> = emptyMap()) {
        when (mode) {
            "rf_study", "rfstudy_exit", "rf_shortstudy", "rfshortstudy_exit" -> {
                val rfType = (data["rf_type"] as? String) ?: "sub_2g"
                val freq   = (data["freq"]    as? String) ?: "0"
                val ver    = (data["ver"]     as? String) ?: "2"
                val command = mutableMapOf<String, Any>(
                    RemoteControlDps.NSDP_CONTROL to mode,
                    "rf_type"    to rfType,
                    "study_feq"  to freq,
                    "ver"        to ver,
                )
                device.setDp(RemoteControlDps.DP_SEND_IR, jsonStringOf(command))
            }

            "rfstudy_send" -> {
                val rfType = (data["rf_type"] as? String) ?: "sub_2g"
                val freq   = (data["freq"]    as? String)?.let { it.toIntOrNull() } ?: 0
                val sendMode = (data["mode"]  as? Int)    ?: 0
                val sendRate = (data["rate"]  as? Int)    ?: 0
                val ver    = (data["ver"]     as? String) ?: "2"
                val command = mutableMapOf<String, Any>(
                    RemoteControlDps.NSDP_CONTROL to mode,
                    "rf_type" to rfType,
                    "feq"     to freq,
                    "mode"    to sendMode,
                    "rate"    to sendRate,
                    "ver"     to ver,
                )
                for (i in 1..9) {
                    val k = "key$i"
                    if (data.containsKey(k)) {
                        @Suppress("UNCHECKED_CAST")
                        val keyData = (data[k] as Map<String, Any>).toMutableMap()
                        if (!keyData.containsKey("ver")) keyData["ver"] = ver
                        command[k] = keyData
                    }
                }
                device.setDp(RemoteControlDps.DP_SEND_IR, jsonStringOf(command))
            }

            "send_cmd" -> {
                val command = data.toMutableMap()
                command[RemoteControlDps.NSDP_CONTROL] = mode
                device.setDp(RemoteControlDps.DP_SEND_IR, jsonStringOf(command))
            }

            else -> {
                // IR-style fallback: forward as a plain mode string
                when (controlType) {
                    1 -> {
                        val command = mapOf<String, Any>(RemoteControlDps.NSDP_CONTROL to mode)
                        device.setDp(RemoteControlDps.DP_SEND_IR, jsonStringOf(command))
                    }
                    2 -> device.setDp(RemoteControlDps.DP_MODE, mode)
                }
            }
        }
    }

    // ── Companion: RF-specific decode utilities ─────────────────────────────────

    companion object {

        /**
         * Decode a Base64-encoded learned RF button into its raw JSON bytes.
         * Returns the JSON as a [ByteArray], or null if decoding fails.
         */
        fun rfPrintButton(base64Code: String): ByteArray? {
            return try {
                val bytes = Base64.getDecoder().decode(base64Code)
                logger.debug { "Learned RF button: ${String(bytes)}" }
                bytes
            } catch (e: Exception) {
                logger.debug { "Failed to decode learned RF button: $base64Code" }
                null
            }
        }

        /**
         * Decode a Base64-encoded learned RF button into a parsed JSON map.
         * Returns null if the string cannot be decoded or is not valid JSON.
         */
        fun rfDecodeButton(base64Code: String): Map<String, Any?>? {
            return try {
                val json = String(Base64.getDecoder().decode(base64Code))
                val element = Json.parseToJsonElement(json) as? JsonObject ?: return null
                element.entries.associate { (k, v) -> k to v.toString().trim('"') }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /** Serialize a Map to a compact JSON string (handles nested maps and lists). */
    private fun jsonStringOf(map: Map<String, Any>): String = buildString {
        append('{')
        map.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append('"'); append(k); append('"'); append(':')
            appendJsonValue(v)
        }
        append('}')
    }

    private fun StringBuilder.appendJsonValue(v: Any?) {
        when (v) {
            null       -> append("null")
            is Boolean -> append(v)
            is Int     -> append(v)
            is Long    -> append(v)
            is Double  -> append(v)
            is String  -> { append('"'); append(v.replace("\"", "\\\"")); append('"') }
            is Map<*, *> -> {
                append('{')
                v.entries.forEachIndexed { i, (mk, mv) ->
                    if (i > 0) append(',')
                    append('"'); append(mk); append('"'); append(':')
                    appendJsonValue(mv)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                v.forEachIndexed { i, item -> if (i > 0) append(','); appendJsonValue(item) }
                append(']')
            }
            else -> { append('"'); append(v.toString()); append('"') }
        }
    }
}
