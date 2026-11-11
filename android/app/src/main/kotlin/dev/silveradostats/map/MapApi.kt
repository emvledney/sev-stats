package dev.silveradostats.map

import dev.silveradostats.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Charger(
  val id: Int,
  val title: String,
  val lat: Double,
  val lng: Double,
  val maxKw: Double,
  val operatorId: Int,
  val statusId: Int,
  /** Connection type id to count. */
  val plugs: Map<Int, Int>,
)

/** Anything the card can show and send to Google Maps. */
data class Place(val name: String, val detail: String, val lat: Double, val lng: Double, val tags: List<String> = emptyList())

/** Open Charge Map lookup tables. */
class Reference(val operators: Map<Int, String>, val plugTypes: Map<Int, String>, val nonOperational: Set<Int>)

/** Plug filter choices, matched against Open Charge Map connection type titles. */
val PLUG_GROUPS = linkedMapOf(
  "CCS" to Regex("CCS", RegexOption.IGNORE_CASE),
  "NACS" to Regex("NACS|Tesla", RegexOption.IGNORE_CASE),
  "CHAdeMO" to Regex("CHAdeMO", RegexOption.IGNORE_CASE),
  "J1772" to Regex("J1772", RegexOption.IGNORE_CASE),
)

// A grid cell with more chargers than this comes back incomplete, which shows on wide views with
// loose filters. Known ceiling: store a regional dataset on the device if that ever matters.
private const val MAX_CHARGERS_PER_CELL = 1000
private const val OCM = "https://api.openchargemap.io/v3"

/**
 * The Map tab's three web services, all read-only lookups that never touch the truck: Open Charge
 * Map (chargers), Google Places (search) and Google Routes (road distances for the range outline).
 * Blocking calls; MapController runs them off the main thread.
 */
object MapApi {
  /** One request. A JSON [body] makes it a POST: the only place the Map tab sends anything. */
  private fun http(url: String, json: JSONObject? = null, headers: Map<String, String> = emptyMap()): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = 10_000
      conn.readTimeout = 30_000
      conn.setRequestProperty("User-Agent", "silverado-stats")
      for ((k, v) in headers) conn.setRequestProperty(k, v)
      if (json != null) {
        val body = json.toString().toByteArray()
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body) }
      }
      val code = conn.responseCode
      val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
      if (code >= 400) throw IOException("${URL(url).host} returned $code: ${text.take(160)}")
      return text
    } finally {
      conn.disconnect()
    }
  }

  private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map(::getJSONObject)

  private val googleKey get() = BuildConfig.MAPS_API_KEY

  private fun google(fields: String) = mapOf("X-Goog-Api-Key" to googleKey, "X-Goog-FieldMask" to fields)

  private fun latLng(lat: Double, lng: Double) = JSONObject().put("latitude", lat).put("longitude", lng)

  fun reference(): Reference {
    val o = JSONObject(http("$OCM/referencedata?key=${BuildConfig.OCM_API_KEY}"))
    fun table(name: String) = o.optJSONArray(name).objects().associate { it.getInt("ID") to it.optString("Title") }
    return Reference(
      table("Operators"),
      table("ConnectionTypes"),
      o.optJSONArray("StatusTypes").objects().filter { !it.optBoolean("IsOperational", true) }.map { it.getInt("ID") }.toSet(),
    )
  }

  /** Chargers in one map grid cell. A cell holding more than the cap comes back incomplete. */
  fun chargers(north: Double, west: Double, south: Double, east: Double, minKw: Int, plugTypeIds: Collection<Int>): List<Charger> {
    var url = "$OCM/poi?output=json&compact=true&verbose=false&maxresults=$MAX_CHARGERS_PER_CELL" +
      "&boundingbox=($north,$west),($south,$east)&key=${BuildConfig.OCM_API_KEY}"
    if (minKw > 0) url += "&minpowerkw=$minKw"
    if (plugTypeIds.isNotEmpty()) url += "&connectiontypeid=${plugTypeIds.joinToString(",")}"
    return JSONArray(http(url)).objects().mapNotNull { o ->
      val a = o.optJSONObject("AddressInfo") ?: return@mapNotNull null
      val conns = o.optJSONArray("Connections").objects()
      Charger(
        id = o.getInt("ID"),
        title = a.optString("Title"),
        lat = a.getDouble("Latitude"),
        lng = a.getDouble("Longitude"),
        maxKw = conns.maxOfOrNull { it.optDouble("PowerKW", 0.0) } ?: 0.0,
        operatorId = o.optInt("OperatorID", 0),
        statusId = o.optInt("StatusTypeID", 0),
        plugs = conns.groupingBy { it.optInt("ConnectionTypeID", 0) }.fold(0) { n, c -> n + c.optInt("Quantity", 1).coerceAtLeast(1) },
      )
    }
  }

  /** Places text search, biased towards the given point. */
  fun search(query: String, lat: Double, lng: Double): List<Place> {
    val body = JSONObject().put("textQuery", query).put(
      "locationBias",
      JSONObject().put("circle", JSONObject().put("center", latLng(lat, lng)).put("radius", 50000.0)),
    )
    val r = JSONObject(http("https://places.googleapis.com/v1/places:searchText", body, google("places.displayName,places.formattedAddress,places.location")))
    return r.optJSONArray("places").objects().mapNotNull { p ->
      val loc = p.optJSONObject("location") ?: return@mapNotNull null
      Place(
        p.optJSONObject("displayName")?.optString("text") ?: query,
        p.optString("formattedAddress"),
        loc.getDouble("latitude"),
        loc.getDouble("longitude"),
      )
    }
  }

  /** Driving distance in metres from one point to each of [to], null where Google finds no route. */
  fun roadMeters(lat: Double, lng: Double, to: List<Pair<Double, Double>>): List<Double?> {
    fun waypoint(a: Double, b: Double) = JSONObject().put("waypoint", JSONObject().put("location", JSONObject().put("latLng", latLng(a, b))))
    val body = JSONObject()
      .put("origins", JSONArray().put(waypoint(lat, lng)))
      .put("destinations", JSONArray(to.map { waypoint(it.first, it.second) }))
      .put("travelMode", "DRIVE")
    val text = http("https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix", body, google("destinationIndex,distanceMeters,condition"))
    val out = arrayOfNulls<Double>(to.size)
    for (e in JSONArray(text).objects()) {
      val i = e.optInt("destinationIndex", 0)
      if (e.optString("condition") == "ROUTE_EXISTS" && i in out.indices) out[i] = e.optDouble("distanceMeters", 0.0)
    }
    return out.toList()
  }
}
