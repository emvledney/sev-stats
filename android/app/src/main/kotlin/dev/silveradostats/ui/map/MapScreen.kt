package dev.silveradostats.ui.map

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.silveradostats.R
import dev.silveradostats.link.UiSettings
import dev.silveradostats.map.MapController
import dev.silveradostats.map.MapFilters
import dev.silveradostats.map.NetworkChoice
import dev.silveradostats.map.PLUG_GROUPS
import dev.silveradostats.map.POWER_CHOICES
import dev.silveradostats.map.Place
import dev.silveradostats.map.TIER_COLORS
import dev.silveradostats.map.powerTier
import dev.silveradostats.ui.Chrome
import dev.silveradostats.ui.layout.DashLayout
import dev.silveradostats.ui.layout.FitText
import dev.silveradostats.ui.simple.SimpleModel
import dev.silveradostats.ui.theme.StatusOk
import dev.silveradostats.ui.theme.TextDim
import dev.silveradostats.vehicle.SessionState

/** Height of everything meant to be tapped on the map: the car guideline's 76 dp. */
private val TOUCH = 76.dp
private val COLUMN_WIDTH = 520.dp
private val EDGE = 16.dp

/** Which picker is open over the map. */
private enum class Picker { POWER, PLUGS, NETWORKS }

/**
 * The Map tab: chargers from Open Charge Map on a north-up Google map, filters, search with the
 * app's own keyboard, and a card that hands the chosen place to Google Maps for navigation.
 *
 * Unlike the rest of the app it stays fully usable out of Park, by the owner's decision: searching
 * and filtering chargers on the move is what it is for. Only the location-permission request waits
 * for Park, like every other system dialog.
 *
 * The range in the header and the outline on the map use the session's measured efficiency once it
 * has one, and the truck's own estimate until then.
 */
@Composable
fun MapScreen(
  session: SessionState,
  settings: UiSettings,
  layout: DashLayout,
  chrome: Chrome,
  onRequestPermissions: () -> Unit,
) {
  val context = LocalContext.current
  val controller = remember { MapController(context) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> controller.start()
        Lifecycle.Event.ON_RESUME -> controller.resume()
        Lifecycle.Event.ON_PAUSE -> controller.pause()
        Lifecycle.Event.ON_STOP -> controller.stop()
        else -> {}
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      controller.destroy()
    }
  }

  val measured = session.rangeAtMeasuredM
  val rangeM = measured ?: session.rangeM
  LaunchedEffect(rangeM) { controller.setRange(rangeM) }

  var query by remember { mutableStateOf("") }
  var keyboard by remember { mutableStateOf(false) }
  var picker by remember { mutableStateOf<Picker?>(null) }
  var results by remember { mutableStateOf<List<Place>>(emptyList()) }
  val body = layout.type.body
  val title = layout.type.title

  fun runSearch(text: String) {
    keyboard = false
    controller.search(text) { results = it }
  }
  val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
    if (result.resultCode == Activity.RESULT_OK && spoken != null) {
      query = spoken
      runSearch(spoken)
    }
  }
  BackHandler(enabled = keyboard) { keyboard = false }

  Column(Modifier.fillMaxSize()) {
    chrome(
      {
        if (rangeM != null) {
          Icon(painterResource(R.drawable.map_ic_battery), null, Modifier.size(28.dp), tint = StatusOk)
          FitText(SimpleModel.distText(rangeM, settings.distUnit, 0), sp = layout.type.tab, weight = FontWeight.Bold)
          val basis = session.efficiency.mPerWh?.takeIf { measured != null }
            ?.let { "at ${SimpleModel.effText(it, settings.distUnit)} ${SimpleModel.effUnit(settings.distUnit)} measured" }
            ?: "truck's estimate"
          FitText(basis, Modifier.weight(1f), sp = body, color = TextDim)
        }
      },
      {},
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
      AndroidView({ controller.mapView }, Modifier.fillMaxSize())

      Column(Modifier.align(Alignment.TopStart).padding(EDGE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchBar(
          query = query,
          sp = title,
          onOpen = { keyboard = true },
          onClear = { query = "" },
          onVoice = {
            runCatching {
              voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
            }
          },
          modifier = Modifier.onSizeChanged { controller.setPadding(it.width + 2 * EDGE.roundToPx(context), it.height + 2 * EDGE.roundToPx(context)) },
        )
        if (!keyboard) {
          FilterChips(controller.filters, body, onPick = { picker = it }, onToggleWorking = { controller.applyFilters(controller.filters.copy(workingOnly = !controller.filters.workingOnly)) })
          // The system's permission dialog is the one thing here that waits for Park.
          if (!controller.hasLocationPermission && !layout.restricted) {
            Button(onClick = onRequestPermissions, modifier = Modifier.height(TOUCH)) { Text("Allow location to show the truck", fontSize = body.sp) }
          }
        }
      }

      controller.target?.let { place ->
        if (!keyboard) {
          PlaceCard(place, controller.milesTo(place), title, body, onClose = { controller.show(null) }, onNavigate = { controller.navigate(place) }, modifier = Modifier.align(Alignment.BottomStart).padding(EDGE))
        }
      }

      if (keyboard) {
        Keyboard(
          sp = title,
          onKey = { query += it },
          onBackspace = { query = query.dropLast(1) },
          onHide = { keyboard = false },
          onSearch = { runSearch(query) },
          modifier = Modifier.align(Alignment.BottomCenter),
        )
      }
    }
  }

  val f = controller.filters
  when (picker) {
    Picker.POWER -> ChoiceDialog(
      title = "Minimum power",
      labels = POWER_CHOICES.map { if (it == 0) "Any power" else "$it kW or more" },
      selected = setOf(POWER_CHOICES.indexOf(f.minKw)),
      multi = false,
      sp = body,
      clearLabel = null,
      onDone = { chosen -> chosen?.firstOrNull()?.let { controller.applyFilters(f.copy(minKw = POWER_CHOICES[it])) }; picker = null },
    )
    Picker.PLUGS -> {
      val names = PLUG_GROUPS.keys.toList()
      ChoiceDialog(
        title = "Plug types",
        labels = names,
        selected = names.indices.filter { names[it] in f.plugs }.toSet(),
        multi = true,
        sp = body,
        clearLabel = "Any plug",
        onDone = { chosen -> if (chosen != null) controller.applyFilters(f.copy(plugs = chosen.map { names[it] }.toSet())); picker = null },
      )
    }
    Picker.NETWORKS -> {
      val choices: List<NetworkChoice> = remember(picker) { controller.networkChoices() }
      ChoiceDialog(
        title = "Networks in view",
        labels = choices.map { "${it.name} (${it.count})" },
        selected = choices.indices.filter { choices[it].id in f.networks }.toSet(),
        multi = true,
        sp = body,
        clearLabel = "All networks",
        onDone = { chosen -> if (chosen != null) controller.applyFilters(f.copy(networks = chosen.map { choices[it].id }.toSet())); picker = null },
      )
    }
    null -> {}
  }
  if (results.isNotEmpty()) {
    val shown = results
    ChoiceDialog(
      title = query,
      labels = shown.map { "${it.name}\n${it.detail}" },
      selected = emptySet(),
      multi = false,
      sp = body,
      clearLabel = null,
      onDone = { chosen -> chosen?.firstOrNull()?.let { controller.show(shown[it], moveCamera = true) }; results = emptyList() },
    )
  }
}

