package dev.silveradostats.report

import dev.silveradostats.DiagState
import dev.silveradostats.ble.BleScan
import dev.silveradostats.bt.BtErrors
import dev.silveradostats.bt.BtUiState
import dev.silveradostats.diag.KV
import dev.silveradostats.link.LinkCheckResult
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.MonoClock
import dev.silveradostats.link.UiSettings
import dev.silveradostats.obd.Vin
import dev.silveradostats.vehicle.EffUnits
import dev.silveradostats.vehicle.Fmt
import dev.silveradostats.vehicle.PropertyRow
import dev.silveradostats.vehicle.SessionState
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The OBDLink MX+ link part of the report (Diagnostics › OBDLink MX+ link and the live link), plus
 * the built-in session of the Simple tab ([session], no adapter involved).
 */
data class MxReport(val settings: UiSettings, val bt: BtUiState, val live: LiveState, val session: SessionState? = null)

/** Builds the JSON report of everything on the Diagnostics screen. */
object Report {
  const val SCHEMA = "silverado-stats/diagnostics/2"

  fun build(s: DiagState, appId: String, versionName: String, versionCode: Int, mx: MxReport? = null): String {
    val root = JSONObject()
    root.put("schema", SCHEMA)
    root.put("generatedAt", iso(System.currentTimeMillis()))
    root.put(
      "app",
      JSONObject().put("applicationId", appId).put("versionName", versionName).put("versionCode", versionCode),
    )
    root.put("lastCrash", s.lastCrash ?: JSONObject.NULL)
    root.put(
      "permissions",
      JSONArray().also { a ->
        s.permissions.forEach {
          a.put(JSONObject().put("permission", it.permission).put("runtime", it.runtime).put("granted", it.granted))
        }
      },
    )
    root.put(
      "car",
      JSONObject()
        .put("connection", s.car.phase.name)
        .put("message", s.car.message)
        .put("api", kv(s.carApi)),
    )
    root.put(
      "uxRestrictions",
      s.ux?.let {
        JSONObject()
          .put("requiresDistractionOptimization", it.requiresDistractionOptimization)
          .put("activeRestrictions", it.activeRestrictions)
          .put("activeRestrictionsHex", String.format(Locale.US, "0x%X", it.activeRestrictions))
          .put("decoded", JSONArray(it.decoded))
          .put("maxStringLength", it.maxStringLength ?: JSONObject.NULL)
          .put("updatedAt", iso(it.updatedAtMs))
      } ?: JSONObject.NULL,
    )
    root.put(
      "energyLive",
      JSONObject()
        .put("updatedAt", if (s.liveUpdatedAtMs > 0) iso(s.liveUpdatedAtMs) else JSONObject.NULL)
        .put("rows", JSONArray().also { a -> s.live.forEach { a.put(row(it)) } }),
    )
    root.put(
      "vhal",
      s.vhal?.let { v ->
        JSONObject()
          .put("takenAt", iso(v.takenAtMs))
          .put("listedCount", v.listedCount)
          .put("listError", v.listError ?: JSONObject.NULL)
          .put("readMs", v.readMs)
          .put("rowCount", v.rows.size)
          .put("availableCount", v.rows.count { it.status.name == "AVAILABLE" })
          .put("rows", JSONArray().also { a -> v.rows.forEach { a.put(row(it)) } })
      } ?: JSONObject.NULL,
    )
    root.put("system", kv(s.build))
    root.put("display", kv(s.display))
    root.put("displayCompose", kv(s.composeDisplay))
    root.put("network", kv(s.network))
    root.put("adapterTest", s.adapterResult?.let(::linkCheck) ?: JSONObject.NULL)
    root.put("mxLink", mx?.let(::mxLink) ?: JSONObject.NULL)
    root.put("builtInSession", mx?.session?.let(::session) ?: JSONObject.NULL)
    root.put("bleScan", s.bleResult?.let(::ble) ?: JSONObject.NULL)
    return root.toString(2)
  }

