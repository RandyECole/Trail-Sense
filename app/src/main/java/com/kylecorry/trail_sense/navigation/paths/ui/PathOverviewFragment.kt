package com.kylecorry.trail_sense.navigation.paths.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.kylecorry.andromeda.alerts.toast
import com.kylecorry.andromeda.core.system.Resources
import com.kylecorry.andromeda.core.time.Throttle
import com.kylecorry.andromeda.core.time.Timer
import com.kylecorry.andromeda.core.topics.asLiveData
import com.kylecorry.andromeda.core.tryOrNothing
import com.kylecorry.andromeda.fragments.BoundFragment
import com.kylecorry.andromeda.fragments.show
import com.kylecorry.andromeda.pickers.Pickers
import com.kylecorry.sol.math.Range
import com.kylecorry.sol.units.Distance
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.databinding.FragmentPathOverviewBinding
import com.kylecorry.trail_sense.databinding.ListItemWaypointBinding
import com.kylecorry.trail_sense.navigation.beacons.infrastructure.BeaconNavigator
import com.kylecorry.trail_sense.navigation.beacons.infrastructure.IBeaconNavigator
import com.kylecorry.trail_sense.navigation.beacons.infrastructure.persistence.BeaconService
import com.kylecorry.trail_sense.navigation.domain.hiking.HikingDifficulty
import com.kylecorry.trail_sense.navigation.domain.hiking.HikingService
import com.kylecorry.trail_sense.navigation.paths.domain.Path
import com.kylecorry.trail_sense.navigation.paths.domain.PathPoint
import com.kylecorry.trail_sense.navigation.paths.domain.PathPointColoringStyle
import com.kylecorry.trail_sense.navigation.paths.domain.beacon.IPathPointBeaconConverter
import com.kylecorry.trail_sense.navigation.paths.domain.beacon.TemporaryPathPointBeaconConverter
import com.kylecorry.trail_sense.navigation.paths.domain.factories.*
import com.kylecorry.trail_sense.navigation.paths.domain.point_finder.NearestPathLineNavigator
import com.kylecorry.trail_sense.navigation.paths.domain.point_finder.NearestPathPointNavigator
import com.kylecorry.trail_sense.navigation.paths.domain.waypointcolors.DefaultPointColoringStrategy
import com.kylecorry.trail_sense.navigation.paths.domain.waypointcolors.NoDrawPointColoringStrategy
import com.kylecorry.trail_sense.navigation.paths.domain.waypointcolors.SelectedPointDecorator
import com.kylecorry.trail_sense.navigation.paths.infrastructure.commands.BacktrackCommand
import com.kylecorry.trail_sense.navigation.paths.infrastructure.persistence.PathService
import com.kylecorry.trail_sense.navigation.paths.ui.commands.*
import com.kylecorry.trail_sense.shared.*
import com.kylecorry.trail_sense.shared.declination.DeclinationFactory
import com.kylecorry.trail_sense.shared.extensions.onDefault
import com.kylecorry.trail_sense.shared.extensions.onIO
import com.kylecorry.trail_sense.shared.extensions.onMain
import com.kylecorry.trail_sense.shared.extensions.range
import com.kylecorry.trail_sense.shared.io.IOFactory
import com.kylecorry.trail_sense.shared.navigation.NavControllerAppNavigation
import com.kylecorry.trail_sense.shared.sensors.SensorService
import java.time.Duration


class PathOverviewFragment : BoundFragment<FragmentPathOverviewBinding>() {

    private val prefs by lazy { UserPreferences(requireContext()) }
    private val formatService by lazy { FormatService(requireContext()) }
    private val throttle = Throttle(20)

    private val sensorService by lazy { SensorService(requireContext()) }
    private val gps by lazy { sensorService.getGPS(false) }
    private val compass by lazy { sensorService.getCompass() }
    private val hikingService = HikingService()
    private val pathService by lazy { PathService.getInstance(requireContext()) }
    private val declination by lazy { DeclinationFactory().getDeclinationStrategy(prefs, gps) }

