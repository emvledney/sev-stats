package dev.silveradostats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.silveradostats.DiagState
import dev.silveradostats.DiagnosticsViewModel
import dev.silveradostats.LiveViewModel
import dev.silveradostats.diag.KV
import dev.silveradostats.ui.theme.StatusError
import dev.silveradostats.ui.theme.StatusOk
import dev.silveradostats.ui.theme.StatusWarn
import dev.silveradostats.ui.theme.TextDim
import dev.silveradostats.vehicle.CarConnState
import dev.silveradostats.vehicle.Fmt
import dev.silveradostats.vehicle.KnownProperties
import dev.silveradostats.vehicle.PropertyRow
import dev.silveradostats.vehicle.RowStatus
import dev.silveradostats.vehicle.VehicleReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
  vm: DiagnosticsViewModel,
  liveVm: LiveViewModel,
  listState: LazyListState,
  showAll: Boolean,
  onShowAll: (Boolean) -> Unit,
  onRequestPermissions: () -> Unit,
  onOpenAppSettings: () -> Unit,
) {
  val s by vm.state.collectAsStateWithLifecycle()
  ComposeInsetsProbe(vm)

  val allRows = s.vhal?.rows.orEmpty()
  val rows = if (showAll) allRows else allRows.filter { it.status == RowStatus.AVAILABLE }

  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = listState,
      contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item(key = "header") { Header(s, vm::refreshAll) }
      s.lastCrash?.let { crash -> item(key = "crash") { CrashSection(crash, vm::clearLastCrash) } }
      item(key = "permissions") { PermissionsSection(s, onRequestPermissions, onOpenAppSettings) }
      item(key = "mxlink") { MxLinkSection(liveVm, onRequestPermissions) }
      item(key = "restrictions") { RestrictionsSection(s) }
      item(key = "energy") { EnergySection(s) }
      item(key = "props-header") {
        PropertiesHeader(s, showAll, onShowAll = onShowAll, shown = rows.size, total = allRows.size, onRefresh = vm::refreshVhal)
      }
      items(rows, key = { "p-${it.id}-${it.areaId}" }) { PropertyRowItem(it) }
      item(key = "system") { KvSection("System & display", s.build + s.display + s.composeDisplay) }
      item(key = "network") { KvSection("Network", s.network, action = "Refresh" to vm::refreshStatic) }
      item(key = "report") { ReportSection(s, vm, liveVm) }
    }
  }
}

/** Measures the insets as Compose sees them, for the System & display section and the report. */
@Composable
private fun ComposeInsetsProbe(vm: DiagnosticsViewModel) {
  val d = LocalDensity.current
  val dir = LocalLayoutDirection.current
  fun fmt(w: WindowInsets) = "left ${w.getLeft(d, dir)}, top ${w.getTop(d)}, right ${w.getRight(d, dir)}, bottom ${w.getBottom(d)}"
  val kvs = listOf(
    KV("Compose safeDrawing (px)", fmt(WindowInsets.safeDrawing)),
    KV("Compose systemBars (px)", fmt(WindowInsets.systemBars)),
    KV("Compose displayCutout (px)", fmt(WindowInsets.displayCutout)),
  )
  LaunchedEffect(kvs) { vm.setComposeDisplay(kvs) }
}

// --- Building blocks ---------------------------------------------------------------------------

@Composable
internal fun Section(
  title: String,
  subtitle: String? = null,
  action: Pair<String, () -> Unit>? = null,
  content: @Composable () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
          if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextDim)
        }
        if (action != null) OutlinedButton(onClick = action.second, modifier = Modifier.heightIn(min = 56.dp)) { Text(action.first) }
      }
      content()
    }
  }
}

