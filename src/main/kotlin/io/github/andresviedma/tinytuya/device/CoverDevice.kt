package io.github.andresviedma.tinytuya.device

import io.github.andresviedma.tinytuya.model.DeviceStatus
import io.github.andresviedma.tinytuya.protocol.TuyaProtocolVersion

/**
 * Represents a Tuya smart cover/blind/curtain device.
 *
 * Standard DPS (Data Points):
 * - 1: Control (open/stop/close)
 * - 2: Position (0-100%)
 * - 3: Direction (forward/back)
 * - 5: Mode
 *
 * @param deviceId The device ID
 * @param localKey The local encryption key
 * @param host The device IP address
 * @param port The device port
 * @param version The protocol version
 */
class CoverDevice(
    deviceId: String,
    localKey: String,
    host: String,
    port: Int = 6668,
    version: TuyaProtocolVersion = TuyaProtocolVersion.V3_3
) : TuyaDevice(deviceId, localKey, host, port, version) {

    companion object {
        const val DP_CONTROL = "1"
        const val DP_POSITION = "2"
        const val DP_DIRECTION = "3"
        const val DP_MODE = "5"

        const val COMMAND_OPEN = "open"
        const val COMMAND_STOP = "stop"
        const val COMMAND_CLOSE = "close"
    }

    /**
     * Get current position (0-100%)
     * 0 = fully closed, 100 = fully open
     */
    fun getPosition(): Int? {
        return currentStatus().getInt(DP_POSITION)
    }

    /**
     * Check if cover is fully open
     */
    fun isOpen(): Boolean {
        return getPosition() == 100
    }

    /**
     * Check if cover is fully closed
     */
    fun isClosed(): Boolean {
        return getPosition() == 0
    }

    /**
     * Open the cover
     */
    suspend fun open(): DeviceStatus {
        return setDp(DP_CONTROL, COMMAND_OPEN)
    }

    /**
     * Close the cover
     */
    suspend fun closeCover(): DeviceStatus {
        return setDp(DP_CONTROL, COMMAND_CLOSE)
    }

    /**
     * Stop the cover movement
     */
    suspend fun stop(): DeviceStatus {
        return setDp(DP_CONTROL, COMMAND_STOP)
    }

    /**
     * Set cover position (0-100%)
     */
    suspend fun setPosition(position: Int): DeviceStatus {
        val clamped = position.coerceIn(0, 100)
        return setDp(DP_POSITION, clamped)
    }

    /**
     * Open the cover partially
     */
    suspend fun openPartial(percent: Int): DeviceStatus {
        return setPosition(percent)
    }

    /**
     * Get current direction
     */
    fun getDirection(): String? {
        return currentStatus().getString(DP_DIRECTION)
    }

    /**
     * Get current mode
     */
    fun getMode(): String? {
        return currentStatus().getString(DP_MODE)
    }
}
