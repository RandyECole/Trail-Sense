package com.kylecorry.trail_sense.navigation.paths.infrastructure.subsystem

import android.annotation.SuppressLint
import android.content.Context
import com.kylecorry.andromeda.core.topics.generic.ITopic
import com.kylecorry.andromeda.core.topics.generic.Topic
import com.kylecorry.andromeda.core.topics.generic.distinct
import com.kylecorry.andromeda.preferences.Preferences
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.navigation.paths.infrastructure.BacktrackScheduler
import com.kylecorry.trail_sense.navigation.paths.infrastructure.commands.StopBacktrackCommand
import com.kylecorry.trail_sense.shared.FeatureState
import com.kylecorry.trail_sense.shared.UserPreferences
import java.time.Duration
import java.util.*

class BacktrackSubsystem private constructor(private val context: Context) {

    private val sharedPrefs by lazy { Preferences(context) }
    private val prefs by lazy { UserPreferences(context) }

    private val _backtrackStateChanged = Topic(defaultValue = Optional.of(calculateBacktrackState()))
    val backtrackStateChanged: ITopic<FeatureState> = _backtrackStateChanged.distinct()

    private val _backtrackFrequencyChanged = Topic(defaultValue = Optional.of(calculateBacktrackFrequency()))
    val backtrackFrequencyChanged: ITopic<Duration> = _backtrackFrequencyChanged.distinct()

    private val stateChangePrefKeys = listOf(
        R.string.pref_backtrack_enabled,
        R.string.pref_low_power_mode,
        R.string.pref_low_power_mode_backtrack
    ).map { context.getString(it) }

    private val frequencyChangePrefKeys = listOf(
        R.string.pref_backtrack_frequency
    ).map { context.getString(it) }

    init {
        sharedPrefs.onChange.subscribe { key ->
            if (key in stateChangePrefKeys) {
                val state = calculateBacktrackState()
                _backtrackStateChanged.notifySubscribers(state)
            }

            if (key in frequencyChangePrefKeys){
                val frequency = calculateBacktrackFrequency()
                _backtrackFrequencyChanged.notifySubscribers(frequency)
            }
            true
        }
    }

    fun enable(startNewPath: Boolean) {
        prefs.backtrackEnabled = true
        BacktrackScheduler.start(context, startNewPath)
    }

    fun disable() {
        StopBacktrackCommand(context).execute()
    }

    private fun calculateBacktrackState(): FeatureState {
        return if (BacktrackScheduler.isOn(context)) {
            FeatureState.On
        } else if (BacktrackScheduler.isDisabled(context)) {
            FeatureState.Unavailable
        } else {
            FeatureState.Off
        }
    }

    private fun calculateBacktrackFrequency(): Duration {
        return prefs.backtrackRecordFrequency
    }


    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: BacktrackSubsystem? = null

        @Synchronized
        fun getInstance(context: Context): BacktrackSubsystem {
            if (instance == null) {
                instance = BacktrackSubsystem(context.applicationContext)
            }
            return instance!!
        }
    }

}