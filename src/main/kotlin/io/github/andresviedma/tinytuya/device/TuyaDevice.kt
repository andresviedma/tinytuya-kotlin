package io.github.andresviedma.tinytuya.device

import io.github.andresviedma.tinytuya.crypto.TuyaCipher
import io.github.andresviedma.tinytuya.model.DeviceStatus
import io.github.andresviedma.tinytuya.network.ConnectionState
import io.github.andresviedma.tinytuya.network.RetryPolicy
import io.github.andresviedma.tinytuya.network.TuyaConnection
import io.github.andresviedma.tinytuya.network.withRetry
import io.github.andresviedma.tinytuya.protocol.ByteUtils.macSha256
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toHexString
import io.github.andresviedma.tinytuya.protocol.ByteUtils.xor
import io.github.andresviedma.tinytuya.protocol.TuyaCommand
import io.github.andresviedma.tinytuya.protocol.TuyaMessage
import io.github.andresviedma.tinytuya.protocol.TuyaProtocolVersion
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Base class for Tuya devices providing high-level control and status operations.
 *
 * @param deviceId The device ID (gwId)
 * @param deviceLocalKey The local encryption key
 * @param host The device IP address
 * @param port The device port (default: 6668)
 * @param version The protocol version (default: 3.3)
 */
