package dev.silveradostats.obd

enum class PollerState { AWAKE, ASLEEP, STOPPED }

/** One decoded value: the probe's {"v", "unit", "status"} plus when it was read (poller clock). */
data class Reading(val v: Any?, val unit: String, val status: String, val label: String, val atS: Double)

/**
 * The result of one [Poller.cycle] (the probe's sample dict).
 *
 * [adapterVoltsRead] says whether the adapter voltage was read in this cycle (the probe only sets
 * "adapter_volts" then); [adapterVolts] is null when it was not read or the adapter couldn't tell.
 */
class Sample(
  val t: Double,
  val values: Map<String, Reading>,
  val errors: Map<String, String>,
  val derived: Map<String, Any>,
  val state: PollerState,
  val busSilent: Boolean,
  val adapterVolts: Double?,
  val adapterVoltsRead: Boolean,
) {
  val asleep: Boolean get() = state != PollerState.AWAKE
}

/**
 * Tiered polling that goes completely quiet on the bus when the truck sleeps (port of cli.Poller).
 *
 * AWAKE    each DID is read at its own period. When a read gets no answer at all, one read of a
 *          known-good DID (the probe, becm 27C6) tells whether just that DID is unsupported (its
 *          period is stretched) or the truck went quiet. From then on only the probe is sent, at
 *          most one every [PROBE_GAP_S], and after [SILENT_PROBES] unanswered probes the truck is
 *          considered asleep. So at most ~4 requests go unanswered while the truck falls asleep.
 * ASLEEP   nothing is sent on the bus. Every [WATCH_S] the adapter's own voltage reading is checked
 *          (adapter-local). A single probe is sent only when that voltage rises [WAKE_RISE_V] above
 *          its lowest value since sleep began or since the last probe (the truck's DC-DC came on).
 *          An adapter that can't read voltage gets one probe at 1, 5 and 30 minutes, then polling stops.
 * STOPPED  nothing more will be sent.
 *
 * Transport errors (bus errors, adapter resets, timeouts) are not treated as silence: they
 * propagate out of [cycle] and end the run. The poller has no thread of its own: the caller runs
 * cycle() / [nextWait] only while a screen of the app is visible.
 */
