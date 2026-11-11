package dev.silveradostats.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** The charger filters, remembered between launches. */
data class MapFilters(
  val minKw: Int = 0,
  /** Keys of [PLUG_GROUPS]. */
  val plugs: Set<String> = emptySet(),
  /** Open Charge Map operator ids; empty = every network. */
  val networks: Set<Int> = emptySet(),
  val workingOnly: Boolean = true,
)

/** One choice in the network picker: operator id, its name and how many chargers in view. */
data class NetworkChoice(val id: Int, val name: String, val count: Int)

val POWER_CHOICES = listOf(0, 50, 150, 250, 350)

private const val METERS_PER_MILE = 1609.344
private val CELL_DEGREES = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0)
private const val DOT_SPACING_DP = 12 // closest two charger dots may sit before the weaker one is hidden
private const val OUTLINE_REFRESH = 0.05 // redraw the outline after moving, or the range changing, by this share of the range
private const val OUTLINE_COLOR = 0xFF378ADD.toInt()

/** Dot and tag colours by power: 150 kW and up, 50 to 149, under 50. */
val TIER_COLORS = intArrayOf(0xFF1D9E75.toInt(), 0xFFEF9F27.toInt(), 0xFF888780.toInt())

fun powerTier(kw: Double): Int = if (kw >= 150) 0 else if (kw >= 50) 1 else 2

/** Chargers already fetched, kept for the life of the process so that leaving the tab does not refetch. */
private object ChargerCache {
  val cells = HashMap<String, List<Charger>>()
  var reference = Reference(emptyMap(), emptyMap(), emptySet())
}

/**
 * Everything behind the Map tab that is not layout: the Google map view, charger loading and dots,
 * the range outline, GPS position, and the hand-off to Google Maps. It reads web services and the
 * phone-style location API only; it never touches the car API (the range comes in from the session).
 */
class MapController(private val context: Context) : LocationListener {
  val mapView = MapView(context)
  private val io = Executors.newFixedThreadPool(4)
  private val main = Handler(Looper.getMainLooper())
  private val prefs = context.getSharedPreferences("map", Context.MODE_PRIVATE)
  private var destroyed = false

  private var map: GoogleMap? = null
  private var here: LatLng? = null
  private var following = true

  // Range and the outline drawn from it
  private var rangeMeters = Double.NaN
  private var outline: Polygon? = null
  private var outlineAt: LatLng? = null
  private var outlineRange = 0.0
  private var outlineBusy = false

  // Chargers
  private var loaded = emptyList<Charger>()
  private val markers = HashMap<Int, Marker>()
  private val pending = HashSet<String>()
  @Volatile private var wanted = emptyList<String>()
  private val dots by lazy { listOf(dot(TIER_COLORS[0], 22), dot(TIER_COLORS[1], 18), dot(TIER_COLORS[2], 14)) }
  private var pin: Marker? = null

  /** The charger or place the card shows, or null. */
  var target by mutableStateOf<Place?>(null)
    private set

  var filters by mutableStateOf(
    MapFilters(
      prefs.getInt("minKw", 0),
      prefs.getStringSet("plugs", emptySet()).orEmpty().toSet(),
      prefs.getStringSet("networks", emptySet()).orEmpty().mapNotNull(String::toIntOrNull).toSet(),
      prefs.getBoolean("workingOnly", true),
    ),
  )
    private set

  var hasLocationPermission by mutableStateOf(false)
    private set

  init {
    mapView.onCreate(null)
    mapView.getMapAsync(::onMapReady)
    if (ChargerCache.reference.operators.isEmpty()) {
      background({ MapApi.reference() }) { ChargerCache.reference = it; loadChargers() }
    }
  }

  // ---- Lifecycle, driven by the screen ----

  fun start() = mapView.onStart()

  fun resume() {
    mapView.onResume()
    startLocation()
  }

  fun pause() = mapView.onPause()

  fun stop() = mapView.onStop()

