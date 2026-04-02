package io.github.andresviedma.tinytuya.device

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Probes a remote control device to determine whether it is control type 1 (DPS 201/202)
 * or type 2 (DPS 1–13) using IR-style study-exit commands, which are the same for both
 * IR and RF devices during detection.
 *
 * Returns 1, 2, or 0 (unknown).
 */
internal suspend fun detectRemoteControlType(device: TuyaDevice): Int {
    // Send IR-style "study_exit" on both DPS layouts to provoke a status response.
    // Type 1: DPS "201" expects a JSON string  {"control": "study_exit"}
    runCatching {
        val cmd = buildJsonObject { put(RemoteControlDps.NSDP_CONTROL, "study_exit") }
        device.setDp(RemoteControlDps.DP_SEND_IR, cmd.toString())
    }
    // Type 2: DPS "1" expects the mode string directly
    runCatching { device.setDp(RemoteControlDps.DP_MODE, "study_exit") }

    val status = runCatching { device.refresh() }.getOrNull()
    return when {
        status == null -> 0
        status.has(RemoteControlDps.DP_SEND_IR) -> {
            logger.debug { "Detected remote control type 1 (DPS 201/202)" }
            1
        }
        status.has(RemoteControlDps.DP_MODE) -> {
            logger.debug { "Detected remote control type 2 (DPS 1–13)" }
            2
        }
        else -> {
            logger.warn { "Could not detect remote control type; set controlType manually" }
            0
        }
    }
}

/**
 * Enters study mode, waits for the user to press a button on a real remote, and returns
 * the learned code (Base64 string), or null on timeout.
 *
 * The [startStudy] and [endStudy] lambdas are supplied by the caller so the same logic
 * works for both IR-mode and RF-mode study sessions.
 */
internal suspend fun waitForLearnedCode(
    device: TuyaDevice,
    startStudy: suspend () -> Unit,
    endStudy: suspend () -> Unit,
    timeout: Duration,
): String? {
    endStudy()
    startStudy()
    return try {
        withTimeout(timeout) {
            device.status.collect { status ->
                val code = when {
                    status.has(RemoteControlDps.DP_LEARNED_ID)     -> status.getString(RemoteControlDps.DP_LEARNED_ID)
                    status.has(RemoteControlDps.DP_LEARNED_REPORT) -> status.getString(RemoteControlDps.DP_LEARNED_REPORT)
                    else -> null
                }
                if (code != null) throw LearnedCodeReceived(code)
            }
            null
        }
    } catch (e: LearnedCodeReceived) {
        logger.debug { "Learned code received" }
        e.code
    } catch (e: TimeoutCancellationException) {
        logger.debug { "waitForLearnedCode timed out" }
        null
    } finally {
        endStudy()
    }
}

/** Used internally to break out of the status-collect loop once a learned code arrives. */
internal class LearnedCodeReceived(val code: String) : Exception()
