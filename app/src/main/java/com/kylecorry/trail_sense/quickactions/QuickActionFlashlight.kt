package com.kylecorry.trail_sense.quickactions

import android.view.View
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import com.kylecorry.andromeda.core.time.Timer
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.CustomUiUtils
import com.kylecorry.trail_sense.shared.QuickActionButton
import com.kylecorry.trail_sense.tools.flashlight.domain.FlashlightState
import com.kylecorry.trail_sense.tools.flashlight.infrastructure.FlashlightSubsystem

class QuickActionFlashlight(btn: ImageButton, fragment: Fragment) :
    QuickActionButton(btn, fragment) {

    private var flashlightState = FlashlightState.Off
    private val flashlight by lazy { FlashlightSubsystem.getInstance(context) }
    private val intervalometer = Timer {
        flashlightState = flashlight.getState()
        updateFlashlightUI()
    }

    private fun updateFlashlightUI() {
        CustomUiUtils.setButtonState(button, flashlightState == FlashlightState.On)
    }

    override fun onCreate() {
        super.onCreate()
        button.setImageResource(R.drawable.flashlight)
        CustomUiUtils.setButtonState(button, false)
        if (!flashlight.isAvailable()) {
            button.visibility = View.GONE
        } else {
            button.setOnClickListener {
                flashlight.toggle()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!intervalometer.isRunning()) {
            intervalometer.interval(20)
        }
    }

    override fun onPause() {
        super.onPause()
        intervalometer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        onPause()
    }

}