  fun destroy() {
    destroyed = true
    io.shutdownNow()
    try {
      (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
    } catch (_: RuntimeException) {
    }
    mapView.onPause()
    mapView.onStop()
    mapView.onDestroy()
  }

  private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

  private fun <T> background(work: () -> T, failed: () -> Unit = {}, done: (T) -> Unit) {
    io.execute {
      val result = runCatching(work)
      main.post {
        if (!destroyed) result.onSuccess(done).onFailure { failed(); toast(it.message ?: it.toString()) }
      }
    }
  }

  private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

  // ---- Position ----

  @SuppressLint("MissingPermission")
  private fun startLocation() {
    hasLocationPermission = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!hasLocationPermission) return
    try {
      map?.isMyLocationEnabled = true
      (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
    } catch (_: RuntimeException) {
      // No GPS provider on this device: the map still works, without the vehicle's position.
    }
  }

  override fun onLocationChanged(l: Location) {
    val first = here == null
    val pos = LatLng(l.latitude, l.longitude)
    here = pos
    if (following) map?.animateCamera(if (first) CameraUpdateFactory.newLatLngZoom(pos, 11f) else CameraUpdateFactory.newLatLng(pos))
    refreshOutline()
  }

  private fun metersBetween(a: LatLng, b: LatLng): Float {
    val d = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, d)
    return d[0]
  }

  /** Straight-line miles from the vehicle to [p], or null without a GPS fix. */
  fun milesTo(p: Place): Int? = here?.let { (metersBetween(it, LatLng(p.lat, p.lng)) / METERS_PER_MILE).roundToInt() }

  // ---- Range outline ----

  /** The range to draw the outline for, in metres; null hides nothing already drawn but stops updates. */
  fun setRange(meters: Double?) {
    rangeMeters = meters?.takeIf { it > 0 } ?: Double.NaN
    refreshOutline()
  }

  /** Redraws the road-aware outline once the truck has moved, or its range has changed, enough to matter. */
  private fun refreshOutline() {
    val m = map ?: return
    val at = here ?: return
    val range = rangeMeters
    if (range.isNaN() || outlineBusy) return
    val last = outlineAt
    val step = (range * OUTLINE_REFRESH).coerceAtLeast(METERS_PER_MILE)
    if (last != null && metersBetween(last, at) < step && abs(range - outlineRange) < step) return
    outlineBusy = true
    // Recorded up front, so a failed lookup is retried after the next move, not on every GPS update.
    outlineAt = at
    outlineRange = range
    val probes = rangeProbes(at.latitude, at.longitude, range)
    background({ MapApi.roadMeters(at.latitude, at.longitude, probes) }, { outlineBusy = false }) { road ->
      outlineBusy = false
      outline?.remove()
      outline = m.addPolygon(
        PolygonOptions()
          .addAll(rangeOutline(at.latitude, at.longitude, range, road).map { LatLng(it.first, it.second) })
          .strokeColor(OUTLINE_COLOR).strokeWidth(dp(3).toFloat())
          .strokePattern(listOf(Dash(dp(14).toFloat()), Gap(dp(10).toFloat())))
          .fillColor(OUTLINE_COLOR and 0x00FFFFFF or 0x1A000000),
      )
    }
  }

  // ---- Map ----

  private fun onMapReady(m: GoogleMap) {
    if (destroyed) return
    map = m
    m.uiSettings.apply {
      // North-up, flat, always.
      isRotateGesturesEnabled = false
      isTiltGesturesEnabled = false
      isCompassEnabled = false
      isMapToolbarEnabled = false
      isZoomControlsEnabled = true
    }
    m.setOnCameraIdleListener { loadChargers() }
    m.setOnCameraMoveStartedListener { if (it == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) following = false }
    m.setOnMyLocationButtonClickListener { following = true; false }
    m.setOnMarkerClickListener { marker -> (marker.tag as? Charger)?.let { show(place(it)) }; true }
    m.setOnMapLongClickListener { show(Place("Dropped pin", "", it.latitude, it.longitude)) }
    startLocation()
    refreshOutline()
  }