@Composable
internal fun KvTable(list: List<KV>) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    list.forEach { kv ->
      Row(Modifier.fillMaxWidth()) {
        Text(kv.key, Modifier.widthIn(min = 200.dp, max = 360.dp).padding(end = 16.dp), style = MaterialTheme.typography.bodyMedium, color = TextDim)
        Text(kv.value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
private fun KvSection(title: String, list: List<KV>, action: Pair<String, () -> Unit>? = null) {
  Section(title, action = action) {
    if (list.isEmpty()) Text("Collecting…", color = TextDim) else KvTable(list)
  }
}

@Composable
internal fun Badge(text: String, color: Color) {
  Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
    Text(
      text,
      Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
      color = color,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

private fun statusColor(st: RowStatus) = when (st) {
  RowStatus.AVAILABLE -> StatusOk
  RowStatus.UNAVAILABLE -> StatusWarn
  RowStatus.ERROR -> StatusError
}

@Composable
internal fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
  Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 60.dp)) {
    Text(text, style = MaterialTheme.typography.labelLarge)
  }
}

internal fun time(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

/** Value age: "850 ms", "12.3 s", "4 min". */
internal fun age(ms: Long): String = when {
  ms < 1_000 -> "$ms ms"
  ms < 120_000 -> String.format(Locale.US, "%.1f s", ms / 1000.0)
  else -> "${ms / 60_000} min"
}

// --- Sections ----------------------------------------------------------------------------------

@Composable
private fun Header(s: DiagState, onRefresh: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Diagnostics", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
      Text(
        "Read-only. Available in Park. Nothing on this screen writes to the vehicle.",
        style = MaterialTheme.typography.bodyLarge,
        color = TextDim,
      )
    }
    val (label, color) = when (s.car.phase) {
      CarConnState.Phase.CONNECTED -> "Car service connected" to StatusOk
      CarConnState.Phase.CONNECTING -> "Connecting…" to StatusWarn
      CarConnState.Phase.DISCONNECTED -> "Car service disconnected" to StatusWarn
      CarConnState.Phase.NOT_AUTOMOTIVE -> "No car service" to StatusError
      CarConnState.Phase.FAILED -> "Car service error" to StatusError
    }
    Badge(label, color)
    Spacer(Modifier.width(16.dp))
    BigButton("Refresh all", onClick = onRefresh)
  }
}

@Composable
private fun CrashSection(text: String, onClear: () -> Unit) {
  Section(
    "Last crash",
    "The app crashed on an earlier run. The full text is in the report until you clear it.",
    action = "Clear" to onClear,
  ) {
    val lines = text.lines()
    val shown = lines.take(CRASH_LINES_SHOWN).joinToString("\n")
    val more = lines.size - CRASH_LINES_SHOWN
    Text(
      if (more > 0) "$shown\n… $more more lines in the report" else shown,
      style = MaterialTheme.typography.bodySmall,
      fontFamily = FontFamily.Monospace,
      color = StatusError,
    )
  }
}

private const val CRASH_LINES_SHOWN = 30

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionsSection(s: DiagState, onRequest: () -> Unit, onSettings: () -> Unit) {
  Section(
    "Permissions",
    "Car energy and speed, and Bluetooth, need a runtime grant. The others are granted at install.",
  ) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      s.permissions.forEach { p ->
        Row(Modifier.width(600.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(p.shortName, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            Text(
              "${if (p.runtime) "runtime" else "install-time"} · ${p.purpose}",
              style = MaterialTheme.typography.bodySmall,
              color = TextDim,
            )
          }
          Badge(if (p.granted) "GRANTED" else "DENIED", if (p.granted) StatusOk else StatusError)
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      BigButton("Request runtime permissions", onClick = onRequest)
      OutlinedButton(onClick = onSettings, modifier = Modifier.heightIn(min = 60.dp)) { Text("Open app settings") }
    }
    Text(
      "On GM head units these can also be granted under Settings › Apps › Silverado Stats › Permissions. " +
        "Android files CAR_ENERGY under \"Car information\", CAR_SPEED under \"Location\" and the Bluetooth ones " +
        "under \"Nearby devices\", so the dialogs ask about your car's information and this device's location.",
      style = MaterialTheme.typography.bodyMedium,
      color = TextDim,
    )
  }
}

@Composable
private fun RestrictionsSection(s: DiagState) {
  Section("Driving restrictions", "From CarUxRestrictionsManager. Out of Park this tab shows only \"Available in Park\"; Simple and Live switch to their driving layouts.") {
    val ux = s.ux
    val items = buildList {
      add(KV("Car service", "${s.car.phase}: ${s.car.message}"))
      if (ux == null) {
        add(KV("UX restrictions", "not available"))
      } else {
        add(KV("Requires distraction optimization", ux.requiresDistractionOptimization.toString()))
        add(KV("Active restrictions", String.format(Locale.US, "0x%03X  %s", ux.activeRestrictions, ux.decoded.joinToString(", "))))
        add(KV("Max restricted string length", ux.maxStringLength?.toString() ?: "?"))
        add(KV("Updated", time(ux.updatedAtMs)))
      }
      addAll(s.carApi)
    }
    KvTable(items)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnergySection(s: DiagState) {
  val byId = s.live.associateBy { it.id }
  Section(
    "Energy (live)",
    if (s.liveUpdatedAtMs > 0) "Refreshed every second while visible · last ${time(s.liveUpdatedAtMs)}" else "Waiting for the car service…",
  ) {
    if (s.live.isEmpty()) {
      Text(
        if (s.car.phase == CarConnState.Phase.CONNECTED) "No values yet." else "Car service unavailable: ${s.car.message}",
        color = TextDim,
      )
      return@Section
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SocTile(byId)
      s.live.forEach { LiveTile(it) }
    }
    Text(
      "Pack power uses the AOSP sign convention (+ charging, − discharging); the session checks GM's against the battery level. " +
        "Pack voltage, current, cell and module data are not in Android's vehicle API: they need the OBD adapter.",
      style = MaterialTheme.typography.bodySmall,
      color = TextDim,
    )
  }
}

@Composable
private fun Tile(label: String, value: String, sub: String, color: Color) {
  Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.width(296.dp)) {
    Column(Modifier.padding(16.dp)) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = TextDim, maxLines = 1)
      Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = color, maxLines = 2)
      Text(sub, style = MaterialTheme.typography.labelSmall, color = TextDim, maxLines = 2)
    }
  }
}

