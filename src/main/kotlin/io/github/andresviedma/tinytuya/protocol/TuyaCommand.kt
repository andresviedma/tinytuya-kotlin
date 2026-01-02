package io.github.andresviedma.tinytuya.protocol

/**
 * Tuya protocol commands.
 * Each command represents a specific operation that can be performed with a Tuya device.
 */
sealed class TuyaCommand(val code: Int) {
    /**
     * UDP discovery broadcast
     */
    data object UDP : TuyaCommand(0x00)

    /**
     * Access Point configuration
     */
    data object AP_CONFIG : TuyaCommand(0x01)

    /**
     * Query device status
     */
    data object STATUS : TuyaCommand(0x08)

    /**
     * Heartbeat / keep-alive
     */
    data object HEART_BEAT : TuyaCommand(0x09)

    /**
     * Query data points (DPS)
     */
    data object DP_QUERY : TuyaCommand(0x0a)

    /**
     * Query data points with timestamp
     */
    data object DP_QUERY_NEW : TuyaCommand(0x10)

    /**
     * Control command (set DPS values)
     */
    data object CONTROL : TuyaCommand(0x07)

    /**
     * Control command (newer protocol)
     */
    data object CONTROL_NEW : TuyaCommand(0x0d)

    /**
     * Update DPS values
     */
    data object DP_REFRESH : TuyaCommand(0x12)

    /**
     * Update DPS (alternative)
     */
    data object UPDATE_DPS : TuyaCommand(0x12)

    /**
     * Negotiate session key (protocol 3.4+)
     */
    data object SESS_KEY_NEG_START : TuyaCommand(0x03)

    /**
     * Finish session key negotiation (protocol 3.4+)
     */
    data object SESS_KEY_NEG_FINISH : TuyaCommand(0x04)

    /**
     * Session key negotiation response (protocol 3.4+)
     */
    data object SESS_KEY_NEG_RESP : TuyaCommand(0x05)

    /**
     * Local network time query
     */
    data object LAN_GW_ACTIVE : TuyaCommand(0x25)

    /**
     * LAN extension command
     */
    data object LAN_EXT_STREAM : TuyaCommand(0x40)

    data object DISCOVER : TuyaCommand(0x13)


    companion object {
        fun fromCode(code: Int): TuyaCommand? = when (code) {
            0x00 -> UDP
            0x01 -> AP_CONFIG
            0x07 -> CONTROL
            0x08 -> STATUS
            0x09 -> HEART_BEAT
            0x0a -> DP_QUERY
            0x0d -> CONTROL_NEW
            0x10 -> DP_QUERY_NEW
            0x12 -> DP_REFRESH
            0x03 -> SESS_KEY_NEG_START
            0x04 -> SESS_KEY_NEG_FINISH
            0x05 -> SESS_KEY_NEG_RESP
            0x25 -> LAN_GW_ACTIVE
            0x40 -> LAN_EXT_STREAM

            0x13 -> DISCOVER
            else -> null
        }
    }
}