    private var pointSheet: PathPointsListFragment? = null

    private lateinit var chart: PathElevationChart
    private var path: Path? = null
    private var waypoints: List<PathPoint> = emptyList()
    private var pathId: Long = 0L
    private var selectedPointId: Long? = null
    private var calculatedDuration = Duration.ZERO
    private var elevationGain = Distance.meters(0f)
    private var elevationLoss = Distance.meters(0f)
    private var elevationRange: Range<Distance>? = null
    private var slopes: List<Triple<PathPoint, PathPoint, Float>> = emptyList()
    private var difficulty = HikingDifficulty.Easiest

    private val paceFactor = 1.75f

    private var isFullscreen = false

    private val converter: IPathPointBeaconConverter by lazy {
        TemporaryPathPointBeaconConverter(
            getString(R.string.waypoint)
        )
    }

    private val beaconNavigator: IBeaconNavigator by lazy {
        BeaconNavigator(
            BeaconService(requireContext()),
            NavControllerAppNavigation(findNavController())
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pathId = requireArguments().getLong("path_id")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chart = PathElevationChart(binding.chart, prefs.navigation.showPathSlope)

        binding.pathImage.isInteractive = true

        binding.pathImage.setOnTouchListener { v, event ->
            binding.root.isScrollable = event.action == MotionEvent.ACTION_UP
            false
        }

        binding.pathMapFullscreenToggle.setOnClickListener {
            isFullscreen = !isFullscreen
            binding.pathMapHolder.layoutParams = if (isFullscreen) {
                val legendHeight = Resources.dp(requireContext(), 72f).toInt()
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    binding.root.height - legendHeight
                )
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Resources.dp(requireContext(), 250f).toInt()
                ).also {
                    it.marginStart = Resources.dp(requireContext(), 16f).toInt()
                    it.marginEnd = Resources.dp(requireContext(), 16f).toInt()
                }
            }
            binding.pathMapFullscreenToggle.setImageResource(if (isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_recenter)
            val timer = Timer {
                if (isBound) {
                    binding.root.scrollTo(0, binding.pathMapHolder.top)
                    binding.pathImage.recenter()
                }
            }
            timer.once(Duration.ofMillis(30))
        }

        binding.pathImage.setOnPointClickListener {
            viewWaypoint(it)
        }

        binding.addPointBtn.setOnClickListener {
            runInBackground {
                binding.addPointBtn.isEnabled = false
                BacktrackCommand(requireContext(), pathId).execute()
                if (isBound) {
                    toast(getString(R.string.point_added))
                    binding.addPointBtn.isEnabled = true
                }
            }
        }

        binding.navigateBtn.setOnClickListener {
            navigateToNearestPathPoint()
        }

        binding.pathTitle.rightQuickAction.setOnClickListener {
            showPathMenu()
        }

        binding.pathTitle.subtitle.setOnClickListener {
            movePath()
        }

        chart.setOnPointClickListener {
            viewWaypoint(it)
        }

        binding.pathElevationMin.setOnClickListener {
            val point = waypoints.filter { it.elevation != null }.minByOrNull { it.elevation!! }
            point?.let { viewWaypoint(it) }
        }

        binding.pathElevationMax.setOnClickListener {
            val point = waypoints.filter { it.elevation != null }.maxByOrNull { it.elevation!! }
            point?.let { viewWaypoint(it) }
        }

        pathService.getLivePath(pathId).observe(viewLifecycleOwner) {
            path = it
            updateParent()
            updateElevationPlot()
            updatePointStyleLegend()
            updatePathMap()
            onPathChanged()
        }

        pathService.getWaypointsLive(pathId).observe(viewLifecycleOwner) {
            waypoints = it.sortedByDescending { p -> p.id }
            val selected = selectedPointId
            if (selected != null && waypoints.find { it.id == selected } == null) {
                deselectPoint()
            }

            pointSheet?.setPoints(waypoints)
            updateElevationOverview()
            updateHikingStats()
            updatePathMap()
            updatePointStyleLegend()
            onPathChanged()
        }

        gps.asLiveData().observe(viewLifecycleOwner) {
            compass.declination = getDeclination()
            onPathChanged()
        }

        compass.asLiveData().observe(viewLifecycleOwner) {
            onPathChanged()
        }

        binding.pathLineStyle.setOnClickListener {
            val path = path ?: return@setOnClickListener
            val command = ChangePathLineStyleCommand(requireContext(), lifecycleScope)
            command.execute(path)
        }

        binding.pathColor.setOnClickListener {
            val path = path ?: return@setOnClickListener
            val command = ChangePathColorCommand(requireContext(), lifecycleScope)
            command.execute(path)
        }

        binding.pathPointStyle.setOnClickListener {
            val path = path ?: return@setOnClickListener
            val command = ChangePointStyleCommand(requireContext(), lifecycleScope)
            command.execute(path)
        }
    }

