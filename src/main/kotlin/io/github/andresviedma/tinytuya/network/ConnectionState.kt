package io.github.andresviedma.tinytuya.network

/**
 * Represents the connection state of a Tuya device
 */
sealed class ConnectionState {
    /**
     * Not connected to the device
     */
    data object Disconnected : ConnectionState()

    /**
     * Currently establishing connection
     */
    data object Connecting : ConnectionState()

    /**
     * Connected and ready to communicate
     */
    data object Connected : ConnectionState()

    /**
     * Connection is being closed
     */
    data object Disconnecting : ConnectionState()

    /**
     * Connection failed with an error
     */
    data class Failed(val error: Throwable) : ConnectionState()
}
