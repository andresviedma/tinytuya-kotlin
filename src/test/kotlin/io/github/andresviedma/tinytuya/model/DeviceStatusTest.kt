package io.github.andresviedma.tinytuya.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class DeviceStatusTest {

    @Test
    fun testFromJsonWithDps() {
        val json = """{"dps":{"1":true,"2":100,"3":"test"}}"""
        val status = DeviceStatus.fromJson(json)

        assertTrue(status.getBoolean("1") ?: false)
        assertEquals(100, status.getInt("2"))
        assertEquals("test", status.getString("3"))
    }

    @Test
    fun testFromJsonWithData() {
        val json = """{"data":{"dps":{"1":false,"2":50}}}"""
        val status = DeviceStatus.fromJson(json)

        assertFalse(status.getBoolean("1") ?: true)
        assertEquals(50, status.getInt("2"))
    }

    @Test
    fun testFromJsonWithTimestamp() {
        val json = """{"dps":{"1":true},"t":1234567890}"""
        val status = DeviceStatus.fromJson(json)

        assertEquals(1234567890L, status.timestamp)
    }

    @Test
    fun testGetBoolean() {
        val status = DeviceStatus(mapOf("1" to JsonPrimitive(true)))
        assertTrue(status.getBoolean("1") ?: false)
        assertNull(status.getBoolean("2"))
    }

    @Test
    fun testGetInt() {
        val status = DeviceStatus(mapOf("2" to JsonPrimitive(42)))
        assertEquals(42, status.getInt("2"))
        assertNull(status.getInt("3"))
    }

    @Test
    fun testGetString() {
        val status = DeviceStatus(mapOf("3" to JsonPrimitive("hello")))
        assertEquals("hello", status.getString("3"))
        assertNull(status.getString("4"))
    }

    @Test
    fun testGetDouble() {
        val status = DeviceStatus(mapOf("4" to JsonPrimitive(3.14)))
        assertEquals(3.14, status.getDouble("4"))
        assertNull(status.getDouble("5"))
    }

    @Test
    fun testHas() {
        val status = DeviceStatus(mapOf("1" to JsonPrimitive(true)))
        assertTrue(status.has("1"))
        assertFalse(status.has("2"))
    }

    @Test
    fun testDpIds() {
        val status = DeviceStatus(mapOf(
            "1" to JsonPrimitive(true),
            "2" to JsonPrimitive(100)
        ))
        assertEquals(setOf("1", "2"), status.dpIds())
    }

    @Test
    fun testEmpty() {
        val status = DeviceStatus.empty()
        assertTrue(status.dps.isEmpty())
        assertNull(status.timestamp)
    }

    @Test
    fun testDpsBuilder() {
        val dps = buildDps {
            set("1", true)
            set("2", 100)
            set("3", "test")
            set("4", 3.14)
        }

        assertEquals(4, dps.size)
        assertEquals(JsonPrimitive(true), dps["1"])
        assertEquals(JsonPrimitive(100), dps["2"])
        assertEquals(JsonPrimitive("test"), dps["3"])
        assertEquals(JsonPrimitive(3.14), dps["4"])
    }

    @Test
    fun testDpsBuilderChaining() {
        val builder = DpsBuilder()
            .set("1", true)
            .set("2", 50)

        val dps = builder.build()
        assertEquals(2, dps.size)
    }

    @Test
    fun testDpsBuilderToJson() {
        val json = DpsBuilder()
            .set("1", true)
            .set("2", 100)
            .toJson()

        assertTrue(json.contains("\"1\""))
        assertTrue(json.contains("true"))
        assertTrue(json.contains("\"2\""))
        assertTrue(json.contains("100"))
    }
}
