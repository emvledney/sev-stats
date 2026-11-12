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
import dev.silveradostats.ui.theme.CardEdge
import dev.silveradostats.ui.theme.CardShape
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
  onRequestPermissions: () -> Unit,
  onOpenAppSettings: () -> Unit,
) {
  val s by vm.state.collectAsStateWithLifecycle()
  ComposeInsetsProbe(vm)

  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = listState,
      contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item(key = "header") { Header() }
      s.lastCrash?.let { crash -> item(key = "crash") { CrashSection(crash, vm::clearLastCrash) } }
      item(key = "permissions") { PermissionsSection(s, onRequestPermissions, onOpenAppSettings) }
      item(key = "mxlink") { MxLinkSection(liveVm, onRequestPermissions) }
      item(key = "report") { ReportSection(s, vm, liveVm) }
    }
  }
}

/** Measures the insets as Compose sees them, for the report. */
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
    shape = CardShape,
    border = CardEdge,
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


@Composable
internal fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
  Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 60.dp)) {
    Text(text, style = MaterialTheme.typography.labelLarge)
  }
}

internal fun time(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))


// --- Sections ----------------------------------------------------------------------------------

@Composable
private fun Header() {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
      Text(
        "Available in Park. Nothing here writes to the vehicle.",
        style = MaterialTheme.typography.bodyLarge,
        color = TextDim,
      )
    }
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

/** Only what is missing is listed: with everything granted this is one line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionsSection(s: DiagState, onRequest: () -> Unit, onSettings: () -> Unit) {
  val denied = s.permissions.filter { !it.granted }
  Section("Permissions", if (denied.isEmpty()) "All granted." else "Car energy and speed, and Bluetooth, need a runtime grant.") {
    if (denied.isEmpty()) return@Section
    FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      denied.forEach { p ->
        Row(Modifier.width(600.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(p.shortName, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            Text("${if (p.runtime) "runtime" else "install-time"} · ${p.purpose}", style = MaterialTheme.typography.bodySmall, color = TextDim)
          }
          Badge("DENIED", StatusError)
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      BigButton("Request runtime permissions", onClick = onRequest)
      OutlinedButton(onClick = onSettings, modifier = Modifier.heightIn(min = 60.dp)) { Text("Open app settings") }
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