  private fun row(r: PropertyRow): JSONObject = JSONObject()
    .put("id", r.idHex)
    .put("name", r.name)
    .put("group", r.group)
    .put("areaType", r.areaType)
    .put("areaId", r.areaId)
    .put("areaIds", JSONArray(r.areaIds))
    .put("listed", r.listed)
    .put("known", r.known)
    .put("access", r.access)
    .put("changeMode", r.changeMode)
    .put("sampleRates", r.sampleRates)
    .put("valueType", r.valueType)
    .put("status", r.status.name)
    .put("value", r.value)
    .put("raw", if (r.rawValue == null) JSONObject.NULL else Fmt.raw(r.rawValue))
    .put("detail", r.detail)
    .put("ageMs", r.timestampAgeMs ?: JSONObject.NULL)
    .put("permissions", r.permissions)
    .put("permissionGranted", r.permissionGranted ?: JSONObject.NULL)

  /** Init-only adapter check (TCP or MX+). Bluetooth addresses are masked to their vendor part. */
  fun linkCheck(r: LinkCheckResult): JSONObject = JSONObject()
    .put("target", BtErrors.maskAddressesIn(r.target))
    .put("ok", r.ok)
    .put("stage", r.stage)
    .put("at", iso(r.atWallMs))
    .put("connectMs", r.connectMs ?: JSONObject.NULL)
    .put("setupMs", r.setupMs ?: JSONObject.NULL)
    .put("totalMs", r.totalMs)
    .put("banner", r.banner ?: JSONObject.NULL)
    .put("ident", r.ident ?: JSONObject.NULL)
    .put("voltsText", r.voltsText ?: JSONObject.NULL)
    .put("volts", r.volts ?: JSONObject.NULL)
    .put("sent", JSONArray(r.sent))
    .put("error", r.error?.let { BtErrors.maskAddressesIn(it) } ?: JSONObject.NULL)

  private fun mxLink(m: MxReport): JSONObject {
    val now = MonoClock.ms()
    val l = m.live
    return JSONObject()
      .put("demoMode", m.settings.demo)
      .put("chosenAdapter", JSONObject().put("name", m.settings.mxName ?: JSONObject.NULL).put("address", BtErrors.maskAddress(m.settings.mxAddress) ?: JSONObject.NULL))
      .put("bluetooth", JSONObject().put("availability", m.bt.availability.name).put("text", m.bt.availabilityText))
      .put(
        "bonded",
        JSONArray().also { a ->
          m.bt.bonded.forEach { d ->
            a.put(JSONObject().put("name", d.name ?: JSONObject.NULL).put("address", BtErrors.maskAddress(d.address)).put("obdLike", d.obdLike))
          }
        },
      )
      .put("pairingMessage", m.bt.pairingMessage ?: JSONObject.NULL)
      .put("lastTestLink", m.bt.check?.let(::linkCheck) ?: JSONObject.NULL)
      .put(
        "live",
        JSONObject()
          .put("status", l.status.name)
          .put("message", BtErrors.maskAddressesIn(l.message))
          .put("adapter", l.adapter?.let { BtErrors.maskAddressesIn(it) } ?: JSONObject.NULL)
          .put("adapterVolts", l.adapterVolts ?: JSONObject.NULL)
          .put("polling", l.polling)
          .put("dataAgeMs", l.dataAgeMs(now) ?: JSONObject.NULL)
          .put("valueCount", l.values.size)
          .put("errors", JSONObject().also { o -> l.errors.forEach { (k, v) -> o.put(k, v) } })
          .put(
            "values",
            JSONObject().also { o ->
              l.values.values.forEach { x ->
                val v = x.v
                o.put(
                  x.name,
                  JSONObject()
                    .put("v", jsonValue(v))
                    .put("unit", x.unit)
                    .put("status", x.status)
                    .put("ageMs", now - x.atMs),
                )
              }
            },
          )
          .put("derived", JSONObject().also { o -> l.derived.forEach { (k, v) -> o.put(k, jsonValue(v)) } }),
      )
  }

