package io.github.andresviedma.tinytuya.device

import io.github.andresviedma.tinytuya.network.DeviceConnectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Represents a Tuya wifi-controlled fog machine device.
 */
@OptIn(ExperimentalTime::class)
class FogMachine(
    val device: TuyaDevice,
): AutoCloseable {
    private val jobScope = CoroutineScope(Dispatchers.IO)
    private var offJob: Job? = null
    private var offInstant: Instant? = null

constructor(config: DeviceConnectionConfig) : this(TuyaDevice(config))

    suspend fun connect(): FogMachine {
        device.connect()
        return this
    }

    override fun close() {
        device.close()
    }

    suspend fun fogOn(interval: Duration? = null) {
        device.setDp("1", true)

        offJob?.cancelAndJoin()
        if (interval != null) {
            val createNewJob = offInstant?.let { (Clock.System.now() + interval) < it } ?: true
            if (createNewJob) {
                offJob?.cancel()
                offJob = jobScope.launch {
                    delay(interval)
                    fogOff()
                }
            }
        }
    }

    suspend fun fogOff() {
        device.setDp("1", false)
        offJob?.cancelAndJoin()
    }
}
