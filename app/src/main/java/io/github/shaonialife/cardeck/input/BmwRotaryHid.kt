package io.github.shaonialife.cardeck.input

enum class CarControllerAction { NEXT, PREVIOUS, LEFT, RIGHT, UP, DOWN, CONFIRM, BACK }

/** Wheel deltas carry their detent count, while a new button or direction press has one step. */
data class CarControllerInput(val action: CarControllerAction, val steps: Int = 1)

/** One instance for each matched /info HID descriptor in an AirPlay session. */
class BmwRotaryHid private constructor() {
    private var lastX = 0
    private var lastY = 0
    private var lastButtons = 0

    /** A malformed report leaves the previous state intact. A release updates it without emitting a key. */
    @Synchronized
    fun decode(report: ByteArray): List<CarControllerInput>? {
        if (report.size != 4) return null
        val buttons = report[0].toInt() and 0x05
        val x = report[1].toInt()
        val y = report[2].toInt()
        val wheel = report[3].toInt()
        if (x == -128 || y == -128 || wheel == -128) return null // descriptor range is -127..127

        val result = mutableListOf<CarControllerInput>()
        // The two absolute axes on this unit report full deflection and a neutral position.
        // Only emit on entry into a detent. Intermediate values are not additional key presses.
        if (x != lastX) {
            if (x == -127) result.add(CarControllerInput(CarControllerAction.LEFT))
            if (x == 127) result.add(CarControllerInput(CarControllerAction.RIGHT))
        }
        if (y != lastY) {
            if (y == -127) result.add(CarControllerInput(CarControllerAction.UP))
            if (y == 127) result.add(CarControllerInput(CarControllerAction.DOWN))
        }
        if (wheel > 0) result.add(CarControllerInput(CarControllerAction.NEXT, wheel))
        if (wheel < 0) result.add(CarControllerInput(CarControllerAction.PREVIOUS, -wheel))
        val pressed = buttons and lastButtons.inv()
        if (pressed and 1 != 0) result.add(CarControllerInput(CarControllerAction.CONFIRM))
        if (pressed and 4 != 0) result.add(CarControllerInput(CarControllerAction.BACK))

        if (x == 0 || x == -127 || x == 127) lastX = x
        if (y == 0 || y == -127 || y == 127) lastY = y
        lastButtons = buttons
        return result
    }

    companion object {
        // MGU-50-P /info HardKeyInputDescriptor, 78 bytes. UUID and report length alone are
        // ambiguous with other head units. Match the full attested report layout including padding.
        private val hardKeys = (
            "05 01 09 08 a1 01 " +                     // Generic Desktop Multi-axis Controller
                "05 09 09 01 15 00 25 01 75 01 95 01 81 02 " + // Button 1
                "05 0c 75 01 95 01 81 01 " +           // padding, one bit
                "0a 24 02 15 00 25 01 75 01 95 01 81 02 " + // Consumer AC Back
                "75 05 95 01 81 01 " +                 // padding, five bits
                "05 01 09 30 09 31 15 81 25 7f 75 08 95 02 81 02 " + // X, Y absolute
                "05 01 09 38 15 81 25 7f 75 08 95 01 81 06 c0" // Wheel relative
            ).split(' ').map { it.toInt(16).toByte() }.toByteArray()

        fun fromDescriptor(descriptor: ByteArray): BmwRotaryHid? =
            if (descriptor.contentEquals(hardKeys)) BmwRotaryHid() else null
    }
}
