package au.opendepth.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.Icon
import com.mapbox.mapboxsdk.annotations.IconFactory
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.*
import com.mapbox.mapboxsdk.style.sources.*
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import kotlin.math.*

data class Waypoint(val id: String, var name: String, var lat: Double, var lng: Double, var color: String = "#EF4444", var desc: String? = null)
data class RoutePoint(val lat: Double, val lng: Double)
data class MbtLayer(val name: String, val path: String, val visible: Boolean = true, val opacity: Float = 1f)

class MainActivity : AppCompatActivity() {

    private val TAG = "SeaBathy"
    private lateinit var mapView: MapView
    private var map: MapboxMap? = null
    private var style: Style? = null
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var cursorColor = "#00C8B4"
    private var coordColor = "#00C8B4"
    private var showDepth = true
    private var depthDisplayMode = "both"
    private var depthTextSize = 12f
    private var coordTextSize = 13f
    private var uiThemeMode = "graphite"
    private var uiAccentHex = "#087F73"
    private var uiPanelHex = "#181D22"
    private var uiCompact = false
    private var uiRounded = true
    private var customBasemapUrl = ""
    private var customBasemapAttribution = ""
    private var currentBasemap = "dark"
    private var styleGeneration = 0
    private var mbtRestoreJob: Job? = null

