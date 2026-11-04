package dev.silveradostats.obd

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The few Python number semantics the probe's decode/derive rely on, reproduced exactly so the
 * Kotlin values are bit-identical to the probe's (checked by ObdGoldenParityTest).
 *
 * Python ints are represented as Long (or Int), Python floats as Double.
 */
internal object Py {
  fun isInt(x: Any?): Boolean = x is Long || x is Int || x is Short || x is Byte

  /** round(x, ndigits) for a float: correctly rounded from the exact binary value, half-even, sign kept. */
  fun round(x: Double, ndigits: Int): Double {
    if (!x.isFinite()) return x
    val r = BigDecimal(x).setScale(ndigits, RoundingMode.HALF_EVEN).toDouble()
    return if (r == 0.0) Math.copySign(0.0, x) else r
  }

  /** round(x, ndigits) with ndigits > 0: ints come back unchanged. */
  fun round(x: Number, ndigits: Int): Number = if (isInt(x)) x.toLong() else round(x.toDouble(), ndigits)

  fun sub(a: Number, b: Number): Number = if (isInt(a) && isInt(b)) a.toLong() - b.toLong() else a.toDouble() - b.toDouble()

  fun mul(a: Number, b: Long): Number = if (isInt(a)) a.toLong() * b else a.toDouble() * b.toDouble()

  /** a / b (true division; always a float). */
  fun truediv(a: Number, b: Number): Double = a.toDouble() / b.toDouble()

  /**
   * Builtin sum() of CPython 3.12+: exact integer sum until the first float, then Neumaier
   * compensated summation (the float path of builtin_sum_impl).
   */
  fun sum(xs: List<Number>): Number {
    var i = 0
    var iResult = 0L
    while (i < xs.size && isInt(xs[i])) iResult += xs[i++].toLong()
    if (i == xs.size) return iResult
    var f = iResult.toDouble() + xs[i++].toDouble()
    var c = 0.0
    while (i < xs.size) {
      val x = xs[i++].toDouble()
      val t = f + x
      c += if (Math.abs(f) >= Math.abs(x)) (f - t) + x else (x - t) + f
      f = t
    }
    if (c != 0.0 && c.isFinite()) f += c
    return f
  }

  /** bool(x) for the values decode produces. */
  fun truthy(x: Any?): Boolean = when (x) {
    null -> false
    is Boolean -> x
    is Double -> x != 0.0
    is Float -> x != 0.0f
    is Number -> x.toLong() != 0L
    is String -> x.isNotEmpty()
    is Collection<*> -> x.isNotEmpty()
    is Map<*, *> -> x.isNotEmpty()
    else -> true
  }

  /** min(xs): the first smallest element. */
  fun min(xs: List<Number>): Number = xs.reduce { best, x -> if (x.toDouble() < best.toDouble()) x else best }

  /** max(xs): the first largest element. */
  fun max(xs: List<Number>): Number = xs.reduce { best, x -> if (x.toDouble() > best.toDouble()) x else best }
}
