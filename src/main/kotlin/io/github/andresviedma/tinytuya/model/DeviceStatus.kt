package io.github.andresviedma.tinytuya.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Represents the status of a Tuya device with data points (DPS)
 *
 * @param dps Map of data point IDs to their values
 * @param timestamp Optional timestamp of the status
 */
@Serializable
data class DeviceStatus(
    val dps: Map<String, JsonElement>,
    val timestamp: Long? = null
) {
    /**
     * Get a DPS value as a Boolean
     */
    fun getBoolean(dpId: String): Boolean? {
        return dps[dpId]?.jsonPrimitive?.booleanOrNull
    }

    /**
     * Get a DPS value as an Int
     */
    fun getInt(dpId: String): Int? {
        return dps[dpId]?.jsonPrimitive?.intOrNull
    }

    /**
     * Get a DPS value as a String
     */
    fun getString(dpId: String): String? {
        return dps[dpId]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Get a DPS value as a Double
     */
    fun getDouble(dpId: String): Double? {
        return dps[dpId]?.jsonPrimitive?.doubleOrNull
    }

    /**
     * Get a raw DPS value
     */
    fun get(dpId: String): JsonElement? {
        return dps[dpId]
    }

    /**
     * Check if a DPS exists
     */
    fun has(dpId: String): Boolean {
        return dps.containsKey(dpId)
    }

    /**
     * Get all DPS IDs
     */
    fun dpIds(): Set<String> {
        return dps.keys
    }

    companion object {
        /**
         * Parse device status from JSON string
         */
        fun fromJson(json: String): DeviceStatus {
            val jsonElement = Json.parseToJsonElement(json).jsonObject

            // Handle different response formats
            val dpsObject = when {
                jsonElement.containsKey("dps") -> jsonElement["dps"]?.jsonObject
                jsonElement.containsKey("data") -> {
                    val data = jsonElement["data"]?.jsonObject
                    data?.get("dps")?.jsonObject
                }
                else -> jsonElement
            }

            val dps = dpsObject?.mapValues { it.value } ?: emptyMap()
            val timestamp = jsonElement["t"]?.jsonPrimitive?.longOrNull

            return DeviceStatus(dps, timestamp)
        }

        /**
         * Create empty status
         */
        fun empty(): DeviceStatus {
            return DeviceStatus(emptyMap())
        }
    }
}

/**
 * Builder for creating DPS updates
 */
class DpsBuilder {
    private val dps = mutableMapOf<String, JsonElement>()

    fun set(dpId: String, value: Boolean): DpsBuilder {
        dps[dpId] = JsonPrimitive(value)
        return this
    }

    fun set(dpId: String, value: Int): DpsBuilder {
        dps[dpId] = JsonPrimitive(value)
        return this
    }

    fun set(dpId: String, value: String): DpsBuilder {
        dps[dpId] = JsonPrimitive(value)
        return this
    }

    fun set(dpId: String, value: Double): DpsBuilder {
        dps[dpId] = JsonPrimitive(value)
        return this
    }

    fun build(): Map<String, JsonElement> {
        return dps.toMap()
    }

    fun toJson(): String {
        return Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(dps)
        )
    }
}

/**
 * Helper function to build DPS map
 */
fun buildDps(builder: DpsBuilder.() -> Unit): Map<String, JsonElement> {
    return DpsBuilder().apply(builder).build()
}
