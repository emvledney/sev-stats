package dev.silveradostats.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.silveradostats.link.AppSettings
import dev.silveradostats.link.LinkCheckResult
import dev.silveradostats.link.LinkEngine
import dev.silveradostats.link.LinkSource
import dev.silveradostats.link.MonoClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable

/** What the Settings › OBDLink MX+ link section shows. */
data class BtUiState(
  val availability: BtAvailability = BtAvailability.NO_BLUETOOTH,
  val availabilityText: String = "",
  val bonded: List<BtDevice> = emptyList(),
  val chosenAddress: String? = null,
  val chosenName: String? = null,
  val discovering: Boolean = false,
  /** When the 30 s discovery window ends ([MonoClock] ms). */
  val discoveryEndsAtMs: Long? = null,
  /** OBD-looking devices found by the last discovery. */
  val found: List<BtDevice> = emptyList(),
  val bondingAddress: String? = null,
  val pairingMessage: String? = null,
  val checkRunning: Boolean = false,
  val check: LinkCheckResult? = null,
)

/**
 * Pairing flow and adapter choice for the MX+ (owned by LinkService; main thread).
 *
 *  - [refresh]: Bluetooth availability and the bonded devices (BLUETOOTH_CONNECT);
 *  - [startPairing]: Classic discovery for at most [DISCOVERY_MS], keeping names that contain
 *    OBDLink / OBD (BLUETOOTH_SCAN), whether the name came with ACTION_FOUND or was resolved later
 *    (ACTION_NAME_CHANGED);
 *  - [pair]: BluetoothDevice.createBond(); the system shows its pairing dialog, and
 *    ACTION_BOND_STATE_CHANGED tells us the outcome. A newly bonded adapter is chosen automatically;
 *  - [testLink]: connect + adapter setup only, through [LinkEngine.requestCheck];
 *  - [setRestricted]: while the app is in driving mode nothing new starts, and a search or a link
 *    test in progress ends, as it does when the app leaves the screen ([onLeftScreen]). A pairing the
 *    system has already started may finish: its dialog is the system's.
 */
