package io.github.andresviedma.tinytuya.network

import io.github.andresviedma.tinytuya.crypto.TuyaCipher
import io.github.andresviedma.tinytuya.model.DiscoveredDevice
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toHexString
import io.github.andresviedma.tinytuya.protocol.TuyaMessage
import io.github.andresviedma.tinytuya.protocol.TuyaProtocolVersion
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Scans the local network for Tuya devices using UDP broadcast.
 * Devices respond with their configuration including IP, device ID, and protocol version.
 *
 * @param scope The coroutine scope for managing scan lifecycle
 */
class TuyaScanner(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
): AutoCloseable {
    private val discoveredDevices = mutableMapOf<String, DiscoveredDevice>()

    /**
     * Scan the network for Tuya devices
     *
     * @param timeout How long to listen for device responses
     * @param ports List of UDP ports to scan (default: 6666, 6667, 7000)
     * @return List of discovered devices
     */
    suspend fun scan(
        timeout: Duration = 10.seconds,
        ports: List<Int> = listOf(6666, 6667, 7000)
    ): List<DiscoveredDevice> {
        discoveredDevices.clear()

        // Create a channel to collect results from all ports
        val resultsChannel = Channel<DiscoveredDevice>(Channel.UNLIMITED)

        // Scan all ports in parallel
        val jobs = ports.map { port ->
            scope.launch {
                scanPort(port, timeout, resultsChannel)
            }
        }

        // Collect results for the specified timeout
        val collectorJob = scope.launch {
            for (device in resultsChannel) {
                // Use IP as key to avoid duplicates
                if (!discoveredDevices.containsKey(device.ip)) {
                    discoveredDevices[device.ip] = device
                }
            }
        }

        // Wait for timeout
        delay(timeout)

        // Cancel all scanning jobs
        jobs.forEach { it.cancel() }
        resultsChannel.close()
        collectorJob.cancel()

        return discoveredDevices.values.toList()
    }

    /**
     * Scan a specific UDP port for devices
     */
    private suspend fun scanPort(
        port: Int,
        timeout: Duration,
        resultsChannel: Channel<DiscoveredDevice>
    ) {
        try {
            val selectorManager = SelectorManager(Dispatchers.IO)
            aSocket(selectorManager)
                .udp()
                .bind(localAddress = InetSocketAddress("0.0.0.0", port))
                .use { socket ->
                    withTimeoutOrNull(timeout) {
                        while (isActive) {
                            var data: ByteArray? = null
                            try {
                                val datagram = socket.receive()
                                data = datagram.packet.readBytes()
                                val sourceAddress = datagram.address

                                // Try to parse the device info
                                parseDeviceInfo(data, sourceAddress)?.let { device ->
                                    resultsChannel.send(device)
                                }
                            } catch (_: CancellationException) {
                                // Nothing to do
                            } catch (e: Exception) {
                                // Ignore parsing errors, continue listening
                                logger.error { "Error parsing scan message: ${e.message} - message: ${data?.toHexString()}" }
                            }
                        }
                    }
                }

        } catch (_: CancellationException) {
            // Nothing to do
        } catch (e: Exception) {
            // Port binding failed or other error, skip this port
            logger.error { "Error binding port $port: ${e.message}" }
        }
    }

    /**
     * Parse device information from UDP broadcast message
     * Tuya devices broadcast JSON messages with their configuration
     */
    private fun parseDeviceInfo(data: ByteArray, sourceAddress: SocketAddress): DiscoveredDevice? {
        try {
            val msg = TuyaMessage.decode(data, TuyaCipher(UDP_KEY, forceMd5 = true))

            // Convert to string and parse JSON
            val jsonString = String(msg.payload, Charsets.UTF_8)
            val json = Json.parseToJsonElement(jsonString).jsonObject

            // Extract device information
            val ip = sourceAddress.toJavaAddress().let { addr ->
                when (addr) {
                    is java.net.InetSocketAddress -> addr.hostString
                    else -> null
                }
            } ?: return null
            val gwId = json["gwId"]?.jsonPrimitive?.content ?: return null
            val productKey = json["productKey"]?.jsonPrimitive?.contentOrNull
            val active = json["active"]?.jsonPrimitive?.booleanOrNull ?: true
            val encrypted = json["encrypt"]?.jsonPrimitive?.booleanOrNull ?: true

            // Determine protocol version from the data
            val versionString = json["version"]?.jsonPrimitive?.contentOrNull
                ?: determineVersionFromData(data)

            val version = try {
                TuyaProtocolVersion.fromString(versionString)
            } catch (e: Exception) {
                TuyaProtocolVersion.V3_3 // Default to 3.3
            }

            return DiscoveredDevice(
                ip = ip,
                gwId = gwId,
                productKey = productKey,
                version = version,
                encrypted = encrypted,
                active = active
            )
        } catch (e: Exception) {
            // Failed to parse, not a valid Tuya device message
            logger.error { "Error parsing scan message: ${e.message} - message: ${data.toHexString()}" }
            return null
        }
    }

    /**
     * Attempt to determine protocol version from the message structure
     */
    private fun determineVersionFromData(data: ByteArray): String {
        // Check for version indicators in the raw data
        // This is a heuristic approach when version is not explicitly in JSON

        // Check for "3.3" string in data
        if (data.indexOf("3.3".toByteArray()) >= 0) return "3.3"
        if (data.indexOf("3.4".toByteArray()) >= 0) return "3.4"
        if (data.indexOf("3.5".toByteArray()) >= 0) return "3.5"
        if (data.indexOf("3.2".toByteArray()) >= 0) return "3.2"
        if (data.indexOf("3.1".toByteArray()) >= 0) return "3.1"

        // Default to 3.3 as it's the most common
        return "3.3"
    }

    /**
     * Extension function to find byte array pattern
     */
    private fun ByteArray.indexOf(pattern: ByteArray): Int {
        for (i in 0..this.size - pattern.size) {
            var found = true
            for (j in pattern.indices) {
                if (this[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    /**
     * Close the scanner and release resources
     */
    override fun close() {
        scope.cancel()
    }

    companion object {
        const val UDP_KEY = "yGAdlopoPVldABfn"
    }
}