  /** Keeps the map's own buttons and the vehicle clear of the search column and the card. */
  fun setPadding(leftPx: Int, topPx: Int) {
    map?.setPadding(leftPx, topPx, 0, 0)
  }

  private fun dot(color: Int, sizeDp: Int): BitmapDescriptor {
    val s = dp(sizeDp)
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Color.WHITE
    c.drawCircle(s / 2f, s / 2f, s / 2f, p)
    p.color = color
    c.drawCircle(s / 2f, s / 2f, s / 2f - s / 8f, p)
    return BitmapDescriptorFactory.fromBitmap(bmp)
  }

  private fun plugTypeIds(): Set<Int> =
    ChargerCache.reference.plugTypes.filterValues { title -> filters.plugs.any { PLUG_GROUPS[it]?.containsMatchIn(title) == true } }.keys

  /** Fetches chargers for the visible area, at any zoom level. Power and plug filters are applied by the server. */
  private fun loadChargers() {
    val p = map?.projection ?: return
    val nw = p.fromScreenLocation(Point(0, 0))
    val se = p.fromScreenLocation(Point(mapView.width, mapView.height))
    // A view that wraps the date line is treated as the whole world.
    val west = if (nw.longitude < se.longitude) nw.longitude else -180.0
    val east = if (nw.longitude < se.longitude) se.longitude else 180.0
    // The world is cut into a fixed grid whose cell size grows with the view, so a view needs only a
    // handful of requests, each cell is fetched once per process, and panning reuses what is loaded.
    val size = CELL_DEGREES.firstOrNull { (east - west) / it <= 6 } ?: CELL_DEGREES.last()
    val kw = filters.minKw
    val types = plugTypeIds().sorted()
    val grid = (floor(west / size).toInt()..floor(east / size).toInt()).flatMap { col ->
      (floor(se.latitude / size).toInt()..floor(nw.latitude / size).toInt()).map { row -> col to row }
    }.associateBy { (col, row) -> "$size:$col:$row:$kw:$types" }
    wanted = grid.keys.toList()
    for ((key, cell) in grid) {
      if (key in ChargerCache.cells || !pending.add(key)) continue
      val (col, row) = cell
      background({
        // Skip cells the view has already left by the time their turn comes.
        if (key !in wanted) {
          null
        } else {
          MapApi.chargers(
            ((row + 1) * size).coerceAtMost(90.0),
            (col * size).coerceAtLeast(-180.0),
            (row * size).coerceAtLeast(-90.0),
            ((col + 1) * size).coerceAtMost(180.0),
            kw,
            types,
          )
        }
      }, { pending -= key }) {
        pending -= key
        if (it != null) {
          ChargerCache.cells[key] = it
          showChargers()
        }
      }
    }
    showChargers()
  }

  /** Applies the network and status filters, thins overlapping dots and syncs the markers. */
  private fun showChargers() {
    val m = map ?: return
    val f = filters
    val ref = ChargerCache.reference
    loaded = wanted.flatMap { ChargerCache.cells[it].orEmpty() }
    // Keep one charger, the most powerful, per small patch of screen. Zooming in separates the rest.
    val scale = 256 * 2.0.pow(m.cameraPosition.zoom.toDouble()) / DOT_SPACING_DP
    val visible = HashMap<Long, Charger>()
    for (c in loaded) {
      if (f.networks.isNotEmpty() && c.operatorId !in f.networks) continue
      if (f.workingOnly && c.statusId in ref.nonOperational) continue
      val x = ((c.lng + 180) / 360 * scale).toLong()
      val y = ((0.5 - ln(tan(PI / 4 + Math.toRadians(c.lat.coerceIn(-85.0, 85.0)) / 2)) / (2 * PI)) * scale).toLong()
      val patch = x shl 32 or y
      val seen = visible[patch]
      if (seen == null || c.maxKw > seen.maxKw) visible[patch] = c
    }
    val byId = visible.values.associateBy { it.id }
    val stale = markers.iterator()
    while (stale.hasNext()) {
      val e = stale.next()
      if (e.key !in byId) {
        e.value.remove()
        stale.remove()
      }
    }
    for ((id, c) in byId) {
      if (id in markers) continue
      val t = powerTier(c.maxKw)
      m.addMarker(MarkerOptions().position(LatLng(c.lat, c.lng)).icon(dots[t]).anchor(0.5f, 0.5f).zIndex(2f - t))
        ?.let { it.tag = c; markers[id] = it }
    }
  }