class Poller(
  val profile: Profile,
  private val transport: Transport,
  val stopWhenAsleep: Boolean = true,
  private val clock: () -> Double = Monotonic::seconds,
  private val wallClock: () -> Double = ::wallSeconds,
  clientFactory: (Channel) -> DidReader = { UdsClient(it) },
) {
  companion object {
    const val SILENT_PROBES = 3
    const val PROBE_GAP_S = 2.0
    const val WATCH_S = 60.0
    const val WAKE_RISE_V = 0.5
    val NO_VOLTAGE_PROBES_S: List<Double> = listOf(60.0, 300.0, 1800.0)
    const val VOLTAGE_FAILURES_LIMIT = 5
    const val VOLTS_EVERY_S = 30.0
    val DEFAULT_PROBE = DidKey("becm", 0x27C6)
  }

  /** One reader per profile module; replaceable in tests. */
  val clients: MutableMap<String, DidReader> =
    profile.modules.entries.associateTo(LinkedHashMap()) { (k, m) -> k to clientFactory(transport.open(m.tx, m.rx)) }
  val reads: Map<DidKey, List<DidField>> = profile.reads()
  val period: MutableMap<DidKey, Double> =
    reads.entries.associateTo(LinkedHashMap()) { (k, fs) -> k to maxOf(0.5, fs.minOf { it.period }) }
  private var due: MutableMap<DidKey, Double> = reads.keys.associateWithTo(LinkedHashMap()) { 0.0 }
  val probeKey: DidKey = if (DEFAULT_PROBE in reads) DEFAULT_PROBE else reads.keys.first()

  /** Latest value of every field read so far. */
  val latest: MutableMap<String, Reading> = LinkedHashMap()

  var state: PollerState = PollerState.AWAKE
    private set

  private var unanswered = 0 // consecutive unanswered probes while awake
  private var nextProbe = 0.0
  private var hasVoltage: Boolean? = null
  private var baselineV: Double? = null
  private var voltageFailures = 0
  private var nextWatch = 0.0
  private var failedProbes = 0
  private var nextVolts = 0.0

  private class Builder(val t: Double) {
    val values = LinkedHashMap<String, Reading>()
    val errors = LinkedHashMap<String, String>()
    var volts: Double? = null
    var voltsRead = false

    fun volts(v: Double?) {
      volts = v
      voltsRead = true
    }
  }

  /** One read; true if the module answered at all (even with an NRC). */
  private fun read(key: DidKey, s: Builder, now: Double): Boolean {
    val r = try {
      clients.getValue(key.module).readDid(key.did)
    } catch (e: NoResponse) {
      s.errors[key.toString()] = e.message ?: "no response"
      return false
    } catch (e: NegativeResponse) {
      s.errors[key.toString()] = e.message ?: "negative response"
      return true
    }
    val data = Vin.maskPayload(key.did, r.data)
    for (f in reads.getValue(key)) s.values[f.name] = Reading(f.decode(data), f.unit, f.status, f.label, now)
    return true
  }

  private fun fallAsleep(now: Double, s: Builder) {
    val v = transport.adapterVoltage()
    hasVoltage = v != null
    baselineV = v
    s.volts(v)
    state = if (stopWhenAsleep) PollerState.STOPPED else PollerState.ASLEEP
    nextWatch = now + watchInterval()
  }

  private fun wake(now: Double) {
    state = PollerState.AWAKE
    unanswered = 0
    failedProbes = 0
    // Bring DIDs back spread out rather than all at once.
    due = reads.keys.withIndex().associateTo(LinkedHashMap()) { (i, k) -> k to now + 0.2 * i }
  }

  fun cycle(): Sample {
    val now = clock()
    val s = Builder(Py.round(wallClock(), 3))
    if (state == PollerState.AWAKE) {
      cycleAwake(now, s)
      if (state == PollerState.AWAKE && now >= nextVolts) {
        nextVolts = now + VOLTS_EVERY_S
        s.volts(transport.adapterVoltage()) // adapter-local, no bus traffic
      }
    } else if (state == PollerState.ASLEEP && now >= nextWatch) {
      cycleAsleep(now, s)
    }
    latest.putAll(s.values)
    val derived = Derive.derive(latest.mapValues { it.value.v })
    return Sample(
      t = s.t,
      values = s.values,
      errors = s.errors,
      derived = derived,
      state = state,
      busSilent = state != PollerState.AWAKE && hasVoltage != false,
      adapterVolts = s.volts,
      adapterVoltsRead = s.voltsRead,
    )
  }

  private fun cycleAwake(now: Double, s: Builder) {
    if (unanswered > 0) {
      // The truck may be going to sleep: only the probe, spaced out.
      if (now < nextProbe) return
      if (read(probeKey, s, now)) {
        unanswered = 0
        wake(now)
        return
      }
      unanswered += 1
      nextProbe = now + PROBE_GAP_S
      if (unanswered >= SILENT_PROBES) fallAsleep(now, s)
      return
    }
    for (key in due.filterValues { it <= now }.keys.toList()) {
      due[key] = now + period.getValue(key)
      if (read(key, s, now)) continue
      if (key != probeKey && read(probeKey, s, now)) {
        // Only this DID is silent: ask for it less often.
        period[key] = minOf(period.getValue(key) * 4, 300.0)
        continue
      }
      unanswered = 1
      nextProbe = now + PROBE_GAP_S
      return
    }
  }

  private fun cycleAsleep(now: Double, s: Builder) {
    val v = transport.adapterVoltage()
    s.volts(v)
    nextWatch = now + watchInterval()
    if (hasVoltage == true) {
      if (v == null) {
        voltageFailures += 1
        if (voltageFailures >= VOLTAGE_FAILURES_LIMIT) state = PollerState.STOPPED
        return
      }
      voltageFailures = 0
      val base = baselineV?.let { minOf(it, v) } ?: v
      baselineV = base
      if (v < base + WAKE_RISE_V) return
    }
    if (read(probeKey, s, now)) {
      wake(now)
      return
    }
    failedProbes += 1
    if (hasVoltage == true) {
      baselineV = v // need a fresh rise before the next probe
    } else if (failedProbes >= NO_VOLTAGE_PROBES_S.size) {
      state = PollerState.STOPPED
    }
    nextWatch = now + watchInterval()
  }

  private fun watchInterval(): Double {
    if (hasVoltage == false) { // no voltage reading: growing gaps between single probes
      return NO_VOLTAGE_PROBES_S[minOf(failedProbes, NO_VOLTAGE_PROBES_S.size - 1)]
    }
    return WATCH_S
  }

  /** Seconds until the next [cycle] should run; null once polling has stopped. */
  fun nextWait(): Double? {
    val now = clock()
    return when {
      state == PollerState.STOPPED -> null
      state == PollerState.ASLEEP -> maxOf(0.05, nextWatch - now)
      unanswered > 0 -> maxOf(0.05, nextProbe - now)
      else -> maxOf(0.05, due.values.min() - now)
    }
  }
}
