package io.github.andresviedma.tinytuya.network

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

    @Test
    fun testNoRetryPolicy() = runBlocking {
        var attempts = 0
        assertFailsWith<Exception> {
            withRetry(RetryPolicy.NONE) {
                attempts++
                throw TimeoutException("Test timeout")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun testSuccessfulFirstAttempt() = runBlocking {
        var attempts = 0
        val result = withRetry(RetryPolicy.STANDARD) {
            attempts++
            "success"
        }
        assertEquals("success", result)
        assertEquals(1, attempts)
    }

    @Test
    fun testRetryAndSucceed() = runBlocking {
        var attempts = 0
        val result = withRetry(RetryPolicy.QUICK) {
            attempts++
            if (attempts < 3) {
                throw TimeoutException("Temporary failure")
            }
            "success"
        }
        assertEquals("success", result)
        assertEquals(3, attempts)
    }

    @Test
    fun testMaxAttemptsExceeded() = runBlocking {
        var attempts = 0
        val policy = RetryPolicy(maxAttempts = 3, initialDelay = 10.milliseconds)

        assertFailsWith<TimeoutException> {
            withRetry(policy) {
                attempts++
                throw TimeoutException("Always fail")
            }
        }
        assertEquals(3, attempts)
    }

    @Test
    fun testNonRetryableException() = runBlocking {
        var attempts = 0
        val policy = RetryPolicy(
            maxAttempts = 3,
            initialDelay = 10.milliseconds,
            retryableExceptions = listOf(TimeoutException::class.java)
        )

        assertFailsWith<IllegalArgumentException> {
            withRetry(policy) {
                attempts++
                throw IllegalArgumentException("Not retryable")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun testDelayCalculation() {
        val policy = RetryPolicy(
            initialDelay = 1.seconds,
            factor = 2.0,
            maxDelay = 10.seconds
        )

        assertEquals(1.seconds, policy.delayForAttempt(1))
        assertEquals(2.seconds, policy.delayForAttempt(2))
        assertEquals(4.seconds, policy.delayForAttempt(3))
        assertEquals(8.seconds, policy.delayForAttempt(4))
        assertEquals(10.seconds, policy.delayForAttempt(5)) // capped at maxDelay
    }

    @Test
    fun testIsRetryable() {
        val policy = RetryPolicy()

        assertTrue(policy.isRetryable(TimeoutException("test")))
        assertTrue(policy.isRetryable(java.net.ConnectException("test")))
        assertTrue(policy.isRetryable(java.io.IOException("test")))
    }
}
