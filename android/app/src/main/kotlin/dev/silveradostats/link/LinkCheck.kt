package dev.silveradostats.link

import dev.silveradostats.obd.ByteLink
import dev.silveradostats.obd.ElmLink
import dev.silveradostats.obd.ElmTransport
import dev.silveradostats.obd.TransportError
import dev.silveradostats.obd.UnsafeRequestError
import java.io.IOException

/**
 * The adapter could not be reached (Bluetooth connect failed). Nothing was written.
 *
 * @param retryable an automatic reconnect may help (adapter asleep, out of range, held by a phone);
 *   false when the user must act first (pairing needed, permission missing, no Bluetooth).
 */
class ConnectFailure(message: String, val retryable: Boolean, cause: Throwable? = null) : Exception(message, cause)

/** Result of the init-only adapter check (Diagnostics › Test link). */
data class LinkCheckResult(
  /** What was tested, e.g. "OBDLink MX+ 00:04:3E:12:34:56". */
  val target: String,
  val ok: Boolean,
  /** "connect", "setup" or "done": how far the check got. */
  val stage: String,
  val connectMs: Long?,
  val setupMs: Long?,
  /** ATZ reply without its echo. */
  val banner: String?,
  /** ATI / STI. */
  val ident: String?,
  /** ATRV as text and parsed. */
  val voltsText: String?,
  val volts: Double?,
  /** Every line written to the adapter, in order (only allowlisted adapter-local commands). */
  val sent: List<String>,
  val error: String?,
  val totalMs: Long,
  val atWallMs: Long,
)

/**
 * Connect + ElmTransport setup only: settle, ATZ (banner check), ATE0 / ATH1 / ATSP7 (must be OK),
 * the cosmetic settings, ATI / STI and ATRV. All adapter-local: no vehicle request is ever sent
 * (the session never opens a channel). The link is always closed afterwards.
 *
 * Blocking: run it off the main thread.
 */
object LinkCheck {
  fun run(
    target: String,
    open: () -> ByteLink,
    elm: (ByteLink) -> ElmLink = { ElmLink(it) },
    clock: () -> Long = MonoClock::ms,
    wallClock: () -> Long = System::currentTimeMillis,
  ): LinkCheckResult {
    val at = wallClock()
    val t0 = clock()
    fun fail(stage: String, connectMs: Long?, setupMs: Long?, sent: List<String>, error: String) =
      LinkCheckResult(target, false, stage, connectMs, setupMs, null, null, null, null, sent, error, clock() - t0, at)

    val link = try {
      open()
    } catch (e: ConnectFailure) {
      return fail("connect", clock() - t0, null, emptyList(), e.message ?: "connect failed")
    } catch (e: IOException) {
      return fail("connect", clock() - t0, null, emptyList(), "connect failed: ${e.message ?: e.javaClass.simpleName}")
    } catch (e: SecurityException) {
      return fail("connect", clock() - t0, null, emptyList(), "permission denied: ${e.message}")
    }
    val connectMs = clock() - t0
    val t1 = clock()
    var elmLink: ElmLink? = null
    try {
      val l = elm(link)
      elmLink = l
      val t = ElmTransport(l)
      val setupMs = clock() - t1
      return LinkCheckResult(
        target = target,
        ok = true,
        stage = "done",
        connectMs = connectMs,
        setupMs = setupMs,
        banner = t.banner,
        ident = t.ident,
        voltsText = t.voltage,
        volts = ElmTransport.parseVolts(t.voltage),
        sent = l.sent,
        error = null,
        totalMs = clock() - t0,
        atWallMs = at,
      )
    } catch (e: TransportError) {
      return fail("setup", connectMs, clock() - t1, elmLink?.sent.orEmpty(), e.message ?: e.javaClass.simpleName)
    } catch (e: UnsafeRequestError) {
      return fail("setup", connectMs, clock() - t1, elmLink?.sent.orEmpty(), "refused: ${e.message}")
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      return fail("setup", connectMs, clock() - t1, elmLink?.sent.orEmpty(), "interrupted")
    } finally {
      // ElmTransport closes the link itself on failure; close it on success too (init only).
      elmLink?.close() ?: link.close()
    }
  }
}