@Composable
private fun SocTile(byId: Map<Int, PropertyRow>) {
  fun avail(id: Int) = byId[id]?.takeIf { it.status == RowStatus.AVAILABLE }?.rawValue
  val level = avail(KnownProperties.EV_BATTERY_LEVEL)
  val current = avail(KnownProperties.EV_CURRENT_BATTERY_CAPACITY)
  val nominal = avail(KnownProperties.INFO_EV_BATTERY_CAPACITY)
  val (soc, basis) = Fmt.socPercent(level, current)?.let { it to "level ÷ usable capacity" }
    ?: Fmt.socPercent(level, nominal)?.let { it to "level ÷ nominal capacity" }
    ?: (null to "needs EV_BATTERY_LEVEL + a capacity")
  Tile(
    "State of charge (computed)",
    soc?.let { String.format(Locale.US, "%.1f %%", it) } ?: "—",
    basis,
    if (soc != null) MaterialTheme.colorScheme.primary else TextDim,
  )
}

@Composable
private fun LiveTile(r: PropertyRow) {
  val ok = r.status == RowStatus.AVAILABLE
  val sub = buildString {
    append(r.name)
    if (ok && r.timestampAgeMs != null) append(" · ${age(r.timestampAgeMs)} old")
    if (!ok) append(" · ${r.status}")
  }
  Tile(
    r.label ?: r.name,
    if (ok) r.value else shortReason(r),
    sub,
    when {
      ok -> MaterialTheme.colorScheme.onSurface
      r.detail.startsWith(VehicleReader.NOT_SUPPORTED) -> StatusWarn
      else -> statusColor(r.status)
    },
  )
}

