package io.github.andresviedma.tinytuya.protocol

/**
 * Tuya protocol commands.
 * Each command represents a specific operation that can be performed with a Tuya device.
 */
enum class TuyaCommand(val code: Int) {
    /**
     * UDP discovery broadcast
     */
    UDP(0x00),

    /**
     * Access Point configuration
     */
    AP_CONFIG(0x01),

    /**
     * Query device status
     */
    STATUS(0x08),

    /**
     * Heartbeat / keep-alive
     */
    HEART_BEAT(0x09),

    /**
     * Query data points (DPS),
     */
    DP_QUERY(0x0a),

    /**
     * Query data points with timestamp
     */
    DP_QUERY_NEW(0x10),

    /**
     * Control command (set DPS values),
     */
    CONTROL(0x07),

    /**
     * Control command (newer protocol),
     */
    CONTROL_NEW(0x0d),

    /**
     * Update DPS values
     */
    DP_REFRESH(0x12),

    /**
     * Update DPS (alternative),
     */
    UPDATE_DPS(0x12),

    /**
     * Negotiate session key (protocol 3.4+),
     */
    SESS_KEY_NEG_START(0x03),

    /**
     * Session key negotiation response (protocol 3.4+),
     */
    SESS_KEY_NEG_RESP(0x04),

    /**
     * Finish session key negotiation (protocol 3.4+),
     */
    SESS_KEY_NEG_FINISH(0x05),

    /**
     * Local network time query
     */
    LAN_GW_ACTIVE(0x25),

    /**
     * LAN extension command
     */
    LAN_EXT_STREAM(0x40),

    /**
     * Scan devices result command
     */
    DISCOVER(0x13);


    companion object {
        fun fromCode(code: Int): TuyaCommand? =
            enumValues<TuyaCommand>().find { it.code == code }
    }
}
