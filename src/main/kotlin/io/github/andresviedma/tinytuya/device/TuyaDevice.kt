package io.github.andresviedma.tinytuya.device

import io.github.andresviedma.tinytuya.crypto.TuyaCipher
import io.github.andresviedma.tinytuya.model.DeviceStatus
import io.github.andresviedma.tinytuya.model.DiscoveredDevice
import io.github.andresviedma.tinytuya.model.TuyaClientException
import io.github.andresviedma.tinytuya.network.ConnectionState
import io.github.andresviedma.tinytuya.network.DeviceConnectionConfig
import io.github.andresviedma.tinytuya.network.RetryPolicy
import io.github.andresviedma.tinytuya.network.TuyaConnection
import io.github.andresviedma.tinytuya.network.withRetry
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
 * Base access for Tuya devices providing high-level control and status operations.
 */
open class TuyaDevice(
    val config: DeviceConnectionConfig,
): AutoCloseable {
    protected val cipher = TuyaCipher(config.deviceLocalKey)
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

    constructor(discoveredDevice: DiscoveredDevice, productKey: String) : this(
        DeviceConnectionConfig(
            deviceId = discoveredDevice.gwId,
            deviceLocalKey = productKey,
            host = discoveredDevice.ip,
            version = discoveredDevice.version,
        )
    )

    /**
     * Connect to the device
     */
    suspend fun connect(): TuyaDevice {
        if (connection != null) {
            return this // Already connected or connecting
        }

        val conn = TuyaConnection(config, cipher, scope)

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

            // Start status polling if configured
            statusPollInterval?.let { interval ->
                startStatusPolling(interval)
            }
        }
        logger.info { "Device ${config.deviceId} connected and initialized" }

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
        ensureConnected()

        val message = when (config.version) {
            TuyaProtocolVersion.V3_4, TuyaProtocolVersion.V3_5 ->
                TuyaMessage.createWithJsonPayload(
                    command = TuyaCommand.DP_QUERY_NEW,
                    json = "{}"
                )
            else ->
                TuyaMessage.createWithJsonPayload(
                    command = TuyaCommand.DP_QUERY,
                    json = buildJsonObject {
                        put("gwId", config.deviceId)
                        put("devId", config.deviceId)
                    }.toString()
                )
        }

        return try {
            val response = sendMessage(message)
            val newStatus = parseStatus(response)
            _status.value = newStatus
            newStatus
        } catch (e: TuyaClientException) {
            if (e.response.returnCode == 1 && e.response.payloadText == "json obj data unvalid") {
                DeviceStatus(emptyMap())
            } else {
                throw e
            }
        }
    }

    /**
     * Set data point values
     */
    suspend fun setDps(dps: Map<String, JsonElement>): DeviceStatus {
        ensureConnected()

        val message = when (config.version) {
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
                        put("devId", config.deviceId)
                        put("uid", config.deviceId)
                        // put("uid", "")
                        put("t", (System.currentTimeMillis() / 1000).toString())
                        put("dps", JsonObject(dps))
                    }.toString(),
                )
        }

        logger.debug { message.payload.toString(Charsets.UTF_8) }

        sendMessage(message)

        // Update local status
        val updatedDps = _status.value.dps.toMutableMap()
        updatedDps.putAll(dps)
        val newStatus = DeviceStatus(updatedDps)
        _status.value = newStatus

        return newStatus
    }

    private suspend fun sendMessage(message: TuyaMessage): TuyaMessage {
        val conn = ensureConnected()
        val response = withRetry(RetryPolicy.STANDARD) {
            conn.send(message)
        }
        if (response.returnCode != 0) throw TuyaClientException(response)
        return response
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
                    logger.warn { "Error refreshing status: ${e.message}" }                }
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
}

fun DiscoveredDevice.device(productKey: String) = TuyaDevice(this, productKey)
