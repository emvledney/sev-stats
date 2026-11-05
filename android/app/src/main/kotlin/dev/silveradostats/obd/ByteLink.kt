package dev.silveradostats.obd

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raw byte connection to an adapter (Bluetooth RFCOMM socket, test fake).
 *
 * Only [ElmLink] writes to it, and only through ElmLink.command, which applies the link
 * discipline and the line allowlist. Both methods throw [FatalTransportError] once the connection
 * has failed or closed: the link is then in an unknown state and nothing else may be sent.
 */
interface ByteLink : Closeable {
  /** Writes all of [bytes]. */
  fun write(bytes: ByteArray)

  /**
   * Waits up to [timeoutMs] (0 = do not wait) for data and returns everything available
   * (empty array when nothing arrived in time), like select() + recv() in the Python probe.
   */
  fun readAvailable(timeoutMs: Long): ByteArray

  override fun close()
}

/**
 * [ByteLink] over a blocking [InputStream] / [OutputStream] pair (a Bluetooth RFCOMM socket; a
 * loopback socket in the tests).
 *
 * A dedicated daemon reader thread blocks in InputStream.read and feeds a [LinkedBlockingQueue],
 * so [readAvailable] can wait with a timeout even though the stream itself has none. End of
 * stream or a read error is queued after any data that arrived before it, then reported as a
 * [LinkLostError] (a [FatalTransportError]) on every later call. [close] closes [closer] (the
 * socket), which also unblocks the reader thread.
 */
class StreamByteLink(
  private val input: InputStream,
  private val output: OutputStream,
  private val closer: Closeable? = null,
  threadName: String = "obd-link-reader",
) : ByteLink {
  private val queue = LinkedBlockingQueue<Any>()
  private val closed = AtomicBoolean(false)

  @Volatile private var failure: LinkLostError? = null

  private val reader = Thread({ readLoop() }, threadName).apply {
    isDaemon = true
    start()
  }

  /**
   * Nothing may escape this thread: on Android an uncaught exception on any thread kills the app.
   * The queue is unbounded, so offer() never blocks and, unlike put(), never throws when [close]
   * has interrupted this thread.
   */
  private fun readLoop() {
    val buf = ByteArray(4096)
    try {
      while (true) {
        val n = input.read(buf)
        if (n < 0) {
          queue.offer(LinkLostError("adapter closed the connection"))
          return
        }
        if (n > 0) queue.offer(buf.copyOf(n))
      }
    } catch (e: Exception) {
      // IOException (socket closed or failed), or anything a Bluetooth stack throws.
      queue.offer(LinkLostError("adapter link failed: ${e.message ?: e.javaClass.simpleName}", e))
    }
  }

  override fun write(bytes: ByteArray) {
    if (closed.get()) throw LinkLostError("adapter link is closed")
    failure?.let { throw it }
    try {
      output.write(bytes)
      output.flush()
    } catch (e: IOException) {
      throw LinkLostError("adapter link failed: ${e.message ?: e.javaClass.simpleName}", e)
    }
  }

  override fun readAvailable(timeoutMs: Long): ByteArray {
    if (closed.get()) throw LinkLostError("adapter link is closed")
    failure?.let { throw it }
    val first = if (timeoutMs <= 0) queue.poll() else queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    if (first == null) return EMPTY
    val out = ByteArrayOutputStream()
    var item: Any? = first
    while (item != null) {
      if (item is LinkLostError) {
        failure = item
        if (out.size() > 0) return out.toByteArray() // deliver what came before; the next call throws
        throw item
      }
      out.write(item as ByteArray)
      item = queue.poll()
    }
    return out.toByteArray()
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    try {
      closer?.close()
    } catch (_: IOException) {
    }
    try {
      input.close()
    } catch (_: IOException) {
    }
    try {
      output.close()
    } catch (_: IOException) {
    }
    reader.interrupt()
    // Wakes a readAvailable() waiting on another thread at once (LinkEngine cuts a setup short).
    queue.offer(LinkLostError("adapter link is closed"))
  }

  private companion object {
    val EMPTY = ByteArray(0)
  }
}
