package com.kylecorry.trail_sense.navigation.paths.infrastructure.persistence

import com.kylecorry.trail_sense.navigation.paths.domain.PathStyle
import java.time.Duration

interface IPathPreferences {
    val defaultPathStyle: PathStyle
    val backtrackHistory: Duration
    val simplifyPathOnImport: Boolean
    val onlyNavigateToPoints: Boolean
    val showPathSlope: Boolean
}