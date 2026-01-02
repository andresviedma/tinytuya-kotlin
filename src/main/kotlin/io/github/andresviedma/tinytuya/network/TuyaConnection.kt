package io.github.andresviedma.tinytuya.network

import io.github.andresviedma.tinytuya.crypto.TuyaCipher
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toHexString
import io.github.andresviedma.tinytuya.protocol.ByteUtils.toIntBE
import io.github.andresviedma.tinytuya.protocol.TuyaCommand
import io.github.andresviedma.tinytuya.protocol.TuyaMessage
import io.github.andresviedma.tinytuya.protocol.TuyaProtocolVersion
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.awaitClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.CancellationException
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Manages TCP connection to a Tuya device with automatic message routing and heartbeat.
 *
 * @param host The device IP address or hostname
 * @param port The device port (default: 6668)
 * @param deviceId The device ID for encryption
 * @param cipher The cipher for encrypting/decrypting messages
 * @param version The protocol version to use
 * @param scope The coroutine scope for managing connection lifecycle
 * @param connectionTimeout Timeout for establishing connection
 * @param responseTimeout Timeout for waiting for responses
 * @param heartbeatInterval Interval for sending heartbeat messages
 */
@OptIn(ExperimentalAtomicApi::class)
class TuyaConnection(
    private val host: String,
    private val port: Int = 6668,
    private val deviceId: String,
    private val cipher: TuyaCipher,
    private val version: TuyaProtocolVersion = TuyaProtocolVersion.V3_3,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val connectionTimeout: Duration = 10.seconds,
    private val responseTimeout: Duration = 5.seconds,
    private val heartbeatInterval: Duration = 30.seconds
) {
    private val writeMutex: Mutex = Mutex()

    private var socket: Socket? = null
    private var writeChannel: ByteWriteChannel? = null
    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null

    private val sequenceNumber = AtomicInt(0)
    private val pendingResponses = mutableMapOf<Int, CompletableDeferred<TuyaMessage>>()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Channel for unsolicited messages (status updates, etc.)
    private val _unsolicitedMessages = Channel<TuyaMessage>(Channel.BUFFERED)
    val unsolicitedMessages: Channel<TuyaMessage> = _unsolicitedMessages

    /**
     * Connect to the device
     */
    suspend fun connect() {
        if (_connectionState.value !is ConnectionState.Disconnected) {
            throw IllegalStateException("Already connected or connecting")
        }

        _connectionState.value = ConnectionState.Connecting

        try {
            withTimeout(connectionTimeout) {
                val selectorManager = SelectorManager(Dispatchers.IO)
                socket = aSocket(selectorManager)
                    .tcp()
                    .connect(host, port)

                _connectionState.value = ConnectionState.Connected

                // Start receiving messages
                startReceiving()

                // Start heartbeat
                startHeartbeat()
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(e)
            cleanup()
            throw e
        }
    }

    /**
     * Disconnect from the device
     */
    suspend fun disconnect() {
        if (_connectionState.value == ConnectionState.Disconnected) {
            return
        }

        _connectionState.value = ConnectionState.Disconnecting
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Send a message and wait for the response
     */
    suspend fun send(message: TuyaMessage): TuyaMessage {
        ensureConnected()

        val messageWithSeq = if (message.sequenceNumber == 0) {
            message.copy(sequenceNumber = nextSequenceNumber())
        } else {
            message
        }

        val deferred = CompletableDeferred<TuyaMessage>()
        pendingResponses[messageWithSeq.sequenceNumber] = deferred

        try {
            logger.debug { "Sending message ${messageWithSeq.sequenceNumber}: ${messageWithSeq.command}"}

            // Encode and send
            val encoded = messageWithSeq.encode(
                cipher = cipher,
                version = version,
                deviceId = deviceId
            )

            socket?.let { sock ->
                writeMutex.withLock {
                    writeChannel().writeFully(encoded)
                }
            } ?: throw IllegalStateException("Socket is null")

            // Wait for response with timeout
            return withTimeout(responseTimeout) {
                deferred.await()
            }

        } catch (e: TimeoutCancellationException) {
            logger.debug { "Timing out message ${messageWithSeq.sequenceNumber}" }
            pendingResponses.remove(messageWithSeq.sequenceNumber)
            throw TimeoutException("Timeout waiting for response to sequence ${messageWithSeq.sequenceNumber}")
        } catch (e: Exception) {
            pendingResponses.remove(messageWithSeq.sequenceNumber)
            throw e
        }
    }

    private fun writeChannel() = (writeChannel ?: socket!!.openWriteChannel(true)).also { writeChannel = it }

    /**
     * Send a message without waiting for a response
     */
    suspend fun sendNoResponse(message: TuyaMessage) {
        ensureConnected()

        val messageWithSeq = if (message.sequenceNumber == 0) {
            message.copy(sequenceNumber = nextSequenceNumber())
        } else {
            message
        }

        val encoded = messageWithSeq.encode(
            cipher = cipher,
            version = version,
            deviceId = deviceId
        )

        socket?.let { sock ->
            val newWriteChannel = writeChannel ?: sock.openWriteChannel(autoFlush = true)
            writeChannel = newWriteChannel
            newWriteChannel.writeFully(encoded)
            // val output = sock.openWriteChannel(autoFlush = true)
            // output.writeFully(encoded)
        } ?: throw IllegalStateException("Socket is null")
    }

    /**
     * Send a heartbeat message
     */
    suspend fun sendHeartbeat(): TuyaMessage {
        val heartbeat = TuyaMessage.createEmpty(
            command = TuyaCommand.HEART_BEAT,
            sequenceNumber = nextSequenceNumber()
        )
        return send(heartbeat)
    }

    private fun startReceiving() {
        receiveJob = scope.launch {
            try {
                val input = socket?.openReadChannel() ?: return@launch

                while (isActive && _connectionState.value == ConnectionState.Connected) {
                    try {
                        val message = readMessage(input)
                        handleReceivedMessage(message)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isActive) {
                            logger.error(e) { "Error decoding received message: ${e.message}" }

                            // Connection error
                            _connectionState.value = ConnectionState.Failed(e)
                            cleanup()
                        }
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ConnectionState.Failed(e)
                    cleanup()
                }
            }
        }
    }

    private suspend fun readMessage(input: ByteReadChannel): TuyaMessage {
        // Read prefix (4 bytes)
        val prefix = ByteArray(4)
        input.readFully(prefix, 0, 4)

        // Verify prefix
        if (!prefix.contentEquals(byteArrayOf(0x00, 0x00, 0x55, 0xaa.toByte()))) {
            throw IllegalStateException("Invalid message prefix")
        }

        // Read header (sequence, command, length)
        val header = ByteArray(12)
        input.readFully(header, 0, 12)

        val payloadLength = header.toIntBE(8) // Length field is at offset 8 in header

        // Read rest of message
        val remaining = ByteArray(payloadLength)
        input.readFully(remaining, 0, payloadLength)

        // TODO Alternative: does not work -- see if this is the right method for some version
        // Read rest of message (return code + payload + CRC + suffix)
        // val remaining = ByteArray(payloadLength + 4) // +4 for suffix
        // input.readFully(remaining, 0, payloadLength + 4)

        // Combine all parts
        val fullMessage = prefix + header + remaining
        logger.debug { "Receiving message hex: ${fullMessage.toHexString()}" }

        val sequence = header.toIntBE(0)
        val command = runCatching { TuyaCommand.fromCode(header.toIntBE(4)) }.getOrNull()
        logger.debug { "Receiving message ${sequence}: $command"}

        // Decode message
        return TuyaMessage.decode(
            data = fullMessage,
            cipher = cipher,
            version = version
        )
    }

    private fun handleReceivedMessage(message: TuyaMessage) {
        // Check if this is a response to a pending request
        val deferred = pendingResponses.remove(message.sequenceNumber)
        if (deferred != null) {
            deferred.complete(message)
        } else {
            logger.debug { "Unsolicited message: ${message.sequenceNumber}: ${message.command}" }

            // Unsolicited message (status update, etc.)
            scope.launch {
                _unsolicitedMessages.send(message)
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.Connected) {
                delay(heartbeatInterval)
                try {
                    sendHeartbeat()
                } catch (_: CancellationException) {
                    // Do nothing
                } catch (e: Exception) {
                    // Heartbeat failed, connection might be dead
                    if (isActive) {
                        _connectionState.value = ConnectionState.Failed(e)
                        cleanup()
                    }
                    break
                }
            }
        }
    }

    private suspend fun cleanup() {
        withContext(NonCancellable) {
            receiveJob?.cancel()
            heartbeatJob?.cancel()

            // Complete all pending responses with cancellation
            pendingResponses.values.forEach { it.cancel() }
            pendingResponses.clear()

            socket?.close()
            socket?.awaitClosed()
            socket = null
        }
    }

    private fun ensureConnected() {
        if (_connectionState.value != ConnectionState.Connected) {
            throw IllegalStateException("Not connected. Current state: ${_connectionState.value}")
        }
    }

    private fun nextSequenceNumber(): Int {
        return sequenceNumber.incrementAndFetch()
    }

    /**
     * Close the connection and release resources
     */
    fun close() {
        runBlocking {
            disconnect()
        }
        scope.cancel()
    }
}

/**
 * Custom exception for timeout scenarios
 */
class TimeoutException(message: String) : Exception(message)
