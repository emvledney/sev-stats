package dev.silveradostats.obd

import kotlin.math.sin

/**
 * In-process simulated Silverado EV (port of transports/sim.py) for developing without the truck.
 *
 * Emulates the Ultium modules of the profile (BECM 0xCB, drive units 0x17/0x1D at priority 0x14 or
 * 0x18, BCM 0x40 and gateway 0x45 only at 0x18) with synthetic, slowly varying values, including the
 * two half summaries (2AF6 = groups 1-96, 2AF5 = groups 97-192), the 192-group array, and a VIN in
 * F190 / 09 02. Modules addressed at the wrong priority stay silent, like the real truck.
 */
class UltiumSim(private val clock: () -> Double = Monotonic::seconds) {
  private val t0 = clock()

  class State(val t: Double, val current: Double, val soc: Double, val groups: DoubleArray, val temps: DoubleArray)

  /** Synthetic truck state, sampled on every request. */
  fun state(): State {
    val t = clock() - t0
    val current = 160 * sin(t / 9) + 40 // A, + = discharge
    val soc = 72.0 - t / 600
    val groups = DoubleArray(GROUPS) { i -> 3.93 + 0.003 * sin(i / 11.0 + t / 40) - current * 0.00004 }
    groups[46] -= 0.006
    groups[130] += 0.005
    val temps = DoubleArray(MODULES) { i -> 25 + 2 * sin(i / 4.0 + t / 120) }
    return State(t, current, soc, groups, temps)
  }

  /** The module's reply, or null to stay silent. */
  fun answer(ecu: Int, pri: Int, payload: ByteArray): ByteArray? {
    if (pri !in ANSWERS_AT[ecu].orEmpty()) return null
    if (u8(payload[0]) in Safety.OBD_DTC_READ_MODES) return if (ecu == 0x45) bytesOf(u8(payload[0]) + 0x40, 0x00) else null
    val did = (u8(payload[1]) shl 8) or u8(payload[2])
    val data = did(ecu, did) ?: return bytesOf(0x7F, 0x22, 0x31)
    return bytesOf(0x62) + payload.copyOfRange(1, 3) + data
  }

  private fun did(ecu: Int, did: Int): ByteArray? {
    val s = state()
    val g = s.groups
    val avg = Py.sum(g.toList()).toDouble() / g.size
    val packV = 96 * avg
    if (ecu == 0xCB) {
      when (did) {
        0x27C6 -> return u16(s.soc * 655.35)
        0x27AF -> return u16(s.soc / 100 * 205.4 * 100)
        0x27C7 -> return u24((s.soc / 100 * 440 * 103).toLong())
        0x27C0 -> return u24((42 * 16.09344).toLong())
        0x2AF5, 0x2AF6 -> {
          // 2AF6 summarises groups 1-96 (modules 1-12), 2AF5 groups 97-192 (modules 13-24)
          val first = if (did == 0x2AF6) 0 else GROUPS / 2
          val half = g.copyOfRange(first, first + GROUPS / 2).toList()
          val lo = first + half.indexOf(half.min())
          val hi = first + half.indexOf(half.max())
          return u16(Py.sum(half).toDouble() / half.size * 1e4) + u16(half.min() * 1e4) + u16(half.max() * 1e4) +
            bytesOf(lo + 1, lo / 8 + 1, hi + 1, hi / 8 + 1)
        }
        0x2AF2 -> return ByteArray(MODULES) { (s.temps.max().toInt() + 80).toByte() }
        0x2AF8 -> return bytesFromHex("989977989978989c6e")
        0x2AF9 -> return bytesFromHex("222222222222111111111111")
        0x2AF7 -> return listOf(-0.1, -0.2, 0.2, 0.2).fold(ByteArray(0)) { acc, d -> acc + u16((packV + d) * 100) }
        0x2AF1 -> return ByteArray(MODULES) { (s.temps[it].toInt() + 80).toByte() }
        0x2AF3, 0x2AF4 -> return bytesOf(s.temps.max().toInt() + 80, s.temps.min().toInt() + 80, 0x0E, 0x03)
        0x2B43 -> {
          val b = (s.soc * 2.55).toInt()
          return bytesOf(b, b) + ByteArray(MODULES) { (b + (it % 3) - 1).toByte() }
        }
        0x5401 -> return bytesOf(0)
        0x0046 -> return bytesOf((Py.sum(s.temps.toList()).toDouble() / MODULES).toInt() + 40)
        0x33E5 -> return bytesOf(0x7D)
      }
      if (did in 0x2AE1..0x2AF0) {
        val first = (did - 0x2AE1) * 12
        var out = ByteArray(0)
        for (i in first until minOf(first + 12, GROUPS)) out += u16(g[i] * 1e4) + bytesOf(i / 8 + 1)
        return out
      }
    }
    if (did == 0xF190 && ecu in setOf(0x17, 0x1D, 0x40, 0x45)) return SIM_VIN.toByteArray(Charsets.US_ASCII)
    if (ecu == 0x17 || ecu == 0x1D) {
      if (did == 0x2885) return u16(packV * 100)
      if (did == 0x2414 && ecu == 0x17) {
        val raw = (s.current * 20).toInt()
        return bytesOf((raw shr 8) and 0xFF, raw and 0xFF)
      }
      if (did == 0x33E5) return bytesOf(0x7D)
    }
    if (ecu == 0x40) {
      if (did == 0x434F) return bytesOf((Py.sum(s.temps.toList()).toDouble() / MODULES).toInt() + 40)
      val base = mapOf(0x40E5 to 21.0, 0x40E6 to 24.5, 0x4127 to 25.5, 0x4124 to 26.0)[did]
      if (base != null) return u16(base * 32)
      if (did == 0x4149) return u16(0.0)
    }
    return null
  }

