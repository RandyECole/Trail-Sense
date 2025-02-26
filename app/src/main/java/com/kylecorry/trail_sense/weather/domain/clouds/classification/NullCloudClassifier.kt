package com.kylecorry.trail_sense.weather.domain.clouds.classification

import android.graphics.Bitmap
import com.kylecorry.sol.science.meteorology.clouds.CloudGenus
import com.kylecorry.trail_sense.shared.ClassificationResult

class NullCloudClassifier: ICloudClassifier {
    override suspend fun classify(bitmap: Bitmap): List<ClassificationResult<CloudGenus>> {
        return emptyList()
    }
}