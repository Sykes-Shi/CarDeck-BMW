package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarAppearanceScheduleTest {
    @Test fun defaultSwitchIncludesFirstMinuteOfEachPeriod() {
        val clock = AppearanceSchedule()
        assertFalse(clock.isDay(419))
        assertTrue(clock.isDay(420))
        assertTrue(clock.isDay(18 * 60 + 29))
        assertFalse(clock.isDay(18 * 60 + 30))
    }

    @Test fun v36SavedDefaultUsesNewBoundaryWhileKeepingCustomMorningAndMode() {
        val schedule = AppearanceSchedule.fromSaved(AppearanceMode.AUTO, 8 * 60, 19 * 60, legacy = true)
        assertEquals(AppearanceSchedule(AppearanceMode.AUTO, 8 * 60, 18 * 60 + 30), schedule)
        assertFalse(schedule.isDay(18 * 60 + 30, receiverNight = false))
    }

    @Test fun v36CustomNightAndV37ExplicitNineteenRemainSelected() {
        assertEquals(20 * 60, AppearanceSchedule.fromSaved(AppearanceMode.NIGHT, 8 * 60, 20 * 60, legacy = true).nightStart)
        assertEquals(19 * 60, AppearanceSchedule.fromSaved(AppearanceMode.AUTO, 7 * 60, 19 * 60, legacy = false).nightStart)
    }

    @Test fun v36LateDayStartRetainsNineteenToKeepTheSameDaytimeWindow() {
        for (dayStart in listOf(18 * 60 + 30, 18 * 60 + 45, 18 * 60 + 59)) {
            val schedule = AppearanceSchedule.fromSaved(AppearanceMode.AUTO, dayStart, 19 * 60, legacy = true)
            assertEquals(AppearanceSchedule(AppearanceMode.AUTO, dayStart, 19 * 60), schedule)
            assertTrue(schedule.isDay(dayStart))
            assertFalse(schedule.isDay(19 * 60))
        }
    }

    @Test fun vehicleReportsAreUsedOnlyInVehicleMode() {
        val automatic = AppearanceSchedule()
        assertFalse(automatic.isDay(720, true))
        assertTrue(automatic.isDay(10, false))
        assertTrue(automatic.isDay(720, null))
        assertFalse(automatic.isDay(10, null))
        assertFalse(automatic.copy(mode = AppearanceMode.AUTO).isDay(10, false))
        assertTrue(automatic.copy(mode = AppearanceMode.DAY).isDay(10, true))
        assertFalse(automatic.copy(mode = AppearanceMode.NIGHT).isDay(720, false))
    }

    @Test fun overnightDaytimeCrossesMidnight() {
        val clock = AppearanceSchedule(dayStart = 22 * 60 + 15, nightStart = 4 * 60 + 45)
        assertTrue(clock.isDay(0))
        assertFalse(clock.isDay(285))
        assertFalse(clock.isDay(1334))
        assertTrue(clock.isDay(1335))
        assertTrue(clock.isDay(1439))
    }

    @Test fun manualModesIgnoreBothBoundaries() {
        for (minute in listOf(0, 420, 18 * 60 + 30, 1439)) {
            assertTrue(AppearanceSchedule(mode = AppearanceMode.DAY).isDay(minute))
            assertFalse(AppearanceSchedule(mode = AppearanceMode.NIGHT).isDay(minute))
        }
    }

    @Test fun oneMinuteDayAtMidnight() {
        val clock = AppearanceSchedule(dayStart = 0, nightStart = 1)
        assertEquals(listOf(true, false, false), listOf(0, 1, 1439).map(clock::isDay))
    }

    @Test(expected = IllegalArgumentException::class) fun identicalTimesAreRejected() {
        AppearanceSchedule(dayStart = 120, nightStart = 120)
    }
}
