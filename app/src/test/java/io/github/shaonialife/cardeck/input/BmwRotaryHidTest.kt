package io.github.shaonialife.cardeck.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BmwRotaryHidTest {
    private fun bytes(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    // CarDeck-log-20261108-214133.txt, /info hidDescriptor[1].
    private val descriptor = bytes("""
        05 01 09 08 a1 01 05 09 09 01 15 00 25 01 75 01 95 01 81 02 05 0c 75 01 95 01 81 01
        0a 24 02 15 00 25 01 75 01 95 01 81 02 75 05 95 01 81 01 05 01 09 30 09 31 15 81
        25 7f 75 08 95 02 81 02 05 01 09 38 15 81 25 7f 75 08 95 01 81 06 c0
    """)
    private val decoder = BmwRotaryHid.fromDescriptor(descriptor)!!

    @Test
    fun capturedRotationsAreRelativeAndNeverWaitForARelease() {
        val samples = List(6) { "00 00 00 01" } + List(4) { "00 00 00 ff" }
        val events = samples.flatMap { decoder.decode(bytes(it))!! }
        assertEquals(List(6) { CarControllerInput(CarControllerAction.NEXT) } +
            List(4) { CarControllerInput(CarControllerAction.PREVIOUS) }, events)
        assertEquals(listOf(CarControllerInput(CarControllerAction.PREVIOUS, 127)), decoder.decode(bytes("00 00 00 81")))
    }

    @Test
    fun capturedDirectionalConfirmAndBackReportsActOncePerPress() {
        val samples = listOf(
            "00 00 81 00" to CarControllerAction.UP, "00 7f 00 00" to CarControllerAction.RIGHT,
            "00 81 00 00" to CarControllerAction.LEFT, "00 00 7f 00" to CarControllerAction.DOWN,
            "04 00 00 00" to CarControllerAction.BACK, "01 00 00 00" to CarControllerAction.CONFIRM,
        )
        for ((sample, action) in samples) {
            assertEquals(listOf(CarControllerInput(action)), decoder.decode(bytes(sample)))
            assertEquals(emptyList<CarControllerInput>(), decoder.decode(bytes(sample)))
            assertEquals(emptyList<CarControllerInput>(), decoder.decode(bytes("00 00 00 00")))
        }
    }

    @Test
    fun absoluteAxisAndRelativeWheelHaveIndependentState() {
        assertEquals(listOf(CarControllerInput(CarControllerAction.RIGHT)), decoder.decode(bytes("00 7f 00 00")))
        assertEquals(emptyList<CarControllerInput>(), decoder.decode(bytes("00 70 00 00")))
        assertEquals(listOf(CarControllerInput(CarControllerAction.NEXT, 3)), decoder.decode(bytes("00 7f 00 03")))
        assertEquals(listOf(CarControllerInput(CarControllerAction.LEFT)), decoder.decode(bytes("00 81 00 00")))
    }

    @Test
    fun rejectsWrongSizeOrOutOfRangeWithoutRearmingHeldButton() {
        decoder.decode(bytes("01 00 00 00"))
        for (bad in listOf("00 00 00", "00 00 00 00 00", "00 80 00 00", "00 00 80 00", "00 00 00 80")) {
            assertNull(decoder.decode(bytes(bad)))
        }
        assertEquals(emptyList<CarControllerInput>(), decoder.decode(bytes("03 00 00 00"))) // padding bit is ignored
        val nextSession = BmwRotaryHid.fromDescriptor(descriptor)!!
        assertEquals(listOf(CarControllerInput(CarControllerAction.CONFIRM)), nextSession.decode(bytes("01 00 00 00")))
    }

    @Test
    fun rejectsOtherHidDevicesAndAnAbsoluteWheelWithTheSameReportLength() {
        assertNull(BmwRotaryHid.fromDescriptor(bytes("05 0d 09 04 a1 01 c0")))
        assertNull(BmwRotaryHid.fromDescriptor(bytes("05 0c 09 01 a1 01 c0")))
        assertNull(BmwRotaryHid.fromDescriptor(descriptor.copyOf().apply { this[lastIndex - 1] = 0x02 }))
    }
}
