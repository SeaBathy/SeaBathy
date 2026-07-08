package au.opendepth.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
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
data class MbtLayer(val name: String, val path: String)

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

    private var tool = "none"
    private var gpsOn = false
    private var gpsMode = 0 // 0 off, 1 locate/free pan, 2 follow lock
    private var gpsCenteredOnce = false
    private var gpsListener: LocationListener? = null
    private val waypoints = mutableListOf<Waypoint>()
    private val routePts = mutableListOf<RoutePoint>()
    private val measurePts = mutableListOf<RoutePoint>()
    private val mbtDbs = mutableMapOf<String, SQLiteDatabase>()
    private val mbtLayerIds = mutableListOf<String>()
    private val mbtFilePaths = mutableMapOf<String, String>()
    private val mbtLayerNames = mutableMapOf<String, String>()
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
        waypoints.addAll(loadWaypoints())
        setupInsets()
        setupMap(savedInstanceState)
        setupUI()
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
            m.cameraPosition = CameraPosition.Builder().target(LatLng(-18.3, 147.7)).zoom(8.0).build()
            applyBasemap("dark") {
                addMapLayers()
                renderWaypoints()
                restoreMbtLayers()
                setCrosshair(true)
                applyVisualSettings()
            }
            m.addOnMapClickListener { ll ->
                val pixel = m.projection.toScreenLocation(ll)
                val features = m.queryRenderedFeatures(pixel, "wpts-circles")
                if (features.isNotEmpty()) {
                    val wptId = features[0].getStringProperty("id")
                    val wpt = waypoints.find { it.id == wptId }
                    if (wpt != null) { showWptEditDialog(wpt); return@addOnMapClickListener true }
                }
                handleMapTap(ll.latitude, ll.longitude)
                true
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

    private fun applyBasemap(type: String, onLoaded: (() -> Unit)? = null) {
        val satJson = """{"version":8,"sources":{"sat":{"type":"raster","tiles":["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],"tileSize":256,"maxzoom":19}},"layers":[{"id":"sat","type":"raster","source":"sat"}]}"""
        val noneJson = """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#0A0F1E"}}]}"""
        val builder = when (type) {
            "dark" -> Style.Builder().fromUri("https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json")
            "osm"  -> Style.Builder().fromUri("https://demotiles.maplibre.org/style.json")
            "sat"  -> Style.Builder().fromJson(satJson)
            else   -> Style.Builder().fromJson(noneJson)
        }
        map?.setStyle(builder) { s -> style = s; onLoaded?.invoke() }
    }

    private fun addMapLayers() {
        val s = style ?: return
        s.addSource(GeoJsonSource("wpts-src", buildWptGeoJson()))
        s.addLayer(CircleLayer("wpts-circles", "wpts-src").withProperties(
            PropertyFactory.circleRadius(10f), PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleStrokeWidth(2f), PropertyFactory.circleStrokeColor("#FFFFFF")))
        s.addLayer(BackgroundLayer("mbt-raster-anchor").withProperties(
            PropertyFactory.backgroundColor(Color.TRANSPARENT)
        ))
        s.addLayer(SymbolLayer("wpts-labels", "wpts-src").withProperties(
            PropertyFactory.textField(Expression.get("name")), PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#E8EDF5"), PropertyFactory.textHaloColor("#0A0F1E"),
            PropertyFactory.textHaloWidth(1.5f), PropertyFactory.textOffset(arrayOf(0f, 1.8f)),
            PropertyFactory.textAnchor("top"), PropertyFactory.textFont(arrayOf("Open Sans Regular"))))
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
        val list = mbtLayerIds.map { id -> MbtLayer(mbtLayerNames[id] ?: id, mbtFilePaths[id] ?: "") }
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
        Toast.makeText(this, "Layer order updated", Toast.LENGTH_SHORT).show()
    }


    private fun setMbtAsBase(id: String) {
        if (!mbtLayerIds.contains(id)) return

        mbtLayerIds.remove(id)
        mbtLayerIds.add(0, id)

        rebuildMbtLayersLive()
        Toast.makeText(this, "Base layer set", Toast.LENGTH_SHORT).show()
    }


    private fun restoreMbtLayers() {
        val json = prefs.getString("mbt_layers", null) ?: return
        val list = try { gson.fromJson<List<MbtLayer>>(json, object : TypeToken<List<MbtLayer>>() {}.type) } catch (e: Exception) { return }
        scope.launch {
            list.forEach { layer ->
                val file = File(layer.path)
                if (!file.exists()) return@forEach
                try {
                    val db = withContext(Dispatchers.IO) {
                        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                    }
                    val id = "mbt_${System.currentTimeMillis()}_${layer.name}"
                    mbtDbs[id] = db
                    mbtLayerIds.add(id)
                    mbtFilePaths[id] = file.absolutePath
                    mbtLayerNames[id] = layer.name
                    addMbtLayerToMap(id, db, file.absolutePath)
                    addMbtPanelRow(id, layer.name)
                } catch (e: Exception) { Log.e(TAG, "restore ${layer.name}: ${e.message}") }
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

    private fun addMbtLayerToMap(id: String, db: SQLiteDatabase, filePath: String = "") {
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
        val tileSet = TileSet("2.0.0", "mbtiles://$destPath")
        tileSet.minZoom = minZ.toFloat()
        tileSet.maxZoom = maxZ.toFloat()
        try {
            if (isVector) {
                s.addSource(VectorSource("${id}-src", tileSet))
                val lineLayer = LineLayer("${id}-layer", "${id}-src")
                lineLayer.setSourceLayer("contours")
                lineLayer.setProperties(
                    PropertyFactory.lineColor("#00C8B4"),
                    PropertyFactory.lineWidth(1.0f),
                    PropertyFactory.lineOpacity(0.85f)
                )
                try { s.addLayerBelow(lineLayer, "wpts-circles") }
                catch (e: Exception) { s.addLayer(lineLayer) }
            } else {
                s.addSource(RasterSource("${id}-src", tileSet, 256))
                val layer = RasterLayer("${id}-layer", "${id}-src").withProperties(PropertyFactory.rasterOpacity(1f))
                val firstVectorLayer = mbtLayerIds.firstOrNull { lid -> s.getLayer("${lid}-layer") is LineLayer }
                val insertBelow = if (firstVectorLayer != null) "${firstVectorLayer}-layer" else "wpts-circles"
                var inserted = false
                try { s.addLayerBelow(layer, insertBelow); inserted = true } catch (e: Exception) {}
                if (!inserted) try { s.addLayerBelow(layer, "wpts-circles"); inserted = true } catch (e: Exception) {}
                if (!inserted) s.addLayer(layer)
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
            setTextColor(Color.parseColor("#E8EDF5"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sw = SwitchMaterial(this).apply {
            isChecked = true
            setOnCheckedChangeListener { _, on ->
                val layer = style?.getLayer("${id}-layer")
                when (layer) {
                    is RasterLayer -> layer.withProperties(PropertyFactory.rasterOpacity(if (on) 1f else 0f))
                    is LineLayer   -> layer.withProperties(PropertyFactory.lineOpacity(if (on) 0.8f else 0f))
                }
            }
        }

        val del = TextView(this).apply {
            text = "DEL"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#EF4444"))
            setBackgroundColor(Color.parseColor("#1A2535"))
            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
            setOnClickListener {
                try { style?.removeLayer("${id}-layer") } catch (e: Exception) {}
                try { style?.removeSource("${id}-src") } catch (e: Exception) {}
                mbtDbs[id]?.close()
                mbtDbs.remove(id)
                mbtLayerIds.remove(id)
                mbtFilePaths.remove(id)
                mbtLayerNames.remove(id)
                saveMbtLayers()
                container.removeView(rowLayout)
            }
        }

        top.addView(tv)
        top.addView(sw)
        top.addView(del, LinearLayout.LayoutParams(52.dp, 30.dp))

        val seek = SeekBar(this).apply {
            max = 100
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00C8B4"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00C8B4"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val layer = style?.getLayer("${id}-layer")
                    when (layer) {
                        is RasterLayer -> layer.withProperties(PropertyFactory.rasterOpacity(p / 100f))
                        is LineLayer   -> layer.withProperties(PropertyFactory.lineOpacity(p / 100f))
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1A2535"))
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
            else -> "#8FA3BF"
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
        val feats = waypoints.joinToString(",") { w ->
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${w.lng},${w.lat}]},"properties":{"id":"${w.id}","name":"${w.name.replace("\"","'")}","color":"${w.color}"}}"""
        }
        return """{"type":"FeatureCollection","features":[$feats]}"""
    }

    private fun renderWaypoints() {
        (style?.getSource("wpts-src") as? GeoJsonSource)?.setGeoJson(buildWptGeoJson())
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
            setTextColor(Color.parseColor("#E8EDF5"))
            setHintTextColor(Color.parseColor("#8FA3BF"))
            setBackgroundColor(Color.parseColor("#1A2535"))
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
                    setTextColor(Color.parseColor("#8FA3BF"))
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
                    setTextColor(Color.parseColor("#E8EDF5"))
                })

                info.addView(TextView(this).apply {
                    val d = (w.desc ?: "").trim()
                    text = if (d.isBlank()) fmtDDM(w.lat, w.lng) else d
                    textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.parseColor("#8FA3BF"))
                })

                val go = TextView(this).apply {
                    text = "GO"
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTextColor(Color.parseColor("#00C8B4"))
                    setBackgroundColor(Color.parseColor("#1A2535"))
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
                    setTextColor(Color.parseColor("#E8EDF5"))
                    setBackgroundColor(Color.parseColor("#1A2535"))
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
            setPadding(18.dp, 10.dp, 18.dp, 4.dp)
        }

        val nameInput = EditText(this).apply {
            setText(wpt.name)
            selectAll()
            hint = "Name"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.parseColor("#E8EDF5"))
            setHintTextColor(Color.parseColor("#8FA3BF"))
            setBackgroundColor(Color.parseColor("#1A2535"))
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        }

        val descInput = EditText(this).apply {
            setText(wpt.desc ?: "")
            hint = "Description / notes"
            minLines = 2
            maxLines = 4
            textSize = 14f
            setTextColor(Color.parseColor("#E8EDF5"))
            setHintTextColor(Color.parseColor("#8FA3BF"))
            setBackgroundColor(Color.parseColor("#1A2535"))
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        }

        val coords = TextView(this).apply {
            text = fmtDDM(wpt.lat, wpt.lng)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8FA3BF"))
            setPadding(0, 8.dp, 0, 8.dp)
        }

        outer.addView(nameInput)
        outer.addView(coords)
        outer.addView(descInput)

        val dialog = android.app.AlertDialog.Builder(this)
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

            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#EF4444"))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                android.app.AlertDialog.Builder(this)
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
                setTextColor(Color.parseColor("#E8EDF5"))
                setHintTextColor(Color.parseColor("#8FA3BF"))
                setBackgroundColor(Color.parseColor("#1A2535"))
                setPadding(12.dp, 10.dp, 12.dp, 10.dp)
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
        val basemaps = listOf("Dark" to "dark","OSM" to "osm","Satellite" to "sat","None" to "none")
        val spin = findViewById<Spinner>(R.id.spinBasemap)
        spin.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, basemaps.map{it.first}) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.parseColor("#E8EDF5"))
                v.setBackgroundColor(Color.parseColor("#1A2535"))
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Color.parseColor("#E8EDF5"))
                v.setBackgroundColor(Color.parseColor("#1A2535"))
                return v
            }
        }.also{it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var first=true
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if(first){first=false;return}
                applyBasemap(basemaps[pos].second){
                    addMapLayers()
                    renderWaypoints()
                    mbtLayerIds.toList().forEach { lid ->
                        val db = mbtDbs[lid]
                        val path = mbtFilePaths[lid]
                        if (db != null && path != null) addMbtLayerToMap(lid, db, path)
                    }
                    refreshRoute()
                    refreshMeasure()
                }
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
                Toast.makeText(this, "Waypoint name already exists", Toast.LENGTH_SHORT).show()
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
            val color = if (id==activeId) Color.parseColor("#00C8B4") else Color.parseColor("#8FA3BF")
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
        val colours = arrayOf("Blue", "Green", "Orange", "Red", "Purple", "White")
        val hexes = arrayOf("#00C8B4", "#22C55E", "#F97316", "#EF4444", "#A855F7", "#E8EDF5")

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 10.dp, 18.dp, 4.dp)
        }

        fun row(label: String, value: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = "$label: $value"
                textSize = 14f
                setTextColor(Color.parseColor("#E8EDF5"))
                setBackgroundColor(Color.parseColor("#1A2535"))
                setPadding(14.dp, 12.dp, 14.dp, 12.dp)
                setOnClickListener { onClick() }
            }
        }

        lateinit var dialog: android.app.AlertDialog

        fun colourName(hex: String): String {
            val idx = hexes.indexOf(hex)
            return if (idx >= 0) colours[idx] else hex
        }

        val cursorRow = row("Cursor colour", colourName(cursorColor)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Cursor colour")
                .setItems(colours) { _, i ->
                    cursorColor = hexes[i]
                    saveVisualSettings()
                    applyVisualSettings()
                    dialog.dismiss()
                    showVisualSettings()
                }.show()
        }

        val coordRow = row("Coordinate colour", colourName(coordColor)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Coordinate colour")
                .setItems(colours) { _, i ->
                    coordColor = hexes[i]
                    saveVisualSettings()
                    applyVisualSettings()
                    dialog.dismiss()
                    showVisualSettings()
                }.show()
        }

        val depthRow = row("Depth mode", depthModeLabel()) {
            val labels = arrayOf("Both", "Normal only", "Waypoint only", "Off")
            val values = arrayOf("both", "normal", "waypoint", "off")
            android.app.AlertDialog.Builder(this)
                .setTitle("Depth mode")
                .setItems(labels) { _, i ->
                    depthDisplayMode = values[i]
                    showDepth = depthDisplayMode != "off"
                    saveVisualSettings()
                    applyVisualSettings()
                    updateCrosshairDepth("")
                    dialog.dismiss()
                    showVisualSettings()
                }.show()
        }

        val coordSizeRow = row("Coordinate size", coordTextSize.toInt().toString()) {
            val sizes = arrayOf("11", "12", "13", "14", "15", "16")
            android.app.AlertDialog.Builder(this)
                .setTitle("Coordinate text size")
                .setItems(sizes) { _, i ->
                    coordTextSize = sizes[i].toFloat()
                    saveVisualSettings()
                    applyVisualSettings()
                    dialog.dismiss()
                    showVisualSettings()
                }.show()
        }

        val depthSizeRow = row("Depth size", depthTextSize.toInt().toString()) {
            val sizes = arrayOf("10", "11", "12", "13", "14", "15", "16")
            android.app.AlertDialog.Builder(this)
                .setTitle("Depth text size")
                .setItems(sizes) { _, i ->
                    depthTextSize = sizes[i].toFloat()
                    saveVisualSettings()
                    applyVisualSettings()
                    dialog.dismiss()
                    showVisualSettings()
                }.show()
        }

        listOf(cursorRow, coordRow, depthRow, coordSizeRow, depthSizeRow).forEach {
            outer.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp })
        }

        dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Visual settings")
            .setView(outer)
            .setPositiveButton("Done", null)
            .create()

        dialog.setOnShowListener { applyVisualSettings() }
        dialog.show()
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
