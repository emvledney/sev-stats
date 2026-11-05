package dev.silveradostats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Static guard for the "never alter the vehicle" rule: fails if any non-test source set of the
 * app contains a vehicle-write, content-provider, private-service, process or reflection call,
 * names a car API class outside the read-only allowlist, or declares a permission outside the
 * spec's exact list. It also pins the driver-distraction setup: exactly MainActivity and Battery
 * Glance's CarAppActivity are distraction-optimized (in the source and in the merged manifest), and
 * the glance accepts any template host only in debug builds.
 *
 * Rules are regexes over the raw file text (comments and strings included, on purpose), so call
 * forms such as `setProperty<Int>(`, `setProperty (`, `` `setProperty`( `` and `::setProperty`
 * are all caught. app/build.gradle.kts declares `src` as an input of the test task, so any
 * source or manifest change re-runs this test (it can never be UP-TO-DATE or FROM-CACHE).
 */
class SourceSafetyTest {

  /**
   * @param allowedIn package directory (relative to dev/silveradostats/, e.g. "bt/") where the rule
   *   does not apply; null = applies everywhere.
   */
  private class Rule(val name: String, pattern: String, val javaOnly: Boolean = false, val allowedIn: String? = null) {
    val regex = Regex(pattern)
  }

  private val rules = listOf(
    // Vehicle writes, in any call form.
    Rule("vehicle property write", """\bset(Int|Boolean|Float)?Property\b"""),
    Rule("vehicle property write", """\bsetPropertiesAsync\b|SetPropertyRequest"""),
    Rule("UX restrictions write", """\bsetRestrictionMode\b|\bsaveUxRestrictionsConfiguration"""),
    // Car managers: only the property and UX-restrictions services, by constant.
    Rule("car manager other than PROPERTY / UX restrictions", """::\s*getCarManager\b"""),
    Rule(
      "car manager other than PROPERTY / UX restrictions",
      """\bgetCarManager`?\s*(<[^>]*>)?\s*\((?!\s*Car\.(PROPERTY_SERVICE|CAR_UX_RESTRICTION_SERVICE)\s*\))""",
    ),
    // Content providers (GM's com.gm.vehicleinfo providers accept writes from any app). No use at all.
    Rule("content provider", """\b(contentResolver|getContentResolver|ContentResolver)\b"""),
    Rule("content provider", """\bContentProviderClient\b|\bacquire\w*ContentProviderClient\b|\bapplyBatch\b|\bbulkInsert\b"""),
    Rule("content provider", """content://"""),
    Rule("provider / reflective call", """\.call(By)?\s*\("""),
    // Reflection and hidden APIs.
    Rule("reflection", """\bget(Declared)?(Field|Method|Constructor)s?\b"""),
    Rule("reflection", """\.(fields|methods|constructors|declaredFields|declaredMethods|declaredConstructors|members)\b"""),
    Rule("reflection", """\bjava\.lang\.reflect\b|\bkotlin\.reflect\b|\bMethodHandles?\b|\bVarHandle\b"""),
    Rule("reflection", """\b(set|trySet|is)Accessible\b|\bnewInstance\b"""),
    Rule("class loading", """\bforName\b|\bloadClass\b|ClassLoader\b|\bclassLoader\b"""),
    Rule("native code", """\bSystem\.load(Library)?\b|\bexternal\s+fun\b"""),
    Rule("Java unicode escape (could hide an identifier)", """\\u+[0-9A-Fa-f]{4}""", javaOnly = true),
    // Other ways to reach GM components or the system.
    Rule("service bind / start", """\bbind\w*Service|\bstart\w*Service\b|\bpeekService\b|\btransact\b"""),
    Rule("broadcast", """\bsend\w*Broadcast"""),
    Rule("process exec", """\bRuntime\b|\bProcessBuilder\b"""),
    Rule("GM package", """\bcom\.gm\b"""),
    Rule("explicit component", """\bComponentName\b|\bsetClassName\b|\bsetComponent\b|\bsetPackage\b"""),
    // Car App Library (Battery Glance): templates only. Its car-hardware API (CarHardwareManager:
    // climate, sensors, car info through CarPropertyManager) and app launching stay unused; the app
    // reads the car only through the read-only VehicleReader. getCarService is not needed at all.
    Rule(
      "Car App Library hardware / app-launch API",
      """[cC]arHardware|[cC]arClimate|\bAutomotiveCar\w+|\bHARDWARE_SERVICE\b|""" +
        """\bandroidx\.car\.app\.hardware\b|\bgetCarService\b|\bstartCarApp\b""",
    ),
    // BLE: no use at all, everywhere (the MX+ is Bluetooth Classic).
    Rule("BLE connect", """\bconnectGatt\b"""),
    Rule("BLE scan / advertise", """\b[bB]luetoothLeScanner\b|\bstartScan\b|\bstartLeScan\b|\b[bB]luetoothLeAdvertiser\b"""),
    // Bluetooth Classic to the OBDLink MX+: pairing, discovery and the RFCOMM serial socket, only
    // inside the bt/ package (which wraps the socket in the OBD core's stream link).
    Rule(
      "Bluetooth Classic pairing / discovery / RFCOMM outside bt/",
      """\bcreateBond\b|\bcreate(Insecure)?RfcommSocketToServiceRecord\b|\bstartDiscovery\b|\bBluetoothSocket\b""",
      allowedIn = "bt/",
    ),
    // Everything else Bluetooth can do stays forbidden, in bt/ too: hidden channel sockets, server
    // sockets, L2CAP, bond removal / PIN injection, other profiles (phone audio, HID, GATT, ...),
    // and changing the head unit's own Bluetooth state or name.
    Rule(
      "Bluetooth hidden / server / profile / adapter-state API",
      """\bcreate(Insecure)?RfcommSocket\b|\blistenUsing\w*|\bcreate(Insecure)?L2capChannel\b|\bremoveBond\b|""" +
        """\bcancelBondProcess\b|\bset(Pin|Passkey|PairingConfirmation|Alias|Name|DiscoverableTimeout)\b|""" +
        """\bgetProfileProxy\b|\bBluetooth(Headset|A2dp\w*|HidDevice|HidHost|Gatt\w*|Hearing\w*|LeAudio|Pan|Pbap|Map|Sap)\b|""" +
        """ACTION_REQUEST_(ENABLE|DISCOVERABLE)|\b(enable|disable|factoryReset)\s*\(\s*\)""",
    ),
  )

  /**
   * The only exceptions to the rules above, each tied to one file and one exact form: binding this
   * app's own, non-exported LinkService by explicit class (a ServiceConnection's callbacks name
   * ComponentName). The matched text is blanked before the rules run.
   */
  private val allowances: Map<String, List<Regex>> = mapOf(
    "link/LinkConnection.kt" to listOf(
      Regex("""import android\.content\.ComponentName\n"""),
      Regex("""override fun onService(Connected|Disconnected)\(name: ComponentName\?"""),
      Regex("""\bapp\.bindService\(Intent\(app, LinkService::class\.java\), connection, Context\.BIND_AUTO_CREATE\)"""),
    ),
    // The hourly job names its own service for JobScheduler; the CSV export writes only the file the user picked.
    "log/LogScheduler.kt" to listOf(
      Regex("""import android\.content\.ComponentName\n"""),
      Regex("""\bJobInfo\.Builder\(JOB_ID, ComponentName\(app, LogJobService::class\.java\)\)"""),
      Regex("""\bapp\.contentResolver\.openOutputStream\(uri, "wt"\)"""),
    ),
  )

  /** The only android.car classes the app may name: all read-only in the way the app uses them. */
  private val allowedCarClasses = setOf(
    "android.car.Car",
    "android.car.hardware.CarPropertyConfig",
    "android.car.hardware.CarPropertyValue",
    "android.car.hardware.property.CarPropertyManager",
    "android.car.drivingstate.CarUxRestrictions",
    "android.car.drivingstate.CarUxRestrictionsManager",
  )
  private val carReference = Regex("""\bandroid\.car\.[\w.*]*""")

  /** The spec's exact permission list. Anything else (write-capable or not) fails the test. */
  private val expectedPermissions = setOf(
    "android.car.permission.CAR_INFO",
    "android.car.permission.CAR_ENERGY",
    "android.car.permission.CAR_ENERGY_PORTS",
    "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
    "android.car.permission.CAR_POWERTRAIN",
    "android.car.permission.CAR_SPEED",
    "android.car.permission.READ_CAR_DISPLAY_UNITS",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.RECEIVE_BOOT_COMPLETED",
  )

  private fun srcDir(): File =
    listOf(File("src"), File("app/src")).firstOrNull { File(it, "main").isDirectory }
      ?: error("cannot find app/src/main from ${File(".").absolutePath}")

  /** main plus any build-type / flavour source set (debug, release, …) that ends up in an APK. */
  private fun shippedSourceSets(): List<File> =
    srcDir().listFiles().orEmpty()
      .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }

  private fun sources(): List<File> = shippedSourceSets().flatMap { set ->
    set.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts", "java") }.toList()
  }

  /** Path below dev/silveradostats/ ("bt/BtClassic.kt"), or the plain path for other files. */
  private fun relPath(f: File): String {
    val p = f.invariantSeparatorsPath
    val marker = "/dev/silveradostats/"
    val i = p.indexOf(marker)
    return if (i >= 0) p.substring(i + marker.length) else p
  }

  /** [text] with the allowed spans of [rel] blanked (same length, so line numbers stay right). */
  private fun applyAllowances(text: String, rel: String): String {
    var out = text
    for (r in allowances[rel].orEmpty()) out = r.replace(out) { m -> m.value.replace(Regex("[^\n]"), " ") }
    return out
  }

  private fun manifests(): List<File> = shippedSourceSets().map { File(it, "AndroidManifest.xml") }.filter { it.isFile }

  /** Violations in one source file, as "line N: rule: matched text". [rel]: path below dev/silveradostats/. */
  internal fun violations(raw: String, java: Boolean = false, rel: String = "ui/Example.kt"): List<String> {
    val text = applyAllowances(raw, rel)
    fun lineOf(index: Int) = text.substring(0, index).count { it == '\n' } + 1
    val out = mutableListOf<String>()
    for (rule in rules) {
      if (rule.javaOnly && !java) continue
      if (rule.allowedIn != null && rel.startsWith(rule.allowedIn)) continue
      rule.regex.findAll(text).forEach { out += "line ${lineOf(it.range.first)}: ${rule.name}: '${it.value.trim()}'" }
    }
    carReference.findAll(text).forEach { m ->
      val ref = m.value
      val ok = ref.startsWith("android.car.permission.") ||
        allowedCarClasses.any { ref == it || ref.startsWith("$it.") }
      if (!ok) out += "line ${lineOf(m.range.first)}: car API class outside the read-only allowlist: '$ref'"
    }
    return out
  }

  @Test
  fun detectorWorks() {
    val mustFail = listOf(
      "cpm.setIntProperty(id, 0, 1)",
      "cpm.setProperty(Int::class.javaObjectType, 0x11400400, 0, 4)",
      // Forms that compiled but slipped past the old literal-text check:
      "cpm.setProperty<Int>(Int::class.javaObjectType, 0x11400400, 0, 4)",
      "cpm.setProperty (Int::class.javaObjectType, 0x11400400, 0, 4)",
      "val w = cpm::setProperty; w(Int::class.javaObjectType, 0x11400400, 0, 4)",
      "VehiclePropertyIds::class.java.fields.first { it.name == \"GEAR_SELECTION\" }.getInt(null)",
      "ctx.classLoader.loadClass(\"android.car.Car\").methods.first().invoke(null)",
      "ctx.bindIsolatedService(Intent(\"com.gm.x\"), 0, \"x\", executor, conn)",
      // Other routes to GM components or the system:
      "ctx.sendBroadcast(Intent(\"x\").setPackage(pkg))",
      "ctx.startService(intent)",
      "ctx.startForegroundService(intent)",
      "Runtime.getRuntime().exec(\"cmd car_service set-property-value\")",
      "ProcessBuilder(\"sh\").start()",
      "val gm = \"com.gm.vehicleinfo\"",
      "Intent().setComponent(ComponentName(pkg, cls))",
      // Providers, reflection, car managers outside the allowlist:
      "context.contentResolver.insert(uri, values)",
      "context.contentResolver.query(uri, null, null, null, null)",
      "client.call(\"m\", null, null)",
      "VehiclePropertyIds::class.java.getField(\"X\")",
      "Class.forName(\"android.car.VehiclePropertyIds\")",
      "cpm.setPropertiesAsync(requests, 1000, null, executor, cb)",
      "import android.car.hardware.hvac.CarHvacManager",
      "import android.car.hardware.property.*",
      "val v = android.car.VehiclePropertyIds.GEAR_SELECTION",
      "car.getCarManager(Car.HVAC_SERVICE)",
      "car.getCarManager(\"hvac\")",
      "car.getCarManager<Any>(CarHvacManager::class.java)",
      "val m = car::getCarManager",
      "device.connectGatt(ctx, false, cb)",
      "adapter.bluetoothLeScanner.startScan(cb)",
      "val s: BluetoothLeScanner? = null",
      "scanner.startScan(null, settings, cb)",
      "adapter.startLeScan(cb)",
      "adapter.bluetoothLeAdvertiser.startAdvertising(s, d, cb)",
      // Bluetooth Classic outside bt/:
      "device.createBond()",
      "device.createRfcommSocketToServiceRecord(uuid)",
      "device.createInsecureRfcommSocketToServiceRecord(uuid)",
      "adapter.startDiscovery()",
      "val s: BluetoothSocket = x",
      // Own-service binding outside the one allowed file and form:
      "ctx.bindService(Intent(ctx, LinkService::class.java), conn, Context.BIND_AUTO_CREATE)",
      "override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {}",
      // Car App Library hardware access and app launching:
      "val hw = carContext.getCarService(CarHardwareManager::class.java)",
      "carContext.getCarService(CarContext.HARDWARE_SERVICE)",
      "import androidx.car.app.hardware.climate.CarClimateFeature",
      "hw.carClimate.setFanSpeed(request)",
      "AutomotiveCarHardwareManager(ctx)",
      "val m = carContext.carService<Any>()?.carHardwareManager",
      "carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE))",
    )
    for (line in mustFail) assertTrue("not detected: $line", violations(line).isNotEmpty())
    // Forbidden in bt/ too:
    val mustFailInBt = listOf(
      "device.connectGatt(ctx, false, cb)",
      "adapter.bluetoothLeScanner.startScan(cb)",
      "adapter.startLeScan(cb)",
      "adapter.bluetoothLeAdvertiser.startAdvertising(s, d, cb)",
      "device.javaClass.getMethod(\"createRfcommSocket\", Int::class.java)",
      "device.createRfcommSocket(1)",
      "adapter.listenUsingRfcommWithServiceRecord(\"x\", uuid)",
      "device.createL2capChannel(0x1001)",
      "device.removeBond()",
      "device.setPin(byteArrayOf(1, 2, 3, 4))",
      "device.setPairingConfirmation(true)",
      "adapter.setName(\"x\")",
      "adapter.getProfileProxy(ctx, listener, BluetoothProfile.HEADSET)",
      "val h: BluetoothHeadset? = null",
      "adapter.enable()",
      "adapter.disable()",
      "Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)",
      "ctx.bindService(Intent(ctx, LinkService::class.java), conn, Context.BIND_AUTO_CREATE)",
    )
    for (line in mustFailInBt) assertTrue("not detected in bt/: $line", violations(line, rel = "bt/BtClassic.kt").isNotEmpty())
    // Allowed in bt/ only:
    val btOnly = listOf(
      "device.createBond()",
      "dev.createRfcommSocketToServiceRecord(SPP_UUID)",
      "dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID)",
      "a.startDiscovery()",
      "private fun wrap(s: BluetoothSocket): ByteLink",
    )
    for (line in btOnly) {
      assertEquals("false positive in bt/: $line", emptyList<String>(), violations(line, rel = "bt/BtClassic.kt"))
      assertTrue("allowed outside bt/: $line", violations(line, rel = "link/LinkService.kt").isNotEmpty())
    }
    // The own-service binding, exactly as LinkConnection.kt writes it, and only there:
    val binding = "private val bound = app.bindService(Intent(app, LinkService::class.java), connection, Context.BIND_AUTO_CREATE)"
    assertEquals(emptyList<String>(), violations(binding, rel = "link/LinkConnection.kt"))
    assertTrue(violations(binding, rel = "link/LinkService.kt").isNotEmpty())
    assertTrue(violations(binding.replace("LinkService", "OtherService"), rel = "link/LinkConnection.kt").isNotEmpty())
    assertTrue(violations("app.bindService(intent, connection, Context.BIND_AUTO_CREATE)", rel = "link/LinkConnection.kt").isNotEmpty())
    assertTrue(violations("Intent().setComponent(ComponentName(\"com.gm.x\", \"Y\"))", rel = "link/LinkConnection.kt").isNotEmpty())
    assertEquals(
      emptyList<String>(),
      violations("override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {", rel = "link/LinkConnection.kt"),
    )
    assertTrue(violations("String s = \"x\"; c.\\u0073etFoo();", java = true).isNotEmpty())

    // Lines the app legitimately contains.
    val mustPass = listOf(
      "cpm.getProperty<Any>(id, 0)",
      "cpm.getCarPropertyConfig(id)",
      "val v: CarPropertyValue<Any?>? = cpm.getProperty(id, area)",
      "propertyManager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager",
      "uxManager = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager",
      "import android.car.hardware.property.CarPropertyManager",
      "import android.car.drivingstate.CarUxRestrictions",
      "Car.CONNECTION_TYPE_EMBEDDED -> \"\$t (EMBEDDED)\"",
      "private const val P = \"android.car.permission.\"",
      "conn.setRequestProperty(\"Content-Type\", \"application/json\")",
      "} catch (e: RuntimeException) {",
      "val cm = context.getSystemService(ConnectivityManager::class.java)",
      "startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))",
      // Battery Glance (Car App Library templates):
      "class GlanceCarAppService : CarAppService() {",
      "override fun onCreateSession(sessionInfo: SessionInfo): Session = GlanceSession()",
      "return PaneTemplate.Builder(pane.build()).setHeader(header).build()",
      "CarToast.makeText(carContext, \"Reconnecting…\", CarToast.LENGTH_SHORT).show()",
      "conn = LinkConnection(carContext, lifecycleScope)",
      // Built-in session sampler:
      "val r = reader.readRaw(id)",
      "private val conn = LinkConnection(app, viewModelScope, obdWanted = store.state.value.tab.needsObd)",
    )
    for (line in mustPass) assertEquals("false positive: $line", emptyList<String>(), violations(line))
  }

  @Test
  fun mainSourcesContainNoForbiddenCalls() {
    val files = sources()
    assertTrue("expected Kotlin sources under ${srcDir()}/main", files.size >= 10)
    val problems = files.flatMap { f -> violations(f.readText(), java = f.extension == "java", rel = relPath(f)).map { "${f.path}: $it" } }
    if (problems.isNotEmpty()) fail("Forbidden calls found:\n" + problems.joinToString("\n"))
  }

  /**
   * The app binds exactly one service: its own LinkService, by explicit class, from
   * link/LinkConnection.kt. (The rules above already fail any other bind; this pins that the
   * allowance is actually used in exactly that one place and form.)
   */
  @Test
  fun onlyTheOwnLinkServiceIsBound() {
    val bind = Regex("""\bbind\w*Service\b""")
    val hits = sources().flatMap { f -> bind.findAll(f.readText()).map { relPath(f) to it.value }.toList() }
    assertEquals(listOf("link/LinkConnection.kt" to "bindService"), hits)
    val text = File(srcDir(), "main/kotlin/dev/silveradostats/link/LinkConnection.kt").readText()
    assertEquals(1, allowances.getValue("link/LinkConnection.kt")[2].findAll(text).count())
  }

  /**
   * Single write path to the adapter, app-wide: outside the OBD core nothing writes to an adapter
   * link or stream. The adapter socket (Bluetooth, in bt/) only hands its streams to
   * the core's StreamByteLink constructor, so ElmLink.command (reached only through
   * ElmTransport / ElmChannel) stays the one writer. The only other writes in the app are the report
   * upload (an HTTP body to the laptop) and the crash file.
   */
  @Test
  fun noFileOutsideObdWritesToTheAdapter() {
    val outside = sources().filter { f -> !relPath(f).startsWith("obd/") && relPath(f).endsWith(".kt") }
    assertTrue(outside.size >= 10)
    val streamHandOff = Regex(
      """\bStreamByteLink\(\s*(\w+)\.(inputStream|getInputStream\(\)),\s*\1\.(outputStream|getOutputStream\(\)),\s*\1\b""",
    )
    val otherWrites = mapOf(
      // HTTP POST of the diagnostics report to the laptop: not an adapter.
      "report/ReportSender.kt" to Regex("""\bconn\.outputStream\.use \{ it\.write\(body\) \}"""),
      // filesDir/last_crash.txt.
      "diag/CrashLog.kt" to Regex("""\bfile\.writeText\("""),
      // The CSV export, to the file the user picked: not an adapter.
      "log/LogScheduler.kt" to Regex("""\bout\.bufferedWriter\(\)\.use \{ LogCsv\.appendTo\(rows, it\) \}"""),
    )
    val forbidden = listOf(
      "adapter command" to Regex("""\.command\s*\("""),
      "adapter link read" to Regex("""\.readAvailable\s*\(|\.settle\s*\("""),
      "write call" to Regex("""\bwrite\w*\s*\("""),
      "output stream" to Regex("""\b(outputStream|getOutputStream|OutputStream)\b"""),
      "writer" to Regex("""\b(PrintWriter|PrintStream|OutputStreamWriter|BufferedWriter|bufferedWriter|printWriter|writer)\b"""),
      // A ByteLink implementation outside the core could write behind ElmLink's back.
      "channel / transport internals" to Regex("""\bElmChannel\b|\.transmit\s*\(|\bobject\s*:\s*(\w+\.)*ByteLink\b|\bclass\b[^\n{]*[:,]\s*(\w+\.)*ByteLink\b"""),
    )
    val problems = mutableListOf<String>()
    for (f in outside) {
      val rel = relPath(f)
      var text = f.readText()
      val handOffs = streamHandOff.findAll(text).count()
      if (rel.startsWith("bt/")) {
        text = streamHandOff.replace(text, "")
      } else if (handOffs > 0) {
        problems += "$rel: only bt/ may wrap a socket in StreamByteLink"
      }
      otherWrites[rel]?.let { r ->
        val n = r.findAll(text).count()
        if (n != 1) problems += "$rel: expected exactly one allowed write, found $n"
        text = r.replace(text, "")
      }
      for ((what, r) in forbidden) {
        r.findAll(text).forEach { m -> problems += "$rel: $what: '${m.value}'" }
      }
    }
    // The hand-off must actually exist where sockets are opened.
    val bt = File(srcDir(), "main/kotlin/dev/silveradostats/bt/BtClassic.kt").readText()
    assertEquals(1, streamHandOff.findAll(bt).count())
    if (problems.isNotEmpty()) fail("Writes to the adapter outside the OBD core:\n" + problems.joinToString("\n"))
  }

  private fun obdSources(): List<File> {
    val dir = File(srcDir(), "main/kotlin/dev/silveradostats/obd")
    assertTrue("OBD core missing at $dir", dir.isDirectory)
    return dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
  }

  /** The OBD core is pure JVM (no Android API at all), so the unit tests exercise exactly what ships. */
  @Test
  fun obdCoreIsPureJvm() {
    val files = obdSources()
    assertTrue(files.size >= 10)
    val androidRef = Regex("""\b(android|androidx|dalvik)\.\w""")
    val problems = files.flatMap { f ->
      androidRef.findAll(f.readText()).map { "${f.name}: '${it.value}'" }.toList()
    }
    if (problems.isNotEmpty()) fail("Android references in the OBD core:\n" + problems.joinToString("\n"))
  }

  /**
   * Single write path to the adapter: inside the OBD core only ElmLink.command writes to the link
   * (exactly one `.write(` in ElmLink.kt; ByteLink.kt is the stream implementation itself), no other
   * file writes to anything, and only ElmTransport.kt (ElmTransport.at / ElmChannel.transmit, after
   * the safety checks) calls `.command(`.
   */
  @Test
  fun obdCoreHasASingleWritePath() {
    val writeCall = Regex("""\.write\s*\(""")
    val commandCall = Regex("""\.command\s*\(""")
    val bareWrite = Regex("""(?<![.\w])write\s*\(""")
    for (f in obdSources()) {
      val text = f.readText()
      val writes = writeCall.findAll(text).count()
      when (f.name) {
        "ElmLink.kt" -> assertEquals("ElmLink.kt must write in exactly one place", 1, writes)
        "ByteLink.kt" -> {}
        else -> assertEquals("${f.name} must not write to any stream", 0, writes)
      }
      if (f.name != "ByteLink.kt") {
        assertEquals("${f.name}: unqualified write( call", 0, bareWrite.findAll(text).count { !text.substring(0, it.range.first).endsWith("fun ") })
      }
      if (f.name != "ElmTransport.kt") {
        assertEquals("${f.name} must not call ElmLink.command", 0, commandCall.findAll(text).count())
      }
    }
  }

  /**
   * The built-in session (Simple tab, Battery Glance without an adapter) is computed by pure JVM
   * code, so its unit tests exercise exactly what ships; only CarVehicleSource touches Android, and
   * it reads the car exclusively through VehicleReader.readRaw (getCarPropertyConfig / getProperty):
   * no CarPropertyManager of its own, no car API class at all.
   */
  @Test
  fun builtInSessionIsPureJvmAndReadsThroughVehicleReaderOnly() {
    val base = File(srcDir(), "main/kotlin/dev/silveradostats")
    val pure = listOf(
      "vehicle/SessionTracker.kt",
      "vehicle/DemoDrive.kt",
      "vehicle/VehicleSession.kt",
      "vehicle/DrivingState.kt",
      "ui/simple/SimpleModel.kt",
      "ui/live/DashboardModel.kt",
      "ui/layout/DashLayout.kt",
    )
    val androidRef = Regex("""\b(android|androidx|dalvik)\.\w""")
    for (rel in pure) {
      val text = File(base, rel).readText()
      assertEquals("$rel must be pure JVM", emptyList<String>(), androidRef.findAll(text).map { it.value }.toList())
    }
    val source = File(base, "vehicle/CarVehicleSource.kt").readText()
    assertFalse("CarVehicleSource names a car API class", Regex("""\bandroid\.car\b|CarPropertyManager|CarPropertyValue|getCarManager""").containsMatchIn(source))
    assertTrue(Regex("""\breader\.readRaw\(id\)""").containsMatchIn(source))
    // Its clock counts while the device sleeps (System.nanoTime does not), so a sleep is seen as a gap.
    assertTrue(Regex("""\bVehicleSession\(scope, SystemClock::elapsedRealtime\b""").containsMatchIn(source))
    assertFalse(Regex("""\bgetProperty\b|registerCallback|subscribePropertyEvents""").containsMatchIn(source))
    // readRaw itself: a config lookup and one getProperty, nothing else on the property manager.
    val reader = File(base, "vehicle/VehicleReader.kt").readText()
    val body = Regex("""fun readRaw\(id: Int\): RawRead \{(.*?)\n  \}""", RegexOption.DOT_MATCHES_ALL).find(reader)?.groupValues?.get(1)
      ?: error("no readRaw in VehicleReader.kt")
    assertEquals(listOf("getCarPropertyConfig", "getProperty"), Regex("""\bcpm\.(\w+)""").findAll(body).map { it.groupValues[1] }.toList())
  }

  @Test
  fun manifestDeclaresExactlyTheSpecPermissions() {
    val files = manifests()
    assertTrue("main manifest present", files.any { it.parentFile?.name == "main" })
    val declared = files.flatMap { usesPermissions(it) }
    assertEquals("duplicate <uses-permission>", declared.size, declared.toSet().size)
    assertEquals(expectedPermissions, declared.toSet())
  }

  /** android:name of every <uses-permission> / <uses-permission-sdk-23> (XML comments ignored). */
  private fun usesPermissions(manifest: File): List<String> {
    val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
    return listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
      val nodes = doc.getElementsByTagName(tag)
      (0 until nodes.length).map { i ->
        val e = nodes.item(i) as Element
        e.getAttributeNS(ANDROID_NS, "name").ifEmpty { error("<$tag> without android:name in $manifest") }
      }
    }
  }

  private fun parse(f: File): Document =
    DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)

  private fun Element.attr(name: String): String = getAttributeNS(ANDROID_NS, name)

  /** Every element named [tag] below [doc] (any depth). */
  private fun elements(doc: Document, tag: String): List<Element> {
    val nodes = doc.getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  /** Direct children of [e] named [tag]. */
  private fun children(e: Element, tag: String): List<Element> {
    val nodes = e.childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
  }

  private fun application(doc: Document): Element = elements(doc, "application").single()

  /** (parent tag, parent android:name, value) of every <meta-data android:name="distractionOptimized">. */
  private fun distractionOptimized(doc: Document): List<Triple<String, String, String>> =
    elements(doc, "meta-data").filter { it.attr("name") == "distractionOptimized" }.map { m ->
      val p = m.parentNode as Element
      Triple(p.tagName, p.attr("name"), m.attr("value"))
    }

  /**
   * Three services: the log's job (run by the system only), the private LinkService (not exported, no
   * intent filter, bound only) and Battery
   * Glance's CarAppService (exported because the Templates Host binds to it, with exactly the
   * CarAppService action and the IOT category; createHostValidator decides which hosts may connect).
   */
  @Test
  fun manifestDeclaresOnlyTheLinkServiceAndTheGlanceService() {
    val doc = parse(File(srcDir(), "main/AndroidManifest.xml"))
    val services = elements(doc, "service").associateBy { it.attr("name") }
    assertEquals(setOf(".link.LinkService", ".log.LogJobService", ".glance.GlanceCarAppService"), services.keys)
    // The log's job: only the system may run it, and nothing outside the app can reach it.
    val job = services.getValue(".log.LogJobService")
    assertEquals("false", job.attr("exported"))
    assertEquals("android.permission.BIND_JOB_SERVICE", job.attr("permission"))
    assertEquals(0, job.getElementsByTagName("intent-filter").length)
    val link = services.getValue(".link.LinkService")
    assertEquals("false", link.attr("exported"))
    assertEquals("no intent filter on LinkService", 0, link.getElementsByTagName("intent-filter").length)
    val glance = services.getValue(".glance.GlanceCarAppService")
    assertEquals("true", glance.attr("exported"))
    val filters = children(glance, "intent-filter")
    assertEquals(1, filters.size)
    assertEquals(listOf("androidx.car.app.CarAppService"), children(filters[0], "action").map { it.attr("name") })
    assertEquals(listOf("androidx.car.app.category.IOT"), children(filters[0], "category").map { it.attr("name") })
    assertEquals(0, children(filters[0], "data").size)
    for (tag in listOf("receiver", "provider")) assertEquals("no <$tag> declared", 0, doc.getElementsByTagName(tag).length)
    // Bound only: nothing in the app starts a service (the rules forbid start*Service everywhere).
    val start = Regex("""\bstart\w*Service\b""")
    assertTrue(sources().none { start.containsMatchIn(it.readText()) })
  }

  /**
   * Driver distraction: the distraction-optimized activities are EXACTLY MainActivity (which locks
   * itself down while restricted; see AndroidWiringTest) and the Car App Library's CarAppActivity
   * (which shows nothing but the Templates Host's rendering of Battery Glance). No other source set
   * adds the tag, and no alias could carry it.
   */
  @Test
  fun distractionOptimizedActivitiesAreExactlyTheAllowlist() {
    val allowed = listOf(Triple("activity", ".MainActivity", "true"), Triple("activity", CAR_APP_ACTIVITY, "true"))
    for (f in manifests()) {
      val expected = if (f.parentFile?.name == "main") allowed else emptyList()
      assertEquals("distraction-optimized components in $f", expected, distractionOptimized(parse(f)))
    }
    val main = File(srcDir(), "main/AndroidManifest.xml")
    assertEquals("two distractionOptimized tags in the main manifest", 2, Regex("\"distractionOptimized\"").findAll(main.readText()).count())
    val doc = parse(main)
    assertEquals("no <activity-alias>", 0, doc.getElementsByTagName("activity-alias").length)
    val activities = elements(doc, "activity").associateBy { it.attr("name") }
    assertEquals(setOf(".MainActivity", CAR_APP_ACTIVITY), activities.keys)
    // The trade-off stays written down next to the flag it explains.
    val text = main.readText()
    val note = text.substring(text.lastIndexOf("<!--", text.indexOf("android:name=\".MainActivity\"")), text.indexOf("android:name=\".MainActivity\""))
    assertTrue("MainActivity's flag explains that it is for internal testing only", note.contains("INTERNAL TESTING ONLY"))
    assertEquals("the flag is its only meta-data", 1, children(activities.getValue(".MainActivity"), "meta-data").size)
    // CarAppActivity as Google's Android Automotive guide declares it, with its own launcher label.
    val car = activities.getValue(CAR_APP_ACTIVITY)
    assertEquals("true", car.attr("exported"))
    assertEquals("singleTask", car.attr("launchMode"))
    assertEquals("@android:style/Theme.DeviceDefault.NoActionBar", car.attr("theme"))
    assertEquals("@string/glance_name", car.attr("label"))
    for (a in activities.values) {
      val filter = children(a, "intent-filter").single()
      assertEquals(listOf("android.intent.action.MAIN"), children(filter, "action").map { it.attr("name") })
      assertEquals(listOf("android.intent.category.LAUNCHER"), children(filter, "category").map { it.attr("name") })
    }
    assertEquals("Battery Glance", Regex("""<string name="glance_name">([^<]*)</string>""").find(File(srcDir(), "main/res/values/strings.xml").readText())?.groupValues?.get(1))
  }

  @Test
  fun manifestDeclaresReadOnlyCarAccess() {
    val f = File(srcDir(), "main/AndroidManifest.xml")
    val manifest = f.readText()
    assertTrue(manifest.contains("android.hardware.type.automotive\" android:required=\"false\""))
    assertTrue(manifest.contains("<uses-library android:name=\"android.car\" android:required=\"false\""))
    assertTrue(manifest.contains("neverForLocation"))
    // Templated app (Car App Library): Templates Host declared (not required: regular Play track), automotive_app_desc, min Car API level.
    val doc = parse(f)
    val features = elements(doc, "uses-feature").associate { it.attr("name") to it.attr("required") }
    assertEquals("false", features["android.hardware.type.automotive"])
    assertEquals("false", features["android.software.car.templates_host"])
    val meta = children(application(doc), "meta-data").associate { it.attr("name") to it.attr("resource").ifEmpty { it.attr("value") } }
    assertEquals("@xml/automotive_app_desc", meta["com.android.automotive"])
    val minApi = meta["androidx.car.app.minCarApiLevel"]?.toIntOrNull()
    assertTrue("androidx.car.app.minCarApiLevel declared", minApi != null && minApi >= 1)
    val desc = parse(File(srcDir(), "main/res/xml/automotive_app_desc.xml"))
    assertEquals("automotiveApp", desc.documentElement.tagName)
    assertEquals(listOf("template"), elements(desc, "uses").map { it.getAttribute("name") })
  }

  /**
   * The variant's merged manifest (this app plus every library), so a dependency can't slip in a
   * permission, a distraction-optimized activity or an exported component. app/build.gradle.kts
   * passes its path (and declares it as an input of the unit-test task).
   */
  private fun mergedManifest(): File {
    val path = System.getProperty("silverado.mergedManifest")
      ?: error("system property silverado.mergedManifest not set: run the tests through Gradle (app/build.gradle.kts sets it)")
    return File(path).also { assertTrue("merged manifest missing: $it", it.isFile) }
  }

  @Test
  fun mergedManifestKeepsTheRules() {
    val f = mergedManifest()
    val doc = parse(f)
    val pkg = doc.documentElement.getAttribute("package")
    assertTrue(pkg.isNotEmpty())
    // Permissions: the spec list, plus AndroidX core's signature-level permission that keeps the
    // app's own runtime-registered receivers private (declared by the app for itself).
    val own = "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    val perms = usesPermissions(f)
    assertEquals("duplicate <uses-permission>", perms.size, perms.toSet().size)
    assertEquals(expectedPermissions + own, perms.toSet())
    for (p in elements(doc, "permission")) {
      assertEquals(own, p.attr("name"))
      assertEquals("signature", p.attr("protectionLevel"))
    }
    // Distraction optimized: these two only (the library's CarAppPermissionActivity is not).
    assertEquals(
      listOf(Triple("activity", "dev.silveradostats.MainActivity", "true"), Triple("activity", CAR_APP_ACTIVITY, "true")),
      distractionOptimized(doc),
    )
    // Exported components: the two launcher entries, the glance service, and the profile installer
    // receiver (only callable with android.permission.DUMP, i.e. by the shell / system).
    val exported = listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { tag ->
      elements(doc, tag)
        .filter { it.attr("exported") == "true" || (it.attr("exported").isEmpty() && children(it, "intent-filter").isNotEmpty()) }
        .map { e -> "$tag ${e.attr("name")}" + e.attr("permission").let { p -> if (p.isEmpty()) "" else " ($p)" } }
    }.toSet()
    assertEquals(
      setOf(
        "activity dev.silveradostats.MainActivity",
        "activity $CAR_APP_ACTIVITY",
        "service dev.silveradostats.glance.GlanceCarAppService",
        "receiver androidx.profileinstaller.ProfileInstallReceiver (android.permission.DUMP)",
      ),
      exported,
    )
    val features = elements(doc, "uses-feature").associate { it.attr("name") to it.attr("required") }
    assertEquals("false", features["android.hardware.type.automotive"])
    assertEquals("false", features["android.software.car.templates_host"])
    for (optional in listOf("android.hardware.wifi", "android.hardware.screen.portrait", "android.hardware.screen.landscape")) {
      assertEquals("$optional must not be required (Play AAOS rule)", "false", features[optional])
    }
  }

  /** Any host only in debug builds; release builds accept the library's allowlisted hosts only. */
  @Test
  fun glanceAcceptsAnyHostOnlyInDebugBuilds() {
    val hits = sources().filter { it.readText().contains("ALLOW_ALL_HOSTS_VALIDATOR") }.map { relPath(it) }
    assertEquals(listOf("glance/GlanceCarAppService.kt"), hits)
    val text = File(srcDir(), "main/kotlin/dev/silveradostats/glance/GlanceCarAppService.kt").readText()
    assertEquals(1, Regex("ALLOW_ALL_HOSTS_VALIDATOR").findAll(text).count())
    val validator = Regex(
      """override fun createHostValidator\(\): HostValidator =\s*if \(BuildConfig\.DEBUG\) \{\s*HostValidator\.ALLOW_ALL_HOSTS_VALIDATOR\s*\}""" +
        """\s*else \{\s*HostValidator\.Builder\(applicationContext\)\s*\.addAllowedHosts\(R\.array\.car_app_hosts_allowlist\)""" +
        """\s*\.build\(\)\s*\}""",
    )
    assertTrue("createHostValidator must allow every host only when BuildConfig.DEBUG", validator.containsMatchIn(text))
    val all = sources().joinToString("\n") { it.readText() }
    assertEquals(1, Regex("""\bfun createHostValidator\b""").findAll(all).count())
    assertEquals(1, Regex("""\baddAllowedHost""").findAll(all).count())
    // The release allowlist: only the Android Automotive Templates Host, each "SHA-256 digest,package".
    val res = parse(File(srcDir(), "main/res/values/car_app_hosts.xml"))
    val arrays = elements(res, "string-array").filter { it.getAttribute("name") == "car_app_hosts_allowlist" }
    val items = arrays.single().getElementsByTagName("item").let { n -> (0 until n.length).map { n.item(it).textContent.trim() } }
    assertTrue(items.isNotEmpty())
    for (item in items) {
      assertTrue("allowlist entry '$item'", Regex("""[0-9a-f]{64},com\.google\.android\.apps\.automotive\.templates\.host""").matches(item))
    }
    // BuildConfig.DEBUG is false in release only as long as the release build type is not debuggable.
    val gradle = File(srcDir().absoluteFile.parentFile, "build.gradle.kts").readText()
    assertFalse(Regex("""isDebuggable\s*=\s*true""").containsMatchIn(gradle))
  }

  /**
   * Battery Glance holds the link only while the host shows it: it binds LinkService (through
   * LinkConnection) when the screen is created, attaches as a visible client on onStart, detaches
   * on onStop and unbinds on onDestroy.
   */
  @Test
  fun glanceKeepsTheLinkOnlyWhileVisible() {
    val text = File(srcDir(), "main/kotlin/dev/silveradostats/glance/GlanceScreen.kt").readText()
    fun body(fn: String): String =
      Regex("""override fun $fn\(owner: LifecycleOwner\) \{([^}]*)\}""").find(text)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
        ?: error("no $fn in GlanceScreen.kt")
    assertEquals("conn = LinkConnection(carContext, lifecycleScope)", body("onCreate"))
    assertEquals("conn?.setVisible(true)", body("onStart"))
    assertEquals("conn?.setVisible(false)", body("onStop"))
    assertEquals("conn?.close() conn = null", body("onDestroy"))
    assertEquals(1, Regex("""\bLinkConnection\(""").findAll(text).count())
    assertEquals(1, Regex("""setVisible\(true\)""").findAll(text).count())
  }

  private companion object {
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    const val CAR_APP_ACTIVITY = "androidx.car.app.activity.CarAppActivity"
  }
}