  /** Functional OBD (01 / 09) answers from every module that would answer. */
  fun functional(mode: Int, pid: Int): List<ByteArray> = when {
    mode == 0x01 && pid == 0x00 -> List(3) { bytesOf(0x41, 0x00, 0x00, 0x18, 0x80, 0x01) }
    mode == 0x01 && pid == 0x0D -> listOf(bytesOf(0x41, 0x0D, 0x00))
    mode == 0x09 && pid == 0x0A -> listOf("GWM-Gateway", "DMCM-DriveMotorCtrl", "BECM-B+EnergyCtrl").map { name ->
      bytesOf(0x49, 0x0A, 0x01) + name.toByteArray(Charsets.US_ASCII).copyOf(maxOf(20, name.length))
    }
    mode == 0x09 && pid == 0x02 -> listOf(bytesOf(0x49, 0x02, 0x01) + SIM_VIN.toByteArray(Charsets.US_ASCII))
    else -> emptyList()
  }

  companion object {
    const val MODULES = 24
    const val GROUPS = MODULES * 8
    const val SIM_VIN = "1GCSIMULATED00000"
    private val ANSWERS_AT: Map<Int, Set<Int>> = mapOf(
      0xCB to setOf(0x14, 0x18), 0x17 to setOf(0x14, 0x18), 0x1D to setOf(0x14, 0x18),
      0x40 to setOf(0x18), 0x45 to setOf(0x18),
    )

    /** struct.pack(">H", clamp(round(v))) with Python's half-even round(). */
    fun u16(v: Double): ByteArray {
      val x = Math.rint(v).coerceIn(0.0, 65535.0).toInt()
      return bytesOf(x shr 8, x and 0xFF)
    }

    private fun u24(x: Long): ByteArray {
      require(x in 0..0xFFFFFF) { "int too big to convert" }
      return bytesOf((x shr 16).toInt() and 0xFF, (x shr 8).toInt() and 0xFF, x.toInt() and 0xFF)
    }
  }
}

/**
 * Transport to [UltiumSim]. Every request still passes the same safety checks and rate limiter as
 * the real transports.
 *
 * Sleepy mode (for tests and demos): [silent] makes every module stop answering, [volts] is what
 * [adapterVoltage] reports (null = an adapter that can't read voltage, the probe sim's behaviour),
 * and [sent] counts every request that reached the simulated bus.
 */
class SimTransport(
  minIntervalS: Double = 0.05,
  clock: () -> Double = Monotonic::seconds,
  sleeper: (Double) -> Unit = ::sleepSeconds,
) : Transport {
  override val name: String = "sim"
  val sim = UltiumSim(clock)
  val limiter = RateLimiter(minIntervalS, clock, sleeper)

  @Volatile var silent: Boolean = false

  @Volatile var volts: Double? = null

  @Volatile var sent: Int = 0
    private set

  override fun open(txId: Long, rxId: Long, functional: Boolean): Channel = SimChannel(txId, rxId, functional)

  override fun describe(): String = "sim (synthetic Silverado EV, 24 modules)"

  override fun adapterVoltage(): Double? = volts

  private inner class SimChannel(txId: Long, rxId: Long, functional: Boolean) : Channel(txId, rxId, functional, limiter) {
    private var out: List<ByteArray> = emptyList()

    override fun transmit(payload: ByteArray) {
      synchronized(this@SimTransport) { sent += 1 }
      if (silent) {
        out = emptyList()
        return
      }
      if (functional) {
        out = sim.functional(u8(payload[0]), u8(payload[1]))
        return
      }
      val pri = (txId shr 24).toInt()
      val ecu = ((txId shr 8) and 0xFF).toInt()
      if (txId <= 0x7FF || rxId != Addressing.ids(ecu, pri).second) {
        out = emptyList()
        return
      }
      val reply = sim.answer(ecu, pri, payload)
      out = if (reply != null && reply.isNotEmpty()) listOf(reply) else emptyList()
    }

    override fun receive(timeoutS: Double): List<ByteArray> {
      val o = out
      out = emptyList()
      return o
    }
  }
}
