package io.github.andresviedma.tinytuya.device

import io.github.andresviedma.tinytuya.model.DeviceStatus
import io.github.andresviedma.tinytuya.network.DeviceConnectionConfig

/**
 * Represents a Tuya smart outlet/plug device.
 *
 * Standard DPS (Data Points):
 * - 1: Power state (on/off)
 * - 2: Countdown timer (seconds)
 * - 4: Current (mA)
 * - 5: Power (W)
 * - 6: Voltage (V)
 */
class OutletDevice(
    config: DeviceConnectionConfig,
) : TuyaDevice(config) {

    companion object {
        const val DP_POWER = "1"
        const val DP_COUNTDOWN = "2"
        const val DP_CURRENT = "4"
        const val DP_POWER_CONSUMPTION = "5"
        const val DP_VOLTAGE = "6"
    }

    /**
     * Check if outlet is currently on
     */
    fun isOn(): Boolean {
        return currentStatus().getBoolean(DP_POWER) ?: false
    }

    /**
     * Check if outlet is currently off
     */
    fun isOff(): Boolean {
        return !isOn()
    }

    /**
     * Turn the outlet on
     */
    suspend fun turnOn(): DeviceStatus {
        return setDp(DP_POWER, true)
    }

    /**
     * Turn the outlet off
     */
    suspend fun turnOff(): DeviceStatus {
        return setDp(DP_POWER, false)
    }

    /**
     * Toggle the outlet power state
     */
    suspend fun toggle(): DeviceStatus {
        return if (isOn()) turnOff() else turnOn()
    }

    /**
     * Set countdown timer (in seconds)
     */
    suspend fun setCountdown(seconds: Int): DeviceStatus {
        return setDp(DP_COUNTDOWN, seconds)
    }

    /**
     * Get current consumption in mA
     */
    fun getCurrent(): Int? {
        return currentStatus().getInt(DP_CURRENT)
    }

    /**
     * Get power consumption in W (0.1W units)
     */
    fun getPower(): Double? {
        return currentStatus().getInt(DP_POWER_CONSUMPTION)?.let { it / 10.0 }
    }

    /**
     * Get voltage in V (0.1V units)
     */
    fun getVoltage(): Double? {
        return currentStatus().getInt(DP_VOLTAGE)?.let { it / 10.0 }
    }

    /**
     * Get energy metrics (current, power, voltage)
     */
    data class EnergyMetrics(
        val current: Int?,
        val power: Double?,
        val voltage: Double?
    )

    /**
     * Get all energy metrics at once
     */
    fun getEnergyMetrics(): EnergyMetrics {
        return EnergyMetrics(
            current = getCurrent(),
            power = getPower(),
            voltage = getVoltage()
        )
    }
}
