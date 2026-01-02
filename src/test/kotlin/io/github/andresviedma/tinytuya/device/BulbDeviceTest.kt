package io.github.andresviedma.tinytuya.device

import kotlin.test.*

class BulbDeviceTest {

    @Test
    fun testHSVToTuyaFormat() {
        val hsv = BulbDevice.HSV(120, 50, 75)
        val format = hsv.toTuyaFormat()
        assertEquals("012005000750", format)
    }

    @Test
    fun testHSVFromTuyaFormat() {
        val hsv = BulbDevice.HSV.fromTuyaFormat("012005000750")
        assertNotNull(hsv)
        assertEquals(120, hsv.hue)
        assertEquals(50, hsv.saturation)
        assertEquals(75, hsv.value)
    }

    @Test
    fun testHSVFromTuyaFormatInvalid() {
        val hsv = BulbDevice.HSV.fromTuyaFormat("invalid")
        assertNull(hsv)
    }

    @Test
    fun testRGBToHSV() {
        val rgb = BulbDevice.RGB(255, 0, 0) // Red
        val hsv = BulbDevice.HSV.fromRGB(rgb)
        assertEquals(0, hsv.hue)
        assertEquals(100, hsv.saturation)
        assertEquals(100, hsv.value)
    }

    @Test
    fun testRGBValidation() {
        assertFailsWith<IllegalArgumentException> {
            BulbDevice.RGB(-1, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BulbDevice.RGB(256, 0, 0)
        }
    }

    @Test
    fun testHSVValidation() {
        assertFailsWith<IllegalArgumentException> {
            BulbDevice.HSV(-1, 50, 50)
        }
        assertFailsWith<IllegalArgumentException> {
            BulbDevice.HSV(361, 50, 50)
        }
        assertFailsWith<IllegalArgumentException> {
            BulbDevice.HSV(180, -1, 50)
        }
        assertFailsWith<IllegalArgumentException> {
            BulbDevice.HSV(180, 101, 50)
        }
    }

    @Test
    fun testBrightnessRange() {
        assertEquals(10, BulbDevice.BRIGHTNESS_MIN)
        assertEquals(1000, BulbDevice.BRIGHTNESS_MAX)
    }

    @Test
    fun testColorTempRange() {
        assertEquals(0, BulbDevice.COLOR_TEMP_MIN)
        assertEquals(1000, BulbDevice.COLOR_TEMP_MAX)
    }
}
