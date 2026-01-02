package io.github.andresviedma.tinytuya.device

import io.github.andresviedma.tinytuya.model.DeviceStatus
import io.github.andresviedma.tinytuya.model.buildDps
import io.github.andresviedma.tinytuya.protocol.TuyaProtocolVersion
import kotlin.math.roundToInt

/**
 * Represents a Tuya smart bulb/light device.
 *
 * Standard DPS (Data Points):
 * - 1: Power state (on/off)
 * - 2: Mode (white/colour/scene)
 * - 3: Brightness (10-1000)<
 * - 4: Color temperature (0-1000)
 * - 5: Color (HSV format: hhhhssssvvvv in hex)
 * - 6: Scene
 *
 * @param deviceId The device ID
 * @param localKey The local encryption key
 * @param host The device IP address
 * @param port The device port
 * @param version The protocol version
 */
class BulbDevice(
    deviceId: String,
    localKey: String,
    host: String,
    port: Int = 6668,
    version: TuyaProtocolVersion = TuyaProtocolVersion.V3_3
) : TuyaDevice(deviceId, localKey, host, port, version) {

    companion object {
        const val DP_POWER = "1"
        const val DP_MODE = "2"
        const val DP_BRIGHTNESS = "3"
        const val DP_COLOR_TEMP = "4"
        const val DP_COLOR = "5"
        const val DP_SCENE = "6"

        const val MODE_WHITE = "white"
        const val MODE_COLOUR = "colour"
        const val MODE_SCENE = "scene"

        // Brightness range
        const val BRIGHTNESS_MIN = 10
        const val BRIGHTNESS_MAX = 1000

        // Color temperature range
        const val COLOR_TEMP_MIN = 0
        const val COLOR_TEMP_MAX = 1000
    }

    /**
     * Check if bulb is currently on
     */
    fun isOn(): Boolean {
        return currentStatus().getBoolean(DP_POWER) ?: false
    }

    /**
     * Check if bulb is currently off
     */
    fun isOff(): Boolean {
        return !isOn()
    }

    /**
     * Turn the bulb on
     */
    suspend fun turnOn(): DeviceStatus {
        return setDp(DP_POWER, true)
    }

    /**
     * Turn the bulb off
     */
    suspend fun turnOff(): DeviceStatus {
        return setDp(DP_POWER, false)
    }

    /**
     * Toggle the bulb power state
     */
    suspend fun toggle(): DeviceStatus {
        return if (isOn()) turnOff() else turnOn()
    }

    /**
     * Get current brightness (10-1000)
     */
    fun getBrightness(): Int? {
        return currentStatus().getInt(DP_BRIGHTNESS)
    }

    /**
     * Get current brightness as percentage (0-100)
     */
    fun getBrightnessPercent(): Int? {
        return getBrightness()?.let {
            ((it - BRIGHTNESS_MIN) * 100 / (BRIGHTNESS_MAX - BRIGHTNESS_MIN))
        }
    }

    /**
     * Set brightness (10-1000)
     */
    suspend fun setBrightness(brightness: Int): DeviceStatus {
        val clamped = brightness.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX)
        return setDp(DP_BRIGHTNESS, clamped)
    }

    /**
     * Set brightness as percentage (0-100)
     */
    suspend fun setBrightnessPercent(percent: Int): DeviceStatus {
        val brightness = BRIGHTNESS_MIN + (percent * (BRIGHTNESS_MAX - BRIGHTNESS_MIN) / 100)
        return setBrightness(brightness)
    }

    /**
     * Get current color temperature (0-1000)
     */
    fun getColorTemp(): Int? {
        return currentStatus().getInt(DP_COLOR_TEMP)
    }

    /**
     * Get current color temperature as percentage (0-100, cold to warm)
     */
    fun getColorTempPercent(): Int? {
        return getColorTemp()?.let {
            (it * 100 / COLOR_TEMP_MAX)
        }
    }

    /**
     * Set color temperature (0-1000)
     * 0 = coolest, 1000 = warmest
     */
    suspend fun setColorTemp(temp: Int): DeviceStatus {
        val clamped = temp.coerceIn(COLOR_TEMP_MIN, COLOR_TEMP_MAX)
        val dps = buildDps {
            set(DP_MODE, MODE_WHITE)
            set(DP_COLOR_TEMP, clamped)
        }
        return setDps(dps)
    }

    /**
     * Set color temperature as percentage (0-100)
     */
    suspend fun setColorTempPercent(percent: Int): DeviceStatus {
        val temp = (percent * COLOR_TEMP_MAX / 100)
        return setColorTemp(temp)
    }

    /**
     * RGB color representation
     */
    data class RGB(val red: Int, val green: Int, val blue: Int) {
        init {
            require(red in 0..255) { "Red must be 0-255" }
            require(green in 0..255) { "Green must be 0-255" }
            require(blue in 0..255) { "Blue must be 0-255" }
        }
    }

    /**
     * HSV color representation
     */
    data class HSV(val hue: Int, val saturation: Int, val value: Int) {
        init {
            require(hue in 0..360) { "Hue must be 0-360" }
            require(saturation in 0..100) { "Saturation must be 0-100" }
            require(value in 0..100) { "Value must be 0-100" }
        }

        /**
         * Convert to Tuya HSV format (hhhhssssvvvv)
         */
        fun toTuyaFormat(): String {
            val h = hue.toString().padStart(4, '0')
            val s = (saturation * 10).toString().padStart(4, '0')
            val v = (value * 10).toString().padStart(4, '0')
            return "$h$s$v"
        }

        companion object {
            /**
             * Parse from Tuya HSV format
             */
            fun fromTuyaFormat(hsv: String): HSV? {
                if (hsv.length != 12) return null
                return try {
                    val h = hsv.substring(0, 4).toInt()
                    val s = hsv.substring(4, 8).toInt() / 10
                    val v = hsv.substring(8, 12).toInt() / 10
                    HSV(h, s, v)
                } catch (e: Exception) {
                    null
                }
            }

            /**
             * Convert RGB to HSV
             */
            fun fromRGB(rgb: RGB): HSV {
                val r = rgb.red / 255.0
                val g = rgb.green / 255.0
                val b = rgb.blue / 255.0

                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val delta = max - min

                val h = when {
                    delta == 0.0 -> 0.0
                    max == r -> 60 * (((g - b) / delta) % 6)
                    max == g -> 60 * (((b - r) / delta) + 2)
                    else -> 60 * (((r - g) / delta) + 4)
                }

                val s = if (max == 0.0) 0.0 else (delta / max)
                val v = max

                return HSV(
                    hue = h.roundToInt().coerceIn(0, 360),
                    saturation = (s * 100).roundToInt(),
                    value = (v * 100).roundToInt()
                )
            }
        }
    }

    /**
     * Get current color in HSV format
     */
    fun getColor(): HSV? {
        val colorString = currentStatus().getString(DP_COLOR) ?: return null
        return HSV.fromTuyaFormat(colorString)
    }

    /**
     * Set color using HSV
     */
    suspend fun setColor(hsv: HSV): DeviceStatus {
        val dps = buildDps {
            set(DP_MODE, MODE_COLOUR)
            set(DP_COLOR, hsv.toTuyaFormat())
        }
        return setDps(dps)
    }

    /**
     * Set color using RGB (will be converted to HSV)
     */
    suspend fun setColorRGB(rgb: RGB): DeviceStatus {
        val hsv = HSV.fromRGB(rgb)
        return setColor(hsv)
    }

    /**
     * Set color using RGB values
     */
    suspend fun setColorRGB(red: Int, green: Int, blue: Int): DeviceStatus {
        return setColorRGB(RGB(red, green, blue))
    }

    /**
     * Get current mode
     */
    fun getMode(): String? {
        return currentStatus().getString(DP_MODE)
    }

    /**
     * Set scene
     */
    suspend fun setScene(scene: String): DeviceStatus {
        val dps = buildDps {
            set(DP_MODE, MODE_SCENE)
            set(DP_SCENE, scene)
        }
        return setDps(dps)
    }
}
