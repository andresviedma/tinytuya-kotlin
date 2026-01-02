package io.github.andresviedma.tinytuya.protocol

/**
 * Represents the different versions of the Tuya protocol.
 * Each version has different encryption and message format requirements.
 */
sealed class TuyaProtocolVersion(val version: String) {
    /**
     * Protocol 3.1 - Basic encryption, older devices
     */
    data object V3_1 : TuyaProtocolVersion("3.1")

    /**
     * Protocol 3.2 - Added support for additional features
     */
    data object V3_2 : TuyaProtocolVersion("3.2")

    /**
     * Protocol 3.3 - Most common version for modern devices
     */
    data object V3_3 : TuyaProtocolVersion("3.3")

    /**
     * Protocol 3.4 - Enhanced security features
     */
    data object V3_4 : TuyaProtocolVersion("3.4")

    /**
     * Protocol 3.5 - Latest version with improved encryption
     */
    data object V3_5 : TuyaProtocolVersion("3.5")

    inline val numeric: Double get() = version.toDouble()

    companion object {
        fun fromString(version: String): TuyaProtocolVersion = when (version) {
            "3.1" -> V3_1
            "3.2" -> V3_2
            "3.3" -> V3_3
            "3.4" -> V3_4
            "3.5" -> V3_5
            else -> throw IllegalArgumentException("Unknown protocol version: $version")
        }
    }
}
