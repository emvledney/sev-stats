package dev.silveradostats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.silveradostats.LiveViewModel
import dev.silveradostats.bt.BtAvailability
import dev.silveradostats.bt.BtController
import dev.silveradostats.bt.BtDevice
import dev.silveradostats.diag.KV
import dev.silveradostats.link.LinkCheckResult
import dev.silveradostats.link.MonoClock
import dev.silveradostats.ui.live.DashboardModel
import dev.silveradostats.ui.live.Tone
import dev.silveradostats.ui.live.Units
import dev.silveradostats.ui.theme.StatusError
import dev.silveradostats.ui.theme.StatusOk
import dev.silveradostats.ui.theme.StatusWarn
import dev.silveradostats.ui.theme.TextDim
import kotlinx.coroutines.delay

/** Diagnostics › OBDLink MX+ link: choose / pair the adapter, test the link, demo mode. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MxLinkSection(vm: LiveViewModel, onRequestPermissions: () -> Unit) {
  val bt by vm.bt.collectAsStateWithLifecycle()
  val live by vm.live.collectAsStateWithLifecycle()
  val settings by vm.settings.collectAsStateWithLifecycle()
  val now by produceState(MonoClock.ms(), bt.discovering) {
    while (true) {
      value = MonoClock.ms()
      delay(1_000)
    }
  }
  Section(
    "OBDLink MX+ link",
    "Bluetooth Classic serial link to the adapter. Test link connects and runs the adapter's own setup only " +
      "(ATZ banner, settings, ATI, STI, ATRV): no vehicle requests. Keep your phone's Bluetooth off so it doesn't hold the MX+.",
    action = "Refresh" to vm::refreshBluetooth,
  ) {
    // Live link status.
    val (chip, tone) = DashboardModel.chip(live.status, live.demo)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Badge(chip, toneColor(tone))
      Text(live.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
      if (live.canRetry) BigButton("Retry", onClick = vm::retry)
    }
    live.adapter?.let { Text("Link: $it", style = MaterialTheme.typography.bodyMedium, color = TextDim) }

    // Demo mode.
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Switch(checked = settings.demo, onCheckedChange = vm::setDemo)
      Column(Modifier.weight(1f)) {
        Text("Demo data (simulated truck)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
          "Uses the built-in simulator instead of the MX+; the dashboard shows a DEMO DATA banner. " +
            "On by default on emulators and non-car devices.",
          style = MaterialTheme.typography.bodySmall,
          color = TextDim,
        )
      }
    }
    HorizontalDivider()

    // Bluetooth availability and the chosen adapter.
    val availColor = when (bt.availability) {
      BtAvailability.READY -> StatusOk
      BtAvailability.NO_BLUETOOTH -> StatusWarn
      else -> StatusError
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Badge(
        when (bt.availability) {
          BtAvailability.READY -> "BLUETOOTH READY"
          BtAvailability.NO_BLUETOOTH -> "NO BLUETOOTH"
          BtAvailability.NO_PERMISSION -> "NO PERMISSION"
          BtAvailability.OFF -> "BLUETOOTH OFF"
        },
        availColor,
      )
      Text(bt.availabilityText, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
      if (bt.availability == BtAvailability.NO_PERMISSION) BigButton("Request permissions", onClick = onRequestPermissions)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Chosen adapter", Modifier.width(220.dp), color = TextDim)
      Text(
        bt.chosenAddress?.let { "${bt.chosenName ?: "(no name)"}  ·  $it" } ?: "none",
        Modifier.weight(1f),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
      )
      if (bt.chosenAddress != null) OutlinedButton(onClick = vm::forgetAdapter, modifier = Modifier.heightIn(min = 56.dp)) { Text("Forget") }
    }

    // Bonded devices.
    Text("Paired devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (bt.bonded.isEmpty()) {
      Text(
        if (bt.availability == BtAvailability.READY) "No paired devices. Use Pair new below." else "Not available: ${bt.availabilityText}",
        color = TextDim,
      )
    }
    bt.bonded.forEach { d -> DeviceRow(d, chosen = d.address == bt.chosenAddress, actions = listOf("Use" to { vm.choose(d) })) }

    // Pair new.
    Text("Pair new", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      if (bt.discovering) {
        BigButton("Stop search", onClick = vm::stopDiscovery)
        CircularProgressIndicator(Modifier.size(32.dp))
        val left = bt.discoveryEndsAtMs?.let { ((it - now) / 1000).coerceAtLeast(0) }
        Text("Searching… ${left ?: BtController.DISCOVERY_MS / 1000} s left", color = TextDim)
      } else {
        BigButton(
          "Find OBDLink (${BtController.DISCOVERY_MS / 1000} s)",
          enabled = bt.availability == BtAvailability.READY && bt.bondingAddress == null,
          onClick = vm::startPairing,
        )
      }
      if (bt.bondingAddress != null) {
        CircularProgressIndicator(Modifier.size(32.dp))
        Text("Pairing ${bt.bondingAddress}…", color = StatusWarn)
      }
    }
    Text(
      "Press the MX+ button so it can be found and paired, then confirm the pairing request on this screen.",
      style = MaterialTheme.typography.bodySmall,
      color = TextDim,
    )
    bt.found.forEach { d ->
      DeviceRow(
        d,
        chosen = d.address == bt.chosenAddress,
        actions = if (d.bonded) listOf("Use" to { vm.choose(d) }) else listOf("Pair" to { vm.pair(d) }, "Use unpaired" to { vm.useUnpaired(d) }),
      )
    }
    bt.pairingMessage?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = StatusWarn) }
    HorizontalDivider()

    // Test link.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      BigButton(
        if (bt.checkRunning) "Testing…" else "Test link",
        enabled = !bt.checkRunning && bt.chosenAddress != null,
        onClick = vm::testLink,
      )
      if (bt.checkRunning) CircularProgressIndicator(Modifier.size(32.dp))
      Text(
        if (bt.chosenAddress == null) "Choose an adapter first." else "Connect + adapter setup only; a running live session pauses meanwhile.",
        color = TextDim,
        modifier = Modifier.align(Alignment.CenterVertically),
      )
    }
    bt.check?.let { LinkCheckView(it) }
  }
}

@Composable
private fun DeviceRow(d: BtDevice, chosen: Boolean, actions: List<Pair<String, () -> Unit>>) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(d.address, Modifier.width(260.dp), fontFamily = FontFamily.Monospace)
    Text(d.name ?: "(no name)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
    if (d.obdLike) Badge("OBD ADAPTER", StatusOk)
    if (chosen) {
      Badge("CHOSEN", MaterialTheme.colorScheme.primary)
    } else {
      actions.forEach { (label, onClick) -> OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 56.dp)) { Text(label) } }
    }
  }
}

/** Result of an init-only adapter check (MX+ or TCP). */
@Composable
internal fun LinkCheckView(r: LinkCheckResult) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Badge(if (r.ok) "LINK OK" else "FAILED (${r.stage})", if (r.ok) StatusOk else StatusError)
      Text(
        buildString {
          append("${r.target} · ${r.totalMs} ms")
          r.connectMs?.let { append(" (connect $it ms") }
          r.setupMs?.let { append(", setup $it ms") }
          if (r.connectMs != null) append(")")
          append(" at ${time(r.atWallMs)}")
        },
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    r.error?.let { Text(it, color = StatusError, style = MaterialTheme.typography.bodyLarge) }
    KvTable(
      buildList {
        r.banner?.let { add(KV("ATZ banner", it)) }
        r.ident?.let { add(KV("Identity (ATI / STI)", it)) }
        if (r.voltsText != null) {
          add(KV("Adapter voltage (ATRV)", r.volts?.let { "${Units.num(it, 2)} V" } ?: r.voltsText))
        }
        add(KV("Commands sent", if (r.sent.isEmpty()) "none" else r.sent.joinToString("  ")))
      },
    )
  }
}

internal fun toneColor(t: Tone) = when (t) {
  Tone.GOOD -> StatusOk
  Tone.WARN -> StatusWarn
  Tone.ERROR -> StatusError
  else -> TextDim
}