open class TuyaDevice(
    val deviceId: String,
    val deviceLocalKey: String,
    val host: String,
    val port: Int = 6668,
    val version: TuyaProtocolVersion = TuyaProtocolVersion.V3_3,
): AutoCloseable {
    private var sessionKey: ByteArray = deviceLocalKey.toByteArray(Charsets.UTF_8)
    protected var cipher = TuyaCipher(sessionKey)
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var connection: TuyaConnection? = null
    private var reconnectJob: Job? = null
    private var statusMonitorJob: Job? = null

    // Device state
    private val _status = MutableStateFlow(DeviceStatus.empty())
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    var initialized: Boolean = false

    // Configuration
    var autoReconnect: Boolean = true
    var reconnectDelay: Duration = 5.seconds
    var statusPollInterval: Duration? = null // null = no polling

    /**
     * Connect to the device
     */
    suspend fun connect(): TuyaDevice {
        if (connection != null) {
            return this // Already connected or connecting
        }

        val conn = TuyaConnection(
            host = host,
            port = port,
            deviceId = deviceId,
            cipher = cipher,
            version = version,
            scope = scope
        )

        connection = conn

        // Monitor connection state
        scope.launch {
            conn.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        _isConnected.value = true
                        onConnected()
                    }
                    is ConnectionState.Disconnected -> {
                        _isConnected.value = false
                        onDisconnected()
                    }
                    is ConnectionState.Failed -> {
                        _isConnected.value = false
                        onConnectionFailed(state.error)

                        if (autoReconnect) {
                            scheduleReconnect()
                        }
                    }
                    else -> { /* Connecting/Disconnecting states */ }
                }
            }
        }

        // Monitor unsolicited messages
        scope.launch {
            for (message in conn.unsolicitedMessages) {
                handleUnsolicitedMessage(message)
            }
        }

        // Connect
        conn.connect()

        // Wait for initialized
        while (!initialized) {
            delay(10)
        }
        logger.info { "Device $deviceId connected and initialized" }

        return this
    }

    /**
     * Disconnect from the device
     */
    suspend fun disconnect() {
        stopStatusPolling()
        cancelReconnect()

        connection?.let {
            it.disconnect()
            it.close()
        }
        connection = null
        _isConnected.value = false
    }

    /**
     * Query the current device status
     */
    suspend fun refresh(): DeviceStatus {
        val conn = ensureConnected()

        val message = when (version) {
            TuyaProtocolVersion.V3_4, TuyaProtocolVersion.V3_5 ->
                TuyaMessage.createWithJsonPayload(
                    command = TuyaCommand.DP_QUERY_NEW,
                    json = "{}"
                )
            else ->
                TuyaMessage.createWithJsonPayload(
                    command = TuyaCommand.DP_QUERY,
                    json = buildJsonObject {
                        put("gwId", deviceId)
                        put("devId", deviceId)
                    }.toString()
                )
        }

        val response = withRetry(RetryPolicy.STANDARD) {
            conn.send(message)
        }

        val newStatus = parseStatus(response)
        _status.value = newStatus
        return newStatus
    }

    /**
     * Set data point values
     */
    suspend fun setDps(dps: Map<String, JsonElement>): DeviceStatus {
        val conn = ensureConnected()

        val message = when (version) {
            TuyaProtocolVersion.V3_4, TuyaProtocolVersion.V3_5 ->
                TuyaMessage.createWithJsonPayload(
                    command = TuyaCommand.CONTROL_NEW,
                    json = buildJsonObject {
                        put("protocol", 5)
                        put("t", (System.currentTimeMillis() / 1000))
                        put("data", JsonObject(mapOf("dps" to JsonObject(dps))))
                    }.toString(),
                )

            else ->
                TuyaMessage.createWithJsonPayload(
                    command = TuyaCommand.CONTROL,
                    json = buildJsonObject {
                        put("devId", deviceId)
                        put("uid", deviceId)
                        // put("uid", "")
                        put("t", (System.currentTimeMillis() / 1000).toString())
                        put("dps", JsonObject(dps))
                    }.toString(),
                )
        }
        logger.debug { message.payload.toString(Charsets.UTF_8) }

        withRetry(RetryPolicy.STANDARD) {
            conn.send(message)
        }

        // Update local status
        val updatedDps = _status.value.dps.toMutableMap()
        updatedDps.putAll(dps)
        val newStatus = DeviceStatus(updatedDps)
        _status.value = newStatus

        return newStatus
    }

    /**
     * Set a single data point
     */
    suspend fun setDp(dpId: String, value: JsonElement): DeviceStatus {
        return setDps(mapOf(dpId to value))
    }

    /**
     * Set a boolean data point
     */
    suspend fun setDp(dpId: String, value: Boolean): DeviceStatus {
        return setDp(dpId, JsonPrimitive(value))
    }

    /**
     * Set an integer data point
     */
    suspend fun setDp(dpId: String, value: Int): DeviceStatus {
        return setDp(dpId, JsonPrimitive(value))
    }

    /**
     * Set a string data point
     */
    suspend fun setDp(dpId: String, value: String): DeviceStatus {
        return setDp(dpId, JsonPrimitive(value))
    }

    /**
     * Get the current cached status
     */
    fun currentStatus(): DeviceStatus {
        return _status.value
    }

    /**
     * Check if device is available (connected and responsive)
     */
    suspend fun isAvailable(): Boolean {
        if (!_isConnected.value) {
            return false
        }

        return try {
            refresh()
            true
        } catch (e: Exception) {
            false
        }
    }

    // Protected methods for subclasses

    /**
     * Called when connection is established
     */
    protected open suspend fun onConnected() {
        negotiateSessionKey()

        // Default: refresh status
        try {
            refresh()
        } catch (e: Exception) {
            // Ignore errors during initial refresh
        }

        initialized = true

        // Start status polling if configured
        statusPollInterval?.let { interval ->
            startStatusPolling(interval)
        }
    }

    /**
     * Called when connection is lost
     */
    protected open suspend fun onDisconnected() {
        // Override in subclasses if needed
    }

    /**
     * Called when connection fails
     */
    protected open suspend fun onConnectionFailed(error: Throwable) {
        // Override in subclasses if needed
    }

    /**
     * Handle unsolicited messages from device
     */
    protected open suspend fun handleUnsolicitedMessage(message: TuyaMessage) {
        // Parse and update status
        try {
            val newStatus = parseStatus(message)
            _status.value = newStatus
        } catch (e: Exception) {
            // Ignore parsing errors
        }
    }

    // Private helper methods

    private fun ensureConnected(): TuyaConnection {
        return connection?.takeIf { _isConnected.value }
            ?: throw IllegalStateException("Device not connected")
    }

    private fun parseStatus(message: TuyaMessage): DeviceStatus {
        val payload = String(message.payload, Charsets.UTF_8)
        return DeviceStatus.fromJson(payload)
    }

    private fun scheduleReconnect() {
        cancelReconnect()

        reconnectJob = scope.launch {
            delay(reconnectDelay)

            try {
                connection?.connect()
            } catch (e: Exception) {
                // Reconnect failed, will try again on next failure
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun startStatusPolling(interval: Duration) {
        stopStatusPolling()

        statusMonitorJob = scope.launch {
            while (isActive && _isConnected.value) {
                delay(interval)
                try {
                    refresh()
                } catch (e: Exception) {
                    // Ignore polling errors
                    logger.warn { "Error refreshing status: ${e.message}" }
                }
            }
        }
    }

    private fun stopStatusPolling() {
        statusMonitorJob?.cancel()
        statusMonitorJob = null
    }

    /**
     * Close the device and release all resources
     */
    override fun close() {
        runBlocking {
            disconnect()
        }
        scope.cancel()
    }

    private suspend fun negotiateSessionKey() {
        if (version.numeric <= 3.3) return

        val conn = ensureConnected()

        val localNonce = "0123456789abcdef" // not-so-random random key
        logger.info { "Session key negotiate start for device $deviceId" }

        // 1. First message
        val message = TuyaMessage.createWithJsonPayload(
            command = TuyaCommand.SESS_KEY_NEG_START,
            json = localNonce,
        )
        val response = conn.send(message)

        // 2. Validate response
        require(response.command == TuyaCommand.SESS_KEY_NEG_RESP) { "Invalid response received in session negotiation: ${response.command}" }
        require(response.payload.size >= 48) { "Session key negotiation step 2 failed, too short response" }

        val remoteNonce = response.payload.copyOfRange(0, 16).toString(Charsets.UTF_8)
        val hmacChecksum = response.payload.copyOfRange(16, 48).toHexString()
        val hmacCheck = localNonce.toByteArray(Charsets.UTF_8).macSha256(sessionKey).toHexString()
        require(hmacCheck == hmacChecksum) { "Session key negotiation step 2 failed HMAC check! wanted=$hmacChecksum but got=$hmacCheck" }

        val rkeyHmac = remoteNonce.toByteArray(Charsets.UTF_8).macSha256(sessionKey)
        logger.debug { "Negotiation. Remote nonce: ${remoteNonce.toByteArray().toHexString()} -- ${rkeyHmac.toHexString()}" }

        // 3. Second message
        val finishMessage = TuyaMessage(
            command = TuyaCommand.SESS_KEY_NEG_FINISH,
            payload = rkeyHmac,
        )
        conn.sendNoResponse(finishMessage)

        // Change the local key
        val newLocalKey0 = localNonce.toByteArray() xor remoteNonce.toByteArray()
        val newLocalKey = cipher.encrypt(newLocalKey0).copyOfRange(0, 16)

        sessionKey = newLocalKey
        cipher = TuyaCipher(newLocalKey)
        conn.changeCipher(cipher)

        logger.info { "Session key negotiate success. Session key: ${newLocalKey.toHexString()}" }
    }
}
