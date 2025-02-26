package com.kylecorry.trail_sense.calibration.ui

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.kylecorry.andromeda.core.coroutines.ControlledRunner
import com.kylecorry.andromeda.core.sensors.IAltimeter
import com.kylecorry.andromeda.core.sensors.IThermometer
import com.kylecorry.andromeda.core.system.Resources
import com.kylecorry.andromeda.core.time.Throttle
import com.kylecorry.andromeda.core.topics.asLiveData
import com.kylecorry.andromeda.fragments.AndromedaPreferenceFragment
import com.kylecorry.andromeda.location.GPS
import com.kylecorry.andromeda.location.IGPS
import com.kylecorry.andromeda.sense.barometer.IBarometer
import com.kylecorry.sol.units.Distance
import com.kylecorry.sol.units.Pressure
import com.kylecorry.sol.units.PressureUnits
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.settings.ui.PressureChartPreference
import com.kylecorry.trail_sense.shared.FormatService
import com.kylecorry.trail_sense.shared.Units
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.shared.sensors.SensorService
import com.kylecorry.trail_sense.weather.domain.RawWeatherObservation
import com.kylecorry.trail_sense.weather.infrastructure.WeatherObservation
import com.kylecorry.trail_sense.weather.infrastructure.subsystem.WeatherSubsystem
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class CalibrateBarometerFragment : AndromedaPreferenceFragment() {

    private lateinit var prefs: UserPreferences
    private lateinit var sensorService: SensorService
    private val throttle = Throttle(20)

    private var pressureTxt: Preference? = null
    private var seaLevelSwitch: SwitchPreferenceCompat? = null
    private var altitudeOutlierSeekBar: SeekBarPreference? = null
    private var pressureSmoothingSeekBar: SeekBarPreference? = null
    private var altitudeSmoothingSeekBar: SeekBarPreference? = null

    private var chart: PressureChartPreference? = null

    private var history: List<WeatherObservation> = listOf()

    private lateinit var barometer: IBarometer
    private lateinit var altimeter: IAltimeter
    private lateinit var thermometer: IThermometer

    private lateinit var units: PressureUnits
    private val formatService by lazy { FormatService(requireContext()) }

    private val weatherSubsystem by lazy { WeatherSubsystem.getInstance(requireContext()) }

    private val runner = ControlledRunner<List<WeatherObservation>>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.barometer_calibration, rootKey)

        setIconColor(Resources.androidTextColorSecondary(requireContext()))

        prefs = UserPreferences(requireContext())
        sensorService = SensorService(requireContext())
        units = prefs.pressureUnits

        barometer = sensorService.getBarometer()
        altimeter = sensorService.getGPSAltimeter()
        thermometer = sensorService.getThermometer()

        bindPreferences()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        weatherSubsystem.weatherChanged.asLiveData().observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                history = runner.cancelPreviousThenRun {
                    weatherSubsystem.getHistory()
                }
            }
        }
    }

    private fun bindPreferences() {
        altitudeOutlierSeekBar = seekBar(R.string.pref_barometer_altitude_outlier)
        pressureSmoothingSeekBar = seekBar(R.string.pref_barometer_pressure_smoothing)
        altitudeSmoothingSeekBar = seekBar(R.string.pref_barometer_altitude_smoothing)

        pressureTxt = findPreference(getString(R.string.pref_holder_pressure))
        seaLevelSwitch = findPreference(getString(R.string.pref_use_sea_level_pressure))
        chart = findPreference(getString(R.string.pref_holder_pressure_chart))

        altitudeOutlierSeekBar?.summary =
            (if (prefs.weather.altitudeOutlier == 0f) "" else "± ") + formatSmallDistance(
                prefs.weather.altitudeOutlier
            )

        pressureSmoothingSeekBar?.summary =
            formatService.formatPercentage(prefs.weather.pressureSmoothing)
        altitudeSmoothingSeekBar?.summary =
            formatService.formatPercentage(prefs.weather.altitudeSmoothing)



        seaLevelSwitch?.setOnPreferenceClickListener {
            if (!altimeter.hasValidReading) {
                altimeter.start(this::updateAltitude)
            }
            true
        }

        altitudeOutlierSeekBar?.updatesContinuously = true
        altitudeOutlierSeekBar?.setOnPreferenceChangeListener { _, newValue ->
            altitudeOutlierSeekBar?.summary =
                (if (newValue.toString()
                        .toFloat() == 0f
                ) "" else "± ") + formatSmallDistance(
                    newValue.toString().toFloat()
                )
            true
        }

        pressureSmoothingSeekBar?.updatesContinuously = true
        pressureSmoothingSeekBar?.setOnPreferenceChangeListener { _, newValue ->
            val change = 100 * newValue.toString().toFloat() / 1000f
            pressureSmoothingSeekBar?.summary = formatService.formatPercentage(change)
            true
        }

        altitudeSmoothingSeekBar?.updatesContinuously = true
        altitudeSmoothingSeekBar?.setOnPreferenceChangeListener { _, newValue ->
            val change = 100 * newValue.toString().toFloat() / 1000f
            altitudeSmoothingSeekBar?.summary = formatService.formatPercentage(change)
            true
        }

        preference(R.string.pref_barometer_info_holder)?.icon?.setTint(
            Resources.getAndroidColorAttr(
                requireContext(),
                android.R.attr.textColorSecondary
            )
        )

    }

    private fun formatSmallDistance(meters: Float): String {
        val distance = Distance.meters(meters).convertTo(prefs.baseDistanceUnits)
        return formatService.formatDistance(distance)
    }

    private fun updateChart() {
        val displayReadings = history.filter {
            Duration.between(
                it.time,
                Instant.now()
            ) <= prefs.weather.pressureHistory
        }.map { it.pressureReading() }
        if (displayReadings.isNotEmpty()) {
            chart?.plot(displayReadings)
        }
    }

    override fun onResume() {
        super.onResume()
        startBarometer()
        thermometer.start(this::updateTemperature)
        if (prefs.weather.useSeaLevelPressure && !altimeter.hasValidReading) {
            altimeter.start(this::updateAltitude)
        }
    }

    override fun onPause() {
        super.onPause()
        stopBarometer()
        altimeter.stop(this::updateAltitude)
        thermometer.stop(this::updateTemperature)
    }

    private fun updateAltitude(): Boolean {
        update()
        return false
    }

    private fun updateTemperature(): Boolean {
        update()
        return true
    }

    private fun startBarometer() {
        barometer.start(this::onPressureUpdate)
    }

    private fun stopBarometer() {
        barometer.stop(this::onPressureUpdate)
    }


    private fun onPressureUpdate(): Boolean {
        update()
        return true
    }

    private fun update() {

        if (throttle.isThrottled()) {
            return
        }

        updateChart()

        val isOnTheWallMode =
            prefs.altimeterMode == UserPreferences.AltimeterMode.Override || !GPS.isAvailable(
                requireContext()
            )

        val seaLevelPressure = prefs.weather.useSeaLevelPressure

        altitudeOutlierSeekBar?.isVisible = !isOnTheWallMode
        pressureSmoothingSeekBar?.isVisible = !isOnTheWallMode
        altitudeSmoothingSeekBar?.isVisible = !isOnTheWallMode

        val pressure = if (seaLevelPressure) {
            // TODO: This isn't going to exactly match what is shown on the weather tab
            RawWeatherObservation(
                0,
                barometer.pressure,
                altimeter.altitude,
                thermometer.temperature,
                if (altimeter is IGPS) (altimeter as IGPS).verticalAccuracy else null
            ).seaLevel(prefs.weather.seaLevelFactorInTemp).pressure
        } else {
            barometer.pressure
        }

        pressureTxt?.summary =
            formatService.formatPressure(
                Pressure(pressure, PressureUnits.Hpa).convertTo(units),
                Units.getDecimalPlaces(units)
            )
    }

}