private fun androidx.compose.ui.unit.Dp.roundToPx(context: android.content.Context): Int = (value * context.resources.displayMetrics.density).toInt()

@Composable
private fun SearchBar(query: String, sp: Int, onOpen: () -> Unit, onClear: () -> Unit, onVoice: () -> Unit, modifier: Modifier = Modifier) {
  Surface(modifier.width(COLUMN_WIDTH).height(TOUCH), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
    Row(Modifier.clickable(onClick = onOpen).padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(painterResource(R.drawable.map_ic_search), null, Modifier.size(28.dp), tint = TextDim)
      Text(
        query.ifEmpty { "Search places" },
        Modifier.weight(1f).padding(horizontal = 12.dp),
        color = if (query.isEmpty()) TextDim else MaterialTheme.colorScheme.onSurface,
        fontSize = sp.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (query.isNotEmpty()) {
        IconButton(onClick = onClear, modifier = Modifier.size(64.dp)) { Icon(painterResource(R.drawable.map_ic_close), "Clear search", tint = TextDim) }
      }
      IconButton(onClick = onVoice, modifier = Modifier.size(64.dp)) { Icon(painterResource(R.drawable.map_ic_mic), "Voice search", tint = TextDim) }
    }
  }
}

/** One row of filter chips; a chip is ticked while its filter is narrowing the map. */
@Composable
private fun FilterChips(f: MapFilters, sp: Int, onPick: (Picker) -> Unit, onToggleWorking: () -> Unit) {
  @Composable
  fun chip(label: String, on: Boolean, icon: Int, onClick: () -> Unit) {
    FilterChip(
      selected = on,
      onClick = onClick,
      label = { Text(label, fontSize = sp.sp, maxLines = 1) },
      leadingIcon = { Icon(painterResource(icon), null, Modifier.size(26.dp)) },
      modifier = Modifier.height(64.dp),
      elevation = androidx.compose.material3.FilterChipDefaults.elevatedFilterChipElevation(),
      colors = androidx.compose.material3.FilterChipDefaults.elevatedFilterChipColors(),
    )
  }
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    chip(if (f.minKw == 0) "Any power" else "${f.minKw} kW+", f.minKw > 0, R.drawable.map_ic_bolt) { onPick(Picker.POWER) }
    chip(if (f.plugs.isEmpty()) "Any plug" else f.plugs.joinToString(", "), f.plugs.isNotEmpty(), R.drawable.map_ic_plug) { onPick(Picker.PLUGS) }
    chip(if (f.networks.isEmpty()) "All networks" else "${f.networks.size} networks", f.networks.isNotEmpty(), R.drawable.map_ic_network) { onPick(Picker.NETWORKS) }
    chip(if (f.workingOnly) "Working only" else "Any status", f.workingOnly, R.drawable.map_ic_check, onToggleWorking)
  }
}

