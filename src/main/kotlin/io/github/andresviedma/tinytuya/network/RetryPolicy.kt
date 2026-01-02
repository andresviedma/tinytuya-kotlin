package io.github.andresviedma.tinytuya.network

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Configuration for retry behavior
 *
 * @param maxAttempts Maximum number of retry attempts (including initial attempt)
 * @param initialDelay Delay before the first retry
 * @param maxDelay Maximum delay between retries
 * @param factor Multiplier for exponential backoff
 * @param retryableExceptions List of exception types that should trigger a retry
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val factor: Double = 2.0,
    val retryableExceptions: List<Class<out Exception>> = listOf(
        TimeoutException::class.java,
        java.net.ConnectException::class.java,
        java.io.IOException::class.java
    )
) {
    /**
     * Check if an exception should trigger a retry
     */
    fun isRetryable(exception: Exception): Boolean {
        return retryableExceptions.any { it.isInstance(exception) }
    }

    /**
     * Calculate delay for a specific attempt
     */
    fun delayForAttempt(attempt: Int): Duration {
        val delay = initialDelay * factor.pow(attempt - 1)
        return minOf(delay, maxDelay)
    }

    private fun Double.pow(n: Int): Double {
        var result = 1.0
        repeat(n) { result *= this }
        return result
    }

    companion object {
        /**
         * No retries
         */
        val NONE = RetryPolicy(maxAttempts = 1)

        /**
         * Quick retries with minimal delay
         */
        val QUICK = RetryPolicy(
            maxAttempts = 3,
            initialDelay = 500.milliseconds,
            maxDelay = 2.seconds,
            factor = 1.5
        )

        /**
         * Standard retry policy with exponential backoff
         */
        val STANDARD = RetryPolicy(
            maxAttempts = 1, //3,
            initialDelay = 1.seconds,
            maxDelay = 10.seconds,
            factor = 2.0
        )

        /**
         * Aggressive retry policy for flaky connections
         */
        val AGGRESSIVE = RetryPolicy(
            maxAttempts = 5,
            initialDelay = 1.seconds,
            maxDelay = 30.seconds,
            factor = 2.0
        )
    }
}

/**
 * Execute a block with retry logic
 */
suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy.STANDARD,
    block: suspend () -> T
): T {
    var attempt = 0
    var lastException: Exception? = null

    while (attempt < policy.maxAttempts) {
        attempt++
        try {
            return block()
        } catch (e: Exception) {
            lastException = e

            if (!policy.isRetryable(e) || attempt >= policy.maxAttempts) {
                throw e
            }
            logger.warn(e) { "Retrying (attempt $attempt) - error: ${e.message}" }

            // Wait before next attempt
            val delay = policy.delayForAttempt(attempt)
            delay(delay)
        }
    }

    throw lastException ?: IllegalStateException("Retry failed with no exception")
}