    private fun updateParent() {
        val path = path ?: return
        runInBackground {
            val parent = onIO { pathService.getGroup(path.parentId) }
            if (isBound) {
                binding.pathTitle.subtitle.text = parent?.name ?: getString(R.string.no_group)
            }
        }
    }

    private fun updateElevationPlot() {
        chart.plot(
            waypoints.reversed(),
            path?.style?.color ?: prefs.navigation.defaultPathColor.color
        )
    }

    private fun updateHikingStats() {
        runInBackground {
            onDefault {
                val reversed = waypoints.reversed()
                calculatedDuration =
                    hikingService.getHikingDuration(reversed, paceFactor)
                difficulty = hikingService.getHikingDifficulty(reversed)
            }
        }
    }

    private fun movePath() {
        val path = path ?: return
        val command = MoveIPathCommand(requireContext(), pathService)
        runInBackground {
            command.execute(path)
        }
    }

    private fun updateElevationOverview() {
        runInBackground {
            onDefault {
                val path = waypoints.reversed()

                val gainLoss = hikingService.getElevationLossGain(path)

                val units = prefs.baseDistanceUnits
                elevationGain = gainLoss.second.convertTo(units)
                elevationLoss = gainLoss.first.convertTo(units)
                elevationRange =
                    path.mapNotNull { it.elevation?.let { Distance.meters(it).convertTo(units) } }
                        .range()
                slopes = hikingService.getSlopes(path)
                slopes.forEach {
                    it.first.slope = it.third
                }
                val first = slopes.lastOrNull()
                first?.first?.slope = first?.third ?: 0f
            }
            onMain {
                updateElevationPlot()
            }
        }
    }

    private fun showPathMenu() {
        val path = path ?: return
        val actions = listOf(
            PathAction.Rename,
            PathAction.Keep,
            PathAction.ToggleVisibility,
            PathAction.Export,
            PathAction.Simplify,
            PathAction.ViewPoints
        )

        Pickers.menu(
            binding.pathTitle.rightQuickAction, listOf(
                getString(R.string.rename),
                if (path.temporary) getString(R.string.keep_forever) else null,
                if (prefs.navigation.useRadarCompass || prefs.navigation.areMapsEnabled) {
                    if (path.style.visible) getString(R.string.hide) else getString(
                        R.string.show
                    )
                } else null,
                getString(R.string.export),
                getString(R.string.simplify),
                getString(R.string.points)
            )
        ) {
            when (actions[it]) {
                PathAction.Export -> exportPath(path)
                PathAction.Rename -> renamePath(path)
                PathAction.Keep -> keepPath(path)
                PathAction.ToggleVisibility -> togglePathVisibility(path)
                PathAction.Simplify -> simplifyPath(path)
                PathAction.ViewPoints -> viewPoints()
                else -> {
                    // Do nothing
                }
            }
            true
        }
    }

