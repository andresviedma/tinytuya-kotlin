package io.github.andresviedma.tinytuya.network

import io.github.andresviedma.tinytuya.protocol.TuyaProtocolVersion
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class DeviceConnectionConfig(
    /** The device IP address or hostname */
    val host: String,
    /** The device port */
    val port: Int = 6668,
    /** The device ID, which will identifies the concrete device instance and never changes */
    val deviceId: String,
    /** The device local key for encription */
    val deviceLocalKey: String,
    /** The protocol version of the device */
    val version: TuyaProtocolVersion = TuyaProtocolVersion.V3_3,
    /** Timeout for establishing connection */
    val connectionTimeout: Duration = 10.seconds,
    /** Timeout for waiting for responses */
    val responseTimeout: Duration = 10.seconds,
    /** Interval for sending heartbeat messages */
    val heartbeatInterval: Duration = 30.seconds,
)