  // ---- Filters ----

  /** Networks among the chargers in view, most common first, plus any already chosen. */
  fun networkChoices(): List<NetworkChoice> {
    val counts = loaded.groupingBy { it.operatorId }.eachCount()
    return (counts.keys + filters.networks)
      .map { NetworkChoice(it, ChargerCache.reference.operators[it] ?: "Unknown network", counts[it] ?: 0) }
      .sortedByDescending { it.count }
  }

  fun applyFilters(next: MapFilters) {
    val refetch = next.minKw != filters.minKw || next.plugs != filters.plugs
    filters = next
    prefs.edit()
      .putInt("minKw", next.minKw)
      .putStringSet("plugs", next.plugs)
      .putStringSet("networks", next.networks.map(Int::toString).toSet())
      .putBoolean("workingOnly", next.workingOnly)
      .apply()
    if (refetch) loadChargers() else showChargers()
  }

  // ---- Search, selection and the hand-off to Google Maps ----

  /** Looks [query] up near the vehicle (or the middle of the map) and hands the matches to [onResults]. */
  fun search(query: String, onResults: (List<Place>) -> Unit) {
    if (query.isBlank()) return
    val near = here ?: map?.cameraPosition?.target ?: return toast("Waiting for a GPS fix")
    background({ MapApi.search(query, near.latitude, near.longitude) }) { results ->
      if (results.isEmpty()) toast("No results for \"$query\"") else onResults(results)
    }
  }

  /** Shows [p] on the map and in the card; [moveCamera] for a search result that may be off screen. */
  fun show(p: Place?, moveCamera: Boolean = false) {
    target = p
    pin?.remove()
    pin = p?.let { map?.addMarker(MarkerOptions().position(LatLng(it.lat, it.lng)).zIndex(10f)) }
    if (p != null && moveCamera) {
      following = false
      map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lng), 13f))
    }
  }

  private fun place(c: Charger): Place {
    val ref = ChargerCache.reference
    val network = ref.operators[c.operatorId]?.takeUnless { it.startsWith("(") }
    val plugText = c.plugs.entries
      .groupingBy { e -> ref.plugTypes[e.key].let { t -> PLUG_GROUPS.entries.firstOrNull { t != null && it.value.containsMatchIn(t) }?.key ?: t ?: "plug" } }
      .fold(0) { n, e -> n + e.value }
      .map { "${it.value} ${it.key}" }.joinToString(", ")
    val status = when (c.statusId) {
      0 -> "Status unknown"
      in ref.nonOperational -> "Out of service"
      else -> "Operational"
    }
    return Place(
      listOfNotNull(network, c.title).joinToString(" · "),
      "",
      c.lat,
      c.lng,
      listOf("${c.maxKw.roundToInt()} kW", plugText, status).filter { it.isNotEmpty() },
    )
  }

  /**
   * Hands the destination to the car's navigation app, which does the routing and guidance. Only
   * navigation links are sent, with no app named: Google Maps is what answers them on the truck.
   */
  fun navigate(p: Place) {
    val at = "${p.lat},${p.lng}"
    val attempts = listOf(
      Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$at")),
      // Fallbacks for a car whose navigation app only takes the generic forms.
      Intent("androidx.car.app.action.NAVIGATE", Uri.parse("geo:$at")),
      Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$at")),
    )
    if (attempts.none { runCatching { context.startActivity(it) }.isSuccess }) toast("No navigation app accepted the destination")
  }
}
