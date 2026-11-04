package dev.silveradostats.obd

/**
 * VIN masking (port of uds.mask_vin and cli._mask_payload). The VIN appears in several DIDs
 * (F190, 4193) and in OBD 09 02; the app never shows, logs or sends it unmasked.
 */
object Vin {
  val VIN_DIDS: Set<Int> = setOf(0xF190, 0x4193)

  private val VIN_SHAPED = Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{17}(?![A-Za-z0-9])")

  /** Keeps make/model/year characters (1-10), hides the serial part. */
  fun mask(vin: String): String = vin.take(10) + "*".repeat(maxOf(0, vin.length - 10))

  /** Hides VIN-bearing replies: F190, 4193, or anything VIN-shaped (17 ASCII letters/digits). */
  fun maskPayload(did: Int, data: ByteArray): ByteArray {
    val text = asciiReplace(data)
    if (did in VIN_DIDS || (data.size == 17 && text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' })) {
      return utf8(mask(text))
    }
    return data
  }

  /** Masks every free-standing 17-character alphanumeric run in [text] (for logs and reports). */
  fun maskText(text: String): String = VIN_SHAPED.replace(text) { mask(it.value) }
}