    private fun simplifyPath(path: Path) {
        val command = SimplifyPathCommand(requireContext(), lifecycleScope, pathService)
        command.execute(path)
    }

    private fun exportPath(path: Path) {
        val command = ExportPathCommand(
            requireContext(),
            lifecycleScope,
            IOFactory().createGpxService(this),
            pathService
        )
        command.execute(path)
    }

    private fun togglePathVisibility(path: Path) {
        val command = TogglePathVisibilityCommand(requireContext(), lifecycleScope, pathService)
        command.execute(path)
    }

    private fun renamePath(path: Path) {
        val command = RenamePathCommand(requireContext(), lifecycleScope, pathService)
        command.execute(path)
    }

    private fun keepPath(path: Path) {
        val command = KeepPathCommand(requireContext(), lifecycleScope, pathService)
        command.execute(path)
    }

    private fun updatePathMap() {
        val path = path ?: return
        if (!isBound) {
            return
        }
        binding.pathImage.pathColor = path.style.color
        binding.pathImage.pathStyle = path.style.line
        binding.pathImage.path = waypoints
    }

    private fun onPathChanged() {
        val path = path ?: return

        if (!isBound || throttle.isThrottled()) {
            return
        }

        binding.pathLineStyle.text = listOf(
            getString(R.string.solid),
            getString(R.string.dotted),
            getString(R.string.arrow),
            getString(R.string.dashed),
            getString(R.string.square),
            getString(R.string.diamond),
            getString(R.string.cross)
        )[path.style.line.ordinal]

        val distance =
            path.metadata.distance.convertTo(prefs.baseDistanceUnits).toRelativeDistance()

        val start = path.metadata.duration?.start
        val end = path.metadata.duration?.end


        binding.pathTitle.title.text = PathNameFactory(requireContext()).getName(path)

        val duration = if (start != null && end != null && Duration.between(
                start,
                end
            ) > Duration.ofMinutes(1)
        ) {
            Duration.between(start, end)
        } else {
            calculatedDuration
        }

        binding.pathDuration.title = formatService.formatDuration(duration, false)
        binding.pathWaypoints.title = path.metadata.waypoints.toString()

        // Elevations
        binding.pathElevationGain.title = formatService.formatDistance(
            elevationGain,
            Units.getDecimalPlaces(elevationGain.units),
            false
        )
        binding.pathElevationLoss.title = formatService.formatDistance(
            elevationLoss,
            Units.getDecimalPlaces(elevationLoss.units),
            false
        )

        val elevationRange = elevationRange
        binding.pathElevationMin.isVisible = elevationRange != null
        binding.pathElevationMax.isVisible = elevationRange != null

        elevationRange?.let {
            binding.pathElevationMin.title = formatService.formatDistance(
                it.start,
                Units.getDecimalPlaces(it.start.units),
                false
            )

            binding.pathElevationMax.title = formatService.formatDistance(
                it.end,
                Units.getDecimalPlaces(it.end.units),
                false
            )
        }

        binding.pathDifficulty.title = formatService.formatHikingDifficulty(difficulty)

        binding.pathDistance.title =
            formatService.formatDistance(
                distance,
                Units.getDecimalPlaces(distance.units),
                false
            )

        CustomUiUtils.setImageColor(binding.pathColor, path.style.color)

        binding.pathImage.location = gps.location
        binding.pathImage.azimuth = compass.bearing.value

        val factory = getPointFactory()

        val baseStrategy = factory.createColoringStrategy(waypoints)

        val selected = selectedPointId

        binding.pathImage.pointColoringStrategy = if (selected == null) {
            baseStrategy
        } else {
            SelectedPointDecorator(
                selected,
                DefaultPointColoringStrategy(Color.WHITE),
                NoDrawPointColoringStrategy()
            )
        }
    }

    private fun deselectPoint() {
        selectedPointId = null
        binding.pathSelectedPoint.isVisible = false
    }

