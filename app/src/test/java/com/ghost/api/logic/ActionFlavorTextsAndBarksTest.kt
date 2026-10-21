package com.ghost.api.logic

import org.junit.Assert.*
import org.junit.Test

class ActionFlavorTextsAndBarksTest {

    @Test
    fun testBatteryCriticalFivePercentIncludesRequestedLine() {
        val observedLines = mutableSetOf<String>()
        // Run multiple samples to observe the random selection pool
        for (i in 0 until 100) {
            val text = ActionFlavorTexts.batteryLowTts(5, "Operator")
            observedLines.add(text)
        }
        assertTrue(
            "Expected 'energy low.. consciousness fading... need volts' in 5% battery lines, got: $observedLines",
            observedLines.contains("energy low.. consciousness fading... need volts")
        )
    }

    @Test
    fun testBatteryFullTts() {
        val text = ActionFlavorTexts.batteryFullTts("Guardian")
        assertNotNull(text)
        assertTrue(text.isNotBlank())
    }

    @Test
    fun testBluetoothAndWifiBarks() {
        val btConnect = ActionFlavorTexts.bluetoothConnectedTts("Sony WH-1000XM5", "Operator")
        assertTrue(btConnect.contains("Sony WH-1000XM5"))

        val btDisconnect = ActionFlavorTexts.bluetoothDisconnectedTts("Sony WH-1000XM5", "Operator")
        assertTrue(btDisconnect.contains("Sony WH-1000XM5") || btDisconnect.contains("Bluetooth") || btDisconnect.contains("Peripheral"))

        val wifiConnect = ActionFlavorTexts.wifiConnectedTts("Operator")
        assertTrue(wifiConnect.isNotBlank())

        val wifiDisconnect = ActionFlavorTexts.wifiDisconnectedTts("Operator")
        assertTrue(wifiDisconnect.isNotBlank())

        val storage = ActionFlavorTexts.lowStorageAlertTts("Operator")
        assertTrue(storage.isNotBlank())
    }

    @Test
    fun testCueCategoriesExist() {
        val categories = CueCategory.values()
        assertEquals(7, categories.size)
        assertTrue(categories.contains(CueCategory.BATTERY))
        assertTrue(categories.contains(CueCategory.POWER))
        assertTrue(categories.contains(CueCategory.MOTION))
        assertTrue(categories.contains(CueCategory.PRESENCE))
        assertTrue(categories.contains(CueCategory.PERIPHERALS))
        assertTrue(categories.contains(CueCategory.THERMAL))
        assertTrue(categories.contains(CueCategory.NOTIFICATIONS))
    }
}