class BtController(
  context: Context,
  private val engine: LinkEngine,
  private val settings: AppSettings,
) : Closeable {
  private val app = context.applicationContext
  val bt = BtClassic(app)
  private val main = Handler(Looper.getMainLooper())
  private val _state = MutableStateFlow(BtUiState())
  val state: StateFlow<BtUiState> = _state.asStateFlow()
  private var registered = false
  private var restricted = false

  private val stopDiscoveryRunnable = Runnable { stopDiscovery() }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
      when (intent.action) {
        // A device found without a name in its inquiry reply gets it later, in ACTION_NAME_CHANGED.
        BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> onFound(intent)
        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onDiscoveryFinished()
        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondState(intent)
        BluetoothAdapter.ACTION_STATE_CHANGED -> refresh()
      }
    }
  }

  init {
    if (bt.hasBluetooth) {
      val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
      }
      // Protected system broadcasts (only the Bluetooth stack can send them). They come from the
      // Bluetooth process, not system_server, so the receiver must be exported to get them.
      ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
      registered = true
    }
    refresh()
  }

  private fun device(intent: Intent): BluetoothDevice? =
    IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

  fun refresh() {
    val (avail, text) = bt.availability()
    val s = settings.state.value
    _state.update {
      it.copy(
        availability = avail,
        availabilityText = text,
        bonded = bt.bonded(),
        chosenAddress = s.mxAddress,
        chosenName = s.mxName,
      )
    }
  }

  /**
   * Permissions may have changed (dialog result, or back from Settings): re-reads the
   * Bluetooth state and, if the link stopped only because "Nearby devices" was missing, reconnects.
   */
  fun onPermissionsChanged() {
    refresh()
    if (_state.value.availability == BtAvailability.READY) engine.retryIfStoppedBy(BtErrors.NO_CONNECT_PERMISSION)
  }

  /**
   * The app went into driving mode (true) or out of it. It need not mean the truck left Park: an
   * unreadable driving state restricts too. Main thread.
   */
  fun setRestricted(on: Boolean) {
    if (restricted == on) return
    restricted = on
    if (on) endRunningWork("Search stopped: the app is in driving mode.")
  }

  /** Nothing of the app is on screen, and the driving state is not watched while so. Main thread. */
  fun onLeftScreen() = endRunningWork("Search stopped: the app left the screen.")

  private fun endRunningWork(why: String) {
    val searching = _state.value.discovering
    stopDiscovery()
    if (searching) _state.update { it.copy(pairingMessage = why) }
    engine.cancelChecks()
  }

  /** Remembers [address] as the adapter to use (LinkService switches the source). */
  fun choose(address: String, name: String?) {
    settings.setAdapter(address, name)
    refresh()
  }

  /** Use a found adapter without pairing (connects with an unauthenticated serial link). */
  fun useUnpaired(address: String, name: String?) {
    _state.update { it.copy(pairingMessage = "Using ${name ?: address} without pairing. Tap Test link.") }
    choose(address, name)
  }

  fun forget() {
    settings.setAdapter(null, null)
    refresh()
  }

  fun startPairing() {
    if (restricted || _state.value.discovering) return
    val err = bt.startDiscovery()
    if (err != null) {
      _state.update { it.copy(pairingMessage = err) }
      return
    }
    main.removeCallbacks(stopDiscoveryRunnable)
    main.postDelayed(stopDiscoveryRunnable, DISCOVERY_MS)
    _state.update {
      it.copy(
        discovering = true,
        discoveryEndsAtMs = MonoClock.ms() + DISCOVERY_MS,
        found = emptyList(),
        pairingMessage = "Searching for OBDLink adapters for ${DISCOVERY_MS / 1000} s. Press the MX+ button so it can be found.",
      )
    }
    engine.setPairing(true)
  }

  fun stopDiscovery() {
    main.removeCallbacks(stopDiscoveryRunnable)
    if (_state.value.discovering) bt.cancelDiscovery()
    onDiscoveryFinished()
  }

  private fun onFound(intent: Intent) {
    if (!_state.value.discovering) return
    val dev = device(intent) ?: return
    val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: bt.nameOf(dev)
    if (!BtErrors.isObdName(name)) return
    val found = BtDevice(dev.address, name, bonded = _state.value.bonded.any { it.address == dev.address })
    _state.update { s -> s.copy(found = (s.found.filter { it.address != found.address } + found).sortedBy { it.name ?: "" }) }
  }

  private fun onDiscoveryFinished() {
    if (!_state.value.discovering) return
    main.removeCallbacks(stopDiscoveryRunnable)
    _state.update {
      it.copy(
        discovering = false,
        discoveryEndsAtMs = null,
        pairingMessage = if (it.found.isEmpty()) {
          "No OBDLink adapter found. Is the MX+ plugged in, awake (press its button) and not connected to a phone?"
        } else {
          "Found ${it.found.size}. Tap Pair, then confirm the pairing request on this screen."
        },
      )
    }
    if (_state.value.bondingAddress == null) engine.setPairing(false)
  }

  /** createBond(): the system shows its pairing dialog. */
  fun pair(address: String) {
    if (restricted) return
    sawBonding = false
    val err = bt.createBond(address)
    if (err != null) {
      _state.update { it.copy(pairingMessage = err) }
      return
    }
    main.removeCallbacks(stopDiscoveryRunnable)
    _state.update {
      it.copy(
        discovering = false,
        discoveryEndsAtMs = null,
        bondingAddress = address,
        pairingMessage = "Pairing… confirm the request on this screen (the MX+ may need its button pressed).",
      )
    }
    engine.setPairing(true)
    // Already bonded (createBond returns no event then): finish right away.
    if (bt.bonded().any { it.address == address }) finishBond(address, true)
  }

  private fun onBondState(intent: Intent) {
    val dev = device(intent) ?: return
    val bonding = _state.value.bondingAddress
    val st = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
    if (dev.address != bonding) {
      refresh()
      return
    }
    when (st) {
      BluetoothDevice.BOND_BONDING -> sawBonding = true
      BluetoothDevice.BOND_BONDED -> finishBond(dev.address, true)
      BluetoothDevice.BOND_NONE -> finishBond(dev.address, false, intent.getIntExtra(EXTRA_BOND_REASON, -1))
      else -> {}
    }
  }

  private var sawBonding = false

  private fun finishBond(address: String, ok: Boolean, reason: Int = -1) {
    val detail = "reason $reason: ${BtErrors.bondFailReason(reason)}; " +
      (if (sawBonding) "pairing started, then stopped" else "pairing never started")
    sawBonding = false
    val name = _state.value.found.firstOrNull { it.address == address }?.name
      ?: bt.bonded().firstOrNull { it.address == address }?.name
    _state.update {
      it.copy(
        bondingAddress = null,
        pairingMessage = if (ok) "Paired with ${name ?: address}. It is now the chosen adapter." else
          "Pairing with ${name ?: address} failed ($detail). Try again with the MX+ button pressed, " +
            "pair it in the truck's Bluetooth settings, or tap \"Use unpaired\".",
      )
    }
    engine.setPairing(false)
    if (ok) choose(address, name) else refresh()
  }

  /** The link source for the chosen adapter. */
  fun sourceFor(address: String, name: String?): LinkSource.Adapter =
    LinkSource.Adapter(address, "${name ?: "OBD adapter"} ($address)") { bt.connect(address) }

  /** Diagnostics › Test link: connect + adapter setup only (no vehicle requests). */
  fun testLink() {
    if (restricted) return
    val s = settings.state.value
    val address = s.mxAddress
    if (address == null) {
      _state.update { it.copy(pairingMessage = "Choose an adapter first (a paired device, or Pair new).") }
      return
    }
    if (_state.value.checkRunning) return
    _state.update { it.copy(checkRunning = true) }
    val src = sourceFor(address, s.mxName)
    engine.requestCheck("${s.mxName ?: "OBD adapter"} $address", src) { result ->
      main.post { _state.update { it.copy(checkRunning = false, check = result) } }
    }
  }

  override fun close() {
    main.removeCallbacks(stopDiscoveryRunnable)
    if (_state.value.discovering) bt.cancelDiscovery()
    if (registered) {
      try {
        app.unregisterReceiver(receiver)
      } catch (_: IllegalArgumentException) {
      }
      registered = false
    }
    engine.setPairing(false)
  }

  companion object {
    /** AOSP BluetoothDevice.EXTRA_REASON (hidden constant; plain Intent extra key). */
    private const val EXTRA_BOND_REASON = "android.bluetooth.device.extra.REASON"

    const val DISCOVERY_MS = 30_000L
  }
}
