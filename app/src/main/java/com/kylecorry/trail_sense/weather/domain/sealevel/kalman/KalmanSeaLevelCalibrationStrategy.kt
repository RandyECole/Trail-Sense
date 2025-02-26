package com.kylecorry.trail_sense.weather.domain.sealevel.kalman

import com.kylecorry.sol.units.Pressure
import com.kylecorry.sol.units.Reading
import com.kylecorry.trail_sense.weather.domain.RawWeatherObservation
import com.kylecorry.trail_sense.weather.domain.sealevel.ISeaLevelCalibrationStrategy
import kotlin.math.pow

class KalmanSeaLevelCalibrationStrategy(private val settings: KalmanSeaLevelCalibrationSettings) :
    ISeaLevelCalibrationStrategy {
    override fun calibrate(readings: List<Reading<RawWeatherObservation>>): List<Reading<Pressure>> {
        return KalmanSeaLevelPressureConverter(
            altitudeOutlierThreshold = settings.altitudeOutlierThreshold,
            defaultGPSError = if (settings.useAltitudeVariance) 34f.pow(2) else 34f,
            defaultPressureError = 1f,
            pressureProcessError = (1 - settings.pressureSmoothing / 100f).pow(4) * 0.1f,
            altitudeProcessError = (1 - settings.altitudeSmoothing / 100f).pow(4) * 10f,
            adjustWithTime = true,
            replaceLastOutlier = true
        ).convert(readings, settings.useTemperature)
    }
}