/** Tile text for a value that could not be read, from what the read actually returned. */
private fun shortReason(r: PropertyRow): String = when {
  r.detail.startsWith(VehicleReader.NOT_SUPPORTED) -> "not offered"
  r.detail.startsWith(VehicleReader.NO_PERMISSION) || r.detail.contains("SecurityException") -> "no permission"
  r.status == RowStatus.UNAVAILABLE -> "unavailable"
  else -> "error"
}

@Composable
private fun PropertiesHeader(
  s: DiagState,
  showAll: Boolean,
  onShowAll: (Boolean) -> Unit,
  shown: Int,
  total: Int,
  onRefresh: () -> Unit,
) {
  val v = s.vhal
  val subtitle = when {
    s.vhalLoading -> "Reading…"
    v == null -> "Not read yet"
    else -> "Listed for this app: ${v.listedCount} · rows: $total · available: ${v.rows.count { it.status == RowStatus.AVAILABLE }} · " +
      "read in ${v.readMs} ms at ${time(v.takenAtMs)}" + (v.listError?.let { " · $it" } ?: "")
  }
  Section("All vehicle properties", subtitle, action = "Re-read" to onRefresh) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      FilterChip(selected = !showAll, onClick = { onShowAll(false) }, label = { Text("Available only", style = MaterialTheme.typography.labelLarge) })
      FilterChip(selected = showAll, onClick = { onShowAll(true) }, label = { Text("All", style = MaterialTheme.typography.labelLarge) })
      Text("showing $shown", color = TextDim)
      if (s.vhalLoading) CircularProgressIndicator(Modifier.size(32.dp))
    }
    if (v == null && s.car.phase != CarConnState.Phase.CONNECTED) {
      Text("Car service unavailable: ${s.car.message}", color = TextDim)
    }
    if (v != null && shown == 0) {
      Text(if (showAll) "No rows." else "No available rows. Switch to All to see errors and permissions.", color = TextDim)
    }
  }
}

@Composable
private fun PropertyRowItem(r: PropertyRow) {
  Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(r.idHex, Modifier.width(170.dp), fontFamily = FontFamily.Monospace, color = TextDim, style = MaterialTheme.typography.bodyMedium)
        Text(r.name, Modifier.weight(1f).padding(end = 16.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(r.value, Modifier.weight(1f).padding(end = 16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Badge(r.status.name, statusColor(r.status))
      }
      val meta = buildList {
        add("${r.group} · ${r.areaType} area ${String.format(Locale.US, "0x%X", r.areaId)}" + if (r.areaIds.size > 1) " of ${r.areaIds.size}" else "")
        add(r.access)
        add(r.changeMode)
        if (r.sampleRates.isNotEmpty()) add(r.sampleRates)
        add(r.valueType)
        r.timestampAgeMs?.let { add("age ${age(it)}") }
        add(if (r.listed) "listed" else "not listed")
        add(r.permissions)
        if (r.note.isNotEmpty()) add(r.note)
      }
      Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = TextDim)
      if (r.detail.isNotEmpty()) Text(r.detail, style = MaterialTheme.typography.bodySmall, color = statusColor(r.status))
    }
  }
}

@Composable
private fun ReportSection(s: DiagState, vm: DiagnosticsViewModel, liveVm: LiveViewModel) {
  Section("Report", "JSON of everything on this screen, POSTed to the laptop running ./probe receive.") {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = s.reportUrl,
        onValueChange = vm::setReportUrl,
        label = { Text("Laptop URL") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.weight(1f),
      )
      BigButton(if (s.reportSending) "Sending…" else "Send to laptop", enabled = !s.reportSending, onClick = { vm.sendReport(liveVm.reportData()) })
    }
    s.reportStatus?.let {
      Text(it, color = if (it.startsWith("Sent")) StatusOk else if (it.startsWith("Failed")) StatusError else TextDim)
    }
  }
}