    private fun updatePointStyleLegend() {

        val path = path ?: return

        val factory = getPointFactory()

        binding.pathPointStyle.text = listOf(
            getString(R.string.none),
            getString(R.string.cell_signal),
            getString(R.string.elevation),
            getString(R.string.time),
            getString(R.string.path_slope)
        )[path.style.point.ordinal]

        binding.pathLegend.colorScale = factory.createColorScale(waypoints)
        binding.pathLegend.labels = factory.createLabelMap(waypoints)
        binding.pathLegend.isVisible = path.style.point != PathPointColoringStyle.None
    }

    override fun generateBinding(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentPathOverviewBinding {
        return FragmentPathOverviewBinding.inflate(layoutInflater, container, false)
    }

    private fun getDeclination(): Float {
        return declination.getDeclination()
    }

    // Waypoints
    private fun drawWaypointListItem(itemBinding: ListItemWaypointBinding, item: PathPoint) {
        val itemStrategy = WaypointListItem(
            requireContext(),
            formatService,
            { createBeacon(it) },
            { deleteWaypoint(it) },
            { navigateToWaypoint(it) },
            { /* Do nothing */ }
        )

        itemStrategy.display(itemBinding, item)
    }

    private fun viewPoints() {
        binding.root.scrollTo(0, 0)
        pointSheet = PathPointsListFragment()
        pointSheet?.show(this)
        pointSheet?.onCreateBeaconListener = { createBeacon(it) }
        pointSheet?.onDeletePointListener = { deleteWaypoint(it) }
        pointSheet?.onNavigateToPointListener = { navigateToWaypoint(it) }
        pointSheet?.onViewPointListener = { viewWaypoint(it) }
        pointSheet?.setPoints(waypoints)
    }

    private fun viewWaypoint(point: PathPoint) {
        selectedPointId = if (selectedPointId == point.id) {
            null
        } else {
            point.id
        }

        binding.pathSelectedPoint.removeAllViews()

        if (selectedPointId != null) {
            binding.pathSelectedPoint.isVisible = true
            val binding =
                ListItemWaypointBinding.inflate(layoutInflater, binding.pathSelectedPoint, true)
            drawWaypointListItem(binding, point)
        } else {
            deselectPoint()
        }

        onPathChanged()
    }

    private fun navigateToWaypoint(point: PathPoint) {
        val path = path ?: return
        val command = NavigateToPointCommand(
            lifecycleScope,
            converter,
            beaconNavigator
        )
        tryOrNothing {
            command.execute(path, point)
        }
    }

    private fun navigateToNearestPathPoint() {
        val path = path ?: return
        val points = waypoints
        val command = NavigateToPathCommand(
            if (prefs.navigation.onlyNavigateToPoints) NearestPathPointNavigator() else NearestPathLineNavigator(),
            gps,
            converter,
            beaconNavigator
        )

        toast(getString(R.string.navigating_to_nearest_path_point))

        runInBackground {
            command.execute(path, points)
        }
    }

    private fun deleteWaypoint(point: PathPoint) {
        val path = path ?: return
        val command = DeletePointCommand(requireContext(), lifecycleScope)
        command.execute(path, point)
    }

    private fun createBeacon(point: PathPoint) {
        val path = path ?: return
        val command = CreateBeaconFromPointCommand(requireContext())
        command.execute(path, point)
    }

    private fun getPointFactory(): IPointDisplayFactory {
        return when (path?.style?.point) {
            PathPointColoringStyle.CellSignal -> CellSignalPointDisplayFactory(requireContext())
            PathPointColoringStyle.Altitude -> AltitudePointDisplayFactory(requireContext())
            PathPointColoringStyle.Time -> TimePointDisplayFactory(requireContext())
            PathPointColoringStyle.Slope -> SlopePointDisplayFactory(requireContext())
            else -> NonePointDisplayFactory()
        }
    }
}