    private var tool = "none"
    private var gpsOn = false
    private var gpsMode = 0 // 0 off, 1 locate/free pan, 2 follow lock
    private var gpsCenteredOnce = false
    private var gpsListener: LocationListener? = null
    private val waypoints = mutableListOf<Waypoint>()
    private val waypointMarkers = mutableMapOf<Marker, String>()
    private val waypointIconCache = mutableMapOf<String, Icon>()
    private val routePts = mutableListOf<RoutePoint>()
    private val measurePts = mutableListOf<RoutePoint>()
    private val mbtDbs = mutableMapOf<String, SQLiteDatabase>()
    private val mbtLayerIds = mutableListOf<String>()
    private val mbtFilePaths = mutableMapOf<String, String>()
    private val mbtLayerNames = mutableMapOf<String, String>()
    private val mbtLayerVisible = mutableMapOf<String, Boolean>()
    private val mbtLayerOpacity = mutableMapOf<String, Float>()
    private var selectedColor = "#EF4444"
    private val COLORS = listOf("#EF4444","#F97316","#EAB308","#22C55E","#3B82F6","#A855F7")
    private var pendingWptLat = 0.0
    private var pendingWptLng = 0.0
    private val TILE_PORT = 7070
    private var tileServerThread: Thread? = null
    private val tileExecutor = java.util.concurrent.ThreadPoolExecutor(
        2, 4, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(8),
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val mbtilesPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { loadMbtilesUri(it) }
        }
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) startGps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("od", MODE_PRIVATE)
        loadVisualSettings()
        loadUiAppearance()
        customBasemapUrl = prefs.getString("custom_basemap_url", "") ?: ""
        customBasemapAttribution = prefs.getString("custom_basemap_attribution", "") ?: ""
        currentBasemap = prefs.getString("last_basemap", "dark") ?: "dark"
        if (currentBasemap == "custom" && customBasemapUrl.isBlank()) currentBasemap = "dark"
        waypoints.addAll(loadWaypoints())
        setupInsets()
        setupMap(savedInstanceState)
        setupUI()
        applyUiAppearance()
        startTileServer()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.topBar)) { view, insets ->
            val h = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            findViewById<View>(R.id.statusBarSpacer).layoutParams.height = h
            view.requestLayout(); insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.routeCard)) { view, insets ->
            val h = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.findViewById<View>(R.id.navBarSpacer).layoutParams.height = h
            view.requestLayout(); insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.sidePanel)) { view, insets ->
            val h = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.findViewById<View>(R.id.panelStatusSpacer).layoutParams.height = h
            view.requestLayout(); insets
        }
        // Waypoint dialog: push above keyboard and nav bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.wptDialog)) { view, insets ->
            val h = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                .coerceAtLeast(insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            view.findViewById<View>(R.id.wptNavSpacer).layoutParams.height = h + 8.dp
            view.requestLayout(); insets
        }
    }

    private fun setupMap(savedState: Bundle?) {
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedState)
        mapView.getMapAsync { m ->
            map = m
            val savedLat = prefs.getFloat("camera_lat", -18.3f).toDouble()
            val savedLng = prefs.getFloat("camera_lng", 147.7f).toDouble()
            val savedZoom = prefs.getFloat("camera_zoom", 8f).toDouble()
            val savedBearing = prefs.getFloat("camera_bearing", 0f).toDouble()
            val savedTilt = prefs.getFloat("camera_tilt", 0f).toDouble()
            m.cameraPosition = CameraPosition.Builder().target(LatLng(savedLat, savedLng)).zoom(savedZoom).bearing(savedBearing).tilt(savedTilt).build()
            rebuildMapStyle(
                requestedType = currentBasemap,
                restoreSavedMbtiles = true
            )
            m.setOnMarkerClickListener { marker ->
                val waypointId = waypointMarkers[marker]
                val waypoint = waypoints.find { it.id == waypointId }
                if (waypoint != null) {
                    showWptEditDialog(waypoint)
                    true
                } else {
                    false
                }
            }
            m.addOnMapClickListener { ll ->
                handleMapTap(ll.latitude, ll.longitude)
                true
            }
            m.addOnCameraIdleListener {
                val camera = m.cameraPosition
                val target = camera.target ?: return@addOnCameraIdleListener
                prefs.edit().putFloat("camera_lat", target.latitude.toFloat()).putFloat("camera_lng", target.longitude.toFloat()).putFloat("camera_zoom", camera.zoom.toFloat()).putFloat("camera_bearing", camera.bearing.toFloat()).putFloat("camera_tilt", camera.tilt.toFloat()).apply()
            }

            m.addOnCameraMoveListener {
                val c = m.cameraPosition.target ?: return@addOnCameraMoveListener
                findViewById<TextView>(R.id.tvCoords).apply {
                    text = fmtDDM(c.latitude, c.longitude)
                    textSize = coordTextSize
                    setTextColor(Color.parseColor(coordColor))
                }
                if (tool != "measure") {
                    updateCrosshairCoords(c.latitude, c.longitude)
                    queryDepthAtCrosshair(c.latitude, c.longitude) { depth ->
                        updateCrosshairDepth(depth)
                    }
                }
            }
            // Tap on crosshair centre opens waypoint dialog at map centre
            m.addOnMapLongClickListener { ll ->
                if (tool == "wpt") {
                    val c = map?.cameraPosition?.target
                    if (c != null) showWptDialog(c.latitude, c.longitude)
                    else showWptDialog(ll.latitude, ll.longitude)
                    true
                }
                else false
            }
        }
    }

    /**
     * Rebuilds all runtime map layers after a basemap/style change.
     * MapLibre destroys custom sources and layers whenever setStyle() runs,
     * so every style change must come through this function.
     */
    private fun rebuildMapStyle(
        requestedType: String = currentBasemap,
        restoreSavedMbtiles: Boolean = false,
        onReady: (() -> Unit)? = null
    ) {
        val type = if (requestedType == "custom" && customBasemapUrl.isBlank()) {
            Toast.makeText(
                this@MainActivity,
                "Configure Custom XYZ in Settings first",
                Toast.LENGTH_SHORT
            ).show()
            if (currentBasemap == "custom") "dark" else currentBasemap
        } else {
            requestedType
        }

        currentBasemap = type
        prefs.edit().putString("last_basemap", type).apply()

        val generation = ++styleGeneration
        mbtRestoreJob?.cancel()
        mbtRestoreJob = null

        applyBasemap(type) {
            if (generation != styleGeneration) return@applyBasemap

            addMapLayers()

            if (restoreSavedMbtiles && mbtLayerIds.isEmpty()) {
                restoreMbtLayers(generation) {
                    finishMapRebuild(generation, onReady)
                }
            } else {
                restoreLoadedMbtLayers(generation)
                finishMapRebuild(generation, onReady)
            }
        }
    }

    private fun finishMapRebuild(generation: Int, onReady: (() -> Unit)?) {
        if (generation != styleGeneration) return

        // Add waypoint layers last so they always remain above basemaps,
        // bathymetry, contours, routes and measurements.
        ensureWaypointLayers()
        refreshRoute()
        refreshMeasure()
        setCrosshair(true)
        applyVisualSettings()
        rebuildMbtPanelRows()
        onReady?.invoke()
    }

    /** Re-adds already-open MBTiles databases to a newly-created style. */
    private fun restoreLoadedMbtLayers(generation: Int) {
        mbtLayerIds.toList().forEach { id ->
            if (generation != styleGeneration) return
            val db = mbtDbs[id]
            val path = mbtFilePaths[id]
            if (db != null && path != null) {
                addMbtLayerToMap(id, db, path, generation)
            }
        }
    }

    private fun applyBasemap(type: String, onLoaded: (() -> Unit)? = null) {
        val noneJson = """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#111418"}}]}"""

        val builder = when (type) {
            "dark" -> Style.Builder().fromUri(
                "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
            )

            "osm" -> Style.Builder().fromUri(
                "https://demotiles.maplibre.org/style.json"
            )

            "custom" -> {
                if (customBasemapUrl.isBlank()) {
                    Toast.makeText(
                        this,
                        "Set a custom XYZ tile URL first",
                        Toast.LENGTH_SHORT
                    ).show()

                    Style.Builder().fromJson(noneJson)
                } else {
                    val escapedUrl = customBasemapUrl
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")

                    val customJson =
                        """{"version":8,"sources":{"custom":{"type":"raster","tiles":["$escapedUrl"],"tileSize":256,"maxzoom":22}},"layers":[{"id":"custom","type":"raster","source":"custom"}]}"""

                    Style.Builder().fromJson(customJson)
                }
            }

            else -> Style.Builder().fromJson(noneJson)
        }

        map?.setStyle(builder) { loadedStyle ->
            style = loadedStyle
            updateMapAttribution(type)
            onLoaded?.invoke()
        }
    }

    private fun updateMapAttribution(type: String) {
        val label = findViewById<TextView>(R.id.tvMapAttribution)

        val text = when (type) {
            "dark" -> "© OpenStreetMap contributors • © CARTO"
            "osm" -> "© OpenStreetMap contributors • MapLibre"
            "custom" -> customBasemapAttribution.trim()
                .ifBlank { "Custom online tiles" }
            else -> ""
        }

        label.text = text
        label.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private data class UiPalette(
        val background: Int,
        val panel: Int,
        val surface: Int,
        val raised: Int,
        val border: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val accent: Int,
        val onAccent: Int,
        val destructive: Int
    )

    private fun parseUiColor(value: String, fallback: String): Int =
        try { Color.parseColor(value) } catch (_: Exception) { Color.parseColor(fallback) }

    private fun readableText(background: Int): Int {
        val r = Color.red(background) / 255.0
        val g = Color.green(background) / 255.0
        val b = Color.blue(background) / 255.0
        fun channel(v: Double) = if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        val luminance = 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        return if (luminance > 0.42) Color.parseColor("#111418") else Color.WHITE
    }

    private fun activePalette(): UiPalette {
        return UiPalette(
            background = Color.parseColor("#111418"),
            panel = Color.parseColor("#181D22"),
            surface = Color.parseColor("#20262D"),
            raised = Color.parseColor("#29313A"),
            border = Color.parseColor("#3A434D"),
            primaryText = Color.parseColor("#F4F6F8"),
            secondaryText = Color.parseColor("#AEB7C2"),
            accent = Color.parseColor("#087F73"),
            onAccent = Color.WHITE,
            destructive = Color.parseColor("#EF5350")
        )
    }

    private fun blendColor(base: Int, overlay: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) * (1f - a) + Color.red(overlay) * a).toInt(),
            (Color.green(base) * (1f - a) + Color.green(overlay) * a).toInt(),
            (Color.blue(base) * (1f - a) + Color.blue(overlay) * a).toInt()
        )
    }

    private fun roundedBackground(fill: Int, stroke: Int? = null, radiusDp: Int = if (uiRounded) 14 else 6): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusDp.dp.toFloat()
            if (stroke != null) setStroke(1.dp, stroke)
        }
    }

    private fun loadUiAppearance() {
        uiThemeMode = "graphite"
        uiAccentHex = "#087F73"
        uiPanelHex = "#181D22"
        uiCompact = false
        uiRounded = true

        prefs.edit()
            .remove("ui_theme_mode")
            .remove("ui_accent_hex")
            .remove("ui_panel_hex")
            .remove("ui_compact")
            .remove("ui_rounded")
            .apply()
    }

    private fun saveUiAppearance() {
        uiThemeMode = "graphite"
        uiAccentHex = "#087F73"
        uiPanelHex = "#181D22"
        uiCompact = false
        uiRounded = true
    }

    private fun themedDialogBuilder(): android.app.AlertDialog.Builder =
        android.app.AlertDialog.Builder(
            android.view.ContextThemeWrapper(this, R.style.Theme_SeaBathy_Dialog)
        )

    private fun styleDialog(dialog: android.app.AlertDialog) {
        val p = activePalette()
        dialog.window?.setBackgroundDrawable(roundedBackground(p.surface, p.border, if (uiRounded) 18 else 8))
        dialog.window?.decorView?.setBackgroundColor(Color.TRANSPARENT)

        dialog.findViewById<TextView>(android.R.id.title)?.setTextColor(p.primaryText)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(p.secondaryText)

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            isAllCaps = false
            setTextColor(p.onAccent)
            background = roundedBackground(p.accent, null, if (uiRounded) 10 else 5)
            minHeight = 42.dp
            setPadding(18.dp, 0, 18.dp, 0)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            isAllCaps = false
            setTextColor(p.primaryText)
            background = roundedBackground(p.raised, p.border, if (uiRounded) 10 else 5)
            minHeight = 42.dp
            setPadding(16.dp, 0, 16.dp, 0)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.apply {
            isAllCaps = false
            setTextColor(p.destructive)
            background = Color.TRANSPARENT.toDrawableCompat()
        }
    }

    private fun Int.toDrawableCompat(): android.graphics.drawable.ColorDrawable =
        android.graphics.drawable.ColorDrawable(this)

    private fun styleDialogInput(input: EditText) {
        val p = activePalette()
        input.setTextColor(p.primaryText)
        input.setHintTextColor(p.secondaryText)
        input.background = roundedBackground(p.raised, p.border, if (uiRounded) 12 else 5)
        input.setPadding(14.dp, if (uiCompact) 9.dp else 12.dp, 14.dp, if (uiCompact) 9.dp else 12.dp)
    }

    private fun styleChoiceText(view: TextView, selected: Boolean = false) {
        val p = activePalette()
        view.setTextColor(if (selected) p.onAccent else p.primaryText)
        view.background = roundedBackground(if (selected) p.accent else p.raised, p.border, if (uiRounded) 10 else 5)
        view.setPadding(16.dp, if (uiCompact) 10.dp else 13.dp, 16.dp, if (uiCompact) 10.dp else 13.dp)
    }

    private fun applyReadableText(
        root: View,
        palette: UiPalette = activePalette()
    ) {
        when (root) {
            is EditText -> {
                root.setTextColor(palette.primaryText)
                root.setHintTextColor(palette.secondaryText)
            }
            is Button -> {
                val label = root.text?.toString().orEmpty()
                val destructive = label.contains("delete", true) ||
                    label.contains("remove", true) ||
                    label.contains("clear", true)
                root.setTextColor(
                    if (destructive) palette.destructive else palette.primaryText
                )
            }
            is TextView -> {
                val label = root.text?.toString().orEmpty()
                val destructive = label.contains("delete", true) ||
                    label.contains("remove", true)
                root.setTextColor(
                    if (destructive) palette.destructive else palette.primaryText
                )
            }
        }

        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applyReadableText(root.getChildAt(i), palette)
            }
        }
    }

    private fun makePanelRow(
        title: String,
        value: String = "",
        destructive: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val palette = activePalette()
        return TextView(this).apply {
            text = if (value.isBlank()) title else "$title\n$value"
            textSize = 14f
            setTextColor(if (destructive) palette.destructive else palette.primaryText)
            background = roundedBackground(palette.raised, palette.border, if (uiRounded) 12 else 6)
            setPadding(16.dp, if (uiCompact) 10.dp else 13.dp, 16.dp, if (uiCompact) 10.dp else 13.dp)
            minHeight = if (uiCompact) 48.dp else 56.dp
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(2.dp.toFloat(), 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun showOpaquePanel(
        title: String,
        content: View,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ): android.app.Dialog {
        val palette = activePalette()
        val dialog = android.app.Dialog(this, R.style.Theme_SeaBathy_Dialog).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(
                palette.panel,
                palette.border,
                if (uiRounded) 18 else 8
            )
        }
        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 16.dp, 12.dp, 12.dp)

            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                setTextColor(palette.primaryText)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ))

            addView(TextView(this@MainActivity).apply {
                text = "✕"
                contentDescription = "Close"
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(palette.primaryText)
                background = roundedBackground(
                    palette.raised,
                    palette.border,
                    if (uiRounded) 10 else 5
                )
                setPadding(14.dp, 8.dp, 14.dp, 8.dp)
                setOnClickListener { dialog.dismiss() }
            })
        }
        card.addView(header)

        val contentCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        contentCard.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(
            contentCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 14.dp
                marginEnd = 14.dp
                bottomMargin = 0
            }
        )

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(16.dp, 4.dp, 16.dp, 16.dp)

            addView(Button(this@MainActivity).apply {
                text = "Close"
                isAllCaps = false
                setTextColor(palette.primaryText)
                background = roundedBackground(
                    palette.raised,
                    palette.border,
                    if (uiRounded) 10 else 5
                )
                minHeight = 44.dp
                setPadding(18.dp, 0, 18.dp, 0)
                setOnClickListener { dialog.dismiss() }
            })

            if (actionLabel != null && onAction != null) {
                addView(Button(this@MainActivity).apply {
                    text = actionLabel
                    isAllCaps = false
                    setTextColor(palette.onAccent)
                    background = roundedBackground(
                        palette.accent,
                        null,
                        if (uiRounded) 10 else 5
                    )
                    minHeight = 44.dp
                    setPadding(20.dp, 0, 20.dp, 0)
                    setOnClickListener { onAction() }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 10.dp })
            }
        }
        card.addView(footer)

        dialog.setContentView(root)

        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            )
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                gravity = Gravity.CENTER
                dimAmount = 0.72f
                windowAnimations = 0
            }
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.96f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        applyReadableText(root, palette)
        dialog.show()
        return dialog
    }

    private fun showOpaqueChoicePanel(
        title: String,
        labels: Array<String>,
        selected: Int = -1,
        onChosen: (Int) -> Unit
    ) {
        val palette = activePalette()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 4.dp, 16.dp, 10.dp)
        }
        lateinit var dialog: android.app.Dialog
        labels.forEachIndexed { index, label ->
            body.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(if (index == selected) palette.onAccent else palette.primaryText)
                background = roundedBackground(
                    if (index == selected) palette.accent else palette.raised,
                    palette.border,
                    if (uiRounded) 11 else 5
                )
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                setOnClickListener {
                    dialog.dismiss()
                    onChosen(index)
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 9.dp })
        }
        dialog = showOpaquePanel(title, body)
    }

    private fun applyUiAppearance() {
        val p = activePalette()
        window.statusBarColor = p.background
        window.navigationBarColor = p.background
        findViewById<View>(R.id.topBar)?.setBackgroundColor(Color.argb(235, Color.red(p.background), Color.green(p.background), Color.blue(p.background)))
        findViewById<View>(R.id.sidePanel)?.setBackgroundColor(p.panel)
        findViewById<TextView>(R.id.tvCoords)?.apply {
            setTextColor(p.accent)
            background = roundedBackground(p.surface, p.border, if (uiRounded) 10 else 5)
        }
        findViewById<Button>(R.id.btnSettings)?.apply {
            text = "Settings     ›"
            setTextColor(p.primaryText)
            background = roundedBackground(p.raised, p.border, if (uiRounded) 12 else 5)
        }
        findViewById<Button>(R.id.btnLoadMbtiles)?.apply {
            setTextColor(p.accent)
            background = roundedBackground(p.surface, p.border, if (uiRounded) 12 else 5)
        }
        findViewById<Spinner>(R.id.spinBasemap)?.background = roundedBackground(p.raised, p.border, if (uiRounded) 10 else 5)
        findViewById<LinearLayout>(R.id.wptDialog)?.apply {
            background = roundedBackground(p.surface, p.border, if (uiRounded) 18 else 8)
            applyReadableText(this, p)
        }
        findViewById<EditText>(R.id.etWptName)?.let { styleDialogInput(it) }
        findViewById<TextView>(R.id.tvWptCoords)?.apply {
            setTextColor(p.primaryText)
            background = roundedBackground(p.raised, p.border, if (uiRounded) 12 else 5)
        }
        findViewById<Button>(R.id.btnWptCancel)?.apply {
            setTextColor(p.primaryText)
            background = roundedBackground(p.raised, p.border, if (uiRounded) 10 else 5)
        }
        findViewById<Button>(R.id.btnWptSave)?.apply {
            setTextColor(p.onAccent)
            background = roundedBackground(p.accent, null, if (uiRounded) 10 else 5)
        }
        listOf(R.id.btnGps, R.id.btnWpt, R.id.btnMeasure).forEach { id ->
            findViewById<ImageButton>(id)?.apply {
                background = roundedBackground(p.raised, p.border, if (uiRounded) 10 else 20)
                imageTintList = android.content.res.ColorStateList.valueOf(p.primaryText)
            }
        }
        applyVisualSettings()
    }

    private fun showCustomBasemapDialog(onSaved: (() -> Unit)? = null) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 12.dp, 20.dp, 8.dp)
        }

        val urlInput = EditText(this).apply {
            hint = "https://example.com/tiles/{z}/{x}/{y}.png"
            setText(customBasemapUrl)
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            minLines = 2
            styleDialogInput(this)
        }

        val attributionInput = EditText(this).apply {
            hint = "Required provider attribution"
            setText(customBasemapAttribution)
            setSingleLine(false)
            minLines = 2
            styleDialogInput(this)
        }

        form.addView(TextView(this).apply {
            text = "XYZ tile URL"
            textSize = 12f
            setTextColor(Color.parseColor("#AEB7C2"))
            setPadding(2.dp, 4.dp, 0, 8.dp)
        })

        form.addView(urlInput)

        form.addView(TextView(this).apply {
            text = "Attribution"
            textSize = 12f
            setTextColor(Color.parseColor("#AEB7C2"))
            setPadding(2.dp, 16.dp, 0, 8.dp)
        })

        form.addView(attributionInput)

        val dialog = themedDialogBuilder()
            .setTitle("Custom online basemap")
            .setMessage(
                "Enter an XYZ tile URL containing {z}, {x} and {y}. " +
                "You are responsible for complying with the provider's licence and attribution requirements."
            )
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener {
                    customBasemapUrl = ""
                    customBasemapAttribution = ""

                    prefs.edit()
                        .remove("custom_basemap_url")
                        .remove("custom_basemap_attribution")
                        .apply()

                    updateMapAttribution("none")
                    dialog.dismiss()
                }

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val url = urlInput.text.toString().trim()
                    val attribution =
                        attributionInput.text.toString().trim()

                    if (url.isBlank()) {
                        urlInput.error = "Enter an XYZ tile URL"
                        return@setOnClickListener
                    }

                    if (
                        !url.contains("{z}") ||
                        !url.contains("{x}") ||
                        !url.contains("{y}")
                    ) {
                        urlInput.error =
                            "URL must contain {z}, {x} and {y}"
                        return@setOnClickListener
                    }

                    customBasemapUrl = url
                    customBasemapAttribution = attribution

                    prefs.edit()
                        .putString(
                            "custom_basemap_url",
                            customBasemapUrl
                        )
                        .putString(
                            "custom_basemap_attribution",
                            customBasemapAttribution
                        )
                        .apply()

                    dialog.dismiss()
                    onSaved?.invoke()
                }
        }

        dialog.show()
    }

    private fun addMapLayers() {
        val s = style ?: run {
            Log.e(TAG, "addMapLayers failed: style is not ready")
            return
        }
        s.addSource(GeoJsonSource("mbt-anchor-src", buildEmptyGeoJson()))
        s.addLayer(CircleLayer("mbt-raster-anchor", "mbt-anchor-src").withProperties(
            PropertyFactory.circleRadius(0f),
            PropertyFactory.circleOpacity(0f)
        ))
        s.addSource(GeoJsonSource("route-src", buildEmptyGeoJson()))
        s.addLayer(LineLayer("route-line", "route-src")
            .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString")))
            .withProperties(PropertyFactory.lineColor("#00C8B4"), PropertyFactory.lineWidth(3f),
                PropertyFactory.lineDasharray(arrayOf(2f, 1f))))
        s.addLayer(CircleLayer("route-pts", "route-src")
            .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
            .withProperties(PropertyFactory.circleRadius(7f), PropertyFactory.circleColor("#00C8B4"),
                PropertyFactory.circleStrokeWidth(2f), PropertyFactory.circleStrokeColor("#FFFFFF")))
        s.addSource(GeoJsonSource("meas-src", buildEmptyGeoJson()))
        s.addLayer(LineLayer("meas-line", "meas-src")
            .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString")))
            .withProperties(PropertyFactory.lineColor("#F59E0B"), PropertyFactory.lineWidth(2f)))
        s.addLayer(CircleLayer("meas-pts", "meas-src")
            .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
            .withProperties(PropertyFactory.circleRadius(5f), PropertyFactory.circleColor("#F59E0B")))
        s.addSource(GeoJsonSource("gps-src", buildEmptyGeoJson()))
        s.addLayer(CircleLayer("gps-acc", "gps-src").withProperties(
            PropertyFactory.circleRadius(22f), PropertyFactory.circleColor(Color.parseColor("#1500C8B4")),
            PropertyFactory.circleStrokeWidth(1.5f), PropertyFactory.circleStrokeColor("#00C8B4")))
        s.addLayer(CircleLayer("gps-dot", "gps-src").withProperties(
            PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#00C8B4"),
            PropertyFactory.circleStrokeWidth(2f), PropertyFactory.circleStrokeColor("#FFFFFF")))
    }

    // ── MBTiles persistence ───────────────────────────────────
    private fun saveMbtLayers() {
        val list = mbtLayerIds.map { id ->
            MbtLayer(
                mbtLayerNames[id] ?: id,
                mbtFilePaths[id] ?: "",
                mbtLayerVisible[id] ?: true,
                mbtLayerOpacity[id] ?: 1f
            )
        }
        prefs.edit().putString("mbt_layers", gson.toJson(list)).apply()
    }

    private fun rebuildMbtPanelRows() {
        val container = findViewById<LinearLayout>(R.id.mbtilesList)
        container.removeAllViews()
        mbtLayerIds.forEach { id -> addMbtPanelRow(id, mbtLayerNames[id] ?: id) }
    }

    private fun rebuildMbtLayersLive() {
        val s = style ?: return

        mbtLayerIds.toList().forEach { id ->
            try { if (s.getLayer("${id}-layer") != null) s.removeLayer("${id}-layer") } catch (e: Exception) {}
            try { if (s.getSource("${id}-src") != null) s.removeSource("${id}-src") } catch (e: Exception) {}
        }

        mbtLayerIds.toList().forEach { id ->
            val db = mbtDbs[id]
            val path = mbtFilePaths[id]
            if (db != null && path != null) addMbtLayerToMap(id, db, path)
        }

        renderWaypoints()
        refreshRoute()
        refreshMeasure()
        saveMbtLayers()
        rebuildMbtPanelRows()
    }

    private fun moveMbtLayer(id: String, direction: Int) {
        val oldIndex = mbtLayerIds.indexOf(id)
        if (oldIndex < 0) return

        val newIndex = (oldIndex + direction).coerceIn(0, mbtLayerIds.lastIndex)
        if (newIndex == oldIndex) return

        mbtLayerIds.removeAt(oldIndex)
        mbtLayerIds.add(newIndex, id)

        rebuildMbtLayersLive()
        Toast.makeText(this@MainActivity, "Layer order updated", Toast.LENGTH_SHORT).show()
    }


    private fun setMbtAsBase(id: String) {
        if (!mbtLayerIds.contains(id)) return

        mbtLayerIds.remove(id)
        mbtLayerIds.add(0, id)

        rebuildMbtLayersLive()
        Toast.makeText(this@MainActivity, "Base layer set", Toast.LENGTH_SHORT).show()
    }


    private fun restoreMbtLayers(
        generation: Int,
        onComplete: () -> Unit
    ) {
        val json = prefs.getString("mbt_layers", null)
        if (json == null) {
            onComplete()
            return
        }

        val list = try {
            gson.fromJson<List<MbtLayer>>(
                json,
                object : TypeToken<List<MbtLayer>>() {}.type
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read saved MBTiles list", e)
            onComplete()
            return
        }

        mbtRestoreJob = scope.launch {
            for (layer in list) {
                if (!isActive || generation != styleGeneration) return@launch

                val file = File(layer.path)
                if (!file.exists()) continue

                try {
                    val db = withContext(Dispatchers.IO) {
                        SQLiteDatabase.openDatabase(
                            file.absolutePath,
                            null,
                            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                        )
                    }

                    if (!isActive || generation != styleGeneration) {
                        db.close()
                        return@launch
                    }

                    val id = "mbt_${System.currentTimeMillis()}_${layer.name}"
                    mbtDbs[id] = db
                    mbtLayerIds.add(id)
                    mbtFilePaths[id] = file.absolutePath
                    mbtLayerNames[id] = layer.name
                    mbtLayerVisible[id] = layer.visible
                    mbtLayerOpacity[id] = layer.opacity.coerceIn(0f, 1f)
                    addMbtLayerToMap(id, db, file.absolutePath, generation)
                } catch (e: Exception) {
                    Log.e(TAG, "restore ${layer.name}", e)
                }
            }

            if (isActive && generation == styleGeneration) {
                onComplete()
            }
        }
    }

    private fun startTileServer() {
        tileServerThread?.interrupt()
        tileServerThread = Thread {
            try {
                val server = ServerSocket(TILE_PORT)
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val socket = server.accept()
                        tileExecutor.execute { handleTileRequest(socket) }
                    } catch (e: Exception) { if (!Thread.currentThread().isInterrupted) break }
                }
                server.close()
            } catch (e: Exception) { Log.e(TAG, "tile server: ${e.message}") }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun handleTileRequest(socket: java.net.Socket) {
        socket.use {
            val reader = socket.getInputStream().bufferedReader()
            val out = socket.getOutputStream()
            val line = reader.readLine() ?: return
            val path = line.split(" ").getOrNull(1) ?: return
            val parts = path.trim('/').split("/")
            if (parts.size < 4) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
            val layerId = parts[0]
            val z = parts[1].toIntOrNull() ?: 0
            val x = parts[2].toIntOrNull() ?: 0
            val y = parts[3].substringBefore('.').toIntOrNull() ?: 0
            val tmsY = (1 shl z) - 1 - y
            val tile = mbtDbs[layerId]?.let { db ->
                try {
                    synchronized(db) {
                        db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                            arrayOf(z.toString(), x.toString(), tmsY.toString())).use { c ->
                            if (c.moveToFirst()) c.getBlob(0) else null
                        }
                    }
                } catch (e: Exception) { null }
            }
            if (tile != null) {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${tile.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                out.write(tile)
            } else {
                out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
            out.flush()
        }
    }

    private fun loadMbtilesUri(uri: Uri) {
        val name = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx)?.substringBeforeLast('.') else null
            }
        } catch (e: Exception) { null } ?: "layer_${System.currentTimeMillis()}"
        val destFile = File(File(filesDir, "mbtiles").also { it.mkdirs() }, "$name.mbtiles")
        scope.launch {
            try {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Loading $name...", Toast.LENGTH_SHORT).show() }
                withContext(Dispatchers.IO) {
                    if (!destFile.exists()) {
                        val ins = contentResolver.openInputStream(uri) ?: throw Exception("Cannot read file")
                        ins.use { input -> destFile.outputStream().use { out -> input.copyTo(out) } }
                    }
                }
                val db = withContext(Dispatchers.IO) {
                    SQLiteDatabase.openDatabase(destFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                }
                val id = "mbt_${System.currentTimeMillis()}"
                mbtDbs[id] = db
                mbtLayerIds.add(id)
                mbtFilePaths[id] = destFile.absolutePath
                mbtLayerNames[id] = name
                mbtLayerVisible[id] = true
                mbtLayerOpacity[id] = 1f
                addMbtLayerToMap(id, db, destFile.absolutePath)
                addMbtPanelRow(id, name)
                saveMbtLayers()
                Toast.makeText(this@MainActivity, "Loaded $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "load failed: ${e.message}")
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                destFile.delete()
            }
        }
    }

    private fun addMbtLayerToMap(
        id: String,
        db: SQLiteDatabase,
        filePath: String = "",
        generation: Int = styleGeneration
    ) {
        if (generation != styleGeneration) return
        val s = style ?: return
        if (filePath.isNotEmpty()) mbtFilePaths[id] = filePath
        val destPath = mbtFilePaths[id] ?: return
        var minZ = 0; var maxZ = 22
        try {
            db.rawQuery("SELECT name,value FROM metadata", null).use { c ->
                while (c.moveToNext()) {
                    when (c.getString(0)) {
                        "minzoom" -> minZ = c.getString(1).toIntOrNull() ?: 0
                        "maxzoom" -> maxZ = c.getString(1).toIntOrNull() ?: 22
                    }
                }
            }
        } catch (e: Exception) {}
        // Detect vector vs raster
        var fmt = "png"
        try {
            db.rawQuery("SELECT value FROM metadata WHERE name='format'", null).use { c ->
                if (c.moveToFirst()) fmt = c.getString(0)
            }
        } catch (e: Exception) {}
        val isVector = fmt.lowercase() in listOf("pbf","mvt")
        if (generation != styleGeneration || s !== style) return
        val tileSet = TileSet("2.0.0", "mbtiles://$destPath")
        tileSet.minZoom = minZ.toFloat()
        tileSet.maxZoom = maxZ.toFloat()
        try {
            if (isVector) {
                s.addSource(VectorSource("${id}-src", tileSet))
                val lineLayer = LineLayer("${id}-layer", "${id}-src")
                lineLayer.setSourceLayer("contours")
                val visible = mbtLayerVisible[id] ?: true
                val opacity = (mbtLayerOpacity[id] ?: 0.85f).coerceIn(0f, 1f)
                lineLayer.setProperties(
                    PropertyFactory.lineColor("#00C8B4"),
                    PropertyFactory.lineWidth(1.0f),
                    PropertyFactory.lineOpacity(if (visible) opacity else 0f)
                )
                try {
                    s.addLayerBelow(lineLayer, "mbt-raster-anchor")
                } catch (anchorError: Exception) {
                    Log.e(TAG, "Vector MBTiles anchor placement failed", anchorError)
                    try {
                        s.addLayerBelow(lineLayer, "mbt-raster-anchor")
                    } catch (waypointError: Exception) {
                        Log.e(TAG, "Vector MBTiles waypoint fallback failed", waypointError)
                    }
                }
            } else {
                s.addSource(RasterSource("${id}-src", tileSet, 256))
                val visible = mbtLayerVisible[id] ?: true
                val opacity = (mbtLayerOpacity[id] ?: 1f).coerceIn(0f, 1f)
                val layer = RasterLayer("${id}-layer", "${id}-src").withProperties(
                    PropertyFactory.rasterOpacity(if (visible) opacity else 0f)
                )
                try {
                    s.addLayerBelow(layer, "mbt-raster-anchor")
                } catch (anchorError: Exception) {
                    Log.e(TAG, "Raster MBTiles anchor placement failed", anchorError)
                    try {
                        s.addLayerBelow(layer, "mbt-raster-anchor")
                    } catch (waypointError: Exception) {
                        Log.e(TAG, "Raster MBTiles waypoint fallback failed", waypointError)
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "addMbtLayer: ${e.message}") }
    }

    private fun addMbtPanelRow(id: String, name: String) {
        val container = findViewById<LinearLayout>(R.id.mbtilesList)

        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 8.dp, 16.dp, 10.dp)
            tag = id
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tv = TextView(this).apply {
            text = name
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#F4F6F8"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sw = SwitchMaterial(this).apply {
            isChecked = mbtLayerVisible[id] ?: true
            setOnCheckedChangeListener { _, on ->
                mbtLayerVisible[id] = on
                val opacity = (mbtLayerOpacity[id] ?: 1f).coerceIn(0f, 1f)
                val layer = style?.getLayer("${id}-layer")
                when (layer) {
                    is RasterLayer -> layer.withProperties(
                        PropertyFactory.rasterOpacity(if (on) opacity else 0f)
                    )
                    is LineLayer -> layer.withProperties(
                        PropertyFactory.lineOpacity(if (on) opacity else 0f)
                    )
                }
                saveMbtLayers()
            }
        }

        val del = TextView(this).apply {
            text = "DEL"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#EF4444"))
            setBackgroundColor(Color.parseColor("#29313A"))
            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
            setOnClickListener {
                try { style?.removeLayer("${id}-layer") } catch (e: Exception) {}
                try { style?.removeSource("${id}-src") } catch (e: Exception) {}
                mbtDbs[id]?.close()
                mbtDbs.remove(id)
                mbtLayerIds.remove(id)
                mbtFilePaths.remove(id)
                mbtLayerNames.remove(id)
                mbtLayerVisible.remove(id)
                mbtLayerOpacity.remove(id)
                saveMbtLayers()
                container.removeView(rowLayout)
            }
        }

        top.addView(tv)
        top.addView(sw)
        top.addView(del, LinearLayout.LayoutParams(52.dp, 30.dp))

        val seek = SeekBar(this).apply {
            max = 100
            progress = ((mbtLayerOpacity[id] ?: 1f) * 100f).roundToInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00C8B4"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00C8B4"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val opacity = (p / 100f).coerceIn(0f, 1f)
                    mbtLayerOpacity[id] = opacity
                    val visible = mbtLayerVisible[id] ?: true
                    val layer = style?.getLayer("${id}-layer")
                    when (layer) {
                        is RasterLayer -> layer.withProperties(
                            PropertyFactory.rasterOpacity(if (visible) opacity else 0f)
                        )
                        is LineLayer -> layer.withProperties(
                            PropertyFactory.lineOpacity(if (visible) opacity else 0f)
                        )
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    saveMbtLayers()
                }
            })
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#29313A"))
        }

        rowLayout.addView(top)
        rowLayout.addView(seek)

        container.addView(divider)
        container.addView(rowLayout)
    }



    @SuppressLint("MissingPermission")
    private fun updateGpsButton() {
        val btn = findViewById<ImageButton>(R.id.btnGps)
        val color = when (gpsMode) {
            1 -> "#22C55E"
            2 -> "#F97316"
            else -> "#AEB7C2"
        }
        btn.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
    }

    private fun stopGps() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        gpsListener?.let {
            try { lm.removeUpdates(it) } catch (e: Exception) {}
        }
        gpsListener = null
    }

    private fun startGps() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (gpsListener != null) return

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val gj = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[${loc.longitude},${loc.latitude}]},"properties":{}}]}"""
                (style?.getSource("gps-src") as? GeoJsonSource)?.setGeoJson(gj)

                when (gpsMode) {
                    1 -> {
                        if (!gpsCenteredOnce) {
                            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 14.0))
                            gpsCenteredOnce = true
                        }
                    }
                    2 -> map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude)))
                }
            }

            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }

        gpsListener = listener
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener) } catch (e: Exception) {}
        try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, listener) } catch (e: Exception) {}
    }


    private fun handleMapTap(lat: Double, lng: Double) {
        when (tool) {
            "wpt" -> {
                val c = map?.cameraPosition?.target
                if (c != null) showWptDialog(c.latitude, c.longitude)
                else showWptDialog(lat, lng)
            }
            "measure" -> {
                measurePts.add(RoutePoint(lat, lng))
                refreshMeasure()
            }
        }
    }

    private fun buildWptGeoJson(): String {
        val features = waypoints.map { w ->
            mapOf<String, Any>(
                "type" to "Feature",
                "geometry" to mapOf(
                    "type" to "Point",
                    "coordinates" to listOf(w.lng, w.lat)
                ),
                "properties" to mapOf(
                    "id" to w.id,
                    "name" to w.name,
                    "color" to w.color
                )
            )
        }
        return gson.toJson(
            mapOf(
                "type" to "FeatureCollection",
                "features" to features
            )
        )
    }

    private fun ensureWaypointLayers() {
        renderWaypoints()
    }

    private fun waypointMarkerIcon(waypoint: Waypoint): Icon {
        val cacheKey = "${waypoint.color}|${waypoint.name}"
        waypointIconCache[cacheKey]?.let { return it }

        val density = resources.displayMetrics.density
        val label = waypoint.name.trim().ifBlank { "Waypoint" }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F4F6F8")
            textSize = 11f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f * density, 0f, 0f, Color.parseColor("#111418"))
        }
        val radius = 8f * density
        val padding = 7f * density
        val textWidth = textPaint.measureText(label)
        val width = max(
            (radius * 2f + padding * 2f).roundToInt(),
            (textWidth + padding * 2f).roundToInt()
        )
        val height = (38f * density).roundToInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = try {
                Color.parseColor(waypoint.color)
            } catch (_: Exception) {
                Color.parseColor("#EF4444")
            }
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.WHITE
        }
        val cx = width / 2f
        val cy = height - radius - 2f * density
        canvas.drawCircle(cx, cy, radius, circlePaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)
        canvas.drawText(label, cx, 12f * density, textPaint)

        return IconFactory.getInstance(this).fromBitmap(bitmap).also {
            waypointIconCache[cacheKey] = it
        }
    }

    private fun renderWaypoints() {
        val currentMap = map
        if (currentMap == null) {
            Log.w(TAG, "renderWaypoints skipped: map is not ready")
        } else {
            waypointMarkers.keys.toList().forEach { marker ->
                try {
                    currentMap.removeMarker(marker)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to remove waypoint marker", e)
                }
            }
            waypointMarkers.clear()

            waypoints.forEach { waypoint ->
                try {
                    val marker = currentMap.addMarker(
                        MarkerOptions()
                            .position(LatLng(waypoint.lat, waypoint.lng))
                            .title(waypoint.name)
                            .snippet(waypoint.desc ?: fmtDDM(waypoint.lat, waypoint.lng))
                            .icon(waypointMarkerIcon(waypoint))
                    )
                    waypointMarkers[marker] = waypoint.id
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to render waypoint ${waypoint.id}", e)
                }
            }
            Log.d(TAG, "Rendered ${waypointMarkers.size} waypoint marker(s)")
        }

        renderWaypointList()
        saveWaypoints()
    }

    private fun renderWaypointList() {
        val container = findViewById<LinearLayout>(R.id.waypointsList)
        container.removeAllViews()

        val search = EditText(this).apply {
            hint = "Search waypoints"
            textSize = 12f
            setSingleLine(true)
            setTextColor(Color.parseColor("#F4F6F8"))
            setHintTextColor(Color.parseColor("#AEB7C2"))
            setBackgroundColor(Color.parseColor("#29313A"))
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun draw(q: String = "") {
            list.removeAllViews()
            val filtered = waypoints.filter {
                it.name.contains(q, true) || (it.desc ?: "").contains(q, true)
            }

            if (filtered.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = if (waypoints.isEmpty()) "No waypoints yet" else "No matching waypoints"
                    textSize = 12f
                    setTextColor(Color.parseColor("#AEB7C2"))
                    setPadding(16.dp, 12.dp, 16.dp, 12.dp)
                })
                return
            }

            filtered.forEach { w ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16.dp, 8.dp, 8.dp, 8.dp)
                }

                val dot = View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        try { setColor(Color.parseColor(w.color)) } catch (e: Exception) { setColor(Color.parseColor("#EF4444")) }
                    }
                }

                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(10.dp, 0, 8.dp, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                info.addView(TextView(this).apply {
                    text = w.name
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.parseColor("#F4F6F8"))
                })

                info.addView(TextView(this).apply {
                    val d = (w.desc ?: "").trim()
                    text = if (d.isBlank()) fmtDDM(w.lat, w.lng) else d
                    textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.parseColor("#AEB7C2"))
                })

                val go = TextView(this).apply {
                    text = "GO"
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTextColor(Color.parseColor("#00C8B4"))
                    setBackgroundColor(Color.parseColor("#29313A"))
                    setOnClickListener {
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(this.windowToken, 0)
                        search.clearFocus()
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(w.lat, w.lng), 14.0))
                        closePanel()
                    }
                }

                val edit = TextView(this).apply {
                    text = "EDIT"
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTextColor(Color.parseColor("#F4F6F8"))
                    setBackgroundColor(Color.parseColor("#29313A"))
                    setOnClickListener { showWptEditDialog(w) }
                }

                row.addView(dot, LinearLayout.LayoutParams(12.dp, 12.dp))
                row.addView(info)
                row.addView(go, LinearLayout.LayoutParams(42.dp, 30.dp).apply { marginEnd = 5.dp })
                row.addView(edit, LinearLayout.LayoutParams(52.dp, 30.dp))
                list.addView(row)
            }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(cs: CharSequence?, start: Int, before: Int, count: Int) { draw(cs?.toString() ?: "") }
            override fun afterTextChanged(e: android.text.Editable?) {}
        })
        search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        search.inputType = android.text.InputType.TYPE_CLASS_TEXT
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                hideKeyboardHard()
                true
            } else false
        }
        search.setOnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
        }

        container.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 16.dp
            rightMargin = 16.dp
            topMargin = 4.dp
            bottomMargin = 6.dp
        })

        container.addView(list)
        draw("")
    }


    private fun showWptEditDialog(wpt: Waypoint) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 12.dp, 20.dp, 8.dp)
        }

        val nameInput = EditText(this).apply {
            setText(wpt.name)
            selectAll()
            hint = "Name"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.parseColor("#F4F6F8"))
            setHintTextColor(Color.parseColor("#AEB7C2"))
            setBackgroundColor(Color.parseColor("#29313A"))
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        }

        val descInput = EditText(this).apply {
            setText(wpt.desc ?: "")
            hint = "Description / notes"
            minLines = 2
            maxLines = 4
            textSize = 14f
            setTextColor(Color.parseColor("#F4F6F8"))
            setHintTextColor(Color.parseColor("#AEB7C2"))
            setBackgroundColor(Color.parseColor("#29313A"))
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        }

        val coords = TextView(this).apply {
            text = "${fmtDDM(wpt.lat, wpt.lng)}   •   EDIT COORDINATES"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00C8B4"))
            setPadding(0, 8.dp, 0, 8.dp)
            setOnClickListener {
                showCoordinateEditor(
                    title = "Waypoint coordinates",
                    initialLat = wpt.lat,
                    initialLng = wpt.lng,
                    positiveLabel = "Save"
                ) { lat, lng ->
                    wpt.lat = lat
                    wpt.lng = lng
                    text = "${fmtDDM(wpt.lat, wpt.lng)}   •   EDIT COORDINATES"
                    renderWaypoints()
                }
            }
        }

        outer.addView(nameInput)
        outer.addView(coords)
        outer.addView(descInput)

        val dialog = themedDialogBuilder()
            .setTitle("Waypoint")
            .setView(outer)
            .setPositiveButton("Save", null)
            .setNeutralButton("Jump", null)
            .setNegativeButton("Delete", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                wpt.name = nameInput.text.toString().trim().ifBlank { wpt.name }
                wpt.desc = descInput.text.toString().trim()
                renderWaypoints()
                dialog.dismiss()
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(wpt.lat, wpt.lng), 14.0))
                dialog.dismiss()
                closePanel()
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(activePalette().destructive)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                themedDialogBuilder()
                    .setTitle("Delete ${wpt.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        waypoints.remove(wpt)
                        renderWaypoints()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        dialog.show()
    }



    private fun hideKeyboardHard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            val v = currentFocus ?: window.decorView
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
        } catch (e: Exception) {}
    }

    private fun showWptDialog(lat: Double, lng: Double) {
        pendingWptLat = lat; pendingWptLng = lng; selectedColor = "#EF4444"
        // Store coords on the dialog view so they survive keyboard events
        findViewById<FrameLayout>(R.id.wptDialogBg).tag = "$lat,$lng"
        findViewById<EditText>(R.id.etWptName).text.clear()

        val dialogBox = findViewById<LinearLayout>(R.id.wptDialog)
        val existingDesc = dialogBox.findViewWithTag<EditText>("wpt_desc_input")
        if (existingDesc == null) {
            val descInput = EditText(this).apply {
                tag = "wpt_desc_input"
                id = resources.getIdentifier("etWptDesc", "id", packageName).takeIf { it != 0 } ?: View.generateViewId()
                hint = "Description / notes"
                minLines = 2
                maxLines = 3
                textSize = 16f
                val palette = activePalette()
                setTextColor(palette.primaryText)
                setHintTextColor(palette.secondaryText)
                background = roundedBackground(
                    palette.raised,
                    palette.border,
                    if (uiRounded) 12 else 5
                )
                setPadding(14.dp, 11.dp, 14.dp, 11.dp)
            }
            val nameInput = findViewById<EditText>(R.id.etWptName)
            val nameIndex = dialogBox.indexOfChild(nameInput)
            dialogBox.addView(descInput, nameIndex + 1, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp })
        } else {
            existingDesc.text.clear()
        }

        updateColorPicker()
        // Update crosshair coords display
        updateCrosshairCoords(lat, lng)
        try {
            findViewById<TextView>(R.id.tvWptCoords).text = fmtDDM(lat, lng)
            findViewById<TextView>(R.id.tvWptCoords).textSize = 16f
            findViewById<TextView>(R.id.tvWptCoords).visibility = View.VISIBLE
        } catch (e: Exception) {}
        val palette = activePalette()
        dialogBox.background = roundedBackground(
            palette.panel,
            palette.border,
            if (uiRounded) 18 else 8
        )
        applyReadableText(dialogBox, palette)
        findViewById<EditText>(R.id.etWptName).apply {
            setTextColor(palette.primaryText)
            setHintTextColor(palette.secondaryText)
            background = roundedBackground(
                palette.raised,
                palette.border,
                if (uiRounded) 12 else 5
            )
        }
        findViewById<TextView>(R.id.tvWptCoords).apply {
            setTextColor(palette.primaryText)
            background = roundedBackground(
                palette.surface,
                palette.border,
                if (uiRounded) 12 else 5
            )
        }
        findViewById<Button>(R.id.btnWptCancel).apply {
            setTextColor(palette.primaryText)
            background = roundedBackground(
                palette.raised,
                palette.border,
                if (uiRounded) 10 else 5
            )
        }
        findViewById<Button>(R.id.btnWptSave).apply {
            setTextColor(palette.onAccent)
            background = roundedBackground(
                palette.accent,
                null,
                if (uiRounded) 10 else 5
            )
        }

        findViewById<FrameLayout>(R.id.wptDialogBg).visibility = View.VISIBLE
        hideKeyboardHard()
        findViewById<EditText>(R.id.etWptName).clearFocus()
    }

    private fun updateCrosshairCoords(lat: Double, lng: Double) {
        try {
            findViewById<TextView>(R.id.tvCrosshairCoords)?.visibility = View.GONE
        } catch (e: Exception) {}
    }

    private fun depthEnabledForCurrentTool(): Boolean {
        return when (depthDisplayMode) {
            "off" -> false
            "normal" -> tool == "none"
            "waypoint" -> tool == "wpt"
            else -> tool == "none" || tool == "wpt"
        }
    }

    private fun depthModeLabel(): String {
        return when (depthDisplayMode) {
            "off" -> "Off"
            "normal" -> "Normal only"
            "waypoint" -> "Waypoint only"
            else -> "Both"
        }
    }

    private fun updateCrosshairDepth(depth: String) {
        try {
            val tv = findViewById<TextView>(R.id.tvCrosshairDepth)
            tv.textSize = depthTextSize
            tv.setTextColor(Color.parseColor("#FFFFFF"))
            if (depthEnabledForCurrentTool() && depth.isNotEmpty()) {
                tv.text = depth
                tv.visibility = View.VISIBLE
            } else {
                tv.visibility = View.GONE
            }
        } catch (e: Exception) {}
    }


    private fun setCrosshair(visible: Boolean) {
        try {
            val ch = findViewById<FrameLayout>(R.id.crosshairContainer)
            ch.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) {
                val c = map?.cameraPosition?.target
                if (c != null) updateCrosshairCoords(c.latitude, c.longitude)
            }
        } catch (e: Exception) {}
    }

    private fun updateColorPicker() {
        listOf(R.id.col0,R.id.col1,R.id.col2,R.id.col3,R.id.col4,R.id.col5).forEachIndexed { i, rid ->
            val v = findViewById<View>(rid)
            val sel = COLORS[i] == selectedColor
            v.scaleX = if (sel) 1.25f else 1f; v.scaleY = if (sel) 1.25f else 1f
        }
    }

    private fun refreshRoute() {
        val feats = mutableListOf<String>()
        if (routePts.size >= 2) {
            val coords = routePts.joinToString(",") { "[${it.lng},${it.lat}]" }
            feats.add("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}""")
        }
        routePts.forEach { feats.add("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.lng},${it.lat}]},"properties":{}}""") }
        (style?.getSource("route-src") as? GeoJsonSource)?.setGeoJson("""{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}""")

        val card = findViewById<LinearLayout>(R.id.routeCard)

        if (routePts.isEmpty()) {
            if (tool == "route") {
                card.visibility = View.VISIBLE
                findViewById<TextView>(R.id.tvRouteInfo).text = "ROUTE PLANNER — tap map to add first point at centre dot"
            } else {
                card.visibility = View.GONE
            }
            return
        }

        card.visibility = View.VISIBLE

        if (routePts.size < 2) {
            findViewById<TextView>(R.id.tvRouteInfo).text = "1 route point — tap map to add next point"
            return
        }

        val nm = totalNm(routePts.map { Pair(it.lat, it.lng) })
        val b = bearing(routePts.first(), routePts.last())
        findViewById<TextView>(R.id.tvRouteInfo).text =
            "ROUTE: ${routePts.size} pts  |  %.2f nm  (%.1f km)  |  %.0f°T  |  @6kt: %s  @12kt: %s".format(nm, nm*1.852, b, eta(nm,6.0), eta(nm,12.0))
    }

    private fun refreshMeasure() {
        val feats = mutableListOf<String>()
        if (measurePts.size >= 2) {
            val coords = measurePts.joinToString(",") { "[${it.lng},${it.lat}]" }
            feats.add("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}""")
        }
        measurePts.forEach { feats.add("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.lng},${it.lat}]},"properties":{}}""") }
        (style?.getSource("meas-src") as? GeoJsonSource)?.setGeoJson("""{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}""")
        val card = findViewById<LinearLayout>(R.id.measureCard)
        if (measurePts.isEmpty()) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE
        if (measurePts.size < 2) { findViewById<TextView>(R.id.tvMeasure).text = "Tap another point…"; return }
        val nm = totalNm(measurePts.map { Pair(it.lat, it.lng) })
        findViewById<TextView>(R.id.tvMeasure).text = "%.2f nm  —  %.1f km".format(nm, nm*1.852)
    }

    private fun distNm(a: Pair<Double,Double>, b: Pair<Double,Double>): Double {
        val R = 3440.065
        val dLat = Math.toRadians(b.first - a.first); val dLng = Math.toRadians(b.second - a.second)
        val x = sin(dLat/2).pow(2) + cos(Math.toRadians(a.first))*cos(Math.toRadians(b.first))*sin(dLng/2).pow(2)
        return 2*R*asin(sqrt(x))
    }
    private fun totalNm(pts: List<Pair<Double,Double>>): Double { var t=0.0; for(i in 1 until pts.size) t+=distNm(pts[i-1],pts[i]); return t }
    private fun bearing(a: RoutePoint, b: RoutePoint): Double {
        val dL = Math.toRadians(b.lng-a.lng); val y = sin(dL)*cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat))*sin(Math.toRadians(b.lat))-sin(Math.toRadians(a.lat))*cos(Math.toRadians(b.lat))*cos(dL)
        return (Math.toDegrees(atan2(y,x))+360)%360
    }
    private fun eta(nm: Double, kts: Double): String {
        if (nm<=0||kts<=0) return "—"
        val h=nm/kts; val hh=h.toInt(); val mm=((h-hh)*60).toInt()
        return if (hh>0) "${hh}h${mm}m" else "${mm}m"
    }
    private fun fmtDDM(lat: Double, lng: Double): String {
        val ld=abs(lat).toInt(); val lm=(abs(lat)-ld)*60
        val nd=abs(lng).toInt(); val nm=(abs(lng)-nd)*60
        return "%d°%06.3f'%s %d°%06.3f'%s".format(ld,lm,if(lat>=0)"N" else "S",nd,nm,if(lng>=0)"E" else "W")
    }


    private fun fmtDD(lat: Double, lng: Double): String =
        "%.6f, %.6f".format(java.util.Locale.US, lat, lng)

    private fun fmtDMS(lat: Double, lng: Double): String {
        fun one(value: Double, positive: String, negative: String): String {
            val a = abs(value)
            val d = a.toInt()
            val minutesFull = (a - d) * 60.0
            val m = minutesFull.toInt()
            val sec = (minutesFull - m) * 60.0
            return "%d°%02d'%05.2f\"%s".format(
                java.util.Locale.US, d, m, sec, if (value >= 0) positive else negative
            )
        }
        return "${one(lat, "N", "S")} ${one(lng, "E", "W")}" 
    }

    private fun formatCoordinatePair(lat: Double, lng: Double, mode: String): String = when (mode) {
        "DD" -> fmtDD(lat, lng)
        "DMS" -> fmtDMS(lat, lng)
        else -> fmtDDM(lat, lng)
    }

    private fun parseSingleCoordinate(raw: String, isLat: Boolean): Double? {
        val text = raw.trim().uppercase()
        val hemi = text.firstOrNull { it in "NSEW" }
        val nums = Regex("[-+]?\\d+(?:\\.\\d+)?").findAll(text)
            .map { it.value.toDouble() }.toList()
        if (nums.isEmpty()) return null

        var value = when (nums.size) {
            1 -> abs(nums[0])
            2 -> abs(nums[0]) + nums[1] / 60.0
            else -> abs(nums[0]) + nums[1] / 60.0 + nums[2] / 3600.0
        }

        val explicitNegative = nums[0] < 0
        if (explicitNegative || hemi == 'S' || hemi == 'W') value = -value
        if (hemi == 'N' || hemi == 'E') value = abs(value)

        val limit = if (isLat) 90.0 else 180.0
        return value.takeIf { it in -limit..limit }
    }

    private fun parseCoordinatePair(raw: String): Pair<Double, Double>? {
        val text = raw.trim().replace('\n', ' ')
        if (text.isBlank()) return null

        val comma = text.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (comma.size == 2) {
            val lat = parseSingleCoordinate(comma[0], true)
            val lng = parseSingleCoordinate(comma[1], false)
            if (lat != null && lng != null) return lat to lng
        }

        val latMatch = Regex("(.+?[NS])", RegexOption.IGNORE_CASE).find(text)
        val lngMatch = Regex("(.+?[EW])", RegexOption.IGNORE_CASE).find(text.substring(latMatch?.range?.last?.plus(1) ?: 0))
        if (latMatch != null && lngMatch != null) {
            val lat = parseSingleCoordinate(latMatch.value, true)
            val lng = parseSingleCoordinate(lngMatch.value, false)
            if (lat != null && lng != null) return lat to lng
        }

        val nums = Regex("[-+]?\\d+(?:\\.\\d+)?").findAll(text).map { it.value }.toList()
        if (nums.size == 2) {
            val lat = nums[0].toDoubleOrNull()
            val lng = nums[1].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return lat to lng
            }
        }
        return null
    }

    private fun showCoordinateEditor(
        title: String,
        initialLat: Double,
        initialLng: Double,
        positiveLabel: String,
        showMoveButton: Boolean = false,
        onPositive: (Double, Double) -> Unit
    ) {
        val palette = activePalette()
        val dialog = android.app.Dialog(this, R.style.Theme_SeaBathy_Dialog).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(
                palette.panel,
                palette.border,
                if (uiRounded) 18 else 8
            )
            setPadding(18.dp, 16.dp, 18.dp, 16.dp)
        }
        root.addView(card)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.primaryText)
            }, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ))
            addView(TextView(this@MainActivity).apply {
                text = "✕"
                contentDescription = "Close"
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(palette.primaryText)
                background = roundedBackground(
                    palette.raised,
                    palette.border,
                    if (uiRounded) 10 else 5
                )
                setPadding(14.dp, 8.dp, 14.dp, 8.dp)
                setOnClickListener { dialog.dismiss() }
            })
        }
        card.addView(header)

        card.addView(TextView(this).apply {
            text = "FORMAT"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            setPadding(2.dp, 18.dp, 0, 7.dp)
        })

        val formats = listOf(
            "Marine — degrees and decimal minutes",
            "Decimal degrees",
            "Degrees, minutes and seconds"
        )
        val modeKeys = listOf("DDM", "DD", "DMS")

        val spinner = Spinner(this).apply {
            background = roundedBackground(
                palette.raised,
                palette.border,
                if (uiRounded) 12 else 5
            )
            adapter = object : ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                formats
            ) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    return (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(activePalette().primaryText)
                        setBackgroundColor(Color.TRANSPARENT)
                        setPadding(14.dp, 12.dp, 14.dp, 12.dp)
                    }
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    val p = activePalette()
                    return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setTextColor(p.primaryText)
                        setBackgroundColor(p.surface)
                        setPadding(16.dp, 15.dp, 16.dp, 15.dp)
                    }
                }
            }.also {
                it.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item
                )
            }
        }
        card.addView(
            spinner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                50.dp
            )
        )

        card.addView(TextView(this).apply {
            text = "COORDINATES"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            setPadding(2.dp, 18.dp, 0, 7.dp)
        })

        val input = EditText(this).apply {
            setText(fmtDDM(initialLat, initialLng))
            hint = "Paste latitude and longitude"
            minLines = 2
            maxLines = 4
            setSelectAllOnFocus(true)
            setTextColor(palette.primaryText)
            setHintTextColor(palette.secondaryText)
            typeface = Typeface.MONOSPACE
            background = roundedBackground(
                palette.raised,
                palette.border,
                if (uiRounded) 12 else 5
            )
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        }
        card.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(TextView(this).apply {
            text = "PREVIEW"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            setPadding(2.dp, 18.dp, 0, 7.dp)
        })

        val preview = TextView(this).apply {
            text = fmtDDM(initialLat, initialLng)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.primaryText)
            background = roundedBackground(
                palette.surface,
                palette.border,
                if (uiRounded) 12 else 5
            )
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        }
        card.addView(preview)

        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val parsed =
                        parseCoordinatePair(input.text.toString())
                            ?: (initialLat to initialLng)
                    input.setText(
                        formatCoordinatePair(
                            parsed.first,
                            parsed.second,
                            modeKeys[position]
                        )
                    )
                    preview.text = fmtDDM(parsed.first, parsed.second)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        input.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    value: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    value: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    val parsed =
                        parseCoordinatePair(value?.toString().orEmpty())
                    preview.text =
                        if (parsed == null) {
                            "Enter valid coordinates"
                        } else {
                            fmtDDM(parsed.first, parsed.second)
                        }
                    preview.setTextColor(
                        if (parsed == null) {
                            activePalette().destructive
                        } else {
                            activePalette().primaryText
                        }
                    )
                }

                override fun afterTextChanged(
                    value: android.text.Editable?
                ) {}
            }
        )

        fun parsedOrError(): Pair<Double, Double>? {
            val parsed = parseCoordinatePair(input.text.toString())
            if (parsed == null) {
                input.error = "Use DD, DDM or DMS coordinates"
            }
            return parsed
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 18.dp, 0, 0)

            addView(Button(this@MainActivity).apply {
                text = "Cancel"
                isAllCaps = false
                setTextColor(palette.primaryText)
                background = roundedBackground(
                    palette.raised,
                    palette.border,
                    if (uiRounded) 10 else 5
                )
                minHeight = 44.dp
                setOnClickListener { dialog.dismiss() }
            })

            if (showMoveButton) {
                addView(Button(this@MainActivity).apply {
                    text = "Move map"
                    isAllCaps = false
                    setTextColor(palette.primaryText)
                    background = roundedBackground(
                        palette.surface,
                        palette.border,
                        if (uiRounded) 10 else 5
                    )
                    minHeight = 44.dp
                    setOnClickListener {
                        val parsed = parsedOrError()
                            ?: return@setOnClickListener
                        map?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(parsed.first, parsed.second),
                                14.0
                            )
                        )
                        dialog.dismiss()
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8.dp })
            }

            addView(Button(this@MainActivity).apply {
                text = positiveLabel
                isAllCaps = false
                setTextColor(palette.onAccent)
                background = roundedBackground(
                    palette.accent,
                    null,
                    if (uiRounded) 10 else 5
                )
                minHeight = 44.dp
                setPadding(18.dp, 0, 18.dp, 0)
                setOnClickListener {
                    val parsed =
                        parsedOrError() ?: return@setOnClickListener
                    onPositive(parsed.first, parsed.second)
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp })
        }
        card.addView(actions)

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(
                    palette.background
                )
            )
            addFlags(
                android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            attributes = attributes.apply {
                gravity = Gravity.CENTER
                dimAmount = 0.72f
            }
            setLayout(
                (resources.displayMetrics.widthPixels * 0.96f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun exportGpx() {
        var gpx = """<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="SeaBathy">"""
        waypoints.forEach { gpx += """<wpt lat="${it.lat}" lon="${it.lng}"><n>${it.name}</n></wpt>""" }
        if (routePts.size>1) { gpx+="<rte><n>Route</n>"; routePts.forEach { gpx+="""<rtept lat="${it.lat}" lon="${it.lng}"/>""" }; gpx+="</rte>" }
        gpx += "</gpx>"
        val file = File(filesDir.resolve("exports").also{it.mkdirs()}, "seabathy_${System.currentTimeMillis()}.gpx")
        file.writeText(gpx)
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type="application/gpx+xml"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Export GPX"))
    }

    private fun setupUI() {
        // Basemap spinner
        val basemaps = listOf("Dark" to "dark", "OSM" to "osm", "Custom XYZ" to "custom", "None" to "none")
        val spin = findViewById<Spinner>(R.id.spinBasemap)
        spin.adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            basemaps.map { it.first }
        ) {
            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                val palette = activePalette()
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(palette.primaryText)
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(14.dp, 10.dp, 14.dp, 10.dp)
                }
            }

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                val palette = activePalette()
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(palette.primaryText)
                    setBackgroundColor(palette.surface)
                    setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                }
            }
        }.also {
            it.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
        }
        spin.background = roundedBackground(
            activePalette().raised,
            activePalette().border,
            if (uiRounded) 10 else 5
        )

        val savedBasemapIndex = basemaps.indexOfFirst { it.second == currentBasemap }
            .takeIf { it >= 0 } ?: 0
        spin.setSelection(savedBasemapIndex, false)

        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val selectedType = basemaps[pos].second
                if (selectedType == currentBasemap) return

                if (selectedType == "custom" && customBasemapUrl.isBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Configure Custom XYZ in Settings first",
                        Toast.LENGTH_SHORT
                    ).show()
                    spin.setSelection(savedBasemapIndex, false)
                    return
                }

                rebuildMapStyle(selectedType)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Panel
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { openPanel() }
        findViewById<ImageButton>(R.id.btnPanelClose).setOnClickListener { closePanel() }
        findViewById<FrameLayout>(R.id.panelScrim).setOnClickListener { closePanel() }
        findViewById<Button>(R.id.btnLoadMbtiles).setOnClickListener {
            mbtilesPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="*/*" })
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showVisualSettings() }

        findViewById<TextView>(R.id.tvCoords).setOnClickListener {
            val c = map?.cameraPosition?.target ?: return@setOnClickListener
            showCoordinateEditor(
                title = "Create waypoint at coordinates",
                initialLat = c.latitude,
                initialLng = c.longitude,
                positiveLabel = "Create waypoint",
                showMoveButton = true
            ) { lat, lng ->
                showWptDialog(lat, lng)
            }
        }

        findViewById<TextView>(R.id.tvWptCoords).setOnClickListener {
            showCoordinateEditor(
                title = "Waypoint coordinates",
                initialLat = pendingWptLat,
                initialLng = pendingWptLng,
                positiveLabel = "Use coordinates"
            ) { lat, lng ->
                pendingWptLat = lat
                pendingWptLng = lng
                findViewById<FrameLayout>(R.id.wptDialogBg).tag = "$lat,$lng"
                findViewById<TextView>(R.id.tvWptCoords).text = fmtDDM(lat, lng)
            }
        }

        // Tool buttons — icon tint toggling
        fun toolBtn(id: Int, t: String, hint: String) {
            findViewById<ImageButton>(id).setOnClickListener {
                if (tool==t) {
                    tool="none"; setHint(null)
                    if(t=="measure"){measurePts.clear();refreshMeasure();setCrosshair(true)}
                    if(t=="wpt") setCrosshair(true)
                    highlightTool(null)
                } else {
                    tool=t; setHint(hint); highlightTool(id)
                    if(t=="wpt") setCrosshair(true)
                    else if(t=="measure") setCrosshair(false)
                    else setCrosshair(true)
                }
            }
        }
        toolBtn(R.id.btnWpt,     "wpt",     "Tap map to drop waypoint marker")
        toolBtn(R.id.btnRoute,   "route",   "Tap map to add route points")
        toolBtn(R.id.btnMeasure, "measure", "Tap map to measure — tap CLEAR when done")

        // GPS: grey=off, green=locate/free pan, orange=follow lock
        updateGpsButton()
        findViewById<ImageButton>(R.id.btnGps).setOnClickListener {
            gpsMode = (gpsMode + 1) % 3
            gpsOn = gpsMode != 0
            gpsCenteredOnce = false

            if (gpsOn) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
                    startGps()
                } else {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            } else {
                stopGps()
                (style?.getSource("gps-src") as? GeoJsonSource)?.setGeoJson(buildEmptyGeoJson())
            }
            updateGpsButton()
        }

        // Measure clear
        findViewById<Button>(R.id.btnMeasureClear).setOnClickListener {
            measurePts.clear(); refreshMeasure(); tool="none"; setHint(null); highlightTool(null)
        }
        // Route removed for now; keep code dormant for possible future route planner
        findViewById<LinearLayout>(R.id.routeCard).visibility = View.GONE
        routePts.clear()

        // Waypoint dialog
        listOf(R.id.col0,R.id.col1,R.id.col2,R.id.col3,R.id.col4,R.id.col5).forEachIndexed { i, rid ->
            findViewById<View>(rid).setOnClickListener { selectedColor=COLORS[i]; updateColorPicker() }
        }
        findViewById<Button>(R.id.btnWptCancel).setOnClickListener {
            findViewById<FrameLayout>(R.id.wptDialogBg).visibility=View.GONE
            hideKeyboardHard()
        }
        findViewById<Button>(R.id.btnWptSave).setOnClickListener {
            var name = findViewById<EditText>(R.id.etWptName).text.toString().trim()
            if (name.isBlank()) {
                var n = 1
                do {
                    name = "WPT $n"
                    n++
                } while (waypoints.any { it.name.equals(name, ignoreCase = true) })
            }
            if (waypoints.any { it.name.equals(name, ignoreCase = true) }) {
                Toast.makeText(this@MainActivity, "Waypoint name already exists", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val desc = findViewById<EditText?>(resources.getIdentifier("etWptDesc", "id", packageName))?.text?.toString()?.trim() ?: ""
            // Read coords from tag as backup in case pendingWpt got reset
            val tag = findViewById<FrameLayout>(R.id.wptDialogBg).tag?.toString()
            if (tag != null) {
                val parts = tag.split(",")
                if (parts.size == 2) { pendingWptLat = parts[0].toDoubleOrNull() ?: pendingWptLat; pendingWptLng = parts[1].toDoubleOrNull() ?: pendingWptLng }
            }
            waypoints.add(Waypoint(UUID.randomUUID().toString(), name, pendingWptLat, pendingWptLng, selectedColor, desc))
            renderWaypoints()
            findViewById<FrameLayout>(R.id.wptDialogBg).visibility=View.GONE
            hideKeyboardHard()
            tool="none"; setHint(null); highlightTool(null)
        }
        // Also allow ime done button to save
        findViewById<EditText>(R.id.etWptName).setOnEditorActionListener { _, _, _ ->
            findViewById<Button>(R.id.btnWptSave).performClick(); true
        }
    }

    private fun highlightTool(activeId: Int?) {
        listOf(R.id.btnWpt, R.id.btnMeasure).forEach { id ->
            val btn = findViewById<ImageButton>(id)
            val color = if (id==activeId) Color.parseColor("#00C8B4") else Color.parseColor("#AEB7C2")
            btn.imageTintList = android.content.res.ColorStateList.valueOf(color)
        }
        updateGpsButton()
    }


    private fun loadVisualSettings() {
        cursorColor = prefs.getString("cursor_color", "#00C8B4") ?: "#00C8B4"
        coordColor = prefs.getString("coord_color", "#00C8B4") ?: "#00C8B4"
        showDepth = prefs.getBoolean("show_depth", true)
        depthDisplayMode = prefs.getString("depth_display_mode", if (showDepth) "both" else "off") ?: "both"
        depthTextSize = prefs.getFloat("depth_text_size", 12f)
        coordTextSize = prefs.getFloat("coord_text_size", 13f)
    }

    private fun saveVisualSettings() {
        prefs.edit()
            .putString("cursor_color", cursorColor)
            .putString("coord_color", coordColor)
            .putBoolean("show_depth", depthDisplayMode != "off")
            .putString("depth_display_mode", depthDisplayMode)
            .putFloat("depth_text_size", depthTextSize)
            .putFloat("coord_text_size", coordTextSize)
            .apply()
    }

    private fun applyVisualSettings() {
        try {
            findViewById<TextView>(R.id.crosshairDot)?.setTextColor(Color.parseColor(cursorColor))
            findViewById<TextView>(R.id.centerDot)?.setTextColor(Color.parseColor(cursorColor))
            findViewById<TextView>(R.id.tvCoords)?.apply {
                textSize = coordTextSize
                setTextColor(Color.parseColor(coordColor))
            }
            findViewById<TextView>(R.id.tvCrosshairDepth)?.textSize = depthTextSize
        } catch (e: Exception) {}
    }

    private fun showVisualSettings() {
        val palette = activePalette()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 0, 16.dp, 12.dp)
            setBackgroundColor(palette.panel)
        }

        fun section(title: String) {
            body.addView(TextView(this).apply {
                text = title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.accent)
                setPadding(2.dp, 18.dp, 0, 8.dp)
            })
        }

        fun addRow(view: View) {
            body.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 9.dp }
            )
        }

        lateinit var settingsDialog: android.app.Dialog

        fun refreshSettings() {
            // Keep the current Settings panel open.
            // Updated values are shown the next time Settings is opened.
            applyReadableText(body, activePalette())
        }

        section("MAP DISPLAY")

        val colourNames = arrayOf(
            "Marine teal", "Green", "Orange", "Red", "Purple", "White"
        )
        val colourValues = arrayOf(
            "#19C6B3", "#22C55E", "#F97316", "#EF4444", "#A855F7", "#F4F6F8"
        )

        fun colourName(value: String): String {
            val index = colourValues.indexOf(value)
            return if (index >= 0) colourNames[index] else "Custom"
        }

        addRow(makePanelRow("Crosshair colour", colourName(cursorColor)) {
            showOpaqueChoicePanel(
                "Crosshair colour",
                colourNames,
                colourValues.indexOf(cursorColor).coerceAtLeast(0)
            ) { index ->
                cursorColor = colourValues[index]
                saveVisualSettings()
                applyVisualSettings()
                refreshSettings()
            }
        })

        addRow(makePanelRow("Coordinate colour", colourName(coordColor)) {
            showOpaqueChoicePanel(
                "Coordinate colour",
                colourNames,
                colourValues.indexOf(coordColor).coerceAtLeast(0)
            ) { index ->
                coordColor = colourValues[index]
                saveVisualSettings()
                applyVisualSettings()
                refreshSettings()
            }
        })

        val depthLabels = arrayOf("Both", "Normal only", "Waypoint only", "Off")
        val depthValues = arrayOf("both", "normal", "waypoint", "off")
        addRow(makePanelRow("Depth display", depthModeLabel()) {
            showOpaqueChoicePanel(
                "Depth display",
                depthLabels,
                depthValues.indexOf(depthDisplayMode).coerceAtLeast(0)
            ) { index ->
                depthDisplayMode = depthValues[index]
                showDepth = depthDisplayMode != "off"
                saveVisualSettings()
                applyVisualSettings()
                updateCrosshairDepth("")
                refreshSettings()
            }
        })

        val coordinateSizes = arrayOf("11", "12", "13", "14", "15", "16")
        addRow(makePanelRow("Coordinate text size", "${coordTextSize.toInt()} sp") {
            showOpaqueChoicePanel(
                "Coordinate text size",
                coordinateSizes,
                coordinateSizes.indexOf(coordTextSize.toInt().toString()).coerceAtLeast(0)
            ) { index ->
                coordTextSize = coordinateSizes[index].toFloat()
                saveVisualSettings()
                applyVisualSettings()
                refreshSettings()
            }
        })

        val depthSizes = arrayOf("10", "11", "12", "13", "14", "15", "16")
        addRow(makePanelRow("Depth text size", "${depthTextSize.toInt()} sp") {
            showOpaqueChoicePanel(
                "Depth text size",
                depthSizes,
                depthSizes.indexOf(depthTextSize.toInt().toString()).coerceAtLeast(0)
            ) { index ->
                depthTextSize = depthSizes[index].toFloat()
                saveVisualSettings()
                applyVisualSettings()
                refreshSettings()
            }
        })

        section("ONLINE MAP")
        addRow(makePanelRow(
            "Custom XYZ",
            if (customBasemapUrl.isBlank()) "Not configured" else "Configured"
        ) {
            showCustomBasemapDialog {
                settingsDialog.dismiss()
                rebuildMapStyle("custom")
            }
        })

        settingsDialog = showOpaquePanel("Settings", body)
        applyReadableText(body, palette)
    }

    private fun setHint(msg: String?) {
        val v = findViewById<TextView>(R.id.tvHint)
        if (msg.isNullOrBlank()) {
            v.visibility = View.GONE
            v.text = ""
        } else {
            v.text = msg
            v.visibility = View.VISIBLE
        }
    }

    private fun openPanel() {
        val panel = findViewById<LinearLayout>(R.id.sidePanel)
        val scrim = findViewById<FrameLayout>(R.id.panelScrim)
        scrim.visibility=View.VISIBLE; scrim.alpha=0f
        panel.animate().translationX(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()
        scrim.animate().alpha(1f).setDuration(250).start()
    }

    private fun closePanel() {
        val panel = findViewById<LinearLayout>(R.id.sidePanel)
        val scrim = findViewById<FrameLayout>(R.id.panelScrim)
        panel.animate().translationX((-300).dp.toFloat()).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        scrim.animate().alpha(0f).setDuration(220).withEndAction{scrim.visibility=View.GONE}.start()
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private fun saveWaypoints() { prefs.edit().putString("wpts", gson.toJson(waypoints)).apply() }
    private fun loadWaypoints(): List<Waypoint> {
        val json=prefs.getString("wpts",null)?:return emptyList()
        return try{gson.fromJson(json,object:TypeToken<List<Waypoint>>(){}.type)}catch(e:Exception){emptyList()}
    }
    private fun buildEmptyGeoJson() = """{"type":"FeatureCollection","features":[]}"""

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause(); saveWaypoints() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); mapView.onSaveInstanceState(out) }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    private fun queryDepthAtCrosshair(lat: Double, lng: Double, callback: (String) -> Unit) {
        val m = map ?: return
        val screenPoint = m.projection.toScreenLocation(LatLng(lat, lng))
        val pixel = android.graphics.PointF(screenPoint.x, screenPoint.y)

        val contourLayerIds = mbtLayerIds
            .filter { id -> style?.getLayer("${id}-layer") is LineLayer }
            .map { id -> "${id}-layer" }
            .toTypedArray()
        if (contourLayerIds.isEmpty()) { callback(""); return }

        // Search with increasing radius until we find contours
        // This gives us the depth of the ZONE we are in, not just on-line depth
        // Same approach as Navionics — find nearest contour lines surrounding the point
        val radii = listOf(40f, 80f, 150f, 300f)
        for (radius in radii) {
            val rect = android.graphics.RectF(
                pixel.x - radius, pixel.y - radius,
                pixel.x + radius, pixel.y + radius
            )
            val features = m.queryRenderedFeatures(rect, *contourLayerIds)
            if (features.isEmpty()) continue

            val depths = features.mapNotNull { feature ->
                feature.getNumberProperty("Contour")?.toInt()
                    ?: feature.getNumberProperty("contour")?.toInt()
                    ?: feature.getNumberProperty("depth")?.toInt()
            }.map { Math.abs(it) }.filter { it > 0 }.sorted()

            if (depths.isEmpty()) continue

            // The shallowest contour found in the search area is the floor of our zone
            // e.g. if we're between -20m and -25m contours, min will be 20
            val zoneDepth = depths.min()
            callback(">${zoneDepth}m")
            return
        }
        callback("")
    }
}