@Composable
private fun PlaceCard(place: Place, miles: Int?, titleSp: Int, bodySp: Int, onClose: () -> Unit, onNavigate: () -> Unit, modifier: Modifier = Modifier) {
  Surface(modifier.width(COLUMN_WIDTH), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        Text(place.name, Modifier.weight(1f), fontSize = titleSp.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) { Icon(painterResource(R.drawable.map_ic_close), "Close", tint = TextDim) }
      }
      if (place.tags.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          place.tags.forEachIndexed { i, tag ->
            // The first tag of a charger is its power, coloured like its dot on the map.
            val tier = if (i == 0) tag.substringBefore(' ').toDoubleOrNull()?.let(::powerTier) else null
            val fill = tier?.let { Color(TIER_COLORS[it]) } ?: MaterialTheme.colorScheme.surfaceVariant
            val ink = when (tier) {
              null -> MaterialTheme.colorScheme.onSurfaceVariant
              1 -> Color.Black
              else -> Color.White
            }
            Text(tag, Modifier.background(fill, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp), color = ink, fontSize = bodySp.sp, maxLines = 1)
          }
        }
      }
      if (place.detail.isNotEmpty()) Text(place.detail, fontSize = bodySp.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
      if (miles != null) Text("$miles mi away", color = TextDim, fontSize = bodySp.sp)
      Button(onClick = onNavigate, modifier = Modifier.fillMaxWidth().height(TOUCH)) {
        Icon(painterResource(R.drawable.map_ic_navigation), null, Modifier.size(26.dp))
        Spacer(Modifier.width(8.dp))
        Text("Navigate in Google Maps", fontSize = titleSp.sp, maxLines = 1)
      }
    }
  }
}

/**
 * The app's own keyboard. The system keyboard refuses input while the truck is moving, so the
 * search box never opens it.
 */
@Composable
private fun Keyboard(sp: Int, onKey: (Char) -> Unit, onBackspace: () -> Unit, onHide: () -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
  @Composable
  fun RowScope.key(label: String, weight: Float = 1f, primary: Boolean = false, onTap: () -> Unit) {
    Surface(
      Modifier.weight(weight).fillMaxSize().padding(3.dp).clickable(onClick = onTap),
      shape = RoundedCornerShape(14.dp),
      color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(label, fontSize = sp.sp, color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
      }
    }
  }

  @Composable
  fun RowScope.chars(keys: String) = keys.forEach { c -> key(c.toString()) { onKey(c) } }

  Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 16.dp) {
    Column(Modifier.padding(8.dp)) {
      val row = Modifier.fillMaxWidth().height(72.dp)
      Row(row) { chars("1234567890") }
      Row(row) { chars("qwertyuiop") }
      Row(row) {
        Spacer(Modifier.weight(0.5f))
        chars("asdfghjkl")
        Spacer(Modifier.weight(0.5f))
      }
      Row(row) {
        chars("zxcvbnm,.")
        key("⌫", 1.5f, onTap = onBackspace)
      }
      Row(row) {
        key("Hide", 1.5f, onTap = onHide)
        key("space", 6f) { onKey(' ') }
        key("Search", 2.5f, primary = true, onTap = onSearch)
      }
    }
  }
}

/**
 * A list to choose from. Single choice closes on the tap; multiple choice has Apply and an optional
 * [clearLabel] button that chooses nothing. [onDone] gets the chosen indices, or null when dismissed.
 */
@Composable
private fun ChoiceDialog(
  title: String,
  labels: List<String>,
  selected: Set<Int>,
  multi: Boolean,
  sp: Int,
  clearLabel: String?,
  onDone: (Set<Int>?) -> Unit,
) {
  var chosen by remember { mutableStateOf(selected) }
  AlertDialog(
    onDismissRequest = { onDone(null) },
    title = { Text(title, fontSize = sp.sp, fontWeight = FontWeight.SemiBold) },
    text = {
      LazyColumn(Modifier.heightIn(max = 420.dp).widthIn(min = 420.dp)) {
        itemsIndexed(labels) { i, label ->
          Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { if (multi) chosen = if (i in chosen) chosen - i else chosen + i else onDone(setOf(i)) },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (multi) Checkbox(checked = i in chosen, onCheckedChange = null) else if (selected.isNotEmpty()) RadioButton(selected = i in chosen, onClick = null)
            Text(label, Modifier.padding(start = 12.dp), fontSize = sp.sp)
          }
        }
      }
    },
    confirmButton = { if (multi) TextButton(onClick = { onDone(chosen) }) { Text("Apply", fontSize = sp.sp) } },
    dismissButton = { if (clearLabel != null) TextButton(onClick = { onDone(emptySet()) }) { Text(clearLabel, fontSize = sp.sp) } },
  )
}
