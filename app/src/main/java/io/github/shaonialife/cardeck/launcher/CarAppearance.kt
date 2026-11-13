package io.github.shaonialife.cardeck.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.time.LocalTime
import io.github.shaonialife.cardeck.SessionController

internal enum class AppearanceMode { VEHICLE, AUTO, DAY, NIGHT }

private const val DEFAULT_NIGHT_START = 18 * 60 + 30
private const val LEGACY_NIGHT_START = 19 * 60

internal data class AppearanceSchedule(
    val mode: AppearanceMode = AppearanceMode.VEHICLE,
    val dayStart: Int = 7 * 60,
    val nightStart: Int = DEFAULT_NIGHT_START,
) {
    init {
        require(dayStart in 0 until 1440 && nightStart in 0 until 1440 && dayStart != nightStart)
    }

    fun isDay(minute: Int, receiverNight: Boolean? = null): Boolean = when (mode) {
        AppearanceMode.DAY -> true
        AppearanceMode.NIGHT -> false
        AppearanceMode.VEHICLE -> receiverNight?.not() ?: scheduledDay(minute)
        AppearanceMode.AUTO -> scheduledDay(minute)
    }

    private fun scheduledDay(minute: Int): Boolean {
        return if (dayStart < nightStart) minute in dayStart until nightStart
        else minute >= dayStart || minute < nightStart
    }

    companion object {
        fun fromSaved(mode: AppearanceMode, dayStart: Int, nightStart: Int, legacy: Boolean): AppearanceSchedule {
            if (dayStart !in 0 until 1440 || nightStart !in 0 until 1440 || dayStart == nightStart) {
                return AppearanceSchedule(mode)
            }
            // V36 wrote 19:00 even on a mode-only change. Keep it when 18:30 would
            // overlap or move ahead of a custom day start, turning a short day into an overnight one.
            val migrateNight = legacy && nightStart == LEGACY_NIGHT_START &&
                dayStart !in DEFAULT_NIGHT_START until LEGACY_NIGHT_START
            return AppearanceSchedule(mode, dayStart, if (migrateNight) DEFAULT_NIGHT_START else nightStart)
        }
    }
}

internal object CarAppearance {
    const val VEHICLE_MODE_CHANGED = "io.github.shaonialife.cardeck.VEHICLE_MODE_CHANGED"
    private const val SCHEDULE_VERSION = 2
    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("car_appearance", Context.MODE_PRIVATE)

    fun schedule(context: Context): AppearanceSchedule {
        val prefs = preferences(context)
        val mode = AppearanceMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: AppearanceMode.VEHICLE
        val defaults = AppearanceSchedule(mode)
        val day = prefs.getInt("day_start", defaults.dayStart)
        val night = prefs.getInt("night_start", defaults.nightStart)
        return AppearanceSchedule.fromSaved(mode, day, night, legacy = prefs.getInt("schedule_version", 1) < SCHEDULE_VERSION)
    }

    fun save(context: Context, value: AppearanceSchedule) {
        preferences(context).edit().putString("mode", value.mode.name)
            .putInt("day_start", value.dayStart).putInt("night_start", value.nightStart)
            .putInt("schedule_version", SCHEDULE_VERSION).apply()
    }

    fun isDay(context: Context): Boolean = schedule(context).isDay(
        LocalTime.now().toSecondOfDay() / 60, SessionController.currentClient?.takeUnless { it.cancelled }?.receiverNightMode(),
    )
}

/** Refresh only the visible projected controls; keep the live map, media session and displays. */
abstract class CarAppearanceActivity : AppCompatActivity() {
    private var cachedPalette: ProjectedPalette? = null
    internal val carPalette: ProjectedPalette
        get() = cachedPalette ?: ProjectedPalette(this, CarAppearance.isDay(this)).also { cachedPalette = it }
    private var appliedDay: Boolean? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshAppearance() }
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshAppearance()
    }

    private fun refreshAppearance() {
        val day = CarAppearance.isDay(this)
        if (appliedDay != day) {
            appliedDay = day
            cachedPalette = ProjectedPalette(this, day)
            onCarAppearanceChanged(day)
        }
    }

    protected abstract fun onCarAppearanceChanged(day: Boolean)

    override fun onStart() {
        super.onStart()
        CarAppearance.preferences(this).registerOnSharedPreferenceChangeListener(preferenceListener)
        val filter = IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(CarAppearance.VEHICLE_MODE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, clockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshAppearance()
    }

    override fun onStop() {
        unregisterReceiver(clockReceiver)
        CarAppearance.preferences(this).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onStop()
    }
}