  /**
   * The Simple tab's session from the truck's built-in data: what it measured and what the car
   * service offers (how coarse the battery level is, whether the charge rate is live while driving).
   */
  private fun session(s: SessionState): JSONObject {
    fun n(x: Double?): Any = x?.takeIf { it.isFinite() } ?: JSONObject.NULL
    return JSONObject()
      .put("feed", s.feed.kind.name)
      .put("sampling", s.feed.sampling)
      .put("missingPermissions", JSONArray(s.feed.missingPermissions.map { it.substringAfterLast('.') }))
      .put("missingSignals", JSONArray(s.feed.missing.map { it.name }.sorted()))
      .put("capacityNominal", s.feed.capacityNominal)
      .put("activeMs", s.activeMs)
      .put("samples", s.samples)
      .put("gaps", s.gaps)
      .put("gapMs", s.gapMs)
      .put("distanceM", n(s.distanceM))
      .put("usedWh", n(s.usedWh))
      .put("chargedWh", n(s.chargedWh))
      .put("charging", s.charging)
      .put("efficiency", s.efficiency.status.name)
      .put("efficiencyEarly", s.efficiency.early)
      .put("miPerKwh", n(s.efficiency.mPerWh?.let(EffUnits::miPerKwh)))
      .put("basisM", n(s.efficiency.basisM))
      .put("basisWh", n(s.efficiency.basisWh))
      .put("levelStepWh", n(s.levelStepWh))
      .put("powerSource", s.power?.source?.name ?: JSONObject.NULL)
      .put("powerKw", n(s.power?.kw))
      .put("chargeRateSeenNonZero", s.chargeRateSeenNonZero)
      // Rate against level (+ discharge): disagreements mean the rate's sign is not the AOSP one, or it is stuck.
      .put(
        "chargeRateCheck",
        JSONObject()
          .put("agreed", s.rateCheck.agreed)
          .put("disagreed", s.rateCheck.disagreed)
          .put("lastRateKw", n(s.rateCheck.lastRateKw))
          .put("lastLevelKw", n(s.rateCheck.lastLevelKw)),
      )
      .put("peakDischargeKw", n(s.peakDischargeKw))
      .put("peakRegenKw", n(s.peakRegenKw))
      .put("levelWh", n(s.levelWh))
      .put("capacityWh", n(s.capacityWh))
      .put("rangeM", n(s.rangeM))
      .put("outsideC", n(s.outsideC))
      .put("gear", s.gear ?: JSONObject.NULL)
  }

  /** Decoded OBD value as JSON: VIN-shaped text masked, non-finite numbers as text (JSON has none). */
  private fun jsonValue(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is String -> Vin.maskText(v)
    is Double -> if (v.isFinite()) v else v.toString()
    is Float -> if (v.isFinite()) v.toDouble() else v.toString()
    is List<*> -> JSONArray().also { a -> v.forEach { a.put(jsonValue(it)) } }
    else -> v
  }

  private fun ble(r: BleScan.Result): JSONObject = JSONObject()
    .put("ok", r.ok)
    .put("error", r.error ?: JSONObject.NULL)
    .put("durationMs", r.durationMs)
    .put(
      "devices",
      JSONArray().also { a ->
        r.devices.forEach {
          a.put(
            JSONObject()
              .put("address", it.address)
              .put("name", it.name ?: JSONObject.NULL)
              .put("rssi", it.rssi)
              .put("serviceUuids", JSONArray(it.serviceUuids))
              .put("connectable", it.connectable)
              .put("seen", it.seen),
          )
        }
      },
    )

  /** Key/value list as an object (insertion order kept); duplicate keys get a suffix. */
  private fun kv(list: List<KV>): JSONObject {
    val o = JSONObject()
    for (e in list) {
      var k = e.key
      var i = 2
      while (o.has(k)) k = "${e.key} #${i++}"
      o.put(k, Fmt.maskVin(e.value))
    }
    return o
  }

  private fun iso(ms: Long): String {
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    f.timeZone = TimeZone.getDefault()
    return f.format(Date(ms))
  }
}
