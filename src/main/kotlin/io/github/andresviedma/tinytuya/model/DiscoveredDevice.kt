package io.github.andresviedma.tinytuya.model

import io.github.andresviedma.tinytuya.protocol.TuyaProtocolVersion

/**
 * Represents a Tuya device discovered on the network
 *
 * @param ip The device IP address
 * @param gwId The gateway ID (device ID)
 * @param productKey The product key identifying the device type
 * @param version The protocol version supported by the device
 * @param encrypted Whether the device requires encryption
 * @param active Whether the device is currently active
 */
data class DiscoveredDevice(
    val ip: String,
    val gwId: String,
    val productKey: String? = null,
    val version: TuyaProtocolVersion,
    val encrypted: Boolean = true,
    val active: Boolean = true
) {
    override fun toString(): String {
        return "Device(ip=$ip, id=$gwId, version=${version.version}, encrypted=$encrypted)"
    